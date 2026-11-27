// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;

/** Routes DTS source actions to Configuration Studio and configuration requests to the settings tools. */
public final class ConfigurationStudioHandler extends AbstractHandler {
    private static final String SETTINGS_COMMAND = "io.github.niwantha33.zew.projectConfiguration";

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
        return entry == Entry.PROJECT_OVERRIDES ? ConfigurationHomeView.ID : HardwareStudioView.ID;
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
        IFile source = selection instanceof IStructuredSelection items && items.size() == 1
                && items.getFirstElement() instanceof IFile file ? file : null;
        String relative = source == null ? null : source.getProjectRelativePath().toPortableString();
        Entry entry = SETTINGS_COMMAND.equals(event.getCommand().getId())
                ? Entry.PROJECT_OVERRIDES : navigationFor(activeMenu instanceof IStructuredSelection, relative);
        try {
            var page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
            var part = page.showView(targetViewId(entry));
            if (part instanceof HardwareStudioView studio) {
                studio.displayCurrentProject(project);
                if (entry == Entry.HARDWARE && project != null && source != null
                        && project.equals(source.getProject()) && source.exists())
                    org.eclipse.ui.ide.IDE.openEditor(page, source, true);
            } else if (part instanceof ConfigurationHomeView settings) {
                if (project != null) settings.displayProject(project);
                else settings.displayMissingProject();
                settings.openKconfigSettings();
            }
        } catch (Exception error) {
            throw new ExecutionException("Could not open Zephyr Configuration Studio.", error);
        }
        return null;
    }
}
