// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
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
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
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

    private final AtomicInteger generation = new AtomicInteger();
    private Label banner;
    private Label status;
    private Text filter;
    private Table table;
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
        controls.setLayout(new GridLayout(3, false));
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

        table = new Table(parent, SWT.FULL_SELECTION | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
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
        // Intentionally no selection listeners, decoder, Job, file or target
        // access here. Opening the view is a constant-cost SWT setup.
    }

    private void chooseTrace() {
        DirectoryDialog dialog = new DirectoryDialog(getSite().getShell(), SWT.OPEN);
        dialog.setText("Select an existing saved Zephyr CTF directory");
        dialog.setMessage("Choose .zew-traces/trace-*/ctf or the containing trace-* directory.");
        String chosen = dialog.open();
        if (chosen != null) load(Path.of(chosen));
    }

    /** Retain the explicit project context without reading or decoding its trace. */
    public void displayProject(IProject project) {
        if (project != null && project.isAccessible()) contextProject = project;
    }

    private IProject selectedProject() {
        var selection = getSite().getWorkbenchWindow().getSelectionService().getSelection();
        if (selection instanceof IStructuredSelection items && items.size() == 1) {
            IProject selected = SelectedProject.importedProject(items.getFirstElement());
            if (selected != null && selected.isAccessible()) contextProject = selected;
        }
        return contextProject != null && contextProject.isAccessible() ? contextProject : null;
    }

    private void load(Path chosen) {
        final Path ctf = (Files.isDirectory(chosen.resolve("ctf")) ? chosen.resolve("ctf") : chosen)
                .toAbsolutePath().normalize();
        if (Files.isSymbolicLink(ctf) || !Files.isRegularFile(ctf.resolve("metadata"), LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(ctf.resolve("channel0_0"), LinkOption.NOFOLLOW_LINKS)) {
            status.setText("Select a saved CTF directory containing metadata and channel0_0.");
            return;
        }
        IProject project = selectedProject();
        if (project == null || !project.isAccessible()) {
            status.setText("Select the imported Zephyr project in Project Explorer to use its managed Python interpreter.");
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
        showIncomplete(false);
        status.setText("Reading saved CTF with the shared offline semantic normalizer...");
        loadJob = new Job("Read saved Zephyr Thread Timeline") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                Path temporary = null;
                try {
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    temporary = Files.createTempDirectory("zew-thread-timeline-");
                    for (String name : List.of("semantic_trace.py", "thread_timeline.py")) {
                        var entry = Activator.getDefault().getBundle()
                                .getEntry("tools/rtos_analysis/" + name);
                        if (entry == null) throw new IOException("Bundled trace tool missing: " + name);
                        try (var input = entry.openStream()) {
                            Files.copy(input, temporary.resolve(name));
                        }
                    }
                    var command = List.of(settings.getProperty("python"),
                            temporary.resolve("thread_timeline.py").toString(),
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
            if (error != null) status.setText(error);
            else refreshRows();
        });
    }

    private void showIncomplete(boolean visible) {
        if (banner == null || banner.isDisposed()) return;
        GridData grid = (GridData) banner.getLayoutData();
        grid.exclude = !visible;
        banner.setVisible(visible);
        banner.getParent().layout(true, true);
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
            status.setText("CTF decoding unavailable: install Babeltrace 2 bt2 for the selected Python. "
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
