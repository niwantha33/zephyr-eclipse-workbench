// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.FileLocator;
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
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;
import io.github.niwantha33.zew.core.EmbeddedHubBoardCatalogue;
import io.github.niwantha33.zew.core.ProcessRunner;

/**
 * Local, selected-checkout HWMv2 board discovery. Not a second
 * configuration editor or a vendor advertisement/compatibility registry.
 * All board resolution is performed by the existing Zephyr Python helper.
 */
public final class EmbeddedHubBoardCatalogueView extends ViewPart {
    public static final String ID = Activator.ID + ".embeddedHubBoards";

    private final AtomicInteger generation = new AtomicInteger();
    private IProject project;
    private volatile Job discoveryJob;
    private List<EmbeddedHubBoardCatalogue.Entry> entries = List.of();
    private String activeTarget = "";
    private Text search;
    private Table table;
    private Label status, details, source;
    private Button inspect;

    @Override public void createPartControl(Composite parent) {
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 12;
        layout.marginHeight = 10;
        parent.setLayout(layout);

        Label title = new Label(parent, SWT.NONE);
        title.setText("EMBEDDEDHUB / BOARD DISCOVERY");

        Label scope = new Label(parent, SWT.WRAP);
        scope.setText("Local Zephyr HWMv2 metadata only. Listed means discovered, NOT built, flashed, debugged or physically verified.");
        scope.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Composite controls = new Composite(parent, SWT.NONE);
        controls.setLayout(new GridLayout(3, false));
        controls.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        search = new Text(controls, SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH);
        search.setMessage("Filter by chip vendor, manufacturer, SoC or exact target");
        search.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        search.addModifyListener(event -> filterRows());
        Button refresh = new Button(controls, SWT.PUSH);
        refresh.setText("Refresh selected project");
        refresh.addListener(SWT.Selection, event -> {
            IProject candidate = selectedProject();
            if (candidate == null) showEmpty("Select one imported Zephyr project in Project Explorer, then refresh.");
            else displayProject(candidate);
        });
        inspect = new Button(controls, SWT.PUSH);
        inspect.setText("Open Configuration Studio");
        inspect.setToolTipText("Opens the current project's configuration, not the browsed catalogue target.");
        inspect.setEnabled(false);
        inspect.addListener(SWT.Selection, event -> inspectProject());

        status = new Label(parent, SWT.WRAP);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        source = new Label(parent, SWT.WRAP);
        source.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        table = new Table(parent, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        table.setLinesVisible(true);
        table.setHeaderVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        String[] headers = {"Chip vendor", "Board maker", "SoC", "Board", "Revision", "Qualifier", "Exact Zephyr target"};
        int[] widths = {110, 120, 150, 180, 80, 145, 310};
        for (int i = 0; i < headers.length; i++) {
            TableColumn column = new TableColumn(table, SWT.LEFT);
            column.setText(headers[i]);
            column.setWidth(widths[i]);
        }
        table.addListener(SWT.Selection, event -> showSelectedRow());

        details = new Label(parent, SWT.WRAP);
        details.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        showEmpty("Select an imported Zephyr application to discover its selected checkout's boards.");
    }

    /** Handler supplies the explicit project; no first-project or global-root fallback. */
    public void displayProject(IProject selected) {
        if (selected == null || !selected.isAccessible()
                || !ZephyrProjectLifecycle.canInspect(selected)) {
            showEmpty("Select an imported Zephyr application first.");
            return;
        }
        project = selected;
        refreshFromCheckout(selected);
    }

    private IProject selectedProject() {
        if (getSite() == null || getSite().getWorkbenchWindow() == null) return project;
        var selection = getSite().getWorkbenchWindow().getSelectionService().getSelection();
        if (selection instanceof IStructuredSelection items && items.size() == 1) {
            IProject selected = SelectedProject.importedProject(items.getFirstElement());
            if (selected != null && ZephyrProjectLifecycle.canInspect(selected)) return selected;
        }
        return project != null && project.isAccessible() ? project : null;
    }

    private void refreshFromCheckout(IProject selected) {
        int ticket = generation.incrementAndGet();
        if (discoveryJob != null) discoveryJob.cancel();
        entries = List.of();
        activeTarget = "";
        table.removeAll();
        status.setText("Discovering board metadata using the selected Zephyr checkout...");
        details.setText("No build, flash, debug or physical hardware operation is running.");
        source.setText("");
        inspect.setEnabled(true);

        var display = status.getDisplay();
        Job job = new Job("EmbeddedHub: discover Zephyr boards") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                try {
                    monitor.beginTask("Query selected Zephyr hardware catalogue", IProgressMonitor.UNKNOWN);
                    Properties settings = ProjectContext.settings(selected);
                    Path workspace = Path.of(settings.getProperty("workspace")).toAbsolutePath().normalize();
                    Path zephyr = workspace.resolve("zephyr");
                    if (!Files.isDirectory(zephyr.resolve("scripts")))
                        throw new java.io.IOException("Zephyr scripts not found under the selected environment workspace.");
                    var scriptUrl = Activator.getDefault().getBundle().getEntry("tools/hardware_catalog.py");
                    if (scriptUrl == null) throw new java.io.IOException("Installed hardware catalogue helper missing.");
                    Path helper = Path.of(FileLocator.toFileURL(scriptUrl).toURI());
                    List<String> lines = Collections.synchronizedList(new ArrayList<>());
                    new ProcessRunner().run(
                        List.of(settings.getProperty("python"), helper.toString(), "--zephyr", zephyr.toString()),
                        workspace, Duration.ofMinutes(3), monitor::isCanceled,
                        line -> {
                            if (lines.size() >= 40_000)
                                throw new IllegalStateException("Board catalogue exceeds safe row count");
                            lines.add(line);
                        }, ProjectContext.environment(settings)).requireSuccess();
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    List<EmbeddedHubBoardCatalogue.Entry> discovered =
                            EmbeddedHubBoardCatalogue.parseLines(lines);
                    String target = settings.getProperty("board", "");
                    display.asyncExec(() -> {
                        if (!status.isDisposed() && generation.get() == ticket && project == selected) {
                            entries = discovered;
                            activeTarget = target;
                            source.setText("Source: selected local Zephyr checkout: " + zephyr);
                            boolean targetListed = EmbeddedHubBoardCatalogue.exact(discovered, target).isPresent();
                            status.setText(discovered.size() + " exact targets discovered."
                                    + (targetListed ? " Current project's target found."
                                        : " Current project's target was not found in this checkout.")
                                    + " Hardware compatibility is not measured.");
                            filterRows();
                        }
                    });
                    return Status.OK_STATUS;
                } catch (Exception error) {
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    display.asyncExec(() -> {
                        if (!status.isDisposed() && generation.get() == ticket) {
                            entries = List.of();
                            table.removeAll();
                            status.setText("Hardware discovery unavailable: " + error.getMessage());
                            details.setText("No catalogue results were accepted; check the selected Zephyr environment.");
                        }
                    });
                    return new Status(IStatus.ERROR, Activator.ID, "EmbeddedHub board discovery failed", error);
                } finally {
                    monitor.done();
                }
            }
        };
        job.setUser(false);
        discoveryJob = job;
        job.schedule();
    }

    private void filterRows() {
        if (table == null || table.isDisposed()) return;
        table.setRedraw(false);
        try {
            table.removeAll();
            var filtered = new ArrayList<>(EmbeddedHubBoardCatalogue.filter(entries, search.getText()));
            // Keep the active project's exact target visible even in a large catalogue.
            filtered.sort(java.util.Comparator.comparingInt(
                    row -> row.exactTarget().equals(activeTarget) ? 0 : 1));
            final int visible = Math.min(1_500, filtered.size());
            int highlighted = -1;
            for (int i = 0; i < visible; i++) {
                var entry = filtered.get(i);
                TableItem item = new TableItem(table, SWT.NONE);
                item.setText(new String[] {entry.chipVendor(), entry.boardManufacturer(), entry.soc(),
                        entry.board(), entry.revision(), entry.qualifier(), entry.exactTarget()});
                item.setData(entry);
                if (entry.exactTarget().equals(activeTarget)) highlighted = i;
            }
            if (highlighted >= 0) table.setSelection(highlighted);
            details.setText(filtered.isEmpty()
                    ? "No matching targets. Search operates only on the selected checkout."
                    : (highlighted >= 0 ? "Current project's exact target is highlighted."
                       : "Select a target to view its discovery evidence; selection does not modify the project.")
                    + (filtered.size() > visible
                        ? " Displaying first " + visible + " of " + filtered.size() + "; narrow your search." : ""));
            if (highlighted >= 0) showSelectedRow();
        } finally {
            table.setRedraw(true);
        }
    }

    private void showSelectedRow() {
        if (table.getSelectionCount() != 1) return;
        Object data = table.getSelection()[0].getData();
        if (data instanceof EmbeddedHubBoardCatalogue.Entry entry) {
            details.setText(EmbeddedHubBoardCatalogue.statusFor(entry, activeTarget)
                    + ". Chip vendor and board manufacturer are distinct. "
                    + "Architecture, supported peripherals and package pins require separate verified evidence.");
        }
    }

    private void inspectProject() {
        if (project == null || !project.isAccessible()) return;
        try {
            var home = (ConfigurationHomeView) getSite().getPage().showView(ConfigurationHomeView.ID);
            home.displayProject(project);
            home.openSection("overview");
        } catch (Exception error) {
            status.setText("Could not open Configuration Studio: " + error.getMessage());
        }
    }

    private void showEmpty(String message) {
        if (discoveryJob != null) discoveryJob.cancel();
        generation.incrementAndGet();
        entries = List.of();
        activeTarget = "";
        project = null;
        if (table != null && !table.isDisposed()) table.removeAll();
        if (status != null && !status.isDisposed()) status.setText(message);
        if (source != null && !source.isDisposed()) source.setText("");
        if (details != null && !details.isDisposed()) details.setText("No remote registry, telemetry or download is used.");
        if (inspect != null && !inspect.isDisposed()) inspect.setEnabled(false);
    }

    @Override public void setFocus() {
        if (search != null && !search.isDisposed()) search.setFocus();
    }

    @Override public void dispose() {
        generation.incrementAndGet();
        if (discoveryJob != null) discoveryJob.cancel();
        super.dispose();
    }
}
