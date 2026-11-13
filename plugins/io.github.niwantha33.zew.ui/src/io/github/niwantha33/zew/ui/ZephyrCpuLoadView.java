// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

/** Opt-in, saved-CTF-only CPU load view; no work at initialization. */
public final class ZephyrCpuLoadView extends ViewPart {
    public static final String ID = Activator.ID + ".cpuLoad";
    private final AtomicInteger generation = new AtomicInteger();
    private IProject contextProject;
    private Path selectedTrace;
    private Path selectedBuild;
    private OfflineTraceAnalysisService service;
    private volatile Job loadJob;
    private boolean settingWindow;
    private Label disclaimer, banner, status, provenance;
    private Text windowStart, windowEnd;
    private Table table, stateTable;
    private Label stateExplanation;

    @Override public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));
        disclaimer = new Label(parent, SWT.WRAP);
        disclaimer.setText(OfflineTraceViewModels.CPU_DISCLAIMER);
        disclaimer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        banner = new Label(parent, SWT.BORDER | SWT.WRAP);
        banner.setText(OfflineTraceViewModels.CPU_INCOMPLETE);
        banner.setForeground(parent.getDisplay().getSystemColor(SWT.COLOR_DARK_RED));
        banner.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        showBanner(false);
        var guided = new Composite(parent, SWT.NONE);
        guided.setLayout(new GridLayout(3, false));
        guided.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button capture = new Button(guided, SWT.PUSH);
        capture.setText("1. Capture new CTF...");
        capture.setToolTipText("Stop active Debug first: capture resets, runs then halts the target.");
        capture.addListener(SWT.Selection, e -> captureNew());
        Button latest = new Button(guided, SWT.PUSH);
        latest.setText("2. Open latest capture");
        latest.setToolTipText("Load newest saved .zew-traces/trace-*/ctf without reading hardware.");
        latest.addListener(SWT.Selection, e -> openLatest());
        var guidance = new Label(parent, SWT.WRAP);
        guidance.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        guidance.setText("Build + flash firmware with CONFIG_TRACING, CONFIG_TRACING_CTF "
                + "and CONFIG_TRACING_BACKEND_RAM enabled. Terminate Debug to release OpenOCD; "
                + "capture explicitly resets/runs/halts the MCU. Saved CTF analysis needs Babeltrace 2 bt2.");
        var controls = new Composite(parent, SWT.NONE);
        controls.setLayout(new GridLayout(6, false));
        controls.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button open = new Button(controls, SWT.PUSH);
        open.setText("Load trace...");
        open.addListener(SWT.Selection, ignored -> chooseTrace());
        new Label(controls, SWT.NONE).setText("Start ns");
        windowStart = new Text(controls, SWT.BORDER);
        windowStart.setLayoutData(new GridData(150, SWT.DEFAULT));
        new Label(controls, SWT.NONE).setText("End ns");
        windowEnd = new Text(controls, SWT.BORDER);
        windowEnd.setLayoutData(new GridData(150, SWT.DEFAULT));
        Button apply = new Button(controls, SWT.PUSH);
        apply.setText("Apply window");
        apply.addListener(SWT.Selection, ignored -> refreshWindow());
        for (Text field : new Text[] {windowStart, windowEnd}) {
            field.setEnabled(false);
            field.addVerifyListener(e -> { if (!e.text.matches("[0-9]*")) e.doit = false; });
            field.addListener(SWT.FocusOut, e -> {
                if (!settingWindow && selectedTrace != null) refreshWindow();
            });
        }
        apply.setEnabled(false);
        status = new Label(parent, SWT.WRAP);
        status.setText("No trace loaded. Choose a saved CTF trace to start analysis.");
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        provenance = new Label(parent, SWT.WRAP);
        provenance.setText("");
        provenance.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        table = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        for (String header : new String[] {"Thread Name", "Running (us)", "Percentage"}) {
            TableColumn column = new TableColumn(table, SWT.LEFT);
            column.setText(header);
            column.setWidth(header.equals("Thread Name") ? 310 : 170);
        }
        table.setVisible(false);
        stateExplanation = new Label(parent, SWT.WRAP);
        stateExplanation.setText("Thread states are observed intervals. Scheduled time includes ISR preemption; CPU percentages above exclude ISR time. Per-ISR identity is unavailable.");
        stateExplanation.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        stateExplanation.setVisible(false);
        stateTable = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
        stateTable.setHeaderVisible(true);
        stateTable.setLinesVisible(true);
        var statesLayout = new GridData(SWT.FILL, SWT.FILL, true, false);
        statesLayout.heightHint = 145;
        stateTable.setLayoutData(statesLayout);
        for (String heading : new String[] {"Thread", "Observed state", "From ns", "To ns"}) {
            TableColumn col = new TableColumn(stateTable, SWT.LEFT);
            col.setText(heading);
            col.setWidth(heading.equals("Thread") ? 220 : 160);
        }
        stateTable.setVisible(false);
        apply.setData("cpu.apply", Boolean.TRUE);
        apply.addListener(SWT.Selection, e -> {}); // no startup action
        this.applyButton = apply;
    }

    private Button applyButton;

    public void displayProject(IProject project) {
        if (project != null && project.isAccessible()) contextProject = project;
    }
    private IProject selectedProject() {
        if (contextProject != null && contextProject.isAccessible()) return contextProject;
        contextProject = TraceCaptureWorkflow.resolve(getSite().getWorkbenchWindow());
        return contextProject;
    }
    private void captureNew() {
        IProject selected = selectedProject();
        if (selected == null) {
            status.setText("Select an imported Zephyr application or its active Debug frame first.");
            return;
        }
        ZephyrTraceHandler.capture(selected, getSite().getShell());
    }
    private void openLatest() {
        IProject selected = selectedProject();
        if (selected == null) {
            status.setText("Select an imported Zephyr project or active Debug frame to locate saved CTF.");
            return;
        }
        try {
            var latest = TraceCaptureWorkflow.latest(selected);
            if (latest.isEmpty()) {
                status.setText("No saved CTF in " + selected.getName()
                        + "/.zew-traces. Build and flash tracing firmware, terminate Debug and Capture new CTF.");
                return;
            }
            load(latest.get());
        } catch (IOException error) {
            status.setText("Cannot find latest saved CTF: " + error.getMessage());
        }
    }
    /** Called only after the user intentionally captured an exact saved CTF. */
    /** Do not overwrite another application's already selected trace. */
    public boolean acceptsCapture(IProject selected) {
        return selected != null && (contextProject == null || contextProject.equals(selected));
    }

    public void loadSavedCapture(Path ctf) {
        if (ctf != null && table != null && !table.isDisposed()) load(ctf);
    }
    private void chooseTrace() {
        DirectoryDialog dialog = new DirectoryDialog(getSite().getShell(), SWT.OPEN);
        dialog.setText("Load existing saved Zephyr CTF");
        dialog.setMessage("Choose trace-*/ctf or the trace-* folder containing ctf.");
        String chosen = dialog.open();
        if (chosen != null) load(Path.of(chosen));
    }
    private void load(Path trace) {
        if (!Activator.getDefault().getPreferenceStore().getBoolean(WorkbenchPreferences.ENABLE_CPU_VIEW)) {
            clear("Zephyr CPU Load view is disabled in Zephyr Workbench preferences.");
            return;
        }
        IProject project = selectedProject();
        if (project == null) {
            clear("Select an imported Zephyr project or active Debug frame to identify the build.");
            return;
        }
        Path ctf = Files.isDirectory(trace.resolve("ctf")) ? trace.resolve("ctf") : trace;
        if (!TraceCaptureWorkflow.isSavedCtf(ctf)) {
            clear("Select a saved CTF folder containing metadata and channel0_0, not an arbitrary directory.");
            return;
        }
        try {
            var settings = ProjectContext.settings(project);
            Path build = ProjectContext.buildDirectory(project, settings);
            if (service == null || !build.equals(selectedBuild)) {
                service = new OfflineTraceAnalysisService(new OfflineTracePythonBridge(project, settings));
            }
            selectedBuild = build;
            selectedTrace = ctf.toAbsolutePath().normalize();
            analyse(null, null);
        } catch (Exception error) {
            clear("Cannot select build: " + error.getMessage());
        }
    }
    private void refreshWindow() {
        if (selectedTrace == null) return;
        try {
            long from = Long.parseLong(windowStart.getText().trim());
            long to = Long.parseLong(windowEnd.getText().trim());
            if (from < 0 || to <= from) throw new NumberFormatException();
            analyse(from, to);
        } catch (NumberFormatException error) {
            clear("Enter start/end nanoseconds with End greater than Start.");
        }
    }
    private void analyse(Long from, Long to) {
        int ticket = generation.incrementAndGet();
        if (loadJob != null) loadJob.cancel();
        status.setText("Reading saved CTF with offline Python; no target access.");
        table.setVisible(false);
        table.removeAll();
        stateTable.removeAll();
        stateTable.setVisible(false);
        stateExplanation.setVisible(false);
        showBanner(false);
        Path trace = selectedTrace, build = selectedBuild;
        OfflineTraceAnalysisService current = service;
        loadJob = new Job("Analyze saved Zephyr CPU trace") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                OfflineTraceAnalysisService.CpuLoadResult result = null;
                OfflineTraceAnalysisService.AnalysisFailure failure = null;
                try {
                    result = current.analyseCpu(trace, build, from, to);
                } catch (OfflineTraceAnalysisService.AnalysisFailure error) {
                    failure = error;
                    result = error.cpu();
                }
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                var finalResult = result;
                var finalFailure = failure;
                org.eclipse.swt.widgets.Display.getDefault().asyncExec(() -> {
                    if (table != null && !table.isDisposed() && generation.get() == ticket)
                        present(finalResult, finalFailure);
                });
                return Status.OK_STATUS;
            }
        };
        loadJob.setUser(true);
        loadJob.schedule();
    }
    private void present(OfflineTraceAnalysisService.CpuLoadResult result,
                         OfflineTraceAnalysisService.AnalysisFailure failure) {
        if (result == null) {
            clear(failure == null ? "Offline CPU analysis unavailable." :
                    failure.kind() + ": " + failure.getMessage());
            return;
        }
        var model = OfflineTraceViewModels.cpu(result);
        showBanner(model.incomplete());
        table.removeAll();
        stateTable.removeAll();
        stateTable.setVisible(false);
        stateExplanation.setVisible(false);
        if (result.startNs() != null && result.endNs() != null) {
            settingWindow = true;
            try {
                windowStart.setText(Long.toString(result.startNs()));
                windowEnd.setText(Long.toString(result.endNs()));
            } finally { settingWindow = false; }
            windowStart.setEnabled(true);
            windowEnd.setEnabled(true);
            applyButton.setEnabled(true);
        }
        provenance.setText("Saved CTF: " + result.trace() + " | Selected build: " + result.build()
                + " | cpu_analysis.account + observed scheduler intervals");
        if (model.noScheduler()) {
            status.setText(model.message());
            table.setVisible(false);
            return;
        }
        for (var row : model.rows()) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(new String[] {row.threadName(), row.runningUs(), row.percentage()});
        }
        table.setVisible(!model.rows().isEmpty());
        if (!model.incomplete()) {
            for (var interval : result.stateIntervals()) {
                var item = new TableItem(stateTable, SWT.NONE);
                String state = interval.state() == OfflineTraceAnalysisService.ObservedState.SCHEDULED
                        ? "Scheduled (ISR-inclusive)" : interval.state().name();
                item.setText(new String[] {interval.name().isBlank() ? "(unknown)" : interval.name(),
                        state, Long.toString(interval.startNs()), Long.toString(interval.endNs())});
            }
            stateTable.setVisible(stateTable.getItemCount() != 0);
            stateExplanation.setVisible(stateTable.getItemCount() != 0);
        }
        status.setText(model.message().isBlank()
                ? "Observed exclusive thread/ISR percentages; unknown intervals remain unassigned."
                : model.message() + " Only observed intervals are represented.");
        table.getParent().layout(true, true);
    }
    private void clear(String message) {
        generation.incrementAndGet();
        if (loadJob != null) loadJob.cancel();
        table.removeAll();
        table.setVisible(false);
        stateTable.removeAll();
        stateTable.setVisible(false);
        stateExplanation.setVisible(false);
        showBanner(false);
        status.setText(message);
    }
    private void showBanner(boolean visible) {
        var data = (GridData) banner.getLayoutData();
        data.exclude = !visible;
        banner.setVisible(visible);
        banner.getParent().layout(true, true);
    }
    @Override public void setFocus() { if (table != null && !table.isDisposed()) table.setFocus(); }
    @Override public void dispose() {
        generation.incrementAndGet();
        if (loadJob != null) loadJob.cancel();
        super.dispose();
    }
}
