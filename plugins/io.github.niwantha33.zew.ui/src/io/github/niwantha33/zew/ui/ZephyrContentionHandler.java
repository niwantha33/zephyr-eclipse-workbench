// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.handlers.HandlerUtil;

/** Opens the read-only saved-CTF contention report without decoding a trace. */
public final class ZephyrContentionHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            var view = (ZephyrContentionView) HandlerUtil.getActiveWorkbenchWindowChecked(event)
                    .getActivePage().showView(ZephyrContentionView.ID);
            var project = SelectedProject.from(event);
            if (project != null && ZephyrProjectLifecycle.canInspect(project))
                view.displayProject(project);
        } catch (Exception error) {
            throw new ExecutionException("Cannot open Zephyr Contention Report.", error);
        }
        return null;
    }
}
