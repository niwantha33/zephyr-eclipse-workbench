// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.handlers.HandlerUtil;

/** Opens the integrated Configuration Studio DTS Topology view; never starts a second process. */
public final class HardwareStudioHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            var page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
            boolean physical = "io.github.niwantha33.zew.physicalHardwareStudio".equals(event.getCommand().getId());
            var part = page.showView(physical ? HardwareStudioView.PHYSICAL_ID : HardwareStudioView.ID);
            if (part instanceof HardwareStudioView view) {
                var project = SelectedProject.from(event);
                if (project != null && ZephyrProjectLifecycle.canInspect(project))
                    view.displayCurrentProject(project);
            }
        } catch (Exception unavailable) {
            throw new ExecutionException("Cannot open embedded DTS Topology", unavailable);
        }
        return null;
    }
}
