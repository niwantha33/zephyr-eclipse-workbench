// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;

/** Switches layouts only. No implicit build, probe, flash or reset. */
public final class ZephyrBeginnerPerspectiveHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) {
        open(HandlerUtil.getActiveWorkbenchWindow(event));
        return null;
    }

    static void open(IWorkbenchWindow window) {
        if (window == null || window.getActivePage() == null) return;
        var perspective = window.getWorkbench().getPerspectiveRegistry()
                .findPerspectiveWithId(ZephyrBeginnerPerspective.ID);
        if (perspective != null) window.getActivePage().setPerspective(perspective);
    }
}
