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

    public static void open(IWorkbenchWindow window) {
        if (window == null || window.getActivePage() == null) return;
        var perspective = window.getWorkbench().getPerspectiveRegistry()
                .findPerspectiveWithId(ZephyrDeveloperPerspective.ID);
        if (perspective == null) {
            MessageDialog.openError(window.getShell(), "Zephyr Developer Workspace",
                    "Developer Workspace is not installed correctly.");
            return;
        }
        window.getActivePage().setPerspective(perspective);
        // A saved Eclipse perspective can still contain the old beginner guide.
        // Replace it with developer-specific actions, preserving user editors.
        showHome(window);
    }

    static void showHome(IWorkbenchWindow window) {
        if (window == null || window.getActivePage() == null) return;
        try {
            var page = window.getActivePage();
            var beginnerGuide = page.findView(ZephyrBeginnerView.ID);
            if (beginnerGuide != null) page.hideView(beginnerGuide);
            page.showView(ZephyrDeveloperHomeView.ID);
        } catch (PartInitException error) {
            MessageDialog.openError(window.getShell(), "Zephyr Developer Workspace",
                    "Cannot open the development dashboard: " + error.getMessage());
        }
    }
}
