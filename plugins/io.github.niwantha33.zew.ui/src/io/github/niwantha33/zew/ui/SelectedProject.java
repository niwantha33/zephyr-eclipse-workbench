// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IDebugElement;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;

/** Shared resource/editor/debug-context resolution; never guess the first workspace project. */
final class SelectedProject {
    private SelectedProject() {}

    static IProject from(ExecutionEvent event) {
        IProject project = null;
        if (HandlerUtil.getCurrentSelection(event) instanceof IStructuredSelection selection && selection.size() == 1)
            project = fromObject(selection.getFirstElement());
        if (project == null && HandlerUtil.getActiveEditor(event) != null)
            project = fromObject(HandlerUtil.getActiveEditor(event).getEditorInput());
        if (project == null) project = fromObject(DebugUITools.getDebugContext());
        return project;
    }

    static IProject importedProject(Object value) {
        IProject project = fromObject(value);
        try {
            return project != null && project.isAccessible() && project.getPersistentProperty(
                    new QualifiedName(Activator.ID, "setupRoot")) != null ? project : null;
        } catch (CoreException ignored) { return null; }
    }

    static IProject fromObject(Object value) {
        if (value == null) return null;
        if (value instanceof IResource resource) return resource.getProject();
        ILaunch launch = value instanceof ILaunch direct ? direct
                : value instanceof IDebugElement element ? element.getLaunch()
                : value instanceof IProcess process ? process.getLaunch() : null;
        if (launch == null) {
            IResource resource = Adapters.adapt(value, IResource.class);
            if (resource != null) return resource.getProject();
            launch = Adapters.adapt(value, ILaunch.class);
        }
        if (launch == null || launch.getLaunchConfiguration() == null) return null;
        try {
            var config = launch.getLaunchConfiguration();
            if (!ZephyrLaunchDelegate.TYPE.equals(config.getType().getIdentifier())) return null;
            String name = config.getAttribute(ZephyrLaunchDelegate.PROJECT, "");
            return name.isBlank() ? null : ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        } catch (CoreException ignored) { return null; }
    }
}
