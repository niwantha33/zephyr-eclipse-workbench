// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import org.eclipse.core.runtime.CoreException;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.part.ViewPart;
import io.github.niwantha33.zew.core.EmbeddedHubBoardCatalogue;
import io.github.niwantha33.zew.core.ProcessRunner;

/**
 * Read-only local board browser. Browsing a target never changes project
 * configuration; board changes use the existing explicit Project Properties flow.
 */
public final class EmbeddedHubBoardCatalogueView extends ViewPart {
    public static final String ID = Activator.ID + ".embeddedHubBoards";
    private static final String ALL_VENDORS = "All chip vendors";
    private static final String ALL_SOCS = "All SoCs";
    private static final int VISIBLE_LIMIT = 200;
    private final AtomicInteger generation = new AtomicInteger();
    private IProject project;
    private volatile Job discoveryJob;
    private List<EmbeddedHubBoardCatalogue.Entry> entries = List.of();
    private String activeTarget = "";
    private Text search, selectedTarget;
    private Combo vendorFilter, socFilter;
    private Table table;
    private Label status, details, basic, boardState, source, currentBoard, technical;
    private Button inspect, changeBoard, reload;
    private ScrolledComposite detailScroll;
    private Composite detailBody;

    @Override public void createPartControl(Composite parent) {
        GridLayout frame = new GridLayout(1, false);
        frame.marginWidth = 10;
        frame.marginHeight = 10;
        frame.verticalSpacing = 7;
        parent.setLayout(frame);

        Label title = new Label(parent, SWT.NONE);
        title.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DIALOG_FONT));
        title.setText("Board Browser");
        Label introduction = new Label(parent, SWT.WRAP);
        introduction.setText("Find an exact Zephyr target. Selecting a row only previews the board.");
        introduction.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        currentBoard = new Label(parent, SWT.WRAP);
        currentBoard.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        SashForm panes = new SashForm(parent, SWT.HORIZONTAL | SWT.SMOOTH);
        panes.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite browser = new Composite(panes, SWT.NONE);
        GridLayout browserLayout = new GridLayout(1, false);
        browserLayout.marginWidth = 7;
        browserLayout.marginHeight = 6;
        browserLayout.verticalSpacing = 8;
        browser.setLayout(browserLayout);
        heading(browser, "FIND BOARDS");
        search = new Text(browser, SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL);
        search.setMessage("Search board, processor or exact target");
        search.setToolTipText("Filter by board name, SoC, revision or exact target.");
        search.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Composite filters = new Composite(browser, SWT.NONE);
        filters.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        filters.setLayout(new GridLayout(2, true));
        vendorFilter = new Combo(filters, SWT.DROP_DOWN | SWT.READ_ONLY);
        vendorFilter.setToolTipText("Chip vendor");
        vendorFilter.add(ALL_VENDORS);
        vendorFilter.select(0);
        vendorFilter.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        socFilter = new Combo(filters, SWT.DROP_DOWN | SWT.READ_ONLY);
        socFilter.setToolTipText("System-on-chip filter");
        socFilter.add(ALL_SOCS);
        socFilter.select(0);
        socFilter.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        status = new Label(browser, SWT.WRAP);
        status.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        table = new Table(browser, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION
                | SWT.V_SCROLL | SWT.H_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(false);
        GridData resultsData = new GridData(SWT.FILL, SWT.FILL, true, true);
        resultsData.minimumHeight = 180;
        table.setLayoutData(resultsData);
        String[] headers = {"Board", "SoC", "Chip vendor", "Project"};
        int[] widths = {190, 140, 105, 75};
        for (int i = 0; i < headers.length; i++) {
            TableColumn column = new TableColumn(table, SWT.LEFT);
            column.setText(headers[i]);
            column.setWidth(widths[i]);
        }
        search.addModifyListener(event -> filterRows());
        vendorFilter.addListener(SWT.Selection, event -> {
            populateSocs();
            filterRows();
        });
        socFilter.addListener(SWT.Selection, event -> filterRows());
        table.addListener(SWT.Selection, event -> showSelectedRow());
        table.addListener(SWT.DefaultSelection, event -> editProjectBoard());

        detailScroll = new ScrolledComposite(panes, SWT.V_SCROLL | SWT.H_SCROLL);
        detailScroll.setExpandVertical(true);
        detailScroll.setExpandHorizontal(true);
        detailBody = new Composite(detailScroll, SWT.NONE);
        GridLayout detailLayout = new GridLayout(1, false);
        detailLayout.marginWidth = 10;
        detailLayout.marginHeight = 6;
        detailLayout.verticalSpacing = 10;
        detailBody.setLayout(detailLayout);
        heading(detailBody, "BOARD DETAILS");

        Group selected = new Group(detailBody, SWT.NONE);
        selected.setText("Selected board");
        selected.setLayout(new GridLayout(1, false));
        selected.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        details = new Label(selected, SWT.WRAP);
        details.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DIALOG_FONT));
        details.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        boardState = new Label(selected, SWT.WRAP);
        boardState.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        Label targetLabel = new Label(selected, SWT.NONE);
        targetLabel.setText("Exact Zephyr build target");
        selectedTarget = new Text(selected, SWT.BORDER | SWT.READ_ONLY);
        selectedTarget.setToolTipText("Copyable target. Previewing does not apply it to the project.");
        selectedTarget.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        basic = new Label(selected, SWT.WRAP);
        basic.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Button advanced = new Button(selected, SWT.CHECK);
        advanced.setText("Show technical details");
        technical = new Label(selected, SWT.WRAP);
        GridData technicalData = new GridData(SWT.FILL, SWT.TOP, true, false);
        technicalData.exclude = true;
        technical.setLayoutData(technicalData);
        technical.setVisible(false);
        advanced.addListener(SWT.Selection, event -> {
            boolean visible = advanced.getSelection();
            ((GridData) technical.getLayoutData()).exclude = !visible;
            technical.setVisible(visible);
            resizeDetails();
        });

        Group actions = new Group(detailBody, SWT.NONE);
        actions.setText("Board actions");
        actions.setLayout(new GridLayout(1, false));
        actions.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        changeBoard = new Button(actions, SWT.PUSH);
        changeBoard.setText("Use This Board...");
        changeBoard.setToolTipText("Opens Project Properties with this exact target selected. "
                + "Only Apply or Apply and Close can change the project.");
        changeBoard.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        changeBoard.setEnabled(false);
        changeBoard.addListener(SWT.Selection, event -> editProjectBoard());
        Label caution = new Label(actions, SWT.WRAP);
        caution.setText("Board changes require confirmation in Project Properties; "
                + "no build, flash or reset is started.");
        caution.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        inspect = new Button(actions, SWT.PUSH);
        inspect.setText("Configure Current Project...");
        inspect.setToolTipText("Open Configuration Studio for the saved project target, "
                + "not the board currently previewed.");
        inspect.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        inspect.setEnabled(false);
        inspect.addListener(SWT.Selection, event -> inspectProject());

        reload = new Button(actions, SWT.PUSH);
        reload.setText("Refresh Board Catalogue");
        reload.setToolTipText("Reload the board list from the selected project's local Zephyr checkout.");
        reload.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        reload.setEnabled(false);
        reload.addListener(SWT.Selection, event -> {
            IProject candidate = selectedProject();
            if (candidate == null) showEmpty("Select a Zephyr project in Project Explorer.");
            else displayProject(candidate);
        });

        source = new Label(detailBody, SWT.WRAP);
        source.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        detailScroll.setContent(detailBody);
        resizeDetails();
        panes.setWeights(new int[] {58, 42});
        showEmpty("Select an imported Zephyr application to browse its board catalogue.");
    }

    private static void heading(Composite parent, String label) {
        Label title = new Label(parent, SWT.NONE);
        title.setText(label);
        title.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DIALOG_FONT));
        title.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
    }

    private void resizeDetails() {
        if (detailScroll == null || detailScroll.isDisposed() || detailBody.isDisposed()) return;
        detailBody.layout(true, true);
        detailScroll.setMinSize(detailBody.computeSize(320, SWT.DEFAULT));
    }

    /** Handler supplies the explicit project; never infer one from the filesystem. */
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
            IProject candidate = SelectedProject.importedProject(items.getFirstElement());
            if (candidate != null && ZephyrProjectLifecycle.canInspect(candidate)) return candidate;
        }
        return project != null && project.isAccessible() ? project : null;
    }

    private void refreshFromCheckout(IProject selected) {
        int ticket = generation.incrementAndGet();
        if (discoveryJob != null) discoveryJob.cancel();
        entries = List.of();
        activeTarget = "";
        table.removeAll();
        vendorFilter.removeAll();
        vendorFilter.add(ALL_VENDORS);
        vendorFilter.select(0);
        status.setText("Loading boards from this project's Zephyr checkout...");
        currentBoard.setText("Project: " + selected.getName() + "  |  Target: loading...");
        details.setText("Choose a board to inspect its exact target.");
        selectedTarget.setText("");
        technical.setText("");
        source.setText("");
        inspect.setEnabled(true);
        changeBoard.setEnabled(true);

        var display = status.getDisplay();
        Job job = new Job("Discover Zephyr boards") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                try {
                    monitor.beginTask("Query local Zephyr hardware catalogue", IProgressMonitor.UNKNOWN);
                    Properties settings = ProjectContext.settings(selected);
                    Path workspace = Path.of(settings.getProperty("workspace")).toAbsolutePath().normalize();
                    Path zephyr = workspace.resolve("zephyr");
                    if (!Files.isDirectory(zephyr.resolve("scripts")))
                        throw new java.io.IOException("Selected Zephyr workspace is incomplete.");
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
                            currentBoard.setText("Project: " + selected.getName() + "  |  Current target: "
                                    + (target.isBlank() ? "not configured" : target));
                            boolean listed = EmbeddedHubBoardCatalogue.exact(discovered, target).isPresent();
                            source.setText("Source: this project's installed Zephyr checkout (local, read-only).");
                            source.setToolTipText(zephyr.toString());
                            status.setText(discovered.size() + " targets found"
                                    + (listed ? "  |  Current target available"
                                            : "  |  Current target not found"));
                            populateVendors();
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
                            currentBoard.setText("Project: " + selected.getName());
                            status.setText("Board list unavailable: " + error.getMessage());
                            details.setText("Check the project's Zephyr environment in Project Properties.");
                            selectedTarget.setText("");
                            technical.setText("");
                        }
                    });
                    return new Status(IStatus.ERROR, Activator.ID, "Zephyr board discovery failed", error);
                } finally {
                    monitor.done();
                }
            }
        };
        job.setUser(false);
        discoveryJob = job;
        job.schedule();
    }

    private void populateVendors() {
        String previous = vendorFilter.getText();
        TreeSet<String> vendors = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (var entry : entries) vendors.add(entry.chipVendor());
        vendorFilter.removeAll();
        vendorFilter.add(ALL_VENDORS);
        for (String vendor : vendors) {
            if (!vendor.isBlank()) vendorFilter.add(vendor);
        }
        int index = vendorFilter.indexOf(previous);
        vendorFilter.select(index >= 0 ? index : 0);
    }

    private void filterRows() {
        if (table == null || table.isDisposed() || vendorFilter == null || vendorFilter.isDisposed()) return;
        table.setRedraw(false);
        try {
            table.removeAll();
            selectedTarget.setText("");
            technical.setText("");
            String chipVendor = vendorFilter.getText();
            var filtered = new ArrayList<>(EmbeddedHubBoardCatalogue.filter(entries, search.getText()));
            if (!chipVendor.isBlank() && !ALL_VENDORS.equals(chipVendor))
                filtered.removeIf(entry -> !entry.chipVendor().equalsIgnoreCase(chipVendor));
            filtered.sort(Comparator.comparingInt(
                    (EmbeddedHubBoardCatalogue.Entry entry) -> entry.exactTarget().equals(activeTarget) ? 0 : 1)
                    .thenComparing(EmbeddedHubBoardCatalogue.Entry::board, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(EmbeddedHubBoardCatalogue.Entry::exactTarget));
            int visible = Math.min(400, filtered.size());
            int activeRow = -1;
            for (int i = 0; i < visible; i++) {
                var entry = filtered.get(i);
                TableItem item = new TableItem(table, SWT.NONE);
                item.setText(new String[] {entry.board(), entry.chipVendor(), entry.soc(),
                        entry.exactTarget().equals(activeTarget) ? "Current" : ""});
                item.setData(entry);
                if (entry.exactTarget().equals(activeTarget)) activeRow = i;
            }
            details.setText(filtered.isEmpty() ? "No matching boards. Try another search or vendor."
                    : "Select a board to see its exact Zephyr target. This does not change the project.");
            status.setText(filtered.size() + " matching targets"
                    + (filtered.size() > visible ? " (showing first " + visible + "; refine your search)" : "")
                    + "  |  Catalogue entries only, not tested hardware.");
            if (activeRow >= 0) {
                table.setSelection(activeRow);
                showSelectedRow();
            }
        } finally {
            table.setRedraw(true);
        }
    }

    private void showSelectedRow() {
        if (table.getSelectionCount() != 1) return;
        Object data = table.getSelection()[0].getData();
        if (data instanceof EmbeddedHubBoardCatalogue.Entry entry) {
            boolean current = entry.exactTarget().equals(activeTarget);
            details.setText(entry.board() + (current ? "  |  Current project board"
                    : "  |  Browse only — not selected for build"));
            selectedTarget.setText(entry.exactTarget());
            technical.setText("Board manufacturer: " + entry.boardManufacturer()
                    + "    Chip vendor: " + entry.chipVendor()
                    + "    SoC: " + entry.soc()
                    + "    Revision: " + entry.revision()
                    + "    Qualifier: " + entry.qualifier()
                    + "\nDiscovery alone does not prove build, flash, debug, peripheral or physical compatibility.");
        }
    }

    private void editProjectBoard() {
        if (project == null || !project.isAccessible()) return;
        var dialog = PreferencesUtil.createPropertyDialogOn(getSite().getShell(), project,
                "io.github.niwantha33.zew.projectProperties", null, null);
        if (dialog != null && dialog.open() == Window.OK && !status.isDisposed())
            refreshFromCheckout(project);
    }

    private void inspectProject() {
        if (project == null || !project.isAccessible()) return;
        try {
            var home = (ConfigurationHomeView) getSite().getPage().showView(ConfigurationHomeView.ID);
            home.displayProject(project);
            home.openSection("overview");
        } catch (Exception error) {
            status.setText("Cannot open Configuration Studio: " + error.getMessage());
        }
    }

    private void showEmpty(String message) {
        if (discoveryJob != null) discoveryJob.cancel();
        generation.incrementAndGet();
        entries = List.of();
        activeTarget = "";
        project = null;
        if (table != null && !table.isDisposed()) table.removeAll();
        if (vendorFilter != null && !vendorFilter.isDisposed()) {
            vendorFilter.removeAll();
            vendorFilter.add(ALL_VENDORS);
            vendorFilter.select(0);
        }
        if (status != null && !status.isDisposed()) status.setText(message);
        if (currentBoard != null && !currentBoard.isDisposed()) currentBoard.setText("Project: none selected");
        if (source != null && !source.isDisposed()) source.setText("Board discovery uses only the selected local Zephyr installation.");
        if (details != null && !details.isDisposed()) details.setText("Choose a project to begin.");
        if (selectedTarget != null && !selectedTarget.isDisposed()) selectedTarget.setText("");
        if (technical != null && !technical.isDisposed()) technical.setText("");
        if (inspect != null && !inspect.isDisposed()) inspect.setEnabled(false);
        if (changeBoard != null && !changeBoard.isDisposed()) changeBoard.setEnabled(false);
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
