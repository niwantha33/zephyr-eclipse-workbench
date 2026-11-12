// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.handlers.HandlerUtil;

/** A user action opens documentation; opening the view never accesses the network. */
public final class ZephyrDocumentationHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            var project = SelectedProject.from(event);
            var view = (ZephyrDocumentationView) HandlerUtil
                    .getActiveWorkbenchWindowChecked(event).getActivePage()
                    .showView(ZephyrDocumentationView.ID);
            if (project != null) view.displayProject(project);
        } catch (Exception error) {
            throw new ExecutionException("Could not open Zephyr Documentation & References.", error);
        }
        return null;
    }
}
