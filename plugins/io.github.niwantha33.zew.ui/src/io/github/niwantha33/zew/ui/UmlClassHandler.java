// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.handlers.HandlerUtil;

/** Native command that opens the automatic, read-only CDT UML view. */
public final class UmlClassHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            var page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
            var view = (UmlClassView) page.showView(UmlClassView.ID);
            view.inspectFromSelection();
        } catch (Exception failure) {
            throw new ExecutionException("Unable to open Automatic UML Class View.", failure);
        }
        return null;
    }
}
