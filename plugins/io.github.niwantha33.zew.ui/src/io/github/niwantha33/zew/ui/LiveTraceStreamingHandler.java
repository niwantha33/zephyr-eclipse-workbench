// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Deliberately inert UI seam for a possible future binary CTF stream.
 * No receiver, socket, serial port, Job, thread, or target operation exists here.
 */
public final class LiveTraceStreamingHandler extends AbstractHandler {
    public static final boolean LIVE_STREAMING_ENABLED = false;

    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        String state = LIVE_STREAMING_ENABLED
                ? "The flag does not enable a streaming implementation."
                : "LIVE_STREAMING_ENABLED is false.";
        MessageDialog.openInformation(
                HandlerUtil.getActiveShellChecked(event),
                "Live CTF streaming not supported",
                "Live CTF streaming is not implemented.\n"
                        + state + "\n"
                        + "Design: docs/LIVE_TRACE_STREAMING_DESIGN.md");
        return null;
    }
}
