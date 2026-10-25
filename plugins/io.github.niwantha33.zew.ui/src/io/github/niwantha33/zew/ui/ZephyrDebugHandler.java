// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.Objects;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;

/** Debug toolbar/menu action: select the project's persistent native DSF-GDB launch. */
public final class ZephyrDebugHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        IResource selected = null;
        if (HandlerUtil.getCurrentSelection(event) instanceof IStructuredSelection selection
                && selection.getFirstElement() instanceof IAdaptable adaptable)
            selected = adaptable.getAdapter(IResource.class);
        if (selected == null && HandlerUtil.getActiveEditor(event) != null)
            selected = HandlerUtil.getActiveEditor(event).getEditorInput().getAdapter(IResource.class);
        if (selected == null) {
            MessageDialog.openInformation(HandlerUtil.getActiveShell(event), "Zephyr Debug",
                    "Select the Zephyr project in Project Explorer or open one of its source files.");
            return null;
        }
        IProject project = selected.getProject();
        try {
            var settings = ProjectContext.settings(project);
            // Validate now, then validate again in the launch delegate directly
            // before touching hardware: a launch can be edited or queued.
            io.github.niwantha33.zew.core.TargetOperations.prepare(
                    ProjectContext.buildDirectory(project, settings),
                    settings.getProperty("board"), "debugserver", "");
            ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
            var type = manager.getLaunchConfigurationType(ZephyrLaunchDelegate.TYPE);
            if (type == null) throw new java.io.IOException("Zephyr launch extension is not installed.");
            ILaunchConfiguration config = null;
            for (ILaunchConfiguration candidate : manager.getLaunchConfigurations(type)) {
                if (Objects.equals(project.getName(), candidate.getAttribute(ZephyrLaunchDelegate.PROJECT, ""))) {
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
            DebugUITools.openLaunchConfigurationDialogOnGroup(
                    HandlerUtil.getActiveShell(event), new StructuredSelection(config),
                    "org.eclipse.debug.ui.launchGroup.debug");
        } catch (Exception ex) {
            MessageDialog.openError(HandlerUtil.getActiveShell(event), "Zephyr Debug",
                    "Unable to prepare this target for debugging:\n" + ex.getMessage());
        }
        return null;
    }
}
