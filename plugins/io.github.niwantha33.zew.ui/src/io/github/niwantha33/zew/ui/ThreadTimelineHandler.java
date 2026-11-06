// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.handlers.HandlerUtil;

/** Opens the offline thread view without loading a trace or connecting to a target. */
public final class ThreadTimelineHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            var view = (ThreadTimelineView) HandlerUtil.getActiveWorkbenchWindowChecked(event)
                    .getActivePage().showView(ThreadTimelineView.ID);
            var project = SelectedProject.from(event);
            if (project != null && ZephyrProjectLifecycle.canInspect(project))
                view.displayProject(project);
        } catch (Exception error) {
            throw new ExecutionException("Unable to open Zephyr Thread Timeline.", error);
        }
        return null;
    }
}
