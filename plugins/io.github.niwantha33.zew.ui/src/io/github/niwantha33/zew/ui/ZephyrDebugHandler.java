// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.Map;
import java.util.Objects;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

/** Prepare the exact board automatically, then open native CDT DSF-GDB configuration. */
public final class ZephyrDebugHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        IProject project = SelectedProject.from(event);
        Shell shell = HandlerUtil.getActiveShell(event);
        if (project == null || !project.isAccessible()) {
            MessageDialog.openInformation(shell, "Zephyr Debug",
                "Select an imported Zephyr project or open one of its source files.");
            return null;
        }
        final String board;
        try {
            board = ProjectContext.settings(project).getProperty("board");
        } catch (Exception ex) {
            MessageDialog.openError(shell, "Zephyr Debug", "Project setup is incomplete: " + ex.getMessage());
            return null;
        }
        Job job = new Job("Prepare Zephyr Debug — " + project.getName()) {
            @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                try {
                    // All tool and module provisioning stays in the existing
                    // verified ZephyrProjectBuilder; only after it succeeds can
                    // the native GDB launch be configured.
                    project.build(IncrementalProjectBuilder.FULL_BUILD, Map.of(), monitor);
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    var settings = ProjectContext.settings(project);
                    if (!board.equals(settings.getProperty("board")))
                        throw new java.io.IOException("Board changed during build; select Debug again.");
                    io.github.niwantha33.zew.core.TargetOperations.prepare(
                        ProjectContext.buildDirectory(project, settings), board, "debugserver", "");
                    if (shell != null) shell.getDisplay().asyncExec(() -> {
                        if (shell.isDisposed()) return;
                        try {
                            openDebugConfiguration(project, shell);
                        } catch (Exception ex) {
                            MessageDialog.openError(shell, "Zephyr Debug", ex.getMessage());
                        }
                    });
                    return Status.OK_STATUS;
                } catch (Exception ex) {
                    if (shell != null) shell.getDisplay().asyncExec(() -> {
                        if (!shell.isDisposed())
                            MessageDialog.openError(shell, "Zephyr Debug", "Automatic preparation failed:\n" + ex.getMessage());
                    });
                    return new Status(IStatus.ERROR, Activator.ID, ex.getMessage(), ex);
                }
            }
        };
        job.setUser(true);
        job.schedule();
        return null;
    }

    private static void openDebugConfiguration(IProject project, Shell shell) throws Exception {
        ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
        var type = manager.getLaunchConfigurationType(ZephyrLaunchDelegate.TYPE);
        if (type == null) throw new java.io.IOException("Zephyr launch extension is not installed.");
        ILaunchConfiguration config = null;
        for (ILaunchConfiguration candidate : manager.getLaunchConfigurations(type)) {
            if (Objects.equals(project.getName(),
                    candidate.getAttribute(ZephyrLaunchDelegate.PROJECT, ""))
                    && "debug".equals(candidate.getAttribute(ZephyrLaunchDelegate.ACTION, ""))) {
                config = candidate;
                break;
            }
        }
        if (config == null) {
            String label = manager.generateLaunchConfigurationName("Zephyr Debug — " + project.getName());
            var copy = type.newInstance(null, label);
            copy.setAttribute(ZephyrLaunchDelegate.PROJECT, project.getName());
            copy.setAttribute(ZephyrLaunchDelegate.ACTION, "debug");
            copy.setAttribute(ZephyrNativeDebugDelegate.PORT, 3333);
            config = copy.doSave();
        }
        DebugUITools.openLaunchConfigurationDialogOnGroup(shell, new StructuredSelection(config),
                "org.eclipse.debug.ui.launchGroup.debug");
    }
}
