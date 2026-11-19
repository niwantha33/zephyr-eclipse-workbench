// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.handlers.HandlerUtil;

/** Opens the integrated Hardware Studio view; never starts a second process. */
public final class HardwareStudioHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            var page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
            var part = page.showView(HardwareStudioView.ID);
            if (part instanceof HardwareStudioView view) {
                var project = SelectedProject.from(event);
                if (project != null && project.isAccessible()) {
                    try {
                        var settings = ProjectContext.settings(project);
                        view.displayProject(project, ProjectContext.buildDirectory(project, settings), null);
                    } catch (Exception incomplete) {
                        view.displayProject(null, null, null);
                    }
                }
            }
        } catch (Exception unavailable) {
            throw new ExecutionException("Cannot open embedded Hardware Studio", unavailable);
        }
        return null;
    }
}
