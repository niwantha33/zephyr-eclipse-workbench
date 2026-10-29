// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;

/** The Zephyr menu and right-click project/config-file actions share one view. */
public final class ConfigurationStudioHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        var activeMenu = HandlerUtil.getActiveMenuSelection(event);
        var selection = activeMenu instanceof IStructuredSelection
                ? activeMenu : HandlerUtil.getCurrentSelection(event);
        var project = selection instanceof IStructuredSelection items && items.size() == 1
                ? SelectedProject.fromObject(items.getFirstElement()) : null;
        if (project == null && activeMenu == null) project = SelectedProject.from(event);
        if (project == null || !ZephyrProjectLifecycle.canInspect(project)) {
            MessageDialog.openInformation(HandlerUtil.getActiveShell(event), "Zephyr Configuration Studio",
                    "Select a Zephyr project or its prj.conf in Project Explorer.");
            return null;
        }
        boolean devicetree = false;
        boolean focusOverrides = false;
        String overlayPath = null;
        if (selection instanceof IStructuredSelection items && items.size() == 1
                && items.getFirstElement() instanceof IFile file) {
            String relative = file.getProjectRelativePath().toPortableString();
            devicetree = io.github.niwantha33.zew.core.ConfigurationStudioResources.hardware(relative);
            focusOverrides = io.github.niwantha33.zew.core.ConfigurationStudioResources.projectSettings(relative);
            if (devicetree) overlayPath = relative;
        }
        try {
            var page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
            var view = (ConfigurationStudioView) page.showView(ConfigurationStudioView.ID);
            view.displayProject(project, devicetree, overlayPath);
            if (focusOverrides) view.focusProjectChanges();
        } catch (Exception error) {
            throw new ExecutionException("Could not open Zephyr Configuration Studio.", error);
        }
        return null;
    }
}
