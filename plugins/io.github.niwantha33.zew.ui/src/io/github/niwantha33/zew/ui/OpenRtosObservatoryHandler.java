// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Opens RTOS Observatory independently of Eclipse without creating a GDB,
 * OpenOCD, target-memory or trace-reading connection.
 */
public final class OpenRtosObservatoryHandler extends AbstractHandler {
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        var shell = HandlerUtil.getActiveShellChecked(event);
        try {
            var plan = ObservatoryDesktopLaunch.plan(System.getenv());
            new ProcessBuilder(plan.arguments())
                    .directory(plan.directory().toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!plan.bridgeStatus())
                MessageDialog.openInformation(shell, "RTOS Observatory",
                        "Opened the standalone analyser without debugger lifecycle evidence.\n\n"
                        + "To publish passive DSF status, select a CDT DSF-GDB launch in "
                        + "the Debug view and choose Toggle Passive Observatory Bridge. "
                        + "Then reopen Observatory. This does not read target memory.");
        } catch (IOException | RuntimeException error) {
            MessageDialog.openError(shell, "RTOS Observatory",
                    "Cannot open the standalone analyser: " + error.getMessage());
        }
        return null;
    }
}
