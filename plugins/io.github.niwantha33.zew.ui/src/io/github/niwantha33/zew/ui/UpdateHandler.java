// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.handlers.HandlerUtil;

public final class UpdateHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) {
        var preferences = Activator.getDefault().getPreferenceStore();
        boolean configure = event.getCommand().getId().endsWith("updateConnection");
        if (configure || !preferences.getBoolean(UpdateConnection.CONFIGURED)) {
            if (new UpdateConnection(HandlerUtil.getActiveShell(event)).open() != Window.OK) return null;
        }
        UpdateController.check(true);
        return null;
    }
}
