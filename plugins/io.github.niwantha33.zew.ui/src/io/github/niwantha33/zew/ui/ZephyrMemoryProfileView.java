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
import org.eclipse.ui.part.ViewPart;
import io.github.niwantha33.zew.core.ProcessRunner;

/**
 * Observed, offline allocation evidence from saved CTF only.
 * This view does not infer missing allocation sizes or claim memory leaks.
 * No file IO, trace decoding or target connection occurs when opening.
 */
public final class ZephyrMemoryProfileView extends ViewPart {
    public static final String ID = Activator.ID + ".memoryProfile";
    public static final String NO_TRACE =
            "No trace loaded. Select an imported Zephyr project and open a saved CTF trace.";
    public static final String NO_EVENTS = MemoryTraceReport.NO_EVENTS;
    public static final String INCOMPLETE = MemoryTraceReport.INCOMPLETE;

    private final AtomicInteger generation = new AtomicInteger();
    private IProject contextProject;
    private Path selectedTrace;
    private volatile Job loadJob;
    private MemoryTraceReport.Snapshot displayed;

    private Label status;
    private Label summary;
    private Label imbalance;
    private Label incomplete;
    private Label diagnostics;
    private Table table;

    @Override public void createPartControl(Composite parent) {
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 12;
        layout.marginHeight = 10;
        parent.setLayout(layout);

        Composite controls = new Composite(parent, SWT.NONE);
        controls.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        controls.setLayout(new GridLayout(3, false));
        Button open = new Button(controls, SWT.PUSH);
        open.setText("Open saved CTF trace...");
        open.addListener(SWT.Selection, ignored -> chooseTrace());
        Button reload = new Button(controls, SWT.PUSH);
        reload.setText("Reload saved trace");
        reload.addListener(SWT.Selection, ignored -> {
            if (selectedTrace == null) status.setText(NO_TRACE);
            else load(selectedTrace);
        });
        Label description = new Label(controls, SWT.WRAP);
        description.setText("Allocation evidence from saved CTF events");
        description.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        status = new Label(parent, SWT.WRAP);
        status.setText(NO_TRACE);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        summary = new Label(parent, SWT.WRAP);
        summary.setText("");
        summary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        imbalance = new Label(parent, SWT.WRAP);
        imbalance.setText("");
        imbalance.setForeground(parent.getDisplay().getSystemColor(SWT.COLOR_DARK_RED));
        imbalance.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        showOptional(imbalance, false);

        incomplete = new Label(parent, SWT.WRAP | SWT.BORDER);
        incomplete.setText(INCOMPLETE);
        incomplete.setForeground(parent.getDisplay().getSystemColor(SWT.COLOR_DARK_RED));
        incomplete.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        showOptional(incomplete, false);

        diagnostics = new Label(parent, SWT.WRAP);
        diagnostics.setText("");
        diagnostics.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        showOptional(diagnostics, false);

        table = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        String[] headings = { "Pool / Slab", "Alloc count", "Free count", "Peak bytes" };
        int[] widths = { 280, 150, 150, 160 };
        for (int i = 0; i < headings.length; i++) {
            TableColumn column = new TableColumn(table, SWT.LEFT);
            column.setText(headings[i]);
            column.setWidth(widths[i]);
        }
        // Passive startup; selected CTF decoded only on explicit button click.
    }

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

    private void chooseTrace() {
        DirectoryDialog dialog = new DirectoryDialog(getSite().getShell(), SWT.OPEN);
        dialog.setText("Select a saved Zephyr CTF directory");
        dialog.setMessage("Choose .zew-traces/trace-*/ctf or the containing trace-* directory.");
        String path = dialog.open();
        if (path != null) load(Path.of(path));
    }

    private void load(Path selected) {
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
            status.setText("Cannot read memory CTF: " + error.getMessage());
            return;
        }

        selectedTrace = ctf;
        int ticket = generation.incrementAndGet();
        if (loadJob != null) loadJob.cancel();
        displayed = null;
        clearReport();
        status.setText("Reading saved CTF allocation evidence...");
        loadJob = new Job("Review saved Zephyr memory allocation evidence") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                Path temporary = null;
                try {
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    temporary = Files.createTempDirectory("zew-memory-profile-");
                    for (String filename : List.of(
                            "semantic_trace.py", "memory_profiler.py", "memory_profile_report.py")) {
                        var entry = Activator.getDefault().getBundle()
                                .getEntry("tools/rtos_analysis/" + filename);
                        if (entry == null) throw new IOException("Bundled trace tool missing: " + filename);
                        try (var input = entry.openStream()) {
                            Files.copy(input, temporary.resolve(filename));
                        }
                    }
                    var command = List.of(settings.getProperty("python"),
                            temporary.resolve("memory_profile_report.py").toString(),
                            "--ctf", ctf.toString());
                    String output = new ProcessRunner().run(command,
                            project.getLocation().toFile().toPath(),
                            Duration.ofSeconds(45), monitor::isCanceled,
                            ignored -> {}, ProjectContext.environment(settings))
                            .requireCompleteOutput();
                    MemoryTraceReport.Snapshot snapshot = MemoryTraceReport.parse(output);
                    if (!monitor.isCanceled()) present(ticket, snapshot, null);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return Status.CANCEL_STATUS;
                } catch (Exception error) {
                    if (!monitor.isCanceled())
                        present(ticket, null, "Cannot read saved memory trace: " + error.getMessage());
                } finally {
                    if (temporary != null) {
                        try (var paths = Files.walk(temporary)) {
                            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                                Files.deleteIfExists(path);
                        } catch (IOException ignored) { /* best effort temporary helper cleanup */ }
                    }
                }
                return Status.OK_STATUS;
            }
        };
        loadJob.setUser(true);
        loadJob.schedule();
    }

    private void present(int ticket, MemoryTraceReport.Snapshot snapshot, String error) {
        org.eclipse.swt.widgets.Display.getDefault().asyncExec(() -> {
            if (generation.get() != ticket || table == null || table.isDisposed()) return;
            render(snapshot, error);
        });
    }

    /** UI-thread-only presentation of already normalized, offline results. */
    private void render(MemoryTraceReport.Snapshot snapshot, String error) {
        if (table == null || table.isDisposed()) return;
        clearReport();
        displayed = snapshot;
        if (error != null) {
            status.setText(error);
            return;
        }
        if (snapshot.decoderUnavailable()) {
            status.setText("CTF decoder unavailable. Install Babeltrace 2 bt2 for the managed Python. "
                    + snapshot.diagnostics());
            return;
        }
        if (snapshot.noEvents()) {
            status.setText(NO_EVENTS);
            return;
        }

        status.setText(snapshot.message());
        summary.setText("Observed current: " + number(snapshot.currentCount()) + " allocations ("
                + number(snapshot.currentBytes()) + " bytes). Observed peak: "
                + number(snapshot.peakBytes()) + " bytes at "
                + number(snapshot.peakTimestampNs()) + " ns.");
        if (snapshot.incomplete()) {
            // Never display a leak-style balance sentence on truncated/lossy CTF.
            incomplete.setText(INCOMPLETE);
            showOptional(incomplete, true);
        } else if (!snapshot.imbalance().isEmpty()) {
            imbalance.setText(snapshot.imbalance());
            showOptional(imbalance, true);
        }

        String explain = snapshot.diagnostics();
        if (!snapshot.bytesComplete()) {
            explain = "Byte figures are unavailable where allocation sizes or pairing are missing. "
                    + "No size was estimated."
                    + (explain.isEmpty() ? "" : " " + explain);
        }
        if (snapshot.stepsLimited() || snapshot.poolsLimited())
            explain += (explain.isEmpty() ? "" : " ")
                    + "The displayed report was bounded; full-trace calculations were preserved.";
        if (!explain.isEmpty()) {
            diagnostics.setText(explain);
            showOptional(diagnostics, true);
        }

        table.setRedraw(false);
        try {
            for (MemoryTraceReport.Pool pool : snapshot.pools()) {
                TableItem row = new TableItem(table, SWT.NONE);
                row.setText(new String[] {
                        pool.name(),
                        Integer.toString(pool.allocCount()),
                        Integer.toString(pool.freeCount()),
                        number(pool.peakBytes()),
                });
            }
        } finally {
            table.setRedraw(true);
        }
    }

    private static String number(Object value) {
        return value == null ? "unavailable" : value.toString();
    }

    private void clearReport() {
        if (table == null || table.isDisposed()) return;
        table.removeAll();
        summary.setText("");
        imbalance.setText("");
        incomplete.setText(INCOMPLETE);
        diagnostics.setText("");
        showOptional(imbalance, false);
        showOptional(incomplete, false);
        showOptional(diagnostics, false);
    }

    private void showOptional(Label control, boolean visible) {
        if (control == null || control.isDisposed()) return;
        GridData data = (GridData) control.getLayoutData();
        data.exclude = !visible;
        control.setVisible(visible);
        control.getParent().layout(true, true);
    }

    @Override public void setFocus() {
        if (table != null && !table.isDisposed()) table.setFocus();
    }

    @Override public void dispose() {
        generation.incrementAndGet();
        if (loadJob != null) loadJob.cancel();
        super.dispose();
    }
}
