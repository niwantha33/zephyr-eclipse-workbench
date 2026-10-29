// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.cdt.dsf.concurrent.RequestMonitor;
import org.eclipse.cdt.dsf.gdb.service.GDBProcesses_7_12;
import org.eclipse.cdt.dsf.gdb.service.command.IGDBControl;
import org.eclipse.cdt.dsf.service.DsfSession;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

/** Embedded targets have no OS process to kill: Terminate ends the local debug session. */
public final class ZephyrDebugProcesses extends GDBProcesses_7_12 {
    public ZephyrDebugProcesses(DsfSession session) { super(session); }

    @Override public void terminate(IThreadDMContext thread, RequestMonitor reply) {
        endSession(reply);
    }

    @Override public void terminate(IThreadDMContext[] threads, RequestMonitor reply) {
        // A multi-selection still represents one owned MCU debug session.
        endSession(reply);
    }

    private void endSession(RequestMonitor reply) {
        var control = getServicesTracker().getService(IGDBControl.class);
        if (control == null) {
            reply.done(new Status(IStatus.ERROR, Activator.ID, "Debug command service is unavailable."));
            return;
        }
        // CDT bounds GDB exit and destroys an unresponsive local backend.
        // Do not send the desktop 'kill' command to an embedded remote target.
        control.terminate(reply);
    }
}
