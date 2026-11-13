// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.handlers.HandlerUtil;

/** Restores an explicit Trace Compass entry point without requiring its plug-ins. */
public final class TraceCompassIntegrationHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            var page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
            var view = (TraceCompassIntegrationView) page.showView(TraceCompassIntegrationView.ID);
            var project = SelectedProject.from(event);
            if (project != null && ZephyrProjectLifecycle.canInspect(project))
                view.displayProject(project);
        } catch (Exception error) {
            throw new ExecutionException("Cannot open optional Trace Compass integration.", error);
        }
        return null;
    }
}
