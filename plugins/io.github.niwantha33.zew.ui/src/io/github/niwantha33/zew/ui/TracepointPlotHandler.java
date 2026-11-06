// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.handlers.HandlerUtil;

/** Opens an offline read-only tracepoint view without loading or polling anything. */
public final class TracepointPlotHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            var view = (TracepointPlotView) HandlerUtil.getActiveWorkbenchWindowChecked(event)
                    .getActivePage().showView(TracepointPlotView.ID);
            var project = SelectedProject.from(event);
            if (project != null && ZephyrProjectLifecycle.canInspect(project))
                view.displayProject(project);
        } catch (Exception error) {
            throw new ExecutionException("Unable to open Zephyr Tracepoint Plot.", error);
        }
        return null;
    }
}
