// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
 * Explicitly loaded, offline CTF thread intervals. No analysis at startup,
 * no target/probe connection and no firmware or project-file mutation.
 * A missing scheduler switch is never replaced with an invented Running row.
 */
public final class ThreadTimelineView extends ViewPart {
    public static final String ID = Activator.ID + ".threadTimeline";
    public static final String NO_TRACE = "No trace loaded. Choose a saved CTF directory to inspect scheduler intervals.";
    public static final String NO_SCHEDULER =
            "No scheduler events found. Scheduler tracing was likely not enabled in the Zephyr build.";
    public static final String INCOMPLETE =
            "This trace is incomplete. Events after the truncation point are missing.";
    public static final String CPU_DISCLAIMER =
            "Derived from context-switch events. Not a hardware measurement.";

    private final AtomicInteger generation = new AtomicInteger();
    private Label banner;
    private Label status;
    private Text filter;
    private Table table;
    private TabFolder tabs;
    private Table cpuTable;
    private Text windowStart, windowEnd;
    private Button cpuApply;
    private Label cpuDisclaimer, cpuWarning, cpuMetrics, cpuStatus;
    private ThreadCpuLoad.Bounds cpuTraceBounds;
    private ThreadTimelineReport.Snapshot displayed;
    private Path selectedTrace;
    private IProject contextProject;
    private volatile Job loadJob;

    @Override public void createPartControl(Composite parent) {
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 12;
        layout.marginHeight = 10;
        parent.setLayout(layout);

        banner = new Label(parent, SWT.WRAP | SWT.BORDER);
        banner.setText(INCOMPLETE);
        banner.setBackground(parent.getDisplay().getSystemColor(SWT.COLOR_INFO_BACKGROUND));
        banner.setForeground(parent.getDisplay().getSystemColor(SWT.COLOR_INFO_FOREGROUND));
        GridData bannerData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        banner.setLayoutData(bannerData);
        banner.setVisible(false);
        bannerData.exclude = true;

        Composite controls = new Composite(parent, SWT.NONE);
        controls.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        controls.setLayout(new GridLayout(5, false));
        Button capture = new Button(controls, SWT.PUSH);
        capture.setText("1. Capture new CTF...");
        capture.setToolTipText("Explicitly reset/run/halt the target to save RAM CTF. Terminate active Debug first.");
        capture.addListener(SWT.Selection, ignored -> captureNew());
        Button latest = new Button(controls, SWT.PUSH);
        latest.setText("2. Open latest capture");
        latest.setToolTipText("Read the newest saved .zew-traces/trace-*/ctf from this application, without target access.");
        latest.addListener(SWT.Selection, ignored -> openLatest());
        Button open = new Button(controls, SWT.PUSH);
        open.setText("Open saved CTF trace...");
        open.addListener(SWT.Selection, ignored -> chooseTrace());
        Button reload = new Button(controls, SWT.PUSH);
        reload.setText("Reload selected trace");
        reload.addListener(SWT.Selection, ignored -> {
            if (selectedTrace != null) load(selectedTrace);
            else status.setText(NO_TRACE);
        });
        filter = new Text(controls, SWT.SEARCH | SWT.ICON_SEARCH | SWT.CANCEL);
        filter.setMessage("Filter by thread name");
        filter.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        filter.addModifyListener(ignored -> refreshRows());

        status = new Label(parent, SWT.WRAP);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        status.setText(NO_TRACE);
        var guidance = new Label(parent, SWT.WRAP);
        guidance.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        guidance.setText("To begin: build and flash CONFIG_TRACING=y + CONFIG_TRACING_CTF=y "
                + "+ CONFIG_TRACING_BACKEND_RAM=y. Terminate Debug before Capture; "
                + "the capture resets/runs/halts the MCU. Then open the latest saved CTF. "
                + "Zephyr 4.4 packed RAM CTF decodes natively; other CTF formats may need Babeltrace 2 bt2.");

        tabs = new TabFolder(parent, SWT.NONE);
        tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        TabItem timelineTab = new TabItem(tabs, SWT.NONE);
        timelineTab.setText("Thread Timeline");
        Composite timelinePage = new Composite(tabs, SWT.NONE);
        timelinePage.setLayout(new GridLayout(1, false));
        timelineTab.setControl(timelinePage);
        table = new Table(timelinePage, SWT.FULL_SELECTION | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        String[] titles = { "Start (ns)", "End (ns)", "Duration (us)",
                            "Thread Name", "Priority", "State" };
        int[] widths = { 150, 150, 140, 220, 90, 130 };
        for (int i = 0; i < titles.length; i++) {
            TableColumn column = new TableColumn(table, SWT.LEFT);
            column.setText(titles[i]);
            column.setWidth(widths[i]);
        }

        TabItem cpuTab = new TabItem(tabs, SWT.NONE);
        cpuTab.setText("Zephyr CPU Load");
        Composite cpuPage = new Composite(tabs, SWT.NONE);
        cpuPage.setLayout(new GridLayout(1, false));
        cpuTab.setControl(cpuPage);
        Composite window = new Composite(cpuPage, SWT.NONE);
        window.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        window.setLayout(new GridLayout(5, false));
        new Label(window, SWT.NONE).setText("Start (ns)");
        windowStart = new Text(window, SWT.BORDER);
        windowStart.setLayoutData(new GridData(165, SWT.DEFAULT));
        new Label(window, SWT.NONE).setText("End (ns)");
        windowEnd = new Text(window, SWT.BORDER);
        windowEnd.setLayoutData(new GridData(165, SWT.DEFAULT));
        for (Text field : new Text[] { windowStart, windowEnd }) {
            field.setEnabled(false);
            field.addVerifyListener(event -> {
                if (!event.text.matches("[0-9]*")) event.doit = false;
            });
        }
        cpuApply = new Button(window, SWT.PUSH);
        cpuApply.setText("Apply window");
        cpuApply.setEnabled(false);
        cpuApply.addListener(SWT.Selection, ignored -> refreshCpuLoad());

        cpuStatus = new Label(cpuPage, SWT.WRAP);
        cpuStatus.setText(NO_TRACE);
        cpuStatus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        cpuMetrics = new Label(cpuPage, SWT.WRAP);
        cpuMetrics.setText("");
        cpuMetrics.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        cpuWarning = new Label(cpuPage, SWT.WRAP);
        cpuWarning.setText("");
        cpuWarning.setForeground(cpuPage.getDisplay().getSystemColor(SWT.COLOR_DARK_RED));
        cpuWarning.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        showCpuWarning(false);
        cpuDisclaimer = new Label(cpuPage, SWT.WRAP);
        cpuDisclaimer.setText(CPU_DISCLAIMER);
        cpuDisclaimer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        cpuTable = new Table(cpuPage, SWT.FULL_SELECTION | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        cpuTable.setHeaderVisible(true);
        cpuTable.setLinesVisible(true);
        cpuTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        String[] cpuTitles = { "Thread Name", "Running (us)", "Percentage" };
        int[] cpuWidths = { 250, 190, 140 };
        for (int i = 0; i < cpuTitles.length; i++) {
            TableColumn column = new TableColumn(cpuTable, SWT.LEFT);
            column.setText(cpuTitles[i]);
            column.setWidth(cpuWidths[i]);
        }
        // No decoder, Job, file read or target access happens when either
        // tab is created or selected. Calculate only after explicit trace load.
    }

    private void chooseTrace() {
        Path chosen = SavedCtfFilePicker.choose(getSite().getShell());
        if (chosen != null) load(chosen);
    }

    private void captureNew() {
        IProject selected = selectedProject();
        if (selected == null) {
            status.setText("Select one imported Zephyr application in Project Explorer or "
                    + "choose its active Debug thread first. Capture cannot guess the project.");
            return;
        }
        ZephyrTraceHandler.capture(selected, getSite().getShell());
    }

    private void openLatest() {
        IProject selected = selectedProject();
        if (selected == null) {
            status.setText("Select an imported Zephyr project or an active Zephyr Debug frame "
                    + "to find its saved CTF captures.");
            return;
        }
        try {
            var latest = TraceCaptureWorkflow.latest(selected);
            if (latest.isEmpty()) {
                status.setText("No saved CTF found in " + selected.getName()
                        + "/.zew-traces. Build and flash RAM CTF firmware, terminate Debug, "
                        + "then click 1. Capture new CTF...");
                return;
            }
            load(latest.get());
        } catch (IOException error) {
            status.setText("Cannot enumerate saved CTF captures: " + error.getMessage());
        }
    }

    /** Reuse only the exact saved CTF generated by an explicitly requested capture. */
    /** Do not overwrite another application's already selected trace. */
    public boolean acceptsCapture(IProject selected) {
        return selected != null && (contextProject == null || contextProject.equals(selected));
    }

    public void loadSavedCapture(Path ctf) {
        if (ctf != null && table != null && !table.isDisposed()) load(ctf);
    }

    /** Retain the explicit project context without reading or decoding its trace. */
    public void displayProject(IProject project) {
        if (project != null && project.isAccessible()) contextProject = project;
    }

    private IProject selectedProject() {
        if (contextProject != null && contextProject.isAccessible()) return contextProject;
        contextProject = TraceCaptureWorkflow.resolve(getSite().getWorkbenchWindow());
        return contextProject;
    }

    private void load(Path chosen) {
        final Path ctf = (Files.isDirectory(chosen.resolve("ctf")) ? chosen.resolve("ctf") : chosen)
                .toAbsolutePath().normalize();
        if (!TraceCaptureWorkflow.isSavedCtf(ctf)) {
            status.setText("Select a saved CTF directory containing metadata and channel0_0.");
            return;
        }
        IProject project = selectedProject();
        if (project == null || !project.isAccessible()) {
            status.setText("Select an imported Zephyr project or its active Debug frame to use the managed Python interpreter.");
            return;
        }
        final java.util.Properties settings;
        try {
            settings = ProjectContext.settings(project);
            if (settings.getProperty("python") == null || settings.getProperty("workspace") == null)
                throw new IOException("Managed Python/Zephyr workspace is not configured");
        } catch (Exception error) {
            status.setText("Cannot load saved trace: " + error.getMessage());
            return;
        }
        selectedTrace = ctf;
        int ticket = generation.incrementAndGet();
        if (loadJob != null) loadJob.cancel();
        displayed = null;
        table.removeAll();
        clearCpuLoad("Reading saved CTF with the shared offline semantic normalizer...");
        showIncomplete(false);
        status.setText("Reading saved CTF with the shared offline semantic normalizer...");
        loadJob = new Job("Read saved Zephyr Thread Timeline") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                Path temporary = null;
                try {
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    temporary = Files.createTempDirectory("zew-thread-timeline-");
                    for (String name : List.of("semantic_trace.py", "zephyr_ctf_binary.py", "thread_timeline.py")) {
                        var entry = Activator.getDefault().getBundle()
                                .getEntry("tools/rtos_analysis/" + name);
                        if (entry == null) throw new IOException("Bundled trace tool missing: " + name);
                        try (var input = entry.openStream()) {
                            Files.copy(input, temporary.resolve(name));
                        }
                    }
                    var command = TracePythonLaunch.command(settings.getProperty("python"),
                            temporary.resolve("thread_timeline.py"),
                            "--ctf", ctf.toString());
                    String output = new ProcessRunner().run(command,
                            project.getLocation().toFile().toPath(),
                            Duration.ofSeconds(45), monitor::isCanceled,
                            ignored -> {}, ProjectContext.environment(settings))
                            .requireCompleteOutput();
                    ThreadTimelineReport.Snapshot snapshot = ThreadTimelineReport.parse(output);
                    if (!monitor.isCanceled()) present(ticket, snapshot, null);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return Status.CANCEL_STATUS;
                } catch (Exception error) {
                    if (!monitor.isCanceled()) present(ticket, null, "Cannot load saved trace: " + error.getMessage());
                } finally {
                    if (temporary != null) {
                        try (var files = Files.walk(temporary)) {
                            for (Path file : files.sorted(Comparator.reverseOrder()).toList())
                                Files.deleteIfExists(file);
                        } catch (IOException ignored) { /* Temporary helper cleanup is best-effort. */ }
                    }
                }
                return Status.OK_STATUS;
            }
        };
        loadJob.setUser(true);
        loadJob.schedule();
    }

    private void present(int ticket, ThreadTimelineReport.Snapshot snapshot, String error) {
        org.eclipse.swt.widgets.Display.getDefault().asyncExec(() -> {
            if (generation.get() != ticket || table == null || table.isDisposed()) return;
            displayed = snapshot;
            table.removeAll();
            showIncomplete(snapshot != null && snapshot.incomplete());
            if (error != null) {
                status.setText(error);
                clearCpuLoad(error);
            } else {
                refreshRows();
                prepareCpuLoad();
            }
        });
    }

    private void showIncomplete(boolean visible) {
        if (banner == null || banner.isDisposed()) return;
        GridData grid = (GridData) banner.getLayoutData();
        grid.exclude = !visible;
        banner.setVisible(visible);
        banner.getParent().layout(true, true);
    }

    private static String displayPercent(java.math.BigDecimal percent) {
        return percent.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private void showCpuWarning(boolean visible) {
        if (cpuWarning == null || cpuWarning.isDisposed()) return;
        GridData grid = (GridData) cpuWarning.getLayoutData();
        grid.exclude = !visible;
        cpuWarning.setVisible(visible);
        cpuWarning.getParent().layout(true, true);
    }

    private void clearCpuLoad(String message) {
        if (cpuTable == null || cpuTable.isDisposed()) return;
        cpuTable.removeAll();
        cpuTraceBounds = null;
        windowStart.setText("");
        windowEnd.setText("");
        windowStart.setEnabled(false);
        windowEnd.setEnabled(false);
        cpuApply.setEnabled(false);
        cpuMetrics.setText("");
        cpuWarning.setText("");
        showCpuWarning(false);
        cpuStatus.setText(message);
    }

    private void prepareCpuLoad() {
        if (displayed != null && displayed.incomplete()) {
            clearCpuLoad("Incomplete CTF or full RAM buffer: observed thread intervals "
                    + "may be inspected, but CPU percentages are withheld.");
            return;
        }
        if (displayed == null || displayed.schedulerEvents() == 0
                || displayed.intervals().isEmpty()) {
            clearCpuLoad(displayed != null && displayed.schedulerEvents() == 0
                    ? NO_SCHEDULER : "No complete scheduler interval is available for CPU-load analysis.");
            return;
        }
        try {
            cpuTraceBounds = displayed.traceStartNs() != null && displayed.traceEndNs() != null
                    ? new ThreadCpuLoad.Bounds(displayed.traceStartNs(), displayed.traceEndNs())
                    : ThreadCpuLoad.observedBounds(displayed.intervals());
            windowStart.setText(Long.toString(cpuTraceBounds.startNs()));
            windowEnd.setText(Long.toString(cpuTraceBounds.endNs()));
            windowStart.setEnabled(true);
            windowEnd.setEnabled(true);
            cpuApply.setEnabled(true);
            refreshCpuLoad();
        } catch (IllegalArgumentException error) {
            clearCpuLoad(error.getMessage());
        }
    }

    private void refreshCpuLoad() {
        if (cpuTable == null || cpuTable.isDisposed() || displayed == null) return;
        cpuTable.removeAll();
        cpuMetrics.setText("");
        cpuWarning.setText("");
        showCpuWarning(false);
        if (displayed.intervals().isEmpty()) {
            cpuStatus.setText(NO_SCHEDULER);
            return;
        }
        final ThreadCpuLoad.Result load;
        try {
            long start = Long.parseLong(windowStart.getText().strip());
            long end = Long.parseLong(windowEnd.getText().strip());
            load = ThreadCpuLoad.calculate(displayed.intervals(), start, end, cpuTraceBounds);
        } catch (NumberFormatException error) {
            cpuStatus.setText("Start and End must be valid integer nanosecond values.");
            return;
        } catch (IllegalArgumentException error) {
            cpuStatus.setText(error.getMessage());
            return;
        }
        cpuTable.setRedraw(false);
        try {
            for (ThreadCpuLoad.ThreadShare thread : load.threads()) {
                TableItem row = new TableItem(cpuTable, SWT.NONE);
                row.setText(new String[] {
                        thread.threadName().isEmpty() ? "(unnamed thread)" : thread.threadName(),
                        thread.runningUs(), displayPercent(thread.percentage()) + "%"
                });
            }
        } finally {
            cpuTable.setRedraw(true);
        }
        cpuMetrics.setText("ISR time: " + load.isrUs() + " us ("
                + displayPercent(load.isrPercentage()) + "% of window)"
                + "   |   Idle time: " + displayPercent(load.idlePercentage()) + "% of window");
        if (load.warnUnknown()) {
            cpuWarning.setText("Warning: " + displayPercent(load.unknownPercentage())
                    + "% of the selected window is Unknown ("
                    + load.unknownUs() + " us).");
            showCpuWarning(true);
        }
        cpuStatus.setText("Window: " + load.startNs() + "–" + load.endNs()
                + " ns. Thread shares use the full selected window; unknown time is not assigned to threads.");
    }

    private void refreshRows() {
        if (table == null || table.isDisposed() || displayed == null) return;
        String needle = filter.getText().strip().toLowerCase(Locale.ROOT);
        table.setRedraw(false);
        try {
            table.removeAll();
            for (ThreadTimelineReport.Interval interval : displayed.intervals()) {
                if (!interval.threadName().toLowerCase(Locale.ROOT).contains(needle)) continue;
                TableItem item = new TableItem(table, SWT.NONE);
                item.setText(new String[] { String.valueOf(interval.startNs()),
                        String.valueOf(interval.endNs()), interval.durationUs(),
                        interval.threadName(), interval.priority(), interval.state() });
                if ("Unknown".equals(interval.state())) {
                    item.setBackground(table.getDisplay().getSystemColor(SWT.COLOR_WIDGET_LIGHT_SHADOW));
                    item.setForeground(table.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
                }
            }
        } finally {
            table.setRedraw(true);
        }
        if (displayed.decoderUnavailable())
            status.setText("CTF format not handled by the native Zephyr decoder; configure Babeltrace 2 bt2. "
                    + displayed.detail());
        else if (displayed.schedulerEvents() == 0)
            status.setText(NO_SCHEDULER + " " + displayed.detail());
        else if (displayed.intervals().isEmpty())
            status.setText("No complete scheduler interval could be proven from the saved events. "
                    + "Check switch-in/out coverage and trace truncation.");
        else
            status.setText("Showing " + table.getItemCount() + " of " + displayed.intervals().size()
                    + " observed intervals. " + displayed.unknownEvents() + " events were not understood."
                    + (displayed.detail().isEmpty() ? "" : " " + displayed.detail()));
    }

    @Override public void setFocus() {
        if (filter != null && !filter.isDisposed()) filter.setFocus();
    }

    @Override public void dispose() {
        generation.incrementAndGet();
        if (loadJob != null) loadJob.cancel();
        super.dispose();
    }
}
