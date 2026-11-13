// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;

/** Switch layout only; never starts a new GDB/OpenOCD session or flashes a target. */
public final class ZephyrDebugWorkspaceHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        var window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window != null) open(window);
        return null;
    }

    static void openOnLaunch() {
        var plugin = Activator.getDefault();
        if (plugin == null || !plugin.getPreferenceStore().getBoolean(
                WorkbenchPreferences.ZEPHYR_DEBUG_PERSPECTIVE_ON_LAUNCH)
                || !PlatformUI.isWorkbenchRunning()) return;
        var workbench = PlatformUI.getWorkbench();
        var display = workbench.getDisplay();
        if (display.isDisposed()) return;
        display.asyncExec(() -> {
            if (display.isDisposed()) return;
            IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
            if (window != null) open(window);
        });
    }

    private static void open(IWorkbenchWindow window) {
        var page = window.getActivePage();
        if (page == null) return;
        var descriptor = window.getWorkbench().getPerspectiveRegistry()
                .findPerspectiveWithId(ZephyrHardwareDebugPerspective.ID);
        if (descriptor == null) return;
        // Eclipse persists old perspective mementos (including placeholders).
        // Explicitly instantiate the passive observability views even when
        // an existing workspace was created before the perspective fix.
        page.setPerspective(descriptor);
        try {
            page.showView(ZephyrTraceConsoleView.ID, null, IWorkbenchPage.VIEW_VISIBLE);
            page.showView(ThreadTimelineView.ID, null, IWorkbenchPage.VIEW_VISIBLE);
            page.showView(ZephyrCpuLoadView.ID, null, IWorkbenchPage.VIEW_VISIBLE);
            page.showView(TraceCompassIntegrationView.ID, null, IWorkbenchPage.VIEW_VISIBLE);
            page.showView("org.eclipse.debug.ui.DebugView",
                    null, IWorkbenchPage.VIEW_ACTIVATE);
        } catch (org.eclipse.ui.PartInitException error) {
            var plugin = Activator.getDefault();
            if (plugin != null) plugin.getLog().log(new org.eclipse.core.runtime.Status(
                    org.eclipse.core.runtime.IStatus.WARNING, Activator.ID,
                    "Zephyr debug perspective could not open one or more native observability views.", error));
        }
    }
}
