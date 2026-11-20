// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.jface.dialogs.MessageDialog;
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
        if (perspective == null) {
            MessageDialog.openError(window.getShell(), "Zephyr Getting Started",
                    "Getting Started Workspace is not installed correctly.");
            return;
        }
        window.getActivePage().setPerspective(perspective);
        // setPerspective() restores saved layouts; a previously closed guide
        // would otherwise leave an empty workbench on repeat invocation.
        try {
            window.getActivePage().showView(ZephyrBeginnerView.ID);
        } catch (PartInitException error) {
            MessageDialog.openError(window.getShell(), "Zephyr Getting Started",
                    "Cannot open the Getting Started guide: " + error.getMessage());
        }
    }
}
