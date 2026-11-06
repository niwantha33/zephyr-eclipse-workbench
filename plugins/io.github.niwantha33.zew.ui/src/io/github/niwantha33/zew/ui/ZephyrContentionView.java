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
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;
import io.github.niwantha33.zew.core.ProcessRunner;

/**
 * Read-only, on-demand evidence-qualified analysis of SAVED semantic CTF
 * events. No target/probe access, startup analysis or timeline visualization.
 */
public final class ZephyrContentionView extends ViewPart {
    public static final String ID = Activator.ID + ".contentionReport";
    public static final String DISCLAIMER =
            "Findings are inferred from trace events. A reported priority inversion means "
            + "the pattern was observed in the event stream; it does not prove that a deadline was missed.";
    public static final String NO_TRACE =
            "No trace loaded. Select an imported Zephyr project and open a saved CTF trace.";
    public static final String NO_PRIORITIES =
            "Pattern A disabled: no numeric thread priorities were observed in this trace.";
    public static final long DEFAULT_THRESHOLD_US = 1000L;
    private static final long MAX_THRESHOLD_US = 86_400_000_000L;

    private final AtomicInteger generation = new AtomicInteger();
    private volatile Job loadJob;
    private IProject contextProject;
    private Path selectedTrace;
    private ContentionReport.Snapshot displayed;
    private Text threshold;
    private Label status, priorities, incomplete, disclaimer;
    private Table table;

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

        table = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        String[] columns = { "Kind", "Object", "Waiter", "Holder", "Duration (us)", "Explanation" };
        int[] widths = { 115, 210, 140, 140, 150, 510 };
        for (int i = 0; i < columns.length; i++) {
            TableColumn column = new TableColumn(table, SWT.LEFT);
            column.setText(columns[i]);
            column.setWidth(widths[i]);
        }
        // Creating or showing the view never reads CTF, starts a Job, or polls a target.
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
            if (table != null && !table.isDisposed()) table.removeAll();
            if (status != null && !status.isDisposed())
                status.setText("Enter a valid nonnegative threshold in microseconds.");
            return;
        }
        if (selectedTrace != null) load(selectedTrace); // re-run offline semantic detector
    }

    private void load(Path selected) {
        Long thresholdUs = currentThreshold();
        if (thresholdUs == null) {
            status.setText("Enter a valid nonnegative threshold in microseconds.");
            return;
        }
        Path ctf = (Files.isDirectory(selected.resolve("ctf")) ? selected.resolve("ctf") : selected)
                .toAbsolutePath().normalize();
        if (Files.isSymbolicLink(ctf)
                || !Files.isRegularFile(ctf.resolve("metadata"), LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(ctf.resolve("channel0_0"), LinkOption.NOFOLLOW_LINKS)) {
            status.setText("Select a saved CTF directory containing metadata and channel0_0.");
            return;
        }
        IProject project = selectedProject();
        if (project == null) {
            status.setText("Select an imported Zephyr project in Project Explorer first.");
            return;
        }
        final java.util.Properties settings;
        try {
            settings = ProjectContext.settings(project);
            if (settings.getProperty("python") == null || settings.getProperty("workspace") == null)
                throw new IOException("Managed project Python/Zephyr workspace is not configured");
        } catch (Exception error) {
            status.setText("Cannot analyze saved CTF: " + error.getMessage());
            return;
        }
        selectedTrace = ctf;
        int ticket = generation.incrementAndGet();
        if (loadJob != null) loadJob.cancel();
        displayed = null;
        table.removeAll();
        priorities.setText("");
        showIncomplete(false);
        status.setText("Analyzing saved CTF resource events with the offline semantic detector...");
        loadJob = new Job("Review saved Zephyr contention events") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                Path temporary = null;
                try {
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    temporary = Files.createTempDirectory("zew-contention-");
                    for (String name : List.of("semantic_trace.py", "contention_detector.py",
                                               "contention_report.py")) {
                        var entry = Activator.getDefault().getBundle()
                                .getEntry("tools/rtos_analysis/" + name);
                        if (entry == null) throw new IOException("Bundled tool missing: " + name);
                        try (var input = entry.openStream()) {
                            Files.copy(input, temporary.resolve(name));
                        }
                    }
                    var command = List.of(settings.getProperty("python"),
                            temporary.resolve("contention_report.py").toString(),
                            "--ctf", ctf.toString(),
                            "--threshold-us", Long.toString(thresholdUs));
                    String output = new ProcessRunner().run(command,
                            project.getLocation().toFile().toPath(),
                            Duration.ofSeconds(45), monitor::isCanceled,
                            ignored -> {}, ProjectContext.environment(settings))
                            .requireCompleteOutput();
                    ContentionReport.Snapshot report = ContentionReport.parse(output);
                    if (!monitor.isCanceled()) present(ticket, report, null);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return Status.CANCEL_STATUS;
                } catch (Exception error) {
                    if (!monitor.isCanceled())
                        present(ticket, null, "Saved trace analysis failed: " + error.getMessage());
                } finally {
                    if (temporary != null) {
                        try (var files = Files.walk(temporary)) {
                            for (Path file : files.sorted(Comparator.reverseOrder()).toList())
                                Files.deleteIfExists(file);
                        } catch (IOException ignored) { /* best-effort temporary tool cleanup */ }
                    }
                }
                return Status.OK_STATUS;
            }
        };
        loadJob.setUser(true);
        loadJob.schedule();
    }

    private void present(int ticket, ContentionReport.Snapshot report, String error) {
        org.eclipse.swt.widgets.Display.getDefault().asyncExec(() -> {
            if (generation.get() != ticket || table == null || table.isDisposed()) return;
            render(report, error);
        });
    }

    /** UI-thread-only rendering, exercised by installed Eclipse smoke tests. */
    private void render(ContentionReport.Snapshot report, String error) {
        if (table == null || table.isDisposed()) return;
        displayed = report;
        table.removeAll();
        priorities.setText("");
        showIncomplete(report != null && report.incomplete());
        if (error != null) {
            status.setText(error);
            return;
        }
        if (report.decoderUnavailable()) {
            status.setText("CTF decoder unavailable. Install Babeltrace 2 bt2 for the managed Python. "
                    + report.error());
            return;
        }
        if (!report.inversionEnabled())
            priorities.setText(NO_PRIORITIES);
        else
            priorities.setText("Pattern A requires observed priorities for the holder, waiter and running medium thread.");
        table.setRedraw(false);
        try {
            for (ContentionReport.Finding finding : report.findings()) {
                TableItem row = new TableItem(table, SWT.NONE);
                row.setText(new String[] {
                        finding.kind(), finding.objectName(), finding.waiter(),
                        finding.holder(), finding.durationUs(), finding.explanation(),
                });
            }
        } finally {
            table.setRedraw(true);
        }
        if (report.observedResourceEvents() == 0)
            status.setText("No mutex or semaphore events were observed in this trace. "
                    + "Resource tracing may not have been enabled.");
        else if (report.findings().isEmpty())
            status.setText("No supported inversion or wait-above-threshold patterns found. "
                    + "This does not prove that resource contention never occurred.");
        else
            status.setText("Showing " + table.getItemCount()
                    + " observed patterns at " + threshold.getText()
                    + " us long-wait threshold."
                    + (report.error().isEmpty() ? "" : " " + report.error()));
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
