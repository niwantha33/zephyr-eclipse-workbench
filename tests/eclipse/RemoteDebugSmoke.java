// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.eclipse.cdt.debug.core.ICDTLaunchConfigurationConstants;
import org.eclipse.cdt.dsf.concurrent.*;
import org.eclipse.cdt.dsf.datamodel.*;
import org.eclipse.cdt.dsf.debug.service.*;
import org.eclipse.cdt.dsf.debug.service.IRunControl.*;
import org.eclipse.cdt.dsf.gdb.IGDBLaunchConfigurationConstants;
import org.eclipse.cdt.dsf.gdb.launching.GdbLaunch;
import org.eclipse.cdt.dsf.gdb.service.command.IGDBControl;
import org.eclipse.cdt.dsf.service.*;
import org.eclipse.core.runtime.*;
import org.eclipse.debug.core.*;
import org.osgi.framework.FrameworkUtil;

/** Live DSF test: discover threads created after connection, fetch frames, then resume and pause. */
public final class RemoteDebugSmoke {
    private static <T> T ask(DsfSession session, Consumer<DataRequestMonitor<T>> request) throws Exception {
        Query<T> query = new Query<>() {
            @Override protected void execute(DataRequestMonitor<T> result) {
                try { request.accept(result); }
                catch (Throwable error) { result.done(new Status(IStatus.ERROR, "zew.editor.smoke", "DSF request failed", error)); }
            }
        };
        session.getExecutor().execute(query);
        return query.get(10, TimeUnit.SECONDS);
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        System.out.println("PASS live DSF: " + message);
    }
    private static boolean suspended(DsfSession session, DsfServicesTracker services, IContainerDMContext container) throws Exception {
        return RemoteDebugSmoke.<Boolean>ask(session, result -> {
            result.setData(services.getService(IRunControl.class).isSuspended(container)); result.done();
        });
    }
    private static void awaitState(DsfSession session, DsfServicesTracker services, IContainerDMContext container, boolean state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (suspended(session, services, container) != state) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Target did not become " + (state ? "suspended" : "running"));
            Thread.sleep(50);
        }
    }
    public static void run(Path fixture) throws Exception {
        String port = System.getProperty("zew.remote.port", "");
        if (port.isBlank()) throw new AssertionError("Native smoke requires an owned GDB server");
        var manager = DebugPlugin.getDefault().getLaunchManager();
        // Use the real, installed Zephyr launch type. A dynamically injected
        // test-only bundle does not reliably register additional launch types
        // when Eclipse's launch manager has already initialised its registry.
        // The test's DSF delegate below is invoked directly; the production
        // type's built-in launch delegate must not attempt to flash hardware.
        var type = manager.getLaunchConfigurationType("io.github.niwantha33.zew.ui.launchType");
        check(type != null, "installed Zephyr launch configuration type registered");
        var working = type.newInstance(null, manager.generateLaunchConfigurationName("Remote thread reliability smoke"));
        working.setAttribute(ICDTLaunchConfigurationConstants.ATTR_PROJECT_NAME, "HelloZephyrEditorSmoke");
        working.setAttribute(ICDTLaunchConfigurationConstants.ATTR_PROGRAM_NAME, fixture.resolve("remote-threads").toString());
        working.setAttribute(ICDTLaunchConfigurationConstants.ATTR_BUILD_BEFORE_LAUNCH, ICDTLaunchConfigurationConstants.BUILD_BEFORE_LAUNCH_DISABLED);
        working.setAttribute(ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_START_MODE, IGDBLaunchConfigurationConstants.DEBUGGER_MODE_REMOTE);
        working.setAttribute(ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_STOP_AT_MAIN, true);
        working.setAttribute(ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_STOP_AT_MAIN_SYMBOL, "zew_first_stop");
        working.setAttribute(IGDBLaunchConfigurationConstants.ATTR_DEBUG_NAME, System.getProperty("zew.remote.gdb"));
        working.setAttribute(IGDBLaunchConfigurationConstants.ATTR_REMOTE_TCP, true);
        working.setAttribute(IGDBLaunchConfigurationConstants.ATTR_HOST, "127.0.0.1");
        working.setAttribute(IGDBLaunchConfigurationConstants.ATTR_PORT, port);
        // Reproduce an old saved configuration. The real product settings must migrate it.
        working.setAttribute(IGDBLaunchConfigurationConstants.ATTR_DEBUGGER_UPDATE_THREADLIST_ON_SUSPEND, false);
        var configuration = working.doSave();
        GdbLaunch launch = null;
        DsfServicesTracker services = null;
        try {
            // Do not use configuration.launch(): that routes through Zephyr's
            // production debug delegate, which owns real target setup/flash.
            // Exercise the same DSF backend with an owned local gdbserver.
            var delegate = new RemoteSmokeDelegate();
            launch = (GdbLaunch) delegate.getLaunch(configuration, ILaunchManager.DEBUG_MODE);
            manager.addLaunch(launch);
            delegate.launch(launch.getLaunchConfiguration(), ILaunchManager.DEBUG_MODE,
                    launch, new NullProgressMonitor());
            check(launch.getLaunchConfiguration().getAttribute(IGDBLaunchConfigurationConstants.ATTR_DEBUGGER_UPDATE_THREADLIST_ON_SUSPEND, false),
                    "saved launch now refreshes threads at every halt");
            var session = launch.getSession();
            services = new DsfServicesTracker(FrameworkUtil.getBundle(RemoteDebugSmoke.class).getBundleContext(), session.getId());
            final var tracker = services;
            IDMContext[] containers = ask(session, result -> {
                var control = tracker.getService(IGDBControl.class);
                tracker.getService(IProcesses.class).getProcessesBeingDebugged(control.getContext(), result);
            });
            check(containers.length == 1 && containers[0] instanceof IContainerDMContext, "native remote process is available");
            var container = (IContainerDMContext) containers[0];
            awaitState(session, tracker, container, true);
            IExecutionDMContext[] initial = ask(session, result -> tracker.getService(IRunControl.class).getExecutionContexts(container, result));
            check(initial.length == 1, "initial thread list cached before worker creation");
            RemoteDebugSmoke.<org.eclipse.cdt.dsf.mi.service.command.output.MIBreakInsertInfo>ask(session, result -> {
                var control = tracker.getService(IGDBControl.class);
                var target = DMContexts.getAncestorOfType(container, IBreakpoints.IBreakpointsTargetDMContext.class);
                control.queueCommand(control.getCommandFactory().createMIBreakInsert(target, "zew_second_stop"), result);
            });
            RemoteDebugSmoke.<Object>ask(session, result -> tracker.getService(IRunControl.class).resume(container,
                    new RequestMonitor(session.getExecutor(), result) { @Override protected void handleSuccess() { result.done(); } }));
            awaitState(session, tracker, container, false);
            awaitState(session, tracker, container, true);
            IExecutionDMContext[] threads = ask(session, result -> tracker.getService(IRunControl.class).getExecutionContexts(container, result));
            check(threads.length == 3, "threads created without MI creation notifications appear after suspend");
            check(java.nio.file.Files.readString(Path.of(System.getenv("ZEW_THREAD_EVENT_LOG"))).lines()
                    .filter(line -> line.startsWith("=thread-created,")).count() >= 3,
                    "test relay actually withheld initial and worker creation notifications");
            boolean readyFrame = false;
            for (var thread : threads) {
                IProcesses.IThreadDMData data = ask(session, result -> tracker.getService(IProcesses.class).getExecutionData(
                        DMContexts.getAncestorOfType(thread, IProcesses.IThreadDMContext.class), result));
                check(!"0".equals(data.getId()) && data.getId() != null && !data.getId().isBlank(), "real thread identity: " + data.getId());
                IStack.IFrameDMContext[] frames = ask(session, result -> tracker.getService(IStack.class).getFrames(thread, 0, 2, result));
                check(frames.length > 0, "stack frames available for " + data.getId());
                IStack.IFrameDMData frame = ask(session, result -> tracker.getService(IStack.class).getFrameData(frames[0], result));
                check(frame.getFunction() != null && !frame.getFunction().isBlank(), "nonblank frame label: " + frame.getFunction());
                readyFrame |= frame.getFunction().contains("zew_second_stop");
            }
            check(readyFrame, "current source frame resolves to the worker-ready breakpoint");
            RemoteDebugSmoke.<Object>ask(session, result -> tracker.getService(IRunControl.class).resume(container,
                    new RequestMonitor(session.getExecutor(), result) { @Override protected void handleSuccess() { result.done(); } }));
            awaitState(session, tracker, container, false);
            RemoteDebugSmoke.<Object>ask(session, result -> tracker.getService(IRunControl.class).suspend(container,
                    new RequestMonitor(session.getExecutor(), result) { @Override protected void handleSuccess() { result.done(); } }));
            awaitState(session, tracker, container, true);
            IExecutionDMContext[] paused = ask(session, result -> tracker.getService(IRunControl.class).getExecutionContexts(container, result));
            check(paused.length == 3, "thread model survives a second resume and pause");
        } finally {
            if (services != null) services.dispose();
            if (launch != null && launch.canTerminate()) launch.terminate();
            configuration.delete();
        }
    }
}
