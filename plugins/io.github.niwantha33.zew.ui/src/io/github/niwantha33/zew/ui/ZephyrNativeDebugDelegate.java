// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import io.github.niwantha33.zew.core.DebugPorts;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.niwantha33.zew.core.DebugServerDiagnostics;
import java.util.regex.Pattern;
import org.eclipse.cdt.debug.core.ICDTLaunchConfigurationConstants;
import org.eclipse.cdt.dsf.gdb.IGDBLaunchConfigurationConstants;
import org.eclipse.cdt.dsf.gdb.launching.GdbLaunchDelegate;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.ILaunchesListener2;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;

/**
 * An actual CDT DSF-GDB launch, not a West shell named "Debug".
 *
 * Supports board-declared OpenOCD, J-Link and pyOCD local GDB servers. Never auto-flashes: West debugserver + CDT remote
 * GDB connect with symbols from this exact independent build.
 */
public final class ZephyrNativeDebugDelegate extends GdbLaunchDelegate {
    public static final String RUNNER = Activator.ID + ".launch.debugRunner";
    public static final String PORT = Activator.ID + ".launch.gdbPort";
    private static final String RESOLVED_RUNNER = RUNNER + ".resolved";
    private static final Pattern DEFAULT_DEBUG_RUNNER =
            Pattern.compile("(?m)^debug-runner:\\s*([A-Za-z0-9_+.-]+)\\s*$");
    private static final Pattern CONFIG_GDB =
            Pattern.compile("(?m)^\\s+gdb:\\s*(.*?)\\s*$");
    private static final String TCL_PORT = PORT + ".tcl";
    private static final String TELNET_PORT = PORT + ".telnet";

    private record Parameters(io.github.niwantha33.zew.core.TargetOperations.Plan plan,
                              String gdb, Path elf, int port, java.util.Properties settings,
                              String runner, List<io.github.niwantha33.zew.core.OpenOcdThreadCheck.Region> threadMetadata) {}

    private Parameters prepare(ILaunchConfiguration configuration) throws CoreException {
        try {
            if (!"debug".equals(configuration.getAttribute(ZephyrLaunchDelegate.ACTION, "debug")))
                throw new IOException("This saved configuration is not a Debug action. "
                        + "Choose Debug As > Zephyr Application for the selected project.");
            String projectName = configuration.getAttribute(ZephyrLaunchDelegate.PROJECT, "");
            var project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (!project.isAccessible()) throw new IOException("Select an open imported Zephyr project.");
            var settings = ProjectContext.settings(project);
            var plan = io.github.niwantha33.zew.core.TargetOperations.prepare(
                    ProjectContext.buildDirectory(project, settings), settings.getProperty("board"),
                    "debugserver", "");
            String yaml = Files.readString(plan.buildDirectory().resolve("zephyr/runners.yaml"), StandardCharsets.UTF_8);
            String runner = io.github.niwantha33.zew.core.DebugBackend.select(yaml,
                    configuration.getAttribute(RUNNER, ""));
            plan = io.github.niwantha33.zew.core.TargetOperations.prepare(
                    plan.buildDirectory(), plan.board(), "debugserver", runner);
            // West supports runtime tool overrides. Old build metadata can name a
            // removed installation; bind the validated shared tool without changing
            // runners.yaml, recompiling firmware, or issuing a flash operation.
            plan = SharedDebugTools.bindDebugTool(plan, settings);
            var gdbEntry = CONFIG_GDB.matcher(yaml);
            if (!gdbEntry.find() || gdbEntry.group(1).isBlank())
                throw new IOException("Zephyr runner configuration does not declare an architecture-correct GDB.");
            String gdb = gdbEntry.group(1).trim();
            if (gdb.length() >= 2 && (gdb.startsWith("\"") && gdb.endsWith("\"")
                    || gdb.startsWith("'") && gdb.endsWith("'")))
                gdb = gdb.substring(1, gdb.length() - 1);
            if (!Files.isRegularFile(Path.of(gdb)))
                throw new IOException("Configured GDB executable is missing: " + gdb
                        + ". Install the matching Zephyr SDK debugger and rebuild.");
            Path elf = io.github.niwantha33.zew.core.BuildArtifacts.required(plan.buildDirectory())
                    .stream().filter(p -> p.toString().endsWith(".elf")).findFirst()
                    .orElseThrow(() -> new IOException("Build has no ELF debug symbols."));
            if (!Files.isRegularFile(elf))
                throw new IOException("Matching ELF debug symbols are missing: " + elf);
            int port = configuration.getAttribute(PORT, 3333);
            if (port < 1024 || port > 65535) throw new IOException("GDB TCP port must be 1024..65535.");
            plan = new DebugPorts(port, configuration.getAttribute(TCL_PORT, 6333),
                    configuration.getAttribute(TELNET_PORT, 4444)).apply(plan, runner);
            var config = io.github.niwantha33.zew.core.BuildArtifacts.configuration(plan.buildDirectory());
            var metadata = runner.equals("openocd") && "y".equals(config.get("CONFIG_DEBUG_THREAD_INFO"))
                    ? OpenOcdThreadMetadata.read(elf) : List.<io.github.niwantha33.zew.core.OpenOcdThreadCheck.Region>of();
            return new Parameters(plan, gdb, elf, port, settings, runner, metadata);
        } catch (Exception error) {
            if (error instanceof CoreException core) throw core;
            throw failure("Debug target validation failed: " + error.getMessage(), error);
        }
    }

    @Override public ILaunch getLaunch(ILaunchConfiguration configuration, String mode) throws CoreException {
        if (!ILaunchManager.DEBUG_MODE.equals(mode))
            throw failure("Use Debug mode for source-level debugging.", null);
        // Select endpoints before constructing either the West plan or CDT launch.
        final DebugPorts ports;
        try { ports = DebugPorts.select(configuration.getAttribute(PORT, 3333)); }
        catch (IOException error) { throw failure("Unable to allocate debug server ports: " + error.getMessage(), error); }
        var working = configuration.getWorkingCopy();
        working.setAttribute(PORT, ports.gdb());
        working.setAttribute(TCL_PORT, ports.tcl());
        working.setAttribute(TELNET_PORT, ports.telnet());
        var params = prepare(working);
        working.setAttribute(RESOLVED_RUNNER, params.runner());
        working.setAttribute(ICDTLaunchConfigurationConstants.ATTR_PROJECT_NAME,
                configuration.getAttribute(ZephyrLaunchDelegate.PROJECT, ""));
        working.setAttribute(ICDTLaunchConfigurationConstants.ATTR_PROGRAM_NAME, params.elf().toString());
        working.setAttribute(ICDTLaunchConfigurationConstants.ATTR_BUILD_BEFORE_LAUNCH,
                ICDTLaunchConfigurationConstants.BUILD_BEFORE_LAUNCH_DISABLED);
        working.setAttribute(ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_START_MODE,
                IGDBLaunchConfigurationConstants.DEBUGGER_MODE_REMOTE);
        working.setAttribute(IGDBLaunchConfigurationConstants.ATTR_REMOTE_TCP, true);
        working.setAttribute(IGDBLaunchConfigurationConstants.ATTR_HOST, "127.0.0.1");
        working.setAttribute(IGDBLaunchConfigurationConstants.ATTR_PORT, Integer.toString(ports.gdb()));
        working.setAttribute(IGDBLaunchConfigurationConstants.ATTR_DEBUG_NAME, params.gdb());
        configureThreadTracking(working);
        ILaunch launch = super.getLaunch(working.doSave(), mode);
        try {
            configureSourceMappings(launch, io.github.niwantha33.zew.core.DebugSourcePaths.inspect(
                    params.plan().buildDirectory(), Path.of(params.settings().getProperty("application")),
                    Path.of(params.settings().getProperty("workspace"), "zephyr")));
        } catch (IOException error) {
            throw failure("Cannot verify debug source aliases: " + error.getMessage(), error);
        }
        return launch;
    }

    /** Configure CDT's own bidirectional source mapping before DSF adapters and breakpoint tracking start. */
    static void configureSourceMappings(ILaunch launch,
            List<io.github.niwantha33.zew.core.DebugSourcePaths.Mapping> mappings) throws CoreException {
        if (mappings.isEmpty()) return;
        if (!(launch.getSourceLocator() instanceof org.eclipse.cdt.dsf.gdb.launching.GdbSourceLookupDirector director))
            throw failure("Native GDB source lookup is unavailable for verified build aliases.", null);
        var previous = director.getSourceContainers();
        var entries = new java.util.ArrayList<org.eclipse.debug.core.sourcelookup.ISourceContainer>();
        var explicit = director.getSubstitutionsPaths();
        var type = DebugPlugin.getDefault().getLaunchManager().getSourceContainerType(
                "org.eclipse.cdt.debug.core.containerType.mapping");
        if (type == null) throw failure("CDT source-path mapping support is missing. Repair the CDT Debug installation.", null);
        StringBuilder memento = new StringBuilder("<mapping name=\"Workbench verified source aliases\" backend_enabled=\"true\">");
        int added = 0;
        for (var mapping : mappings) {
            // getCompilationPath() is NOT evidence that GDB has a substitute-path
            // rule. It may resolve from unrelated CDT lookup containers while
            // the actual backend map stays empty. Check the GDB map itself.
            String existing = explicit.get(mapping.compilationPath());
            if (existing != null) {
                if (!existing.replace('\\', '/').equals(mapping.localPath().replace('\\', '/')))
                    throw failure("Conflicting GDB source mapping for a verified compilation alias.", null);
                continue;
            }
            String entry = "<mapEntry backendPath=\"" + xmlAttribute(mapping.compilationPath())
                    + "\" localPath=\"" + xmlAttribute(mapping.localPath()) + "\"/>";
            memento.append("<mapEntry memento=\"").append(xmlAttribute(entry)).append("\"/>");
            added++;
        }
        if (added > 0) {
            memento.append("</mapping>");
            entries.add(type.createSourceContainer(memento.toString()));
            entries.addAll(java.util.Arrays.asList(previous));
            director.setSourceContainers(entries.toArray(org.eclipse.debug.core.sourcelookup.ISourceContainer[]::new));
        }
        // Check the observable GDB-substitution contract rather than assuming
        // that a source-container memento was accepted and backend-enabled.
        var applied = director.getSubstitutionsPaths();
        for (var mapping : mappings) {
            String actual = applied.get(mapping.compilationPath());
            if (actual == null || !actual.replace('\\', '/').equals(mapping.localPath().replace('\\', '/')))
                throw failure("CDT did not install the verified GDB source alias: " + mapping.compilationPath(), null);
        }
    }

    private static String xmlAttribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;").replace("'", "&apos;");
    }

    /** RTOS threads may appear after reset without target-side creation notifications. */
    static void configureThreadTracking(org.eclipse.debug.core.ILaunchConfigurationWorkingCopy working) {
        working.setAttribute(IGDBLaunchConfigurationConstants.ATTR_DEBUGGER_NON_STOP, false);
        working.setAttribute(IGDBLaunchConfigurationConstants.ATTR_DEBUGGER_UPDATE_THREADLIST_ON_SUSPEND, true);
    }

    @Override protected org.eclipse.cdt.dsf.debug.service.IDsfDebugServicesFactory newServiceFactory(
            ILaunchConfiguration configuration, String version) {
        try {
            return new ZephyrDebugServicesFactory(version, configuration,
                    "openocd".equals(configuration.getAttribute(RESOLVED_RUNNER, "")));
        } catch (CoreException error) { Activator.getDefault().getLog().log(error.getStatus()); }
        return super.newServiceFactory(configuration, version);
    }

    @Override public void launch(ILaunchConfiguration configuration, String mode, ILaunch launch,
                                 IProgressMonitor monitor) throws CoreException {
        if (!ILaunchManager.DEBUG_MODE.equals(mode)) throw failure("Not a Debug launch.", null);
        configuration = launch.getLaunchConfiguration();
        var params = prepare(configuration);
        TargetOperationGuard.Lease lease = null;
        ManagedServer session = null;
        try {
            if (monitor.isCanceled()) return;
            lease = TargetOperationGuard.acquire("debugserver");
            if (portOccupied(params.port()))
                throw new IOException("GDB TCP port " + params.port() + " is already in use. "
                        + "Another process claimed the allocated port during startup. Retry Debug to select free ports.");
            var builder = new ProcessBuilder(params.plan().command(params.settings().getProperty("python")));
            builder.directory(Path.of(params.settings().getProperty("workspace")).toFile());
            builder.environment().putAll(ProjectContext.environment(params.settings()));
            builder.redirectErrorStream(true);
            var console = new MessageConsole("Zephyr DSF-GDB — "
                    + configuration.getAttribute(ZephyrLaunchDelegate.PROJECT, ""), null);
            console.setWaterMarks(256 * 1024, 1024 * 1024);
            ConsolePlugin.getDefault().getConsoleManager().addConsoles(new IConsole[] {console});
            ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
            if (io.github.niwantha33.zew.core.OpenOcdRegistry.providerFor(params.plan().board())
                    == io.github.niwantha33.zew.core.OpenOcdRegistry.Provider.ESPRESSIF) {
                try (var info = console.newMessageStream()) {
                    info.println("Espressif Debug uses hardware breakpoints; available slots depend on the CPU."
                            + " Delete unused breakpoints to leave a slot for stepping."
                            + " This does not repair invalid flash mappings or flash firmware.");
                }
            }
            try (var info = console.newMessageStream()) {
                info.println("Debug endpoints: GDB=" + params.port()
                        + ", TCL=" + configuration.getAttribute(TCL_PORT, 6333)
                        + ", Telnet=" + configuration.getAttribute(TELNET_PORT, 4444));
                info.println("Symbols: " + params.elf() + " | target: " + params.plan().board()
                        + " | profile: " + params.settings().getProperty("zew.buildConfiguration", "default")
                        + ". Debug attaches without flashing; use Flash when the device firmware differs from this build.");
                info.println("CPU registers: select a suspended frame, then Window > Show View > Other > Debug > Registers. "
                        + "SoC peripheral registers: Window > Show View > Other > Debug > SoC Registers; "
                        + "load the exact chip vendor SVD, select a register and Refresh while halted.");
                info.println("Zephyr thread tracking: refresh the native CDT thread list after every suspend. "
                        + "Routine OpenOCD INFO register-polling is hidden after one explanation; warnings and errors are preserved.");
            }
            Process server = builder.start();
            session = new ManagedServer(server, console.newMessageStream(), lease, launch, params.port(),
                    params.plan().westArguments().get(params.plan().westArguments().indexOf("--runner") + 1));
            lease = null; // ManagedServer now owns this physical operation.
            var owned = session;
            server.onExit().thenRun(owned::serverExited);
            session.startLogPump();
            long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
            boolean ready = false;
            while (System.nanoTime() < deadline && server.isAlive()) {
                if (monitor.isCanceled()) throw new java.io.InterruptedIOException("Debug session cancelled.");
                if (session.diagnostics.isListening(params.port())) { ready = true; break; }
                Thread.sleep(100);
            }
            if (!ready || !server.isAlive()) {
                session.awaitLogDrain(1500);
                Integer exitCode = server.isAlive() ? null : server.exitValue();
                throw new IOException(session.diagnose(params.plan().board(), params.port(), exitCode));
            }
            session.register();
            if (!params.threadMetadata().isEmpty()) {
                int tclPort = configuration.getAttribute(TCL_PORT, 6333);
                long tclDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (!session.diagnostics.isTclListening(tclPort) && server.isAlive() && System.nanoTime() < tclDeadline) {
                    if (monitor.isCanceled()) throw new java.io.InterruptedIOException("Debug session cancelled.");
                    Thread.sleep(50);
                }
                if (!session.diagnostics.isTclListening(tclPort))
                    throw new IOException("Owned OpenOCD Tcl endpoint was not ready for read-only Zephyr metadata verification.");
                try (var info = console.newMessageStream()) {
                    info.println("Preflight: comparing static Zephyr thread metadata in target memory with " + params.elf());
                    try {
                        io.github.niwantha33.zew.core.OpenOcdThreadCheck.verify(tclPort, params.threadMetadata());
                    } catch (IOException error) {
                        info.println("Thread metadata verification failed: " + error.getMessage());
                        throw error;
                    }
                    info.println("Verified target thread metadata against the selected ELF; attaching GDB using extended-remote.");
                }
            }
            // Superclass initializes a true GdbLaunch with Eclipse breakpoints,
            // registers, variables, call stacks, memory and source stepping.
            super.launch(configuration, mode, launch, monitor);
            if (!monitor.isCanceled() && !launch.isTerminated()) {
                // CDT can reconstruct/reinitialise source containers while DSF
                // starts. Validate the active locator, not only the pre-launch
                // locator from getLaunch(). CDT's source-change notifications
                // will then propagate this backend mapping to GDB.
                configureSourceMappings(launch, io.github.niwantha33.zew.core.DebugSourcePaths.inspect(
                        params.plan().buildDirectory(), Path.of(params.settings().getProperty("application")),
                        Path.of(params.settings().getProperty("workspace"), "zephyr")));
                try (var info = console.newMessageStream()) {
                    String name = configuration.getAttribute(ZephyrLaunchDelegate.PROJECT, "");
                    info.println(DebugBreakpointStatus.describe(ResourcesPlugin.getWorkspace().getRoot().getProject(name)));
                    if (launch.getSourceLocator() instanceof org.eclipse.cdt.dsf.gdb.launching.GdbSourceLookupDirector source
                            && !source.getSubstitutionsPaths().isEmpty())
                        info.println("Verified debug source substitutions: " + source.getSubstitutionsPaths());
                }
                SocRegistersHandler.showDebugDataOnLaunch();
            }
            if (monitor.isCanceled() || launch.isTerminated()) session.close();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (session != null) session.close();
            throw failure("Debug server start interrupted.", interrupted);
        } catch (Exception error) {
            if (session != null) session.close();
            throw error instanceof CoreException core ? core
                    : failure("Native Debug launch failed: " + error.getMessage(), error);
        } finally {
            if (lease != null) lease.close();
        }
    }

    private static boolean portOccupied(int port) {
        try (var socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(port));
            return false;
        } catch (IOException busy) { return true; }
    }

    private static CoreException failure(String message, Throwable cause) {
        return new CoreException(new Status(IStatus.ERROR, Activator.ID, message, cause));
    }

    public static final class ManagedServer implements ILaunchesListener2, AutoCloseable {
        private final Process process;
        private final MessageConsoleStream stream;
        private final TargetOperationGuard.Lease lease;
        private final ILaunch launch;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean closeScheduled = new AtomicBoolean();
        private final DebugServerDiagnostics diagnostics;
        private volatile Thread logThread;
        private volatile Thread childTracker;
        private final java.util.Set<ProcessHandle> ownedChildren = java.util.concurrent.ConcurrentHashMap.newKeySet();

        String diagnose(String board, int port, Integer exitCode) {
            return diagnostics.describe(board, port, exitCode);
        }

        void awaitLogDrain(long millis) {
            Thread t = logThread;
            if (t == null || t == Thread.currentThread()) return;
            try { t.join(millis); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }

        ManagedServer(Process process, MessageConsoleStream stream, TargetOperationGuard.Lease lease, ILaunch launch, int port, String runner) {
            this.diagnostics = new DebugServerDiagnostics(port, runner);
            this.process = process; this.stream = stream; this.lease = lease; this.launch = launch;
        }
        void serverExited() {
            // A server failure must also end GDB; otherwise the launch appears live
            // and its resources can prevent a retry after the server has gone.
            var worker = new Thread(() -> {
                awaitLogDrain(1500);
                boolean unexpected = !closed.get();
                close();
                if (unexpected && launch.canTerminate()) {
                    try { launch.terminate(); }
                    catch (CoreException error) { Activator.getDefault().getLog().log(error.getStatus()); }
                }
            }, "zew-debugserver-exited");
            worker.setDaemon(true);
            worker.start();
        }
        void startLogPump() {
            // Do not depend on console traffic to discover children: a quiet
            // runner may exit while its GDB-server child continues owning a port.
            childTracker = new Thread(() -> {
                while (!closed.get() && process.isAlive()) {
                    ownedChildren.addAll(process.descendants().toList());
                    try { Thread.sleep(50); }
                    catch (InterruptedException stop) { return; }
                }
            }, "zew-owned-debugserver-children");
            childTracker.setDaemon(true);
            childTracker.start();
            var reader = new Thread(() -> {
                var output = new io.github.niwantha33.zew.core.DebugConsoleOutput();
                try (var input = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = input.readLine()) != null) {
                        ownedChildren.addAll(process.descendants().toList());
                        diagnostics.accept(line);
                        if (!closed.get()) for (String visible : output.accept(line, System.nanoTime())) stream.println(visible);
                        String advice = diagnostics.runtimeAdvice(line);
                        if (!closed.get() && !advice.isBlank()) stream.println("Workbench: " + advice);
                    }
                    if (!closed.get()) for (String visible : output.flush()) stream.println(visible);
                } catch (IOException ignored) { /* exit/terminate closes the stream */ }
            }, "zew-owned-debugserver-output");
            reader.setDaemon(true);
            logThread = reader;
            reader.start();
        }
        void register() {
            if (closed.get()) return;
            DebugPlugin.getDefault().getLaunchManager().addLaunchListener(this);
            if (launch instanceof org.eclipse.cdt.dsf.gdb.launching.GdbLaunch gdb) {
                try {
                    gdb.getSession().getExecutor().execute(() -> {
                        if (!closed.get()) gdb.getSession().addServiceEventListener(this, null);
                    });
                } catch (java.util.concurrent.RejectedExecutionException ignored) { closeAsync(); }
            }
            // Close can race listener registration after server startup fails.
            if (closed.get()) DebugPlugin.getDefault().getLaunchManager().removeLaunchListener(this);
        }
        @org.eclipse.cdt.dsf.service.DsfServiceEventHandler
        public void commandControlStopped(
                org.eclipse.cdt.dsf.debug.service.command.ICommandControlService.ICommandControlShutdownDMEvent event) {
            closeAsync();
        }
        private void closeAsync() {
            if (!closeScheduled.compareAndSet(false, true)) return;
            var cleanup = new Thread(this::close, "zew-owned-debugserver-stop");
            cleanup.setDaemon(true);
            cleanup.start();
        }
        @Override public void launchesAdded(ILaunch[] added) {}
        @Override public void launchesChanged(ILaunch[] changed) {}
        @Override public void launchesRemoved(ILaunch[] removed) { check(removed); }
        @Override public void launchesTerminated(ILaunch[] terminated) { check(terminated); }
        private void check(ILaunch[] launches) {
            for (ILaunch item : launches) if (item == launch) { closeAsync(); return; }
        }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            DebugPlugin.getDefault().getLaunchManager().removeLaunchListener(this);
            if (launch instanceof org.eclipse.cdt.dsf.gdb.launching.GdbLaunch gdb) {
                try {
                    gdb.getSession().getExecutor().execute(() -> gdb.getSession().removeServiceEventListener(this));
                } catch (java.util.concurrent.RejectedExecutionException ignored) { /* session already ended */ }
            }
            Thread tracker = childTracker;
            if (tracker != null) tracker.interrupt();
            ownedChildren.addAll(process.descendants().toList());
            for (var child : ownedChildren) if (child.isAlive()) child.destroy();
            process.destroy();
            try { if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) process.destroyForcibly(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); process.destroyForcibly();
            }
            for (var child : ownedChildren) if (child.isAlive()) child.destroyForcibly();
            // Releasing the operation lease before forcible exit finishes causes
            // immediate retries to race the previous server's USB/port teardown.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((process.isAlive() || ownedChildren.stream().anyMatch(ProcessHandle::isAlive))
                    && System.nanoTime() < deadline) {
                try { Thread.sleep(25); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
            try { process.getInputStream().close(); } catch (IOException ignored) {}
            try { process.getOutputStream().close(); } catch (IOException ignored) {}
            try { stream.close(); } catch (IOException ignored) {}
            lease.close();
        }
    }
}
