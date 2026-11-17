// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.handlers.HandlerUtil;

/** Opens an opt-in, offline task drift view; never starts capture or GDB. */
public final class ZephyrTaskDriftHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            var view = (ZephyrTaskDriftView) HandlerUtil.getActiveWorkbenchWindowChecked(event)
                    .getActivePage().showView(ZephyrTaskDriftView.ID);
            var project = SelectedProject.from(event);
            if (project != null && ZephyrProjectLifecycle.canInspect(project))
                view.displayProject(project);
        } catch (Exception error) {
            throw new ExecutionException("Cannot open Zephyr Task Drift analysis.", error);
        }
        return null;
    }
}
