// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;

/** Open Quick Help from Zephyr menu or selected editor text, never changing source. */
public final class QuickHelpHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            IEditorPart editor = HandlerUtil.getActiveEditor(event);
            ISelection selection = HandlerUtil.getCurrentSelection(event);
            if (!(selection instanceof ITextSelection) && editor != null
                    && editor.getSite().getSelectionProvider() != null)
                selection = editor.getSite().getSelectionProvider().getSelection();
            String text = selection instanceof ITextSelection picked ? picked.getText().strip() : "";
            // An arbitrary multiline selection is not a useful search expression.
            if (text.length() > 128 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0)
                text = "";
            var project = editor == null ? SelectedProject.from(event)
                    : SelectedProject.importedProject(editor.getEditorInput());
            var view = (QuickHelpView) HandlerUtil.getActiveWorkbenchWindowChecked(event)
                    .getActivePage().showView(QuickHelpView.ID);
            view.searchFor(text, project);
        } catch (Exception error) {
            throw new ExecutionException("Unable to open offline Quick Help", error);
        }
        return null;
    }
}
