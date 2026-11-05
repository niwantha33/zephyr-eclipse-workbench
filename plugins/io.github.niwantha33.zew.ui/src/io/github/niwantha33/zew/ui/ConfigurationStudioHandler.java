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
    /** Ordinary Zephyr menu launches always start with the visual guide.
     * Resource-specific deep links require an explicit project/file popup.
     * Merely having prj.conf selected in Project Explorer is not a deep link.
     */
    enum Entry { START, HARDWARE, PROJECT_OVERRIDES }

    static Entry navigationFor(boolean explicitPopup, String resourcePath) {
        if (!explicitPopup || resourcePath == null) return Entry.START;
        if (io.github.niwantha33.zew.core.ConfigurationStudioResources.hardware(resourcePath))
            return Entry.HARDWARE;
        if (io.github.niwantha33.zew.core.ConfigurationStudioResources.projectSettings(resourcePath))
            return Entry.PROJECT_OVERRIDES;
        return Entry.START;
    }

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
        String relative = null;
        if (selection instanceof IStructuredSelection items && items.size() == 1
                && items.getFirstElement() instanceof IFile file)
            relative = file.getProjectRelativePath().toPortableString();
        Entry entry = navigationFor(activeMenu instanceof IStructuredSelection, relative);
        boolean devicetree = entry == Entry.HARDWARE;
        boolean focusOverrides = entry == Entry.PROJECT_OVERRIDES;
        String overlayPath = devicetree ? relative : null;
        try {
            var page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
            if (devicetree || focusOverrides) {
                // Explicit right-click deep links preserve the original
                // advanced editors, syntax validation and source ownership.
                var advanced = (ConfigurationStudioView) page.showView(ConfigurationStudioView.ID);
                advanced.displayProject(project, devicetree, overlayPath);
                if (focusOverrides) advanced.focusProjectChanges();
            } else {
                // The ordinary Configuration Studio menu is ALWAYS the
                // three-section task-first experience, regardless of what
                // file happens to be selected in Project Explorer.
                var home = (ConfigurationHomeView) page.showView(ConfigurationHomeView.ID);
                home.displayProject(project);
            }
        } catch (Exception error) {
            throw new ExecutionException("Could not open Zephyr Configuration Studio.", error);
        }
        return null;
    }
}
