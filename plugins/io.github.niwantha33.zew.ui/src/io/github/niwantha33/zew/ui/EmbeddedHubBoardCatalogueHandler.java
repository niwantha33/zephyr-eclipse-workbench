// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.handlers.HandlerUtil;

/** Open local EmbeddedHub board catalogue for the explicitly selected project. */
public final class EmbeddedHubBoardCatalogueHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            // Capture selection before showView potentially transfers Eclipse focus.
            var project = SelectedProject.from(event);
            var view = (EmbeddedHubBoardCatalogueView) HandlerUtil
                    .getActiveWorkbenchWindowChecked(event).getActivePage()
                    .showView(EmbeddedHubBoardCatalogueView.ID);
            if (project != null && ZephyrProjectLifecycle.canInspect(project))
                view.displayProject(project);
        } catch (Exception error) {
            throw new ExecutionException("Unable to open EmbeddedHub Board Discovery.", error);
        }
        return null;
    }
}
