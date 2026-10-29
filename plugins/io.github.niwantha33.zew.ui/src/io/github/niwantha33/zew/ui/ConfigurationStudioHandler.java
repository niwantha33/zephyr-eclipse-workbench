// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;

/** Open the native configuration editor for the selected existing application. */
public final class ConfigurationStudioHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        var project = SelectedProject.from(event);
        if (project == null || !ZephyrProjectLifecycle.supports(project)) {
            MessageDialog.openInformation(HandlerUtil.getActiveShell(event), "Zephyr Configuration Studio",
                    "Select an imported Zephyr application in Project Explorer or an open source editor.");
            return null;
        }
        try {
            var page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
            var view = (ConfigurationStudioView) page.showView(ConfigurationStudioView.ID);
            view.displayProject(project);
        } catch (Exception error) {
            throw new ExecutionException("Could not open Zephyr Configuration Studio.", error);
        }
        return null;
    }
}
