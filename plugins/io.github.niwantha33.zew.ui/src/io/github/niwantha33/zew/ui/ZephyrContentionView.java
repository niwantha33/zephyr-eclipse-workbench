// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
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
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;
import io.github.niwantha33.zew.core.ProcessRunner;

/**
 * Read-only, on-demand evidence-qualified analysis of SAVED semantic CTF
 * events. No target/probe access, startup analysis or timeline visualization.
 */
public final class ZephyrContentionView extends ViewPart {
    public static final String ID = Activator.ID + ".contentionReport";
    public static final String DISCLAIMER = OfflineTraceViewModels.CONTENTION_DISCLAIMER;
    public static final String NO_TRACE =
            "No trace loaded. Select an imported Zephyr project and open a saved CTF trace.";
    public static final String NO_PRIORITIES = OfflineTraceViewModels.NO_PRIORITIES;
    public static final long DEFAULT_THRESHOLD_US = 1000L;
    private static final long MAX_THRESHOLD_US = 86_400_000_000L;

    private final AtomicInteger generation = new AtomicInteger();
    private volatile Job loadJob;
    private IProject contextProject;
    private Path selectedTrace;
    private OfflineTraceAnalysisService.ContentionResult displayed;
    private OfflineTraceAnalysisService service;
    private Path selectedBuild;
    private Text threshold;
    private Label status, priorities, incomplete, disclaimer;
    private Table table;
    private Table objectTable;

    @Override public void createPartControl(Composite parent) {
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 12;
        layout.marginHeight = 10;
        parent.setLayout(layout);

        disclaimer = new Label(parent, SWT.WRAP);
        disclaimer.setText(DISCLAIMER);
        disclaimer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Composite controls = new Composite(parent, SWT.NONE);
        controls.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        controls.setLayout(new GridLayout(4, false));
        Button open = new Button(controls, SWT.PUSH);
        open.setText("Open saved CTF trace...");
        open.addListener(SWT.Selection, ignored -> chooseTrace());
        Button reload = new Button(controls, SWT.PUSH);
        reload.setText("Reload");
        reload.addListener(SWT.Selection, ignored -> {
            if (selectedTrace != null) load(selectedTrace);
            else status.setText(NO_TRACE);
        });
        new Label(controls, SWT.NONE).setText("Long-wait threshold (us)");
        threshold = new Text(controls, SWT.BORDER);
        threshold.setLayoutData(new GridData(115, SWT.DEFAULT));
        threshold.setText(Long.toString(DEFAULT_THRESHOLD_US));
        threshold.addVerifyListener(event -> {
            if (!event.text.matches("[0-9]*")) event.doit = false;
        });
        threshold.addModifyListener(ignored -> thresholdChanged());

        status = new Label(parent, SWT.WRAP);
        status.setText(NO_TRACE);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        priorities = new Label(parent, SWT.WRAP);
        priorities.setText("");
        priorities.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        incomplete = new Label(parent, SWT.WRAP | SWT.BORDER);
        incomplete.setText("Trace incomplete: findings may be missing. "
                + "No waits beyond the observed trace are inferred.");
        incomplete.setForeground(parent.getDisplay().getSystemColor(SWT.COLOR_DARK_RED));
        incomplete.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        showIncomplete(false);

        TabFolder tabs = new TabFolder(parent, SWT.NONE);
        tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        TabItem findingsTab = new TabItem(tabs, SWT.NONE);
        findingsTab.setText("Blocking findings");
        table = new Table(tabs, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        findingsTab.setControl(table);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        String[] columns = { "Kind", "Object", "Waiter", "Holder", "Duration (us)", "Explanation" };
        int[] widths = { 115, 210, 140, 140, 150, 510 };
        for (int i = 0; i < columns.length; i++) {
            TableColumn column = new TableColumn(table, SWT.LEFT);
            column.setText(columns[i]);
            column.setWidth(widths[i]);
        }
        table.setVisible(false);

        TabItem objectsTab = new TabItem(tabs, SWT.NONE);
        objectsTab.setText("Synchronization objects");
        objectTable = new Table(tabs, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        objectsTab.setControl(objectTable);
        objectTable.setHeaderVisible(true);
        objectTable.setLinesVisible(true);
        String[] objectColumns = { "Type", "Object", "Takes/Gets", "Gives/Puts",
                "Failures", "Blocking", "Paired blocks", "Longest block (us)",
                "Long blocks", "Unpaired blocks" };
        int[] objectWidths = { 130, 245, 110, 110, 90, 95, 115, 155, 100, 120 };
        for (int i = 0; i < objectColumns.length; i++) {
            TableColumn column = new TableColumn(objectTable, SWT.LEFT);
            column.setText(objectColumns[i]);
            column.setWidth(objectWidths[i]);
        }
        objectTable.setVisible(false);
        // No queue fill level, capacity, gate ownership or live debug state is inferred.
        // Creating/showing the view never reads CTF, starts a Job or polls a target.
    }

    /** Project context is provided explicitly by the Zephyr menu command. */
    public void displayProject(IProject project) {
        if (project != null && project.isAccessible()) contextProject = project;
    }

    private IProject selectedProject() {
        var selection = getSite().getWorkbenchWindow().getSelectionService().getSelection();
        if (selection instanceof IStructuredSelection selected && selected.size() == 1) {
            IProject project = SelectedProject.importedProject(selected.getFirstElement());
            if (project != null && project.isAccessible()) contextProject = project;
        }
        return contextProject != null && contextProject.isAccessible() ? contextProject : null;
    }

    private void chooseTrace() {
        DirectoryDialog dialog = new DirectoryDialog(getSite().getShell(), SWT.OPEN);
        dialog.setText("Select a saved Zephyr CTF directory");
        dialog.setMessage("Select .zew-traces/trace-*/ctf or its parent capture directory.");
        String choice = dialog.open();
        if (choice != null) load(Path.of(choice));
    }

    private Long currentThreshold() {
        try {
            long parsed = Long.parseLong(threshold.getText().strip());
            return parsed >= 0 && parsed <= MAX_THRESHOLD_US ? parsed : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private void thresholdChanged() {
        if (threshold == null || threshold.isDisposed()) return;
        Long value = currentThreshold();
        if (value == null) {
            generation.incrementAndGet();
            if (loadJob != null) loadJob.cancel();
            displayed = null;
            if (table != null && !table.isDisposed()) { table.removeAll(); table.setVisible(false); }
            if (objectTable != null && !objectTable.isDisposed()) {
                objectTable.removeAll(); objectTable.setVisible(false);
            }
            if (status != null && !status.isDisposed())
                status.setText("Enter a valid nonnegative threshold in microseconds.");
            return;
        }
        if (selectedTrace != null) load(selectedTrace); // re-run offline semantic detector
    }

    private void load(Path selected) {
        if (!Activator.getDefault().getPreferenceStore().getBoolean(WorkbenchPreferences.ENABLE_CONTENTION_VIEW)) {
            status.setText("Zephyr Contention Report view is disabled in Zephyr Workbench preferences.");
            table.removeAll();
            table.setVisible(false);
            objectTable.removeAll();
            objectTable.setVisible(false);
            return;
        }
        Long thresholdUs = currentThreshold();
        if (thresholdUs == null) {
            status.setText("Enter a valid nonnegative threshold in microseconds.");
            return;
        }
        IProject project = selectedProject();
        if (project == null) {
            status.setText("Select an imported Zephyr project in Project Explorer first.");
            return;
        }
        final Path build;
        try {
            var settings = ProjectContext.settings(project);
            build = ProjectContext.buildDirectory(project, settings);
            if (service == null || !build.equals(selectedBuild))
                service = new OfflineTraceAnalysisService(new OfflineTracePythonBridge(project, settings));
        } catch (Exception error) {
            status.setText("Cannot select build: " + error.getMessage());
            return;
        }
        selectedBuild = build;
        selectedTrace = selected.toAbsolutePath().normalize();
        int ticket = generation.incrementAndGet();
        if (loadJob != null) loadJob.cancel();
        displayed = null;
        table.removeAll();
        table.setVisible(false);
        objectTable.removeAll();
        objectTable.setVisible(false);
        priorities.setText("");
        showIncomplete(false);
        status.setText("Reading saved CTF resource events with offline Python...");
        OfflineTraceAnalysisService current = service;
        Path source = selectedTrace;
        loadJob = new Job("Review saved Zephyr contention events") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                OfflineTraceAnalysisService.ContentionResult result = null;
                OfflineTraceAnalysisService.AnalysisFailure failure = null;
                try {
                    result = current.analyseContention(source, build, thresholdUs);
                } catch (OfflineTraceAnalysisService.AnalysisFailure error) {
                    failure = error;
                    result = error.contention();
                }
                if (!monitor.isCanceled()) present(ticket, result, failure);
                return Status.OK_STATUS;
            }
        };
        loadJob.setUser(true);
        loadJob.schedule();
    }

    private void present(int ticket, OfflineTraceAnalysisService.ContentionResult report,
                         OfflineTraceAnalysisService.AnalysisFailure failure) {
        org.eclipse.swt.widgets.Display.getDefault().asyncExec(() -> {
            if (generation.get() != ticket || table == null || table.isDisposed()) return;
            render(report, failure);
        });
    }

    /** UI-thread-only rendering of the typed JSON analysis result. */
    private void render(OfflineTraceAnalysisService.ContentionResult report,
                        OfflineTraceAnalysisService.AnalysisFailure failure) {
        displayed = report;
        table.removeAll();
        table.setVisible(false);
        objectTable.removeAll();
        objectTable.setVisible(false);
        priorities.setText("");
        showIncomplete(report != null && report.incomplete());
        if (report == null) {
            status.setText(failure == null ? "Offline contention analysis unavailable."
                    : failure.kind() + ": " + failure.getMessage());
            return;
        }
        var model = OfflineTraceViewModels.contention(report);
        if (model.noPriorities()) priorities.setText(NO_PRIORITIES);
        table.setRedraw(false);
        try {
            for (var finding : model.rows()) {
                TableItem row = new TableItem(table, SWT.NONE);
                row.setText(new String[] {finding.kind(), finding.object(), finding.waiter(),
                        finding.holder(), finding.durationUs(), finding.explanation()});
            }
        } finally { table.setRedraw(true); }
        table.setVisible(!model.rows().isEmpty());
        objectTable.setRedraw(false);
        try {
            for (var observed : report.objects()) {
                TableItem row = new TableItem(objectTable, SWT.NONE);
                String longest = observed.maxBlockedNs() == null ? "unknown"
                        : formatMicroseconds(observed.maxBlockedNs());
                row.setText(new String[] { observed.kind(), observed.object(),
                        Integer.toString(observed.takeGet()), Integer.toString(observed.givePut()),
                        Integer.toString(observed.failures()),
                        Integer.toString(observed.blockingEvents()),
                        Integer.toString(observed.pairedBlocks()), longest,
                        Integer.toString(observed.longBlocks()),
                        Integer.toString(observed.unpairedBlocks()) });
            }
        } finally { objectTable.setRedraw(true); }
        objectTable.setVisible(!report.objects().isEmpty());
        if (model.rows().isEmpty() && report.objects().isEmpty())
            status.setText(OfflineTraceViewModels.CONTENTION_EMPTY
                    + " No identified synchronization objects. Saved CTF: " + report.trace());
        else status.setText("Observed blocking patterns: " + model.rows().size()
                + " | Synchronization objects: " + report.objects().size()
                + " | Unattributed events: " + report.unattributedEvents()
                + " | Threshold: " + report.thresholdUs() + " us"
                + " | Saved CTF: " + report.trace() + " | Selected build: " + report.build());
        if (report.incomplete()) status.setText(status.getText() + " | Trace incomplete.");
        table.getParent().layout(true, true);
    }

    private static String formatMicroseconds(long ns) {
        return (ns / 1000) + "." + String.format(java.util.Locale.ROOT, "%03d", ns % 1000);
    }

    private void showIncomplete(boolean visible) {
        if (incomplete == null || incomplete.isDisposed()) return;
        GridData grid = (GridData) incomplete.getLayoutData();
        grid.exclude = !visible;
        incomplete.setVisible(visible);
        incomplete.getParent().layout(true, true);
    }

    @Override public void setFocus() {
        if (threshold != null && !threshold.isDisposed()) threshold.setFocus();
    }

    @Override public void dispose() {
        generation.incrementAndGet();
        if (loadJob != null) loadJob.cancel();
        super.dispose();
    }
}
