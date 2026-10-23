// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.handlers.HandlerUtil;

public final class ToolsHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) {
        new ToolsDialog(HandlerUtil.getActiveShell(event)).open();
        return null;
    }
}
