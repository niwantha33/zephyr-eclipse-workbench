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
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
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
    private Label status;
    private Text bindingDetails;
    private Job reader;
    private IFile selectedYaml;
    private IProject selectedContext;
    private IFile observedFile;
    private YamlSourceLoader.Source observedSource;
    private String observedSha;
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
        header.setText("ZEPHYR YAML STRUCTURE  /  SELECTED SOURCE OR SAVED OBSERVATION  /  READ-ONLY");
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        var controls = new Composite(parent, SWT.NONE);
        controls.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        controls.setLayout(new GridLayout(3, false));
        var selectedButton = new Button(controls, SWT.PUSH);
        selectedButton.setText("Inspect selected YAML");
        selectedButton.addListener(SWT.Selection, event -> inspectSelection());
        var refreshButton = new Button(controls, SWT.PUSH);
        refreshButton.setText("Refresh source");
        refreshButton.addListener(SWT.Selection, event -> refreshSource());
        var button = new Button(controls, SWT.PUSH);
        button.setText("Open YAML observation JSON…");
        button.setToolTipText("Import an existing zew.yaml-observation/1 report instead of a source file.");
        button.addListener(SWT.Selection, event -> chooseReport());

        status = new Label(parent, SWT.WRAP);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        status.setText("Select a .yaml/.yml file in Project Explorer, or import a saved JSON observation. "
                + "Select your managed Zephyr application first when inspecting checkout files.");

        tree = new TreeViewer(parent, SWT.BORDER | SWT.FULL_SELECTION
                | SWT.H_SCROLL | SWT.V_SCROLL | SWT.SINGLE);
        tree.getTree().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
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
        bindingDetails = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        GridData detailsLayout = new GridData(SWT.FILL, SWT.BOTTOM, true, false);
        detailsLayout.heightHint = 135;
        bindingDetails.setLayoutData(detailsLayout);
        bindingDetails.setText("Binding intelligence: select a Devicetree binding under dts/bindings/."
                + " An exact Zephyr checkout is required; no build match is implied.");
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
        observedFile = null;
        observedSource = null;
        observedSha = null;
        tree.setInput(List.of());
        status.setText("Loading saved YAML observation…");
        bindingDetails.setText("Binding intelligence unavailable for imported JSON without verified source context.");
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
        if (snapshot == null) {
            bindingDetails.setText("Binding intelligence: no selected source.");
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
        tree.setInput(List.of());
        status.setText("Inspecting selected YAML with the project's managed Python…");
        bindingDetails.setText("Binding intelligence: inspecting only if this is a Devicetree binding.");
        IProject managed = SelectedProject.importedProject(file.getProject());
        if (managed == null) managed = selectedContext;
        IProject context = managed;
        if (context == null) {
            status.setText("Select a managed Zephyr application before inspecting this YAML file.");
            return;
        }
        reader = new Job("Inspect selected Zephyr YAML (read-only)") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                YamlDocumentModel.Snapshot snapshot = null;
                YamlSourceLoader.Source source = null;
                YamlBindingIntelligence.Summary binding = null;
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
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    return Status.CANCEL_STATUS;
                } catch (Exception exception) {
                    failure = "YAML inspection unavailable: select a file within this managed "
                            + "application or its exact Zephyr checkout, and check the Python setup. "
                            + "No file was modified.";
                }
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                YamlDocumentModel.Snapshot loaded = snapshot;
                YamlSourceLoader.Source loadedSource = source;
                YamlBindingIntelligence.Summary bindingResult = binding;
                String problem = failure;
                Display.getDefault().asyncExec(() -> {
                    if (request != generation.get() || tree == null || tree.getTree().isDisposed()) return;
                    if (problem != null) {
                        status.setText(problem);
                        bindingDetails.setText("Binding intelligence: unavailable; source or checkout context unverified.");
                        tree.setInput(List.of());
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
                });
                return Status.OK_STATUS;
            }
        };
        reader.setSystem(true);
        reader.schedule();
    }

    private void navigateToSource() {
        if (observedFile == null || observedSource == null || observedSha == null) {
            status.setText("A saved JSON observation has no verified workspace source for navigation.");
            return;
        }
        if (!(tree.getStructuredSelection().getFirstElement() instanceof YamlTreeProjection.Row row))
            return;
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
            if (document == null || row.sourceRange().start().line() > document.getNumberOfLines()) {
                status.setText("The selected YAML line is no longer available. Refresh the observation.");
                return;
            }
            int offset = document.getLineOffset(row.sourceRange().start().line() - 1);
            int lineLength = document.getLineLength(row.sourceRange().start().line() - 1);
            String line = document.get(offset, lineLength);
            int count = Math.min(Math.max(0, row.sourceRange().start().column() - 1),
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
        getSite().getWorkbenchWindow().getSelectionService().removeSelectionListener(followSelection);
        super.dispose();
    }
}
