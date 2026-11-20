// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.jface.dialogs.MessageDialog;

/** Switch UI layout only: no build, flash, debug connection or hardware reset. */
public final class ZephyrDeveloperPerspectiveHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) {
        open(HandlerUtil.getActiveWorkbenchWindow(event));
        return null;
    }

    static void open(IWorkbenchWindow window) {
        if (window == null || window.getActivePage() == null) return;
        var perspective = window.getWorkbench().getPerspectiveRegistry()
                .findPerspectiveWithId(ZephyrDeveloperPerspective.ID);
        if (perspective == null) {
            MessageDialog.openError(window.getShell(), "Zephyr Developer Workspace",
                    "Developer Workspace is not installed correctly.");
            return;
        }
        window.getActivePage().setPerspective(perspective);
        // A saved Eclipse perspective may have hidden or closed the guide.
        // Explicitly reopening the workspace restores its actionable landing page.
        showGuide(window);
    }

    static void showGuide(IWorkbenchWindow window) {
        if (window == null || window.getActivePage() == null) return;
        try {
            window.getActivePage().showView(ZephyrBeginnerView.ID);
        } catch (PartInitException error) {
            MessageDialog.openError(window.getShell(), "Zephyr Developer Workspace",
                    "Cannot open Getting Started guide: " + error.getMessage());
        }
    }
}
