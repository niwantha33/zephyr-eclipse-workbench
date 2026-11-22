// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IPartListener;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Automatic, source-backed class/struct UML preview for the active CDT file.
 * It never writes to the source, builds firmware or contacts a network service.
 */
public final class UmlClassView extends ViewPart {
    public static final String ID = Activator.ID + ".umlClasses";
    private final AtomicInteger generation = new AtomicInteger();
    private IFile source;
    private UmlClassModel.Snapshot snapshot = UmlClassModel.unavailable("", "No source selected.");
    private UmlProjectGraph.Snapshot graph = UmlProjectGraph.unavailable("", "");
    private boolean projectMode;
    private UmlClassCanvas canvas;
    private Label status;
    private Button exportButton;
    private Job reader;
    private final ISelectionListener listener = (part, selection) -> {
        if (part == this || !(selection instanceof IStructuredSelection items)
                || items.size() != 1) return;
        IFile file = asFile(items.getFirstElement());
        if (isCode(file) && !file.equals(source)) {
            if (projectMode) source = file;
            else inspect(file);
        }
    };
    private final IPartListener editorListener = new IPartListener() {
        @Override public void partActivated(IWorkbenchPart part) {
            if (part instanceof IEditorPart editor
                    && editor.getEditorInput() instanceof IFileEditorInput input) {
                IFile file = input.getFile();
                if (isCode(file) && !file.equals(source)) {
            if (projectMode) source = file;
            else inspect(file);
        }
            }
        }
        @Override public void partBroughtToTop(IWorkbenchPart part) {}
        @Override public void partClosed(IWorkbenchPart part) {}
        @Override public void partDeactivated(IWorkbenchPart part) {}
        @Override public void partOpened(IWorkbenchPart part) {}
    };

    @Override public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));
        var title = new Label(parent, SWT.WRAP);
        title.setText("AUTOMATIC UML  /  SOURCE OUTLINE + VERIFIED PROJECT CALL RELATIONSHIPS");
        title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        var tools = new Composite(parent, SWT.NONE);
        tools.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        tools.setLayout(new GridLayout(4, false));
        Button generate = new Button(tools, SWT.PUSH);
        generate.setText("Generate from active file");
        generate.addListener(SWT.Selection, e -> {
            projectMode = false;
            inspectFromSelection();
        });
        Button project = new Button(tools, SWT.PUSH);
        project.setText("Project links");
        project.setToolTipText("Scan local CDT project source definitions and resolved direct calls; no build or external service.");
        project.addListener(SWT.Selection, e -> inspectProject());
        Button refresh = new Button(tools, SWT.PUSH);
        refresh.setText("Refresh diagram");
        refresh.addListener(SWT.Selection, e -> {
            if (projectMode) inspectProject();
            else if (source != null) inspect(source);
            else inspectFromSelection();
        });
        exportButton = new Button(tools, SWT.PUSH);
        exportButton.setText("Copy PlantUML");
        exportButton.setToolTipText("Copy deterministic .puml text; no output file is written.");
        exportButton.setEnabled(false);
        exportButton.addListener(SWT.Selection, e -> copyExport());

        status = new Label(parent, SWT.WRAP);
        status.setText("Select a C/C++ source or header. Diagram uses CDT's saved-source outline; "
                + "unsaved editor changes may not appear.");
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        var scroll = new ScrolledComposite(parent, SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL);
        scroll.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        scroll.setExpandHorizontal(false);
        scroll.setExpandVertical(false);
        canvas = new UmlClassCanvas(scroll, SWT.NONE);
        canvas.onOpen(this::openType);
        canvas.onOpenFunction(this::openFunction);
        scroll.setContent(canvas);

        getSite().getPage().addSelectionListener(listener);
        getSite().getPage().addPartListener(editorListener);
        inspectFromSelection();
    }

    /** A command can preserve the explicit source before opening this view. */
    public boolean inspectFile(IFile file) {
        if (!isCode(file)) return false;
        inspect(file);
        return true;
    }

    public void inspectFromSelection() {
        IEditorPart editor = getSite().getPage().getActiveEditor();
        if (editor != null && editor.getEditorInput() instanceof IFileEditorInput input
                && isCode(input.getFile())) {
            inspect(input.getFile());
            return;
        }
        var selection = getSite().getWorkbenchWindow().getSelectionService().getSelection();
        if (selection instanceof IStructuredSelection items && items.size() == 1) {
            IFile file = asFile(items.getFirstElement());
            if (isCode(file)) {
                inspect(file);
                return;
            }
        }
        if (source == null)
            status.setText("Open a C/C++ editor or select a .c/.h/.cpp/.hpp file in Project Explorer.");
    }

    private void inspect(IFile file) {
        projectMode = false;
        source = file;
        final int token = generation.incrementAndGet();
        if (reader != null) reader.cancel();
        status.setText("Reading CDT source outline: " + file.getProjectRelativePath());
        reader = new Job("Generate UML from selected CDT source") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                UmlClassModel.Snapshot model = UmlCdtSource.inspect(file);
                if (monitor.isCanceled() || generation.get() != token)
                    return Status.CANCEL_STATUS;
                canvas.getDisplay().asyncExec(() -> {
                    if (canvas.isDisposed() || generation.get() != token || source != file) return;
                    show(model);
                });
                return Status.OK_STATUS;
            }
        };
        reader.setSystem(true);
        reader.schedule();
    }

    /** Bounded background generation; this never invokes a compiler or changes sources. */
    private void inspectProject() {
        IFile file = source;
        if (!isCode(file)) {
            status.setText("Open a saved C/C++ file in the project, then choose Project links.");
            return;
        }
        projectMode = true;
        final int token = generation.incrementAndGet();
        if (reader != null) reader.cancel();
        var project = file.getProject();
        var display = canvas.getDisplay();
        status.setText("Resolving CDT project call graph: " + project.getName()
                + " (up to " + UmlProjectGraph.MAX_FILES + " source files)...");
        reader = new Job("Resolve source-backed UML project links") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                UmlProjectGraph.Snapshot observation = UmlProjectCdtIndex.inspect(project, monitor);
                if (monitor.isCanceled() || generation.get() != token)
                    return Status.CANCEL_STATUS;
                display.asyncExec(() -> {
                    if (canvas.isDisposed() || generation.get() != token || !projectMode)
                        return;
                    showProject(observation);
                });
                return Status.OK_STATUS;
            }
        };
        reader.setSystem(true);
        reader.schedule();
    }

    /** Supplies a project projection for installed SWT regression checks. */
    public void displayProjectObservation(UmlProjectGraph.Snapshot observation) {
        showProject(observation);
    }

    private void showProject(UmlProjectGraph.Snapshot observation) {
        projectMode = true;
        graph = observation;
        canvas.showProject(observation);
        exportButton.setEnabled(observation.available());
        status.setText(observation.project() + " | " + observation.nodes().size()
                + " observed functions in " + observation.scannedFiles() + " source files | "
                + observation.edges().size() + " resolved direct-call links"
                + (observation.incomplete() ? " | INCOMPLETE" : "")
                + " | " + observation.explanation()
                + " Double-click a function to open its source.");
    }

    /** Supplies a source-outline projection for installed-UI and integration tests. */
    public void displayObservation(UmlClassModel.Snapshot observation) {
        show(observation);
    }

    private void show(UmlClassModel.Snapshot model) {
        projectMode = false;
        snapshot = model;
        canvas.show(model);
        exportButton.setEnabled(model.available());
        if (!model.available()) {
            status.setText(model.source() + " | Diagram unavailable: " + model.explanation());
            return;
        }
        status.setText(model.source() + " | " + model.types().size()
                + " CDT source-outline boxes shown"
                + (model.truncated() ? " (more types omitted; first 32 shown)" : "")
                + " | Read-only source outline, not a call graph or runtime measurement. "
                + model.explanation() + " Double-click a box to open its definition.");
    }

    private void openType(UmlClassModel.Type type) {
        if (source == null || !source.isAccessible()) return;
        try {
            IEditorPart editor = IDE.openEditor(getSite().getPage(), source, true);
            if (editor instanceof ITextEditor text)
                text.selectAndReveal(type.offset(), 0);
        } catch (Exception failure) {
            status.setText("Cannot open source: " + failure.getClass().getSimpleName());
        }
    }

    private void openFunction(UmlProjectGraph.Node fn) {
        if (source == null || !source.getProject().isAccessible()) return;
        try {
            IFile target = source.getProject().getFile(new org.eclipse.core.runtime.Path(fn.file()));
            if (!target.isAccessible()) {
                status.setText("Definition is no longer available: " + fn.file());
                return;
            }
            IEditorPart editor = IDE.openEditor(getSite().getPage(), target, true);
            if (editor instanceof ITextEditor text) text.selectAndReveal(fn.offset(), 0);
        } catch (Exception failure) {
            status.setText("Cannot open project definition: " + failure.getClass().getSimpleName());
        }
    }

    private void copyExport() {
        if (projectMode ? !graph.available() : !snapshot.available()) return;
        Clipboard clipboard = new Clipboard(getSite().getShell().getDisplay());
        try {
            String content = projectMode ? UmlProjectGraph.plantUml(graph)
                    : UmlClassModel.plantUml(snapshot);
            clipboard.setContents(new Object[] {content},
                    new org.eclipse.swt.dnd.Transfer[] {TextTransfer.getInstance()});
            status.setText("Source-backed PlantUML copied; save as .puml to render elsewhere."
                    + (projectMode ? " Edges are static direct calls, not observed execution." : ""));
        } catch (RuntimeException failure) {
            status.setText("Could not copy PlantUML: " + failure.getClass().getSimpleName());
        } finally {
            clipboard.dispose();
        }
    }

    private static IFile asFile(Object selection) {
        if (selection instanceof IFile file) return file;
        return Adapters.adapt(selection, IFile.class);
    }

    private static boolean isCode(IFile file) {
        if (file == null || !file.isAccessible()) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".c") || name.endsWith(".h") || name.endsWith(".cc")
                || name.endsWith(".cpp") || name.endsWith(".cxx")
                || name.endsWith(".hh") || name.endsWith(".hpp") || name.endsWith(".hxx");
    }

    @Override public void setFocus() {
        if (canvas != null && !canvas.isDisposed()) canvas.setFocus();
    }

    @Override public void dispose() {
        generation.incrementAndGet();
        if (reader != null) reader.cancel();
        getSite().getPage().removeSelectionListener(listener);
        getSite().getPage().removePartListener(editorListener);
        super.dispose();
    }
}
