// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.handlers.HandlerUtil;

/** Opens the saved-trace CPU view; explicitly loading a trace starts the analysis. */
public final class ZephyrCpuLoadHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            var view = (ZephyrCpuLoadView) HandlerUtil.getActiveWorkbenchWindowChecked(event)
                    .getActivePage().showView(ZephyrCpuLoadView.ID);
            var project = SelectedProject.from(event);
            if (project != null && ZephyrProjectLifecycle.canInspect(project))
                view.displayProject(project);
        } catch (Exception error) {
            throw new ExecutionException("Unable to open Zephyr CPU Load.", error);
        }
        return null;
    }
}
