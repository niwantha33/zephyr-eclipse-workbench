// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Opens Eclipse's existing target-memory inspector for the active debug context.
 * No second GDB connection, memory read, target halt or write is performed here.
 */
public final class ZephyrTargetMemoryHandler extends AbstractHandler {
    public static final String MEMORY_VIEW_ID = "org.eclipse.debug.ui.MemoryView";
    public static final String CDT_BROWSER_VIEW_ID =
            "org.eclipse.cdt.debug.ui.memory.memorybrowser.MemoryBrowser";

    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        var window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window == null || window.getActivePage() == null) return null;

        IWorkbenchPage page = window.getActivePage();
        IWorkbench workbench = window.getWorkbench();
        String preferred = preferredViewId(workbench);
        try {
            page.showView(preferred, null, IWorkbenchPage.VIEW_ACTIVATE);
        } catch (PartInitException preferredError) {
            if (MEMORY_VIEW_ID.equals(preferred))
                throw unavailable(preferredError);
            // Some installations advertise the optional CDT view but cannot
            // instantiate it. The Platform Memory view is always our fallback.
            try {
                page.showView(MEMORY_VIEW_ID, null, IWorkbenchPage.VIEW_ACTIVATE);
            } catch (PartInitException fallbackError) {
                fallbackError.addSuppressed(preferredError);
                throw unavailable(fallbackError);
            }
        }
        return null;
    }

    static String preferredViewId(IWorkbench workbench) {
        return workbench.getViewRegistry().find(CDT_BROWSER_VIEW_ID) != null
                ? CDT_BROWSER_VIEW_ID : MEMORY_VIEW_ID;
    }

    private static ExecutionException unavailable(PartInitException error) {
        var plugin = Activator.getDefault();
        if (plugin != null) plugin.getLog().log(new Status(IStatus.ERROR, Activator.ID,
                "Cannot open Eclipse target memory inspection view.", error));
        return new ExecutionException("Cannot open Eclipse target memory inspection view.", error);
    }
}
