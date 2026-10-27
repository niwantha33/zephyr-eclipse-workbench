// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.Arrays;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.ui.ILaunchShortcut2;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;

/** Standard Eclipse Debug As / toolbar integration, using the same managed CDT launch. */
public class ZephyrDebugShortcut implements ILaunchShortcut2 {
    static IProject project(Object value) {
        return SelectedProject.importedProject(value);
    }
    private IProject project(ISelection selection) {
        return selection instanceof IStructuredSelection items && items.size() == 1
                ? project(items.getFirstElement()) : null;
    }
    private IProject project(IEditorPart editor) {
        return editor == null ? null : project(editor.getEditorInput());
    }
    protected String supportedMode() { return ILaunchManager.DEBUG_MODE; }
    protected String action() { return "debug"; }
    protected void start(IProject project, String mode) {
        if (!supportedMode().equals(mode)) return;
        var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        ZephyrDebugHandler.start(project, window == null ? null : window.getShell());
    }
    @Override public void launch(ISelection selection, String mode) { start(project(selection), mode); }
    @Override public void launch(IEditorPart editor, String mode) { start(project(editor), mode); }
    @Override public IResource getLaunchableResource(ISelection selection) { return project(selection); }
    @Override public IResource getLaunchableResource(IEditorPart editor) { return project(editor); }
    @Override public ILaunchConfiguration[] getLaunchConfigurations(ISelection selection) {
        return configurations(project(selection));
    }
    @Override public ILaunchConfiguration[] getLaunchConfigurations(IEditorPart editor) {
        return configurations(project(editor));
    }
    private ILaunchConfiguration[] configurations(IProject project) {
        if (project == null) return new ILaunchConfiguration[0];
        var manager = DebugPlugin.getDefault().getLaunchManager();
        try {
            return Arrays.stream(manager.getLaunchConfigurations(manager.getLaunchConfigurationType(ZephyrLaunchDelegate.TYPE)))
                    .filter(config -> matches(config, project)).toArray(ILaunchConfiguration[]::new);
        } catch (CoreException error) { return new ILaunchConfiguration[0]; }
    }
    private boolean matches(ILaunchConfiguration config, IProject project) {
        try {
            return project.getName().equals(config.getAttribute(ZephyrLaunchDelegate.PROJECT, ""))
                    && action().equals(config.getAttribute(ZephyrLaunchDelegate.ACTION, ""));
        } catch (CoreException error) { return false; }
    }
}
