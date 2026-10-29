// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.Map;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * The menu Build/Rebuild commands use the project builder, not global
 * preference paths. The builder manages West/SDK/modules and exact CPU.
 */
public final class ZephyrBuildHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        IProject project = SelectedProject.from(event);
        if (project == null || !project.isAccessible()) {
            MessageDialog.openInformation(HandlerUtil.getActiveShell(event), "Zephyr Build",
                "Select an imported Zephyr project in Project Explorer or open one of its source files.");
            return null;
        }
        boolean pristine = event.getCommand().getId().endsWith(".rebuild");
        try {
            ProjectContext.settings(project);
        } catch (Exception missing) {
            var dialog = org.eclipse.ui.dialogs.PreferencesUtil.createPropertyDialogOn(
                    HandlerUtil.getActiveShell(event), project, Activator.ID + ".projectProperties", null, null);
            if (dialog == null || dialog.open() != org.eclipse.jface.window.Window.OK) return null;
            try { ProjectContext.settings(project); }
            catch (Exception incomplete) { return null; }
        }
        Job job = new Job((pristine ? "Pristine Rebuild" : "Build") + " Zephyr — " + project.getName()) {
            @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                try {
                    project.build(IncrementalProjectBuilder.FULL_BUILD, Activator.ID + ".zephyrBuilder",
                        pristine ? Map.of("zew.pristine", "true") : Map.of(), monitor);
                    return monitor.isCanceled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
                } catch (org.eclipse.core.runtime.CoreException ex) {
                    return ex.getStatus();
                }
            }
        };
        job.setUser(true);
        job.schedule();
        return null;
    }
}
