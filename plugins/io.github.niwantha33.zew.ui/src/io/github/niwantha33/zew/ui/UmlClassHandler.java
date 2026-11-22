// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.handlers.HandlerUtil;

/** Native command that opens the automatic, read-only CDT UML view. */
public final class UmlClassHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            var page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
            // Resolve before opening the view: showing it can change the
            // active workbench part and the selection-service result.
            IFile file = null;
            if (page.getActivePart() instanceof IEditorPart editor
                    && editor.getEditorInput() instanceof IFileEditorInput input)
                file = input.getFile();
            if (file == null && HandlerUtil.getCurrentSelection(event)
                    instanceof IStructuredSelection selection && selection.size() == 1)
                file = asFile(selection.getFirstElement());
            if (file == null && page.getActiveEditor() != null
                    && page.getActiveEditor().getEditorInput() instanceof IFileEditorInput input)
                file = input.getFile();
            var view = (UmlClassView) page.showView(UmlClassView.ID);
            if (file == null || !view.inspectFile(file)) view.inspectFromSelection();
        } catch (Exception failure) {
            throw new ExecutionException("Unable to open Automatic UML Class View.", failure);
        }
        return null;
    }

    private static IFile asFile(Object selected) {
        if (selected instanceof IFile file) return file;
        return Adapters.adapt(selected, IFile.class);
    }
}
