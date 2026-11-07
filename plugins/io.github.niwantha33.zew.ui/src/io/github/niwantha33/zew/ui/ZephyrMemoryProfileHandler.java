// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.handlers.HandlerUtil;

/** Opens the saved-CTF memory view without loading files or polling a target. */
public final class ZephyrMemoryProfileHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            var view = (ZephyrMemoryProfileView) HandlerUtil.getActiveWorkbenchWindowChecked(event)
                    .getActivePage().showView(ZephyrMemoryProfileView.ID);
            var project = SelectedProject.from(event);
            if (project != null && ZephyrProjectLifecycle.canInspect(project))
                view.displayProject(project);
        } catch (Exception error) {
            throw new ExecutionException("Cannot open Zephyr Memory Profile.", error);
        }
        return null;
    }
}
