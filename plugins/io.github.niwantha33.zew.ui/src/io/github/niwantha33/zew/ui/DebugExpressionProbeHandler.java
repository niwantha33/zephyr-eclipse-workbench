// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.handlers.HandlerUtil;

/** Open the optional read-only GDB expression evidence view. */
public final class DebugExpressionProbeHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        var window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window != null && window.getActivePage() != null) {
            try {
                window.getActivePage().showView(DebugExpressionProbeView.ID);
            } catch (org.eclipse.ui.PartInitException error) {
                throw new ExecutionException("Cannot open Zephyr Expression Probe", error);
            }
        }
        return null;
    }
}
