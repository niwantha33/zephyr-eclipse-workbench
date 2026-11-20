// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.handlers.HandlerUtil;

/** Explicit board/profile editing through the existing validated project property page. */
public final class ZephyrProjectSettingsHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) {
        var project = SelectedProject.from(event);
        if (project == null || !ZephyrProjectLifecycle.canInspect(project)) {
            MessageDialog.openInformation(HandlerUtil.getActiveShell(event),
                    "Select a Zephyr project",
                    "Select an imported Zephyr project in Project Explorer before editing its board.");
            return null;
        }
        var dialog = PreferencesUtil.createPropertyDialogOn(HandlerUtil.getActiveShell(event),
                project, "io.github.niwantha33.zew.projectProperties", null, null);
        if (dialog != null) dialog.open();
        return null;
    }
}
