// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.cdt.core.IAddress;
import org.eclipse.cdt.dsf.concurrent.DataRequestMonitor;
import org.eclipse.cdt.dsf.datamodel.DMContexts;
import org.eclipse.cdt.dsf.debug.service.IRunControl;
import org.eclipse.cdt.dsf.debug.service.IStack;
import org.eclipse.cdt.dsf.mi.service.IMIExecutionDMContext;
import org.eclipse.cdt.dsf.mi.service.IMICommandControl;
import org.eclipse.cdt.dsf.mi.service.MIStack;
import org.eclipse.cdt.dsf.mi.service.command.output.MIFrame;
import org.eclipse.cdt.dsf.mi.service.command.output.MIStackListFramesInfo;
import org.eclipse.cdt.dsf.service.DsfSession;
import org.eclipse.cdt.utils.Addr32;
import org.eclipse.cdt.utils.Addr64;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

/**
 * Retains CDT's stack service and its caches, repairing only a false
 * "Target not available" result for a newly discovered suspended thread.
 *
 * Some GDB targets report threads in -thread-info without emitting
 * =thread-created. CDT's MIStack command cache can then mark a newly
 * discovered thread context unavailable even though IRunControl reports it
 * suspended. A direct MI request is safe ONLY in that precise case.
 *
 * On other errors (or running targets) propagate the original CDT status.
 * No frames are invented, no cache state is mutated, and no commands are
 * issued while a target is running.
 */
public final class ZephyrStack extends MIStack {
    public ZephyrStack(DsfSession session) { super(session); }

    @Override public void getFrameData(IStack.IFrameDMContext frame,
            DataRequestMonitor<IStack.IFrameDMData> reply) {
        super.getFrameData(frame, new DataRequestMonitor<IStack.IFrameDMData>(getExecutor(), reply) {
            @Override protected void handleCompleted() {
                if (isSuccess()) {
                    reply.setData(getData());
                    reply.done();
                    return;
                }
                IStatus original = getStatus();
                if (original == null || original.getMessage() == null ||
                        !original.getMessage().contains("Target not available")) {
                    reply.setStatus(original != null ? original : new Status(IStatus.ERROR,
                            Activator.ID, "Stack frame query failed without status"));
                    reply.done();
                    return;
                }
                var context = DMContexts.getAncestorOfType(frame, IMIExecutionDMContext.class);
                var run = getServicesTracker().getService(IRunControl.class);
                var mi = getServicesTracker().getService(IMICommandControl.class);
                if (context == null || run == null || mi == null || !run.isSuspended(context)) {
                    reply.setStatus(original);
                    reply.done();
                    return;
                }
                // Do not use MIStack's CommandCache: its unavailable-state flag
                // is the source of this specific false negative. Queue a real
                // frame command using the same production MI command factory.
                mi.queueCommand(mi.getCommandFactory().createMIStackListFrames(context,
                                frame.getLevel(), frame.getLevel()),
                        new DataRequestMonitor<MIStackListFramesInfo>(getExecutor(), reply) {
                    @Override protected void handleCompleted() {
                        if (!isSuccess()) {
                            // A real GDB failure must never be converted to
                            // success or a fabricated source/function label.
                            reply.setStatus(getStatus());
                            reply.done();
                            return;
                        }
                        for (MIFrame miFrame : getData().getMIFrames()) {
                            if (miFrame.getLevel() == frame.getLevel()) {
                                reply.setData(new RealFrame(miFrame));
                                reply.done();
                                return;
                            }
                        }
                        reply.setStatus(new Status(IStatus.ERROR, Activator.ID,
                                "GDB returned no data for requested frame level " + frame.getLevel()));
                        reply.done();
                    }
                });
            }
        });
    }

    private record RealFrame(MIFrame frame) implements IStack.IFrameDMData {
        @Override public IAddress getAddress() {
            String address = frame.getAddress();
            if (address == null || !address.matches("0x[0-9a-fA-F]+")) return new Addr32(0);
            return address.length() <= 10 ? new Addr32(address) : new Addr64(address);
        }
        @Override public String getFile() { return frame.getFile(); }
        @Override public String getFunction() { return frame.getFunction(); }
        @Override public int getLine() { return frame.getLine(); }
        @Override public int getColumn() { return 0; }
        @Override public String getModule() { return ""; }
    }
}
