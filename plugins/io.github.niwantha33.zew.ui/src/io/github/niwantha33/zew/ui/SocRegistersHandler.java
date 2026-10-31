// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;

/** Open the views supported by the native CDT launch, rather than an unrelated peripheral plug-in. */
public final class SocRegistersHandler extends AbstractHandler {
    public static final String VIEW_ID = Activator.ID + ".socRegisters";
    private static final String[] DATA_VIEWS = {
        "org.eclipse.debug.ui.VariableView", "org.eclipse.debug.ui.ExpressionView", "org.eclipse.debug.ui.RegisterView"
    };

    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        showDebugData();
        return null;
    }

    /** Safe from the launch worker; all workbench access is deferred to its UI thread. */
    public static void showDebugData() { showDebugData(true); }

    /** Startup favours CPU registers, which remain useful when a frame has no locals. */
    public static void showDebugDataOnLaunch() { showDebugData(false, null); }

    /** Native GDB launch supplies its exact imported Zephyr project. */
    public static void showDebugDataOnLaunch(org.eclipse.core.resources.IProject project) {
        showDebugData(false, project);
    }

    private static void showDebugData(boolean peripherals) { showDebugData(peripherals, null); }

    private static void showDebugData(boolean peripherals, org.eclipse.core.resources.IProject project) {
        if (!PlatformUI.isWorkbenchRunning()) return;
        var workbench = PlatformUI.getWorkbench();
        var display = workbench.getDisplay();
        if (display.isDisposed()) return;
        display.asyncExec(() -> {
            var window = workbench.getActiveWorkbenchWindow();
            var page = window == null ? null : window.getActivePage();
            if (page == null) return;
            for (String id : DATA_VIEWS) {
                try { page.showView(id, null, IWorkbenchPage.VIEW_VISIBLE); }
                catch (org.eclipse.ui.PartInitException error) { log(id, error); }
            }
            try {
                var registers = page.showView(VIEW_ID, null,
                        peripherals ? IWorkbenchPage.VIEW_ACTIVATE : IWorkbenchPage.VIEW_VISIBLE);
                if (project != null && registers instanceof SocRegistersView view)
                    view.useLaunchProject(project);
                if (!peripherals) page.showView(DATA_VIEWS[2], null, IWorkbenchPage.VIEW_ACTIVATE);
            }
            catch (org.eclipse.ui.PartInitException error) { log(VIEW_ID, error); }
        });
    }

    private static void log(String id, Exception error) {
        var plugin = Activator.getDefault();
        if (plugin != null) plugin.getLog().log(new Status(IStatus.WARNING, Activator.ID,
                "Could not open native debug data view " + id + ". Use Window > Show View > Debug.", error));
    }
}
