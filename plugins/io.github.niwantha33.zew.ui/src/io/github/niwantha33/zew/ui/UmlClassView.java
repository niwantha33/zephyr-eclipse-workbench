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
import org.eclipse.ui.ISelectionListener;
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
    private UmlClassCanvas canvas;
    private Label status;
    private Button exportButton;
    private Job reader;
    private final ISelectionListener listener = (part, selection) -> {
        if (part == this || !(selection instanceof IStructuredSelection items)
                || items.size() != 1) return;
        IFile file = asFile(items.getFirstElement());
        if (isCode(file) && file != source) inspect(file);
    };

    @Override public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));
        var title = new Label(parent, SWT.WRAP);
        title.setText("AUTOMATIC UML  /  C & C++ CLASS AND STRUCTURE VIEW  /  CDT SOURCE OUTLINE");
        title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        var tools = new Composite(parent, SWT.NONE);
        tools.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        tools.setLayout(new GridLayout(3, false));
        Button generate = new Button(tools, SWT.PUSH);
        generate.setText("Generate from active file");
        generate.addListener(SWT.Selection, e -> inspectFromSelection());
        Button refresh = new Button(tools, SWT.PUSH);
        refresh.setText("Refresh diagram");
        refresh.addListener(SWT.Selection, e -> {
            if (source != null) inspect(source);
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
        scroll.setContent(canvas);

        getSite().getPage().addSelectionListener(listener);
        inspectFromSelection();
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

    private void show(UmlClassModel.Snapshot model) {
        snapshot = model;
        canvas.show(model);
        exportButton.setEnabled(model.available());
        if (!model.available()) {
            status.setText(model.source() + " | Diagram unavailable: " + model.explanation());
            return;
        }
        status.setText(model.source() + " | " + model.types().size()
                + " CDT class/struct/union definitions shown"
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

    private void copyExport() {
        if (!snapshot.available()) return;
        try (Clipboard clipboard = new Clipboard(getSite().getShell().getDisplay())) {
            clipboard.setContents(new Object[] {UmlClassModel.plantUml(snapshot)},
                    new org.eclipse.swt.dnd.Transfer[] {TextTransfer.getInstance()});
            status.setText("PlantUML text copied from the displayed CDT outline; "
                    + "save it as .puml to render elsewhere.");
        } catch (RuntimeException failure) {
            status.setText("Could not copy PlantUML: " + failure.getClass().getSimpleName());
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
        super.dispose();
    }
}
