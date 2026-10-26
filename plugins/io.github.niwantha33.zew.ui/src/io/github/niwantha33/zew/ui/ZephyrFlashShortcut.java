// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;

/** Explicit Flash entry in Eclipse Run As and the standard Run toolbar history. */
public final class ZephyrFlashShortcut extends ZephyrDebugShortcut {
    @Override protected String supportedMode() { return ILaunchManager.RUN_MODE; }
    @Override protected String action() { return "flash"; }
    @Override protected void start(IProject project, String mode) {
        if (!supportedMode().equals(mode)) return;
        var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        launchProject(project, window == null ? null : window.getShell());
    }
    static void launchProject(IProject project, Shell shell) {
        try {
            if (project == null || !project.isAccessible())
                throw new java.io.IOException("Select an imported Zephyr project or open one of its source files.");
            ProjectContext.settings(project);
            var manager = DebugPlugin.getDefault().getLaunchManager();
            var type = manager.getLaunchConfigurationType(ZephyrLaunchDelegate.TYPE);
            ILaunchConfiguration config = null;
            for (var candidate : manager.getLaunchConfigurations(type)) {
                if (project.getName().equals(candidate.getAttribute(ZephyrLaunchDelegate.PROJECT, ""))
                        && "flash".equals(candidate.getAttribute(ZephyrLaunchDelegate.ACTION, ""))) {
                    config = candidate;
                    break;
                }
            }
            var copy = config == null ? type.newInstance(null,
                    manager.generateLaunchConfigurationName("Zephyr Flash — " + project.getName())) : config.getWorkingCopy();
            copy.setAttribute(ZephyrLaunchDelegate.PROJECT, project.getName());
            copy.setAttribute(ZephyrLaunchDelegate.ACTION, "flash");
            copy.setAttribute(org.eclipse.cdt.debug.core.ICDTLaunchConfigurationConstants.ATTR_BUILD_BEFORE_LAUNCH,
                    org.eclipse.cdt.debug.core.ICDTLaunchConfigurationConstants.BUILD_BEFORE_LAUNCH_DISABLED);
            copy.setMappedResources(new IResource[] {project});
            DebugUITools.launch(copy.doSave(), ILaunchManager.RUN_MODE);
        } catch (Exception error) {
            MessageDialog.openError(shell, "Zephyr Flash", error.getMessage());
        }
    }
}
