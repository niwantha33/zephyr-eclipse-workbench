// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import org.eclipse.cdt.dsf.debug.service.IDsfDebugServicesFactory;
import org.eclipse.cdt.dsf.debug.service.IStack;
import org.eclipse.cdt.dsf.debug.service.IProcesses;
import org.eclipse.cdt.dsf.gdb.launching.GdbLaunchDelegate;
import org.eclipse.cdt.dsf.gdb.service.*;
import org.eclipse.cdt.dsf.gdb.service.command.GDBControl_7_12;
import org.eclipse.cdt.dsf.mi.service.IMIBackend;
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
            ILaunch launch = super.getLaunch(working.doSave(), mode);
            String compilation = configuration.getAttribute("zew.test.compilationRoot", "");
            if (!compilation.isBlank()) {
                String source = configuration.getAttribute("zew.test.sourceRoot", "");
                var mapping = ui.loadClass("io.github.niwantha33.zew.ui.ZephyrNativeDebugDelegate")
                        .getDeclaredMethod("configureSourceMappings", ILaunch.class, java.util.List.class);
                mapping.setAccessible(true);
                mapping.invoke(null, launch, java.util.List.of(
                        new io.github.niwantha33.zew.core.DebugSourcePaths.Mapping(compilation, source)));
                // The installed product helper must make an actual backend-enabled
                // CDT mapping, not merely resolve a path via a fallback container.
                var director = (org.eclipse.cdt.dsf.gdb.launching.GdbSourceLookupDirector) launch.getSourceLocator();
                var backend = director.getSubstitutionsPaths();
                if (!source.replace('\\', '/').equals(
                        java.util.Objects.toString(backend.get(compilation), "").replace('\\', '/')))
                    throw new IllegalStateException("Production helper failed to install a GDB source substitution: " + backend);
            }
            return launch;
        } catch (Exception error) { throw new CoreException(new Status(IStatus.ERROR, "zew.editor.smoke", "Remote launch settings", error)); }
    }
    @Override protected IDsfDebugServicesFactory newServiceFactory(ILaunchConfiguration configuration, String version) {
        // Linux also offers a second PTY MI channel. Use the stdio MI transport used on Windows
        // so the test relay can prove that creation notifications did not reach CDT.
        return new GdbDebugServicesFactory(version, configuration) {
            @Override protected IProcesses createProcessesService(DsfSession session) {
                try {
                    var implementation = Platform.getBundle("io.github.niwantha33.zew.ui")
                            .loadClass("io.github.niwantha33.zew.ui.ZephyrDebugProcesses");
                    return (IProcesses) implementation.getConstructor(DsfSession.class).newInstance(session);
                } catch (Exception error) { throw new IllegalStateException("Production debug processes unavailable", error); }
            }
            @Override protected IStack createStackService(DsfSession session) {
                // Use the installed production implementation without making
                // private UI packages part of the public OSGi API.
                try {
                    var bundle = Platform.getBundle("io.github.niwantha33.zew.ui");
                    var implementation = bundle.loadClass("io.github.niwantha33.zew.ui.ZephyrStack");
                    return (IStack) implementation.getConstructor(DsfSession.class).newInstance(session);
                } catch (Exception error) { throw new IllegalStateException("Production ZephyrStack unavailable", error); }
            }
            @Override protected IMIBackend createBackendGDBService(DsfSession session, ILaunchConfiguration config) {
                return new GDBBackend_7_12(session, config) {
                    @Override public boolean isFullGdbConsoleSupported() { return false; }
                    @Override public boolean useTargetAsync() { return true; }
                };
            }
            @Override protected ICommandControl createCommandControl(DsfSession session, ILaunchConfiguration config) {
                // This integration fixture owns a plain gdbserver --once.
                // OpenOCD's production command factory forces extended-remote,
                // which is inappropriate for the single-process gdbserver test.
                // Use the standard remote protocol here. The production
                // OpenOCD command factory is covered by dedicated tests.
                return new GDBControl_7_12(session, config,
                        new org.eclipse.cdt.dsf.gdb.service.command.CommandFactory_6_8());
            }
        };
    }
}
