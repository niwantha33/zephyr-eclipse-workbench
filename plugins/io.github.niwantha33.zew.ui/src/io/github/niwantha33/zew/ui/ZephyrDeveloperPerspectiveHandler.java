// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.handlers.HandlerUtil;

/** Switch UI layout only: no build, flash, debug connection or hardware reset. */
public final class ZephyrDeveloperPerspectiveHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) {
        var window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window == null || window.getActivePage() == null) return null;
        var perspective = window.getWorkbench().getPerspectiveRegistry()
                .findPerspectiveWithId(ZephyrDeveloperPerspective.ID);
        if (perspective != null) window.getActivePage().setPerspective(perspective);
        return null;
    }
}
