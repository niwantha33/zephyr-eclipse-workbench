// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.handlers.HandlerUtil;

/** Opens the offline investigation view without requiring a project or a target connection. */
public final class FaultAnalysisHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage().showView(FaultAnalysisView.ID);
        } catch (Exception error) { throw new ExecutionException("Could not open Zephyr fault investigation.", error); }
        return null;
    }
}
