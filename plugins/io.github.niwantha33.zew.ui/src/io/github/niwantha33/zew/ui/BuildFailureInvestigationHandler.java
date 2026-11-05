// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Path;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;

/** Native entry point; does not start another build merely by opening investigation. */
public final class BuildFailureInvestigationHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            IProject selected = SelectedProject.from(event);
            var view = (BuildFailureInvestigationView) HandlerUtil.getActiveWorkbenchWindowChecked(event)
                .getActivePage().showView(BuildFailureInvestigationView.ID);
            view.displayProject(selected);
        } catch (Exception error) { throw new ExecutionException("Could not open build failure investigation.", error); }
        return null;
    }
    static void showFailure(IProject project, Path build, Path log, String report) {
        if (!PlatformUI.isWorkbenchRunning()) return;
        var display = PlatformUI.getWorkbench().getDisplay();
        display.asyncExec(() -> {
            if (display.isDisposed() || !PlatformUI.isWorkbenchRunning()) return;
            var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null || window.getActivePage() == null) return;
            try {
                var view = (BuildFailureInvestigationView) window.getActivePage()
                    .showView(BuildFailureInvestigationView.ID);
                view.displayFailure(project, build, log, report);
            } catch (Exception error) {
                Activator.getDefault().getLog().log(new Status(IStatus.WARNING, Activator.ID,
                    "Build failure remains available in the Zephyr console; investigation view could not open.", error));
            }
        });
    }
}
