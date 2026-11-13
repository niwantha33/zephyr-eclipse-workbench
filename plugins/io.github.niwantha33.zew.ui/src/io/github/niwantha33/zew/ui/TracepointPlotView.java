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
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.part.ViewPart;
import io.github.niwantha33.zew.core.ProcessRunner;

/**
 * Explicitly loaded offline CTF application trace samples. Read-only.
 * The project configuration and trace are not opened at view startup.
 * A bounded timestamp/value table is used instead of an embedded chart widget.
 */
public final class TracepointPlotView extends ViewPart {
    public static final String ID = Activator.ID + ".tracepointPlot";
    public static final String NO_TRACE =
            "No trace loaded. Select an imported Zephyr project and open a saved CTF trace.";
    public static final String CONFIG_FILE = "zephyr-tracepoints.properties";

    private final AtomicInteger generation = new AtomicInteger();
    private IProject contextProject;
    private Path selectedTrace;
    private volatile Job loadJob;
    private TracepointReport.Snapshot displayed;
    private Label status;
    private Label configStatus;
    private Label incompleteBanner;
    private Label statistics;
    private Combo selector;
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
        var hint = new Label(controls, SWT.WRAP);
        hint.setText("Offline CTF / user-defined named events");
        hint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        status = new Label(parent, SWT.WRAP);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        status.setText(NO_TRACE);

        incompleteBanner = new Label(parent, SWT.WRAP | SWT.BORDER);
        incompleteBanner.setForeground(parent.getDisplay().getSystemColor(SWT.COLOR_DARK_RED));
        incompleteBanner.setText("This trace is incomplete. Some application samples may be missing.");
        incompleteBanner.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        showIncomplete(false);

        configStatus = new Label(parent, SWT.WRAP);
        configStatus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        configStatus.setText("Tracepoints are driven only by " + CONFIG_FILE + " in the project root.");

        Composite selected = new Composite(parent, SWT.NONE);
        selected.setLayout(new GridLayout(2, false));
        selected.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        new Label(selected, SWT.NONE).setText("Tracepoint");
        selector = new Combo(selected, SWT.DROP_DOWN | SWT.READ_ONLY);
        selector.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        selector.setEnabled(false);
        selector.addListener(SWT.Selection, ignored -> refreshSamples());

        statistics = new Label(parent, SWT.WRAP);
        statistics.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        statistics.setText("Min: —   Max: —   Mean: —");

        var title = new Label(parent, SWT.WRAP);
        title.setText("Time-series samples (table view)");
        title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        table = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        for (String name : List.of("Timestamp (ns)", "Value")) {
            TableColumn col = new TableColumn(table, SWT.LEFT);
            col.setText(name);
            col.setWidth(name.equals("Value") ? 200 : 230);
        }
        // View construction is passive: no config parsing, subprocess, Job,
        // CTF decoding, target access or polling at IDE startup.
    }

    /** Bind an explicitly selected project; never auto-load its configuration. */
    public void displayProject(IProject project) {
        if (project != null && project.isAccessible()) contextProject = project;
    }

    private IProject selectedProject() {
        var selection = getSite().getWorkbenchWindow().getSelectionService().getSelection();
        if (selection instanceof IStructuredSelection items && items.size() == 1) {
            IProject project = SelectedProject.importedProject(items.getFirstElement());
            if (project != null && project.isAccessible()) contextProject = project;
        }
        return contextProject != null && contextProject.isAccessible() ? contextProject : null;
    }

    private void chooseTrace() {
        Path chosen = SavedCtfFilePicker.choose(getSite().getShell());
        if (chosen != null) load(chosen);
    }

    private void load(Path chosen) {
        Path ctf = (Files.isDirectory(chosen.resolve("ctf")) ? chosen.resolve("ctf") : chosen)
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
            if (settings.getProperty("python") == null)
                throw new IOException("Managed project Python is not configured");
        } catch (Exception ex) {
            status.setText("Cannot load tracepoint samples: " + ex.getMessage());
            return;
        }
        selectedTrace = ctf;
        int ticket = generation.incrementAndGet();
        if (loadJob != null) loadJob.cancel();
        displayed = null;
        clearDisplayed();
        status.setText("Reading saved CTF named events through the offline semantic normalizer...");
        loadJob = new Job("Read saved Zephyr application tracepoints") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                Path temporary = null;
                try {
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    temporary = Files.createTempDirectory("zew-user-tracepoints-");
                    for (String filename : List.of(
                            "semantic_trace.py", "tracepoint_config.py", "tracepoint_samples.py")) {
                        var entry = Activator.getDefault().getBundle()
                                .getEntry("tools/rtos_analysis/" + filename);
                        if (entry == null) throw new IOException("Bundled trace tool missing: " + filename);
                        try (var input = entry.openStream()) {
                            Files.copy(input, temporary.resolve(filename));
                        }
                    }
                    Path root = project.getLocation().toFile().toPath();
                    var command = TracePythonLaunch.command(settings.getProperty("python"),
                            temporary.resolve("tracepoint_samples.py"),
                            "--project", root.toString(), "--ctf", ctf.toString());
                    String output = new ProcessRunner().run(command, root,
                            Duration.ofSeconds(45), monitor::isCanceled,
                            ignored -> {}, ProjectContext.environment(settings))
                            .requireCompleteOutput();
                    TracepointReport.Snapshot result = TracepointReport.parse(output);
                    if (!monitor.isCanceled()) present(ticket, result, null);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return Status.CANCEL_STATUS;
                } catch (Exception ex) {
                    if (!monitor.isCanceled())
                        present(ticket, null, "Cannot read tracepoint samples: " + ex.getMessage());
                } finally {
                    if (temporary != null) {
                        try (var paths = Files.walk(temporary)) {
                            for (Path p : paths.sorted(Comparator.reverseOrder()).toList())
                                Files.deleteIfExists(p);
                        } catch (IOException ignored) { /* best-effort ephemeral helper cleanup */ }
                    }
                }
                return Status.OK_STATUS;
            }
        };
        loadJob.setUser(true);
        loadJob.schedule();
    }

    private void present(int ticket, TracepointReport.Snapshot result, String error) {
        org.eclipse.swt.widgets.Display.getDefault().asyncExec(() -> {
            if (generation.get() != ticket || table == null || table.isDisposed()) return;
            render(result, error);
        });
    }

    /** UI-thread-only application of an already parsed, offline report. */
    private void render(TracepointReport.Snapshot result, String error) {
            if (table == null || table.isDisposed()) return;
            displayed = result;
            clearDisplayed();
            if (error != null) {
                status.setText(error);
                return;
            }
            showIncomplete(result.incomplete());
            if (result.status().equals("inactive")) {
                status.setText("Application tracepoints inactive: no " + CONFIG_FILE
                        + " declarations found in project root.");
                return;
            }
            if (result.status().equals("invalid")) {
                status.setText("Tracepoint configuration error: " + result.configError());
                return;
            }
            if (result.status().equals("decode-unavailable")) {
                status.setText("CTF decoder unavailable: " + result.detail());
                return;
            }
            var missing = result.series().stream().filter(s -> !s.found())
                    .map(TracepointReport.Series::name).toList();
            configStatus.setText(missing.isEmpty()
                    ? "All declared tracepoints were found in the selected trace."
                    : "declared but not found in trace: " + String.join(", ", missing));
            int selectedIndex = 0;
            for (var series : result.series()) {
                if (series.found()) selector.add(series.label() + " (" + series.name() + ")");
            }
            if (selector.getItemCount() == 0) {
                status.setText("No configured tracepoint samples found in this saved trace. "
                        + result.unknownEvents() + " raw events were not understood.");
                return;
            }
            selector.setEnabled(true);
            selector.select(selectedIndex);
            refreshSamples();
    }

    private void clearDisplayed() {
        if (table == null || table.isDisposed()) return;
        table.removeAll();
        selector.removeAll();
        selector.setEnabled(false);
        statistics.setText("Min: —   Max: —   Mean: —");
        configStatus.setText("Tracepoints are driven only by " + CONFIG_FILE + " in the project root.");
        showIncomplete(false);
    }

    private void showIncomplete(boolean visible) {
        if (incompleteBanner == null || incompleteBanner.isDisposed()) return;
        GridData data = (GridData) incompleteBanner.getLayoutData();
        data.exclude = !visible;
        incompleteBanner.setVisible(visible);
        incompleteBanner.getParent().layout(true, true);
    }

    private void refreshSamples() {
        if (displayed == null || selector == null || selector.isDisposed()
                || selector.getSelectionIndex() < 0) return;
        int foundIndex = selector.getSelectionIndex();
        TracepointReport.Series selected = null;
        for (var series : displayed.series()) {
            if (!series.found()) continue;
            if (foundIndex-- == 0) { selected = series; break; }
        }
        if (selected == null) return;
        table.setRedraw(false);
        try {
            table.removeAll();
            for (TracepointReport.Sample sample : selected.samples()) {
                TableItem row = new TableItem(table, SWT.NONE);
                row.setText(new String[] {
                        Long.toString(sample.timestampNs()),
                        sample.value().stripTrailingZeros().toPlainString() });
            }
        } finally {
            table.setRedraw(true);
        }
        statistics.setText("Min: " + selected.min().stripTrailingZeros().toPlainString()
                + "   Max: " + selected.max().stripTrailingZeros().toPlainString()
                + "   Mean: " + selected.mean().stripTrailingZeros().toPlainString());
        status.setText("Showing " + selected.samples().size() + " saved samples for "
                + selected.label() + ". " + displayed.unknownEvents()
                + " raw events were not understood."
                + (displayed.detail().isEmpty() ? "" : " " + displayed.detail()));
    }

    @Override public void setFocus() {
        if (selector != null && !selector.isDisposed()) selector.setFocus();
    }

    @Override public void dispose() {
        generation.incrementAndGet();
        if (loadJob != null) loadJob.cancel();
        super.dispose();
    }
}
