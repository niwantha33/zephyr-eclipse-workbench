// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;

/** Every Zephyr configuration action opens the SAME three-section Studio. */
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

    static String targetViewId(Entry entry) {
        // Resource-specific actions select a section, NOT a different,
        // independent editor with stale selection and seven primary tabs.
        return ConfigurationHomeView.ID;
    }

    static String initialSection(Entry entry) {
        return switch (entry) {
            case HARDWARE -> "hardware";
            case PROJECT_OVERRIDES -> "system";
            case START -> "overview";
        };
    }

    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        var activeMenu = HandlerUtil.getActiveMenuSelection(event);
        var selection = activeMenu instanceof IStructuredSelection
                ? activeMenu : HandlerUtil.getCurrentSelection(event);
        var project = selection instanceof IStructuredSelection items && items.size() == 1
                ? SelectedProject.fromObject(items.getFirstElement()) : null;
        if (project == null && activeMenu == null) project = SelectedProject.from(event);
        // Open the Studio even with no selected project: its empty state is
        // actionable and explains the next step. A missing selection must
        // never result in the old Start Here view or a blank workbench.
        if (project != null && !ZephyrProjectLifecycle.canInspect(project))
            project = null;
        String relative = null;
        if (selection instanceof IStructuredSelection items && items.size() == 1
                && items.getFirstElement() instanceof IFile file)
            relative = file.getProjectRelativePath().toPortableString();
        Entry entry = navigationFor(activeMenu instanceof IStructuredSelection, relative);
        try {
            var page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
            var home = (ConfigurationHomeView) page.showView(targetViewId(entry));
            if (project != null) home.displayProject(project);
            else home.displayMissingProject();
            home.openSection(initialSection(entry));
        } catch (Exception error) {
            throw new ExecutionException("Could not open Zephyr Configuration Studio.", error);
        }
        return null;
    }
}
