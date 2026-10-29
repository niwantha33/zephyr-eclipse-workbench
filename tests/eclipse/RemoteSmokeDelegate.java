// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import org.eclipse.cdt.dsf.debug.service.IDsfDebugServicesFactory;
import org.eclipse.cdt.dsf.gdb.launching.GdbLaunchDelegate;
import org.eclipse.cdt.dsf.gdb.service.*;
import org.eclipse.cdt.dsf.gdb.service.command.GDBControl_7_12;
import org.eclipse.cdt.dsf.mi.service.IMIBackend;
import org.eclipse.cdt.dsf.mi.service.command.CommandFactory;
import org.eclipse.cdt.dsf.debug.service.command.ICommandControl;
import org.eclipse.cdt.dsf.service.DsfSession;
import org.eclipse.core.runtime.*;
import org.eclipse.debug.core.*;
import org.eclipse.core.runtime.Platform;

/** Test-only transport: actual GDB/gdbserver, production thread settings and service factory. */
public final class RemoteSmokeDelegate extends GdbLaunchDelegate {
    @Override public ILaunch getLaunch(ILaunchConfiguration configuration, String mode) throws CoreException {
        try {
            var ui = Platform.getBundle("io.github.niwantha33.zew.ui");
            var working = configuration.getWorkingCopy();
            var settings = ui.loadClass("io.github.niwantha33.zew.ui.ZephyrNativeDebugDelegate")
                    .getDeclaredMethod("configureThreadTracking", ILaunchConfigurationWorkingCopy.class);
            settings.setAccessible(true); settings.invoke(null, working);
            return super.getLaunch(working.doSave(), mode);
        } catch (Exception error) { throw new CoreException(new Status(IStatus.ERROR, "zew.editor.smoke", "Remote launch settings", error)); }
    }
    @Override protected IDsfDebugServicesFactory newServiceFactory(ILaunchConfiguration configuration, String version) {
        // Linux also offers a second PTY MI channel. Use the stdio MI transport used on Windows
        // so the test relay can prove that creation notifications did not reach CDT.
        return new GdbDebugServicesFactory(version, configuration) {
            @Override protected IMIBackend createBackendGDBService(DsfSession session, ILaunchConfiguration config) {
                return new GDBBackend_7_12(session, config) {
                    @Override public boolean isFullGdbConsoleSupported() { return false; }
                    @Override public boolean useTargetAsync() { return true; }
                };
            }
            @Override protected ICommandControl createCommandControl(DsfSession session, ILaunchConfiguration config) {
                try {
                    var commands = (CommandFactory) Platform.getBundle("io.github.niwantha33.zew.ui")
                            .loadClass("io.github.niwantha33.zew.ui.ZephyrDebugServicesFactory$OpenOcdCommands").getConstructor().newInstance();
                    return new GDBControl_7_12(session, config, commands);
                } catch (Exception error) { throw new IllegalStateException(error); }
            }
        };
    }
}
