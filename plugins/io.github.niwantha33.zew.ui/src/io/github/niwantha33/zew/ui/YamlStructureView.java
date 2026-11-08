// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.part.ViewPart;

/**
 * Step 5: native, read-only structure tree of an imported YAML observation.
 *
 * Intentionally accepts only the JSON produced by Step 3. Selecting and
 * parsing original project YAML files, source navigation and refresh on build
 * changes are the separately approval-gated Step 6. Never presents a parsed
 * YAML document as a validated Zephyr binding, board or test schema.
 */
public final class YamlStructureView extends ViewPart {
    public static final String ID = Activator.ID + ".yamlStructure";
    private static final long MAX_JSON_BYTES = 4 * 1024 * 1024;

    private final AtomicInteger generation = new AtomicInteger();
    private TreeViewer tree;
    private YamlFlowCanvas graph;
    private Label status;
    private Text bindingDetails;
    private Label hardwareStatus;
    private TreeViewer hardwareTree;
    private Label twisterStatus;
    private TreeViewer twisterTree;
    private Label diagnosticStatus;
    private TreeViewer diagnosticTree;
    private volatile IFile markerOwner;
    private Job reader;
    private IFile selectedYaml;
    private IProject selectedContext;
    private IFile observedFile;
    private YamlSourceLoader.Source observedSource;
    private String observedSha;
    private YamlDocumentModel.Snapshot observedSnapshot;
    private final ISelectionListener followSelection = (part, selection) -> {
        if (part == this || !(selection instanceof IStructuredSelection items) || items.size() != 1) return;
        Object selected = items.getFirstElement();
        if (selected instanceof IProject project) {
            IProject managed = SelectedProject.importedProject(project);
            if (managed != null) selectedContext = managed;
            return;
        }
        IFile file = selected instanceof IFile direct ? direct : Adapters.adapt(selected, IFile.class);
        if (file != null && isYaml(file)) {
            selectedYaml = file;
            IProject managed = SelectedProject.importedProject(file.getProject());
            if (managed != null) selectedContext = managed;
            loadSource(file);
        }
    };

    @Override
    public void createPartControl(Composite parent) {
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 10;
        layout.marginHeight = 10;
        parent.setLayout(layout);

        var header = new Label(parent, SWT.WRAP);
        header.setText("ZEPHYR YAML VISUAL STUDIO  /  CONNECTED GRAPH  /  SOURCE OUTLINE");
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        var controls = new Composite(parent, SWT.NONE);
        controls.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        controls.setLayout(new GridLayout(4, false));
        var selectedButton = new Button(controls, SWT.PUSH);
        selectedButton.setText("Inspect selected YAML");
        selectedButton.addListener(SWT.Selection, event -> inspectSelection());
        var refreshButton = new Button(controls, SWT.PUSH);
        refreshButton.setText("Refresh source");
        refreshButton.addListener(SWT.Selection, event -> refreshSource());
        var editButton = new Button(controls, SWT.PUSH);
        editButton.setText("Edit selected scalar…");
        editButton.setToolTipText("User-owned application YAML only; preview and validate before an explicit save.");
        editButton.addListener(SWT.Selection, event -> editSelectedScalar());
        var button = new Button(controls, SWT.PUSH);
        button.setText("Open YAML observation JSON…");
        button.setToolTipText("Import an existing zew.yaml-observation/1 report instead of a source file.");
        button.addListener(SWT.Selection, event -> chooseReport());

        status = new Label(parent, SWT.WRAP);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        status.setText("Select a .yaml/.yml file in Project Explorer, or import a saved JSON observation. "
                + "Select your managed Zephyr application first when inspecting checkout files.");

        // Graph is the primary view; the original precise source outline is
        // retained as a separate tab for inspection and scalar-edit selection.
        var visualizeTabs = new TabFolder(parent, SWT.NONE);
        visualizeTabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        var diagramTab = new TabItem(visualizeTabs, SWT.NONE);
        diagramTab.setText("Connected blocks");
        var diagramPanel = new Composite(visualizeTabs, SWT.NONE);
        var diagramLayout = new GridLayout(1, false);
        diagramLayout.marginWidth = 3;
        diagramLayout.marginHeight = 3;
        diagramPanel.setLayout(diagramLayout);
        diagramTab.setControl(diagramPanel);
        var graphToolbar = new Composite(diagramPanel, SWT.NONE);
        graphToolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        var toolbarLayout = new GridLayout(4, false);
        toolbarLayout.marginWidth = 0;
        toolbarLayout.marginHeight = 0;
        graphToolbar.setLayout(toolbarLayout);
        var smaller = new Button(graphToolbar, SWT.PUSH);
        smaller.setText("−");
        smaller.setToolTipText("Zoom out of YAML graph");
        var reset = new Button(graphToolbar, SWT.PUSH);
        reset.setText("100%");
        reset.setToolTipText("Reset YAML graph zoom");
        var larger = new Button(graphToolbar, SWT.PUSH);
        larger.setText("+");
        larger.setToolTipText("Zoom into YAML graph");
        var graphLegend = new Label(graphToolbar, SWT.NONE);
        graphLegend.setText("Blue: map  •  Purple: list  •  Green: value  |  Links: YAML parent → child");
        graphLegend.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        graph = new YamlFlowCanvas(diagramPanel, SWT.NONE);
        graph.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        graph.setNavigate((line, column) -> navigateToSource(line, column));
        smaller.addListener(SWT.Selection, event -> graph.setZoom(graph.zoomPercent() - 10));
        larger.addListener(SWT.Selection, event -> graph.setZoom(graph.zoomPercent() + 10));
        reset.addListener(SWT.Selection, event -> graph.setZoom(100));

        var outlineTab = new TabItem(visualizeTabs, SWT.NONE);
        outlineTab.setText("Source outline");
        tree = new TreeViewer(visualizeTabs, SWT.BORDER | SWT.FULL_SELECTION
                | SWT.H_SCROLL | SWT.V_SCROLL | SWT.SINGLE);
        outlineTab.setControl(tree.getTree());
        tree.getTree().setHeaderVisible(true);
        tree.getTree().setLinesVisible(true);
        tree.setContentProvider(new ITreeContentProvider() {
            @Override public Object[] getElements(Object input) {
                return input instanceof List<?> items ? items.toArray() : new Object[0];
            }
            @Override public Object[] getChildren(Object parent) {
                return parent instanceof YamlTreeProjection.Row row
                        ? row.children().toArray() : new Object[0];
            }
            @Override public Object getParent(Object element) { return null; }
            @Override public boolean hasChildren(Object element) {
                return element instanceof YamlTreeProjection.Row row
                        && !row.children().isEmpty();
            }
            @Override public void dispose() {}
            @Override public void inputChanged(Viewer viewer, Object previous, Object next) {}
        });
        column("YAML key / index", 270, 0);
        column("Type", 115, 1);
        column("Value preview", 380, 2);
        column("Line:column", 100, 3);
        tree.setInput(List.of());
        var detailsTabs = new TabFolder(parent, SWT.NONE);
        GridData detailsLayout = new GridData(SWT.FILL, SWT.BOTTOM, true, false);
        detailsLayout.heightHint = 190;
        detailsTabs.setLayoutData(detailsLayout);
        var bindingTab = new TabItem(detailsTabs, SWT.NONE);
        bindingTab.setText("Devicetree binding");
        bindingDetails = new Text(detailsTabs, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY
                | SWT.WRAP | SWT.V_SCROLL);
        bindingTab.setControl(bindingDetails);
        bindingDetails.setText("Binding intelligence: select a Devicetree binding under dts/bindings/."
                + " An exact Zephyr checkout is required; no build match is implied.");

        var hardwareTab = new TabItem(detailsTabs, SWT.NONE);
        hardwareTab.setText("Hardware Model v2");
        var hardwarePanel = new Composite(detailsTabs, SWT.NONE);
        hardwarePanel.setLayout(new GridLayout(1, false));
        hardwareTab.setControl(hardwarePanel);
        hardwareStatus = new Label(hardwarePanel, SWT.WRAP);
        hardwareStatus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        hardwareStatus.setText("Select an HWMv2 board.yml or soc.yml in the managed checkout.");
        hardwareTree = new TreeViewer(hardwarePanel, SWT.BORDER | SWT.FULL_SELECTION
                | SWT.H_SCROLL | SWT.V_SCROLL | SWT.SINGLE);
        hardwareTree.getTree().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        hardwareTree.getTree().setHeaderVisible(true);
        hardwareTree.getTree().setLinesVisible(true);
        hardwareTree.setContentProvider(new ITreeContentProvider() {
            @Override public Object[] getElements(Object input) {
                return input instanceof List<?> items ? items.toArray() : new Object[0];
            }
            @Override public Object[] getChildren(Object element) {
                return element instanceof YamlHardwareModel.Row row
                        ? row.children().toArray() : new Object[0];
            }
            @Override public Object getParent(Object element) { return null; }
            @Override public boolean hasChildren(Object element) {
                return element instanceof YamlHardwareModel.Row row
                        && !row.children().isEmpty();
            }
            @Override public void dispose() {}
            @Override public void inputChanged(Viewer viewer, Object oldValue, Object newValue) {}
        });
        hardwareColumn("Board / SoC / qualifier", 290, true);
        hardwareColumn("Resolved metadata", 370, false);
        hardwareTree.setInput(List.of());

        var twisterTab = new TabItem(detailsTabs, SWT.NONE);
        twisterTab.setText("Twister tests");
        var twisterPanel = new Composite(detailsTabs, SWT.NONE);
        twisterPanel.setLayout(new GridLayout(1, false));
        twisterTab.setControl(twisterPanel);
        twisterStatus = new Label(twisterPanel, SWT.WRAP);
        twisterStatus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        twisterStatus.setText("Select a Twister testcase.yaml, sample.yaml or test_config.yaml.");
        twisterTree = new TreeViewer(twisterPanel, SWT.BORDER | SWT.FULL_SELECTION
                | SWT.H_SCROLL | SWT.V_SCROLL | SWT.SINGLE);
        twisterTree.getTree().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        twisterTree.getTree().setHeaderVisible(true);
        twisterTree.getTree().setLinesVisible(true);
        twisterTree.setContentProvider(new ITreeContentProvider() {
            @Override public Object[] getElements(Object input) {
                return input instanceof List<?> items ? items.toArray() : new Object[0];
            }
            @Override public Object[] getChildren(Object parentRow) {
                return parentRow instanceof YamlTwisterModel.Row row
                        ? row.children().toArray() : new Object[0];
            }
            @Override public Object getParent(Object element) { return null; }
            @Override public boolean hasChildren(Object element) {
                return element instanceof YamlTwisterModel.Row row
                        && !row.children().isEmpty();
            }
            @Override public void dispose() {}
            @Override public void inputChanged(Viewer viewer, Object oldValue, Object newValue) {}
        });
        twisterColumn("Scenario / config", 300, true);
        twisterColumn("Declared value preview", 370, false);
        twisterTree.setInput(List.of());

        var diagnosticsTab = new TabItem(detailsTabs, SWT.NONE);
        diagnosticsTab.setText("Diagnostics");
        var diagnosticsPanel = new Composite(detailsTabs, SWT.NONE);
        diagnosticsPanel.setLayout(new GridLayout(1, false));
        diagnosticsTab.setControl(diagnosticsPanel);
        diagnosticStatus = new Label(diagnosticsPanel, SWT.WRAP);
        diagnosticStatus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        diagnosticStatus.setText("Select a saved YAML file for source-linked syntax diagnostics.");
        diagnosticTree = new TreeViewer(diagnosticsPanel, SWT.BORDER | SWT.FULL_SELECTION
                | SWT.H_SCROLL | SWT.V_SCROLL | SWT.SINGLE);
        diagnosticTree.getTree().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        diagnosticTree.getTree().setHeaderVisible(true);
        diagnosticTree.getTree().setLinesVisible(true);
        diagnosticTree.setContentProvider(new ITreeContentProvider() {
            @Override public Object[] getElements(Object input) {
                return input instanceof List<?> items ? items.toArray() : new Object[0];
            }
            @Override public Object[] getChildren(Object row) { return new Object[0]; }
            @Override public Object getParent(Object row) { return null; }
            @Override public boolean hasChildren(Object row) { return false; }
            @Override public void dispose() {}
            @Override public void inputChanged(Viewer viewer, Object oldValue, Object newValue) {}
        });
        diagnosticColumn("Severity", 85, 0);
        diagnosticColumn("Reason", 195, 1);
        diagnosticColumn("Line:column", 105, 2);
        diagnosticColumn("Explanation", 430, 3);
        diagnosticTree.setInput(List.of());
        diagnosticTree.addDoubleClickListener(event -> {
            if (diagnosticTree.getStructuredSelection().getFirstElement()
                    instanceof YamlDiagnosticModel.Row row && row.line() > 0)
                navigateToSource(row.line(), row.column());
        });
        tree.addDoubleClickListener(event -> navigateToSource());
        getSite().getWorkbenchWindow().getSelectionService().addSelectionListener(followSelection);
        inspectSelection();
    }

    private void column(String name, int width, int kind) {
        var column = new TreeViewerColumn(tree, SWT.LEFT);
        column.getColumn().setText(name);
        column.getColumn().setWidth(width);
        column.setLabelProvider(new ColumnLabelProvider() {
            @Override public String getText(Object element) {
                if (!(element instanceof YamlTreeProjection.Row row)) return "";
                return switch (kind) {
                    case 0 -> row.label();
                    case 1 -> row.kind();
                    case 2 -> row.preview();
                    case 3 -> row.location();
                    default -> "";
                };
            }
        });
    }

    private void hardwareColumn(String label, int width, boolean first) {
        var column = new TreeViewerColumn(hardwareTree, SWT.LEFT);
        column.getColumn().setText(label);
        column.getColumn().setWidth(width);
        column.setLabelProvider(new ColumnLabelProvider() {
            @Override public String getText(Object item) {
                if (!(item instanceof YamlHardwareModel.Row row)) return "";
                return first ? row.label() : row.detail();
            }
        });
    }

    private void twisterColumn(String header, int width, boolean first) {
        var column = new TreeViewerColumn(twisterTree, SWT.LEFT);
        column.getColumn().setText(header);
        column.getColumn().setWidth(width);
        column.setLabelProvider(new ColumnLabelProvider() {
            @Override public String getText(Object item) {
                if (!(item instanceof YamlTwisterModel.Row row)) return "";
                return first ? row.name() : row.preview();
            }
        });
    }

    private void diagnosticColumn(String header, int width, int field) {
        var column = new TreeViewerColumn(diagnosticTree, SWT.LEFT);
        column.getColumn().setText(header);
        column.getColumn().setWidth(width);
        column.setLabelProvider(new ColumnLabelProvider() {
            @Override public String getText(Object element) {
                if (!(element instanceof YamlDiagnosticModel.Row row)) return "";
                return switch (field) {
                    case 0 -> row.severity();
                    case 1 -> row.code();
                    case 2 -> row.location();
                    case 3 -> row.message();
                    default -> "";
                };
            }
        });
    }

    /** Remove only our own transient markers, always off the SWT thread. */
    private void clearPublishedYamlMarkers(int request) {
        IFile previous = markerOwner;
        if (previous == null) return;
        Job cleanup = new Job("Clear obsolete YAML validation markers") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                try {
                    if (YamlDiagnosticMarkers.replace(previous, null, null,
                            () -> !monitor.isCanceled() && request == generation.get()))
                        markerOwner = null;
                } catch (org.eclipse.core.runtime.CoreException ignored) {
                    return Status.CANCEL_STATUS;
                }
                return Status.OK_STATUS;
            }
        };
        cleanup.setSystem(true);
        cleanup.schedule();
    }

    private void chooseReport() {
        var dialog = new FileDialog(getSite().getShell(), SWT.OPEN);
        dialog.setText("Open a saved Zephyr YAML observation");
        dialog.setFilterNames(new String[] {"YAML observation JSON (*.json)", "All files (*.*)"});
        dialog.setFilterExtensions(new String[] {"*.json", "*.*"});
        String chosen = dialog.open();
        if (chosen == null) return;

        // Any previous import is stale immediately; discard its delayed UI result.
        int request = generation.incrementAndGet();
        if (reader != null) reader.cancel();
        clearPublishedYamlMarkers(request);
        observedFile = null;
        observedSource = null;
        observedSha = null;
        observedSnapshot = null;
        tree.setInput(List.of());
        graph.setObservation(null);
        status.setText("Loading saved YAML observation…");
        bindingDetails.setText("Binding intelligence unavailable for imported JSON without verified source context.");
        hardwareTree.setInput(List.of());
        hardwareStatus.setText("HWMv2 metadata unavailable for an imported JSON report without selected checkout context.");
        twisterTree.setInput(List.of());
        twisterStatus.setText("Twister metadata requires an explicitly selected checkout, not imported JSON.");
        diagnosticTree.setInput(List.of());
        diagnosticStatus.setText("Imported JSON is not an authenticated workspace source; no diagnostics or markers.");
        Path report;
        try {
            report = Path.of(chosen);
        } catch (RuntimeException error) {
            status.setText("Cannot open the selected observation path.");
            return;
        }
        reader = new Job("Import saved YAML observation") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                YamlDocumentModel.Snapshot snapshot = null;
                String error = null;
                try {
                    if (!Files.isRegularFile(report) || Files.size(report) > MAX_JSON_BYTES)
                        throw new IOException("Missing or oversized report");
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    snapshot = YamlDocumentModel.parse(Files.readString(report, StandardCharsets.UTF_8));
                } catch (IOException | IllegalArgumentException exception) {
                    // Avoid displaying private YAML text or arbitrary parser/file paths.
                    error = "The selected report is inaccessible, oversized or not a valid YAML observation v1.";
                }
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                YamlDocumentModel.Snapshot loaded = snapshot;
                String failure = error;
                Display.getDefault().asyncExec(() -> {
                    if (request != generation.get() || tree == null
                            || tree.getTree().isDisposed()) return;
                    if (failure != null) {
                        tree.setInput(List.of());
                        status.setText(failure);
                    } else {
                        displayObservation(loaded);
                    }
                });
                return Status.OK_STATUS;
            }
        };
        reader.setSystem(true);
        reader.schedule();
    }

    /**
     * Accept a previously parsed and independently verified observation.
     * Must be called on the SWT UI thread, never on a background Job.
     */
    public void displayObservation(YamlDocumentModel.Snapshot snapshot) {
        if (Display.getCurrent() == null)
            throw new IllegalStateException("YAML view updates require the UI thread");
        if (tree == null || tree.getTree().isDisposed()) return;
        tree.setInput(YamlTreeProjection.rows(snapshot));
        graph.setObservation(snapshot);
        observedSnapshot = observedFile == null ? null : snapshot;
        if (snapshot == null) {
            bindingDetails.setText("Binding intelligence: no selected source.");
            hardwareTree.setInput(List.of());
            hardwareStatus.setText("HWMv2: no selected source.");
            twisterTree.setInput(List.of());
            twisterStatus.setText("Twister: no selected source.");
            diagnosticTree.setInput(List.of());
            diagnosticStatus.setText("Diagnostics: no selected source.");
            status.setText("No YAML observation has been selected.");
            return;
        }
        String source = snapshot.relativePath() == null ? "(path not verified)"
                : YamlTreeProjection.preview(snapshot.relativePath());
        status.setText((observedFile == null ? "Imported recorded " : "Saved-file ") + snapshot.status() + " — " + snapshot.kind()
                + " — " + snapshot.reason() + " — " + source
                + ". Syntax observation only; Zephyr schema validity is unverified.");
        if (snapshot.parsed() && snapshot.root() != null)
            tree.expandToLevel(2);
    }


    private static boolean isYaml(IFile file) {
        if (file == null || file.getName() == null) return false;
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    private void inspectSelection() {
        var selection = getSite().getWorkbenchWindow().getSelectionService().getSelection();
        IFile file = null;
        if (selection instanceof IStructuredSelection items && items.size() == 1) {
            Object chosen = items.getFirstElement();
            if (chosen instanceof IProject project) {
                IProject managed = SelectedProject.importedProject(project);
                if (managed != null) selectedContext = managed;
            }
            file = chosen instanceof IFile direct ? direct : Adapters.adapt(chosen, IFile.class);
        }
        if (file == null) {
            IEditorPart editor = getSite().getPage().getActiveEditor();
            if (editor != null && editor.getEditorInput() instanceof IFileEditorInput input)
                file = input.getFile();
        }
        if (isYaml(file)) {
            selectedYaml = file;
            IProject managed = SelectedProject.importedProject(file.getProject());
            if (managed != null) selectedContext = managed;
            loadSource(file);
        } else if (selectedYaml != null && isYaml(selectedYaml)) {
            loadSource(selectedYaml);
        } else {
            status.setText("Select exactly one .yaml or .yml file in Project Explorer. "
                    + "Select a managed Zephyr application first for checkout-owned files.");
        }
    }

    private void refreshSource() {
        IFile target = observedFile == null ? selectedYaml : observedFile;
        if (target == null) {
            status.setText("No selected YAML source to refresh.");
            return;
        }
        loadSource(target);
    }

    private void loadSource(IFile file) {
        int request = generation.incrementAndGet();
        if (reader != null) reader.cancel();
        observedFile = null;
        observedSource = null;
        observedSha = null;
        observedSnapshot = null;
        tree.setInput(List.of());
        graph.setObservation(null);
        status.setText("Inspecting selected YAML with the project's managed Python…");
        bindingDetails.setText("Binding intelligence: inspecting only if this is a Devicetree binding.");
        hardwareTree.setInput(List.of());
        hardwareStatus.setText("HWMv2: loading only for canonical board.yml and soc.yml sources.");
        twisterTree.setInput(List.of());
        twisterStatus.setText("Twister: waiting for selected saved test YAML and checkout schema.");
        diagnosticTree.setInput(List.of());
        diagnosticStatus.setText("Diagnostics: verifying saved YAML and selected checkout rules…");
        IProject managed = SelectedProject.importedProject(file.getProject());
        if (managed == null) managed = selectedContext;
        IProject context = managed;
        if (context == null) {
            clearPublishedYamlMarkers(request);
            diagnosticStatus.setText("Diagnostics unavailable: no managed application context.");
            status.setText("Select a managed Zephyr application before inspecting this YAML file.");
            return;
        }
        reader = new Job("Inspect selected Zephyr YAML (read-only)") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                YamlDocumentModel.Snapshot snapshot = null;
                YamlSourceLoader.Source source = null;
                YamlBindingIntelligence.Summary binding = null;
                YamlHardwareModel.Summary hardware = null;
                YamlTwisterModel.Summary twister = null;
                YamlDiagnosticModel.Summary diagnostics = null;
                String markerNotice = "";
                String failure = null;
                try {
                    Properties settings = ProjectContext.settings(context);
                    Path app = context.getLocation().toFile().toPath();
                    Path zephyr = Path.of(settings.getProperty("workspace"), "zephyr");
                    if (file.getLocation() == null)
                        throw new java.io.IOException("The selected YAML file has no local source path.");
                    source = YamlSourceLoader.resolve(file.getLocation().toFile().toPath(), app, zephyr);
                    var entry = Activator.getDefault().getBundle().getEntry("scripts/yaml_observe_file.py");
                    if (entry == null)
                        throw new java.io.IOException("The packaged YAML source parser is missing.");
                    Path script = Path.of(FileLocator.toFileURL(entry).toURI());
                    snapshot = YamlSourceLoader.inspect(Path.of(settings.getProperty("python")),
                            script, source,
                            () -> monitor.isCanceled() || generation.get() != request);
                    var diagnosticEntry = Activator.getDefault().getBundle().getEntry(
                            "scripts/yaml_diagnostic_inspect.py");
                    if (diagnosticEntry == null) {
                        diagnostics = YamlDiagnosticModel.unavailable("inspector-not-installed");
                    } else {
                        try {
                            Path diagnosticScript = Path.of(FileLocator.toFileURL(diagnosticEntry).toURI());
                            diagnostics = YamlDiagnosticModel.inspect(
                                    Path.of(settings.getProperty("python")), diagnosticScript,
                                    source, zephyr, app, () -> monitor.isCanceled()
                                            || generation.get() != request);
                        } catch (java.io.IOException unavailable) {
                            diagnostics = YamlDiagnosticModel.unavailable(
                                    "source-diagnostics-unavailable");
                        }
                    }
                    if (!"unavailable".equals(diagnostics.state())
                            && !snapshot.sha256().equals(diagnostics.sourceSha256()))
                        throw new java.io.IOException("Diagnostic source and YAML observation hashes differ");
                    if (snapshot.parsed() && "devicetree-binding".equals(snapshot.kind())) {
                        var bindingEntry = Activator.getDefault().getBundle().getEntry(
                                "scripts/yaml_binding_inspect.py");
                        if (bindingEntry == null) {
                            binding = YamlBindingIntelligence.unavailable("inspector-not-installed");
                        } else {
                            Path bindingScript = Path.of(FileLocator.toFileURL(bindingEntry).toURI());
                            try {
                                binding = YamlBindingIntelligence.inspect(
                                        Path.of(settings.getProperty("python")), bindingScript, source,
                                        zephyr, app, () -> monitor.isCanceled()
                                                || generation.get() != request);
                            } catch (java.io.IOException exception) {
                                binding = YamlBindingIntelligence.unavailable(
                                        "selected-edtlib-unavailable-or-rejected");
                            }
                        }
                        if (!snapshot.sha256().equals(YamlSourceLoader.hash(source.file())))
                            throw new java.io.IOException("YAML source changed between inspection stages");
                    }
                    if (snapshot.parsed() && ("hwmv2-board".equals(snapshot.kind())
                            || "hwmv2-soc".equals(snapshot.kind()))) {
                        var hardwareEntry = Activator.getDefault().getBundle().getEntry(
                                "scripts/yaml_hwmv2_inspect.py");
                        if (hardwareEntry == null) {
                            hardware = YamlHardwareModel.unavailable("hwmv2-resolver-not-installed");
                        } else {
                            try {
                                Path hardwareScript = Path.of(FileLocator.toFileURL(hardwareEntry).toURI());
                                hardware = YamlHardwareModel.inspect(
                                        Path.of(settings.getProperty("python")), hardwareScript,
                                        source, zephyr, app, () -> monitor.isCanceled()
                                                || generation.get() != request);
                            } catch (java.io.IOException exception) {
                                hardware = YamlHardwareModel.unavailable("selected-hwmv2-api-unavailable");
                            }
                        }
                        if (!snapshot.sha256().equals(YamlSourceLoader.hash(source.file())))
                            throw new java.io.IOException("YAML source changed during HWMv2 inspection");
                    }
                    if (snapshot.parsed() && ("twister-test".equals(snapshot.kind())
                            || "twister-config".equals(snapshot.kind()))) {
                        var twisterEntry = Activator.getDefault().getBundle().getEntry(
                                "scripts/yaml_twister_inspect.py");
                        if (twisterEntry == null) {
                            twister = YamlTwisterModel.unavailable("twister-inspector-not-installed");
                        } else {
                            try {
                                Path twisterScript = Path.of(FileLocator.toFileURL(twisterEntry).toURI());
                                twister = YamlTwisterModel.inspect(
                                        Path.of(settings.getProperty("python")), twisterScript,
                                        source, zephyr, app, () -> monitor.isCanceled()
                                                || generation.get() != request);
                            } catch (java.io.IOException unavailable) {
                                twister = YamlTwisterModel.unavailable(
                                        "selected-twister-validation-unavailable");
                            }
                        }
                        if (!snapshot.sha256().equals(YamlSourceLoader.hash(source.file())))
                            throw new java.io.IOException("YAML source changed during Twister inspection");
                    }
                    if (!snapshot.sha256().equals(YamlSourceLoader.hash(source.file())))
                        throw new java.io.IOException("YAML source changed during validation");
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    return Status.CANCEL_STATUS;
                } catch (Exception exception) {
                    // A generic message hid the cause (e.g. parser missing,
                    // source outside the selected checkout, or no managed Python).
                    // Display only bounded, single-line diagnostic text.
                    String reason = exception.getMessage();
                    if (reason == null || reason.isBlank()) reason = exception.getClass().getSimpleName();
                    reason = reason.replace('\n', ' ').replace('\r', ' ').trim();
                    if (reason.length() > 190) reason = reason.substring(0, 190) + "…";
                    failure = "YAML inspection unavailable: " + reason
                            + ". Check the selected file and managed Python (PyYAML). "
                            + "No file was modified.";
                }
                if (monitor.isCanceled() || request != generation.get()) return Status.CANCEL_STATUS;
                try {
                    boolean applied = YamlDiagnosticMarkers.replace(markerOwner,
                            failure == null ? file : null,
                            failure == null ? diagnostics : null,
                            () -> !monitor.isCanceled() && request == generation.get());
                    if (applied) markerOwner = failure == null ? file : null;
                } catch (org.eclipse.core.runtime.CoreException markerError) {
                    markerNotice = " Workspace problem markers could not be updated.";
                }
                YamlDocumentModel.Snapshot loaded = snapshot;
                YamlSourceLoader.Source loadedSource = source;
                YamlBindingIntelligence.Summary bindingResult = binding;
                YamlHardwareModel.Summary hardwareResult = hardware;
                YamlTwisterModel.Summary twisterResult = twister;
                YamlDiagnosticModel.Summary diagnosticResult = diagnostics;
                String diagnosticsNotice = markerNotice;
                String problem = failure;
                Display.getDefault().asyncExec(() -> {
                    if (request != generation.get() || tree == null || tree.getTree().isDisposed()) return;
                    if (problem != null) {
                        status.setText(problem);
                        bindingDetails.setText("Binding intelligence: unavailable; source or checkout context unverified.");
                        hardwareTree.setInput(List.of());
                        hardwareStatus.setText("HWMv2: unavailable; source or checkout context unverified.");
                        twisterTree.setInput(List.of());
                        twisterStatus.setText("Twister: unavailable; source or checkout context unverified.");
                        diagnosticTree.setInput(List.of());
                        diagnosticStatus.setText("Diagnostics unavailable: source or checkout context unverified."
                                + diagnosticsNotice);
                        tree.setInput(List.of());
                        graph.setObservation(null);
                        return;
                    }
                    observedFile = file;
                    observedSource = loadedSource;
                    observedSha = loaded.sha256();
                    displayObservation(loaded);
                    if (bindingResult != null)
                        bindingDetails.setText(YamlBindingIntelligence.describe(bindingResult));
                    else
                        bindingDetails.setText("Binding intelligence: not applicable to this YAML document."
                                + " Syntax parsing does not imply Zephyr semantic validation.");
                    hardwareTree.setInput(YamlHardwareModel.hierarchy(hardwareResult));
                    if (hardwareResult == null) {
                        hardwareStatus.setText("HWMv2: not applicable to this YAML file.");
                    } else {
                        hardwareStatus.setText("HWMv2 " + hardwareResult.state() + ": "
                                + hardwareResult.reason()
                                + ". Selected-checkout metadata only; not an active build target.");
                        if (hardwareResult.resolved()) hardwareTree.expandToLevel(2);
                    }
                    twisterTree.setInput(YamlTwisterModel.hierarchy(twisterResult));
                    if (twisterResult == null) {
                        twisterStatus.setText("Twister: not applicable to this YAML source.");
                    } else {
                        twisterStatus.setText("Twister " + twisterResult.state() + ": "
                                + twisterResult.reason()
                                + ". Schema metadata only — no test plan, execution or result verified.");
                        if (twisterResult.resolved()) twisterTree.expandToLevel(2);
                    }
                    diagnosticTree.setInput(YamlDiagnosticModel.rows(diagnosticResult));
                    if (diagnosticResult == null) {
                        diagnosticStatus.setText("YAML diagnostics were not produced." + diagnosticsNotice);
                    } else if ("checked".equals(diagnosticResult.state())) {
                        diagnosticStatus.setText("Verified " + diagnosticResult.scope()
                                + " only — " + diagnosticResult.reason()
                                + ". No binding, build, or test execution implied." + diagnosticsNotice);
                    } else {
                        diagnosticStatus.setText("Diagnostics " + diagnosticResult.state()
                                + " — " + diagnosticResult.reason() + " ("
                                + diagnosticResult.findings().size() + " findings)."
                                + " Unknown positions are not marked." + diagnosticsNotice);
                    }
                });
                return Status.OK_STATUS;
            }
        };
        reader.setSystem(true);
        reader.schedule();
    }

    private boolean hasDirtySourceEditor(IFile file) {
        for (var reference : getSite().getPage().getEditorReferences()) {
            IEditorPart editor = reference.getEditor(false);
            if (editor != null && editor.isDirty()
                    && editor.getEditorInput() instanceof IFileEditorInput input
                    && file.equals(input.getFile()))
                return true;
        }
        return false;
    }

    /**
     * The UI collects a single scalar replacement. No write occurs until
     * managed-Python preflight, a complete old/new token preview, and a
     * separate explicit confirmation.
     */
    private void editSelectedScalar() {
        if (observedFile == null || observedSource == null
                || observedSnapshot == null || observedSha == null) {
            status.setText("Edit unavailable: select an inspected application YAML source, not imported JSON.");
            return;
        }
        IFile file = observedFile;
        YamlSourceLoader.Source source = observedSource;
        YamlDocumentModel.Snapshot snapshot = observedSnapshot;
        if (!"application".equals(source.kind()) || file.isLinked() || file.isDerived()
                || !file.getProject().equals(selectedContext)) {
            status.setText("Only local, user-owned application YAML sources are editable. "
                    + "Checkout, generated and linked files remain read-only.");
            return;
        }
        if (hasDirtySourceEditor(file)) {
            status.setText("Save or discard unsaved YAML editor changes before preparing a patch.");
            return;
        }
        if (!(tree.getStructuredSelection().getFirstElement() instanceof YamlTreeProjection.Row row)) {
            status.setText("Select a scalar value row in the YAML structure tree first.");
            return;
        }
        YamlScalarEdit.Target target = YamlScalarEdit.target(snapshot, row);
        if (target == null) {
            status.setText("Selected YAML row is not a scalar value; maps and sequences are not writable.");
            return;
        }
        var dialog = new InputDialog(getSite().getShell(), "Edit YAML scalar",
                "New value for " + row.label() + " (" + target.scalar().scalarType() + "). "
                        + "This is a typed, single-value source patch; no YAML is rewritten yet.",
                target.scalar().value(), null);
        if (dialog.open() != Window.OK) return;
        String replacement = dialog.getValue();
        int request = generation.get();
        Job previewJob = new Job("Validate YAML scalar patch preview") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                YamlScalarEdit.Preview preview = null;
                String failure = null;
                try {
                    if (monitor.isCanceled() || request != generation.get())
                        return Status.CANCEL_STATUS;
                    preview = YamlScalarEdit.prepare(source, snapshot, row, replacement);
                    var entry = Activator.getDefault().getBundle().getEntry(
                            "scripts/yaml_validate_patch.py");
                    if (entry == null)
                        throw new IOException("Managed candidate validator is not installed.");
                    Path script = Path.of(FileLocator.toFileURL(entry).toURI());
                    Properties settings = ProjectContext.settings(file.getProject());
                    YamlPatchValidator.validate(Path.of(settings.getProperty("python")),
                            script, preview, () -> monitor.isCanceled()
                                    || generation.get() != request);
                } catch (InterruptedException canceled) {
                    Thread.currentThread().interrupt();
                    return Status.CANCEL_STATUS;
                } catch (Exception error) {
                    failure = "Scalar edit preview rejected: invalid type, unavailable managed Python, "
                            + "stale source, or unsafe YAML path. Nothing was changed.";
                }
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                YamlScalarEdit.Preview ready = preview;
                String problem = failure;
                Display.getDefault().asyncExec(() -> {
                    if (request != generation.get() || tree.getTree().isDisposed()) return;
                    if (problem != null) {
                        status.setText(problem);
                        MessageDialog.openInformation(getSite().getShell(),
                                "YAML preview rejected", problem);
                        return;
                    }
                    if (hasDirtySourceEditor(file)) {
                        status.setText("YAML editor has unsaved changes. No patch applied.");
                        return;
                    }
                    String summary = "Source: " + source.relative()
                            + "\nScalar type: " + ready.scalarType()
                            + "\n\nExact scalar-token patch:\n"
                            + ready.diff()
                            + "\n\nThe candidate passed managed YAML syntax inspection."
                            + "\nThis does not prove Devicetree, board, or Twister schema validity."
                            + "\n\nAn original-file backup will be kept. Apply this patch?";
                    if (!MessageDialog.openConfirm(getSite().getShell(),
                            "Preview and confirm YAML edit", summary))
                        return;
                    if (request != generation.get() || hasDirtySourceEditor(file)) {
                        status.setText("Patch discarded: source selection changed or editor is dirty.");
                        return;
                    }
                    applyConfirmedPatch(request, file, ready);
                });
                return Status.OK_STATUS;
            }
        };
        previewJob.setSystem(true);
        previewJob.schedule();
        status.setText("Checking the candidate YAML patch before showing the confirmation preview…");
    }

    private void applyConfirmedPatch(int request, IFile file,
                                     YamlScalarEdit.Preview confirmed) {
        Job applyJob = new Job("Apply confirmed YAML source patch") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                if (monitor.isCanceled() || request != generation.get())
                    return Status.CANCEL_STATUS;
                Path backup = null;
                String problem = null;
                try {
                    backup = YamlScalarEdit.apply(confirmed);
                    file.refreshLocal(IResource.DEPTH_ZERO, monitor);
                } catch (Exception failure) {
                    problem = backup == null
                            ? "YAML patch was not applied. Source may have changed or atomic replacement failed. "
                                    + "A retained backup may exist; inspect before retrying."
                            : "YAML patch was saved, but the Eclipse workspace refresh failed. "
                                    + "Refresh the project manually.";
                }
                Path backupCopy = backup;
                String error = problem;
                Display.getDefault().asyncExec(() -> {
                    if (tree.getTree().isDisposed()) return;
                    if (backupCopy != null && request == generation.get()) {
                        MessageDialog.openInformation(getSite().getShell(),
                                "YAML patch applied",
                                "Saved only the confirmed scalar token. Original backup: "
                                        + backupCopy.getFileName()
                                        + "\nVerify domain-specific validation before building.");
                        loadSource(file);
                    } else if (error != null && request == generation.get()) {
                        status.setText(error);
                        MessageDialog.openInformation(getSite().getShell(),
                                "YAML patch not confirmed", error);
                    }
                });
                return error == null ? Status.OK_STATUS : Status.CANCEL_STATUS;
            }
        };
        applyJob.setSystem(true);
        applyJob.schedule();
    }

    private void navigateToSource() {
        if (!(tree.getStructuredSelection().getFirstElement() instanceof YamlTreeProjection.Row row))
            return;
        navigateToSource(row.sourceRange().start().line(), row.sourceRange().start().column());
    }

    private void navigateToSource(int targetLine, int targetColumn) {
        if (observedFile == null || observedSource == null || observedSha == null) {
            status.setText("A saved JSON observation has no verified workspace source for navigation.");
            return;
        }
        try {
            YamlSourceLoader.Source current = YamlSourceLoader.resolve(observedSource.file(),
                    "application".equals(observedSource.kind()) ? observedSource.root() : null,
                    "zephyr".equals(observedSource.kind()) ? observedSource.root() : null);
            if (!current.equals(observedSource)
                    || !observedSha.equals(YamlSourceLoader.hash(observedSource.file()))) {
                status.setText("YAML source changed since inspection. Refresh before navigating.");
                return;
            }
            if (!observedFile.exists()) {
                status.setText("The originating workspace file is no longer accessible.");
                return;
            }
            IEditorPart part = IDE.openEditor(getSite().getPage(), observedFile, true);
            ITextEditor editor = part instanceof ITextEditor text ? text : part.getAdapter(ITextEditor.class);
            if (editor == null) {
                status.setText("The selected editor cannot navigate to a YAML source location.");
                return;
            }
            if (editor.isDirty()) {
                status.setText("The source editor contains unsaved changes. Save and refresh before navigating.");
                return;
            }
            var document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
            if (document == null || targetLine < 1 || targetLine > document.getNumberOfLines()) {
                status.setText("The selected YAML line is no longer available. Refresh the observation.");
                return;
            }
            int offset = document.getLineOffset(targetLine - 1);
            int lineLength = document.getLineLength(targetLine - 1);
            String line = document.get(offset, lineLength);
            int count = Math.min(Math.max(0, targetColumn - 1),
                    line.codePointCount(0, line.length()));
            editor.selectAndReveal(offset + line.offsetByCodePoints(0, count), 0);
        } catch (Exception error) {
            status.setText("Cannot navigate to the original YAML line. Refresh and verify the source.");
        }
    }

    @Override public void setFocus() {
        if (tree != null && !tree.getTree().isDisposed())
            tree.getTree().setFocus();
    }

    @Override public void dispose() {
        generation.incrementAndGet();
        if (reader != null) reader.cancel();
        clearPublishedYamlMarkers(generation.get());
        getSite().getWorkbenchWindow().getSelectionService().removeSelectionListener(followSelection);
        super.dispose();
    }
}
