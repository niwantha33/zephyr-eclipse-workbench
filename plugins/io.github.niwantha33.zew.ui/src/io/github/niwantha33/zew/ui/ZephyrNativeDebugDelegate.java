// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
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
 * Initially supports OpenOCD's verified local GDB TCP endpoint only. Other
 * runners remain explicitly unavailable until their transport and lifecycle
 * contracts are integrated. Never auto-flashes: West debugserver + CDT remote
 * GDB connect with symbols from this exact independent build.
 */
public final class ZephyrNativeDebugDelegate extends GdbLaunchDelegate {
    public static final String PORT = Activator.ID + ".launch.gdbPort";
    private static final Pattern DEFAULT_DEBUG_RUNNER =
            Pattern.compile("(?m)^debug-runner:\\s*([A-Za-z0-9_+.-]+)\\s*$");
    private static final Pattern CONFIG_GDB =
            Pattern.compile("(?m)^\\s+gdb:\\s*(.*?)\\s*$");
    private static final Pattern CUSTOM_PORT =
            Pattern.compile("--gdb-port(?:=|\\s+)([0-9]{2,5})");

    private record Parameters(io.github.niwantha33.zew.core.TargetOperations.Plan plan,
                              String gdb, Path elf, int port, java.util.Properties settings) {}

    private Parameters prepare(ILaunchConfiguration configuration) throws CoreException {
        try {
            String projectName = configuration.getAttribute(ZephyrLaunchDelegate.PROJECT, "");
            var project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (!project.isAccessible()) throw new IOException("Select an open imported Zephyr project.");
            var settings = ProjectContext.settings(project);
            var plan = io.github.niwantha33.zew.core.TargetOperations.prepare(
                    ProjectContext.buildDirectory(project, settings), settings.getProperty("board"),
                    "debugserver", "");
            String yaml = Files.readString(plan.buildDirectory().resolve("zephyr/runners.yaml"), StandardCharsets.UTF_8);
            var runner = DEFAULT_DEBUG_RUNNER.matcher(yaml);
            if (!runner.find())
                throw new IOException("This board has no declared default debug runner in generated runners.yaml.");
            if (!runner.group(1).equals("openocd"))
                throw new IOException("Native DSF-GDB currently supports only OpenOCD-backed targets. "
                        + "This board declares " + runner.group(1)
                        + ". External Debug Server remains available; other backends need verified integration.");
            // West supports runtime tool overrides. Old build metadata can name a
            // removed installation; bind the validated shared tool without changing
            // runners.yaml, recompiling firmware, or issuing a flash operation.
            var selectedOpenOcd = SharedDebugTools.select(settings.getProperty("board"), settings)
                    .orElseThrow(() -> io.github.niwantha33.zew.core.OpenOcdRegistry
                            .missing(settings.getProperty("board")));
            plan = io.github.niwantha33.zew.core.TargetOperations.withOpenOcd(plan, selectedOpenOcd);
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
            var configuredPort = CUSTOM_PORT.matcher(yaml);
            if (configuredPort.find() && Integer.parseInt(configuredPort.group(1)) != port)
                throw new IOException("Zephyr runner sets GDB port " + configuredPort.group(1)
                        + "; set the Debug Configurations port to match before launching.");
            return new Parameters(plan, gdb, elf, port, settings);
        } catch (Exception error) {
            if (error instanceof CoreException core) throw core;
            throw failure("Debug target validation failed: " + error.getMessage(), error);
        }
    }

    @Override public ILaunch getLaunch(ILaunchConfiguration configuration, String mode) throws CoreException {
        if (!ILaunchManager.DEBUG_MODE.equals(mode))
            throw failure("Use Debug mode for source-level debugging.", null);
        var params = prepare(configuration);
        // Populate CDT's own launch attributes before its DSF-GDB launch
        // factory runs. Preserve user-created launch configurations across updates.
        var working = configuration.getWorkingCopy();
        working.setAttribute(ICDTLaunchConfigurationConstants.ATTR_PROJECT_NAME,
                configuration.getAttribute(ZephyrLaunchDelegate.PROJECT, ""));
        working.setAttribute(ICDTLaunchConfigurationConstants.ATTR_PROGRAM_NAME, params.elf().toString());
        working.setAttribute(ICDTLaunchConfigurationConstants.ATTR_BUILD_BEFORE_LAUNCH,
                ICDTLaunchConfigurationConstants.BUILD_BEFORE_LAUNCH_DISABLED);
        working.setAttribute(ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_START_MODE,
                IGDBLaunchConfigurationConstants.DEBUGGER_MODE_REMOTE);
        working.setAttribute(IGDBLaunchConfigurationConstants.ATTR_REMOTE_TCP, true);
        working.setAttribute(IGDBLaunchConfigurationConstants.ATTR_HOST, "127.0.0.1");
        working.setAttribute(IGDBLaunchConfigurationConstants.ATTR_PORT, Integer.toString(params.port()));
        working.setAttribute(IGDBLaunchConfigurationConstants.ATTR_DEBUG_NAME, params.gdb());
        working.setAttribute(IGDBLaunchConfigurationConstants.ATTR_DEBUGGER_NON_STOP, false);
        working.doSave();
        return super.getLaunch(configuration, mode);
    }

    @Override public void launch(ILaunchConfiguration configuration, String mode, ILaunch launch,
                                 IProgressMonitor monitor) throws CoreException {
        if (!ILaunchManager.DEBUG_MODE.equals(mode)) throw failure("Not a Debug launch.", null);
        var params = prepare(configuration);
        TargetOperationGuard.Lease lease = null;
        ManagedServer session = null;
        try {
            if (monitor.isCanceled()) return;
            lease = TargetOperationGuard.acquire("debugserver");
            if (isListening(params.port()))
                throw new IOException("GDB TCP port " + params.port() + " is already in use. "
                        + "Close the other GDB server; never attach to an unknown server.");
            var builder = new ProcessBuilder(params.plan().command(params.settings().getProperty("python")));
            builder.directory(Path.of(params.settings().getProperty("workspace")).toFile());
            builder.environment().putAll(ProjectContext.environment(params.settings()));
            builder.redirectErrorStream(true);
            var console = new MessageConsole("Zephyr DSF-GDB — "
                    + configuration.getAttribute(ZephyrLaunchDelegate.PROJECT, ""), null);
            ConsolePlugin.getDefault().getConsoleManager().addConsoles(new IConsole[] {console});
            ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
            Process server = builder.start();
            session = new ManagedServer(server, console.newMessageStream(), lease, launch);
            lease = null; // ManagedServer now owns this physical operation.
            var owned = session;
            server.onExit().thenRun(() -> { owned.awaitLogDrain(1500); owned.close(); });
            session.startLogPump();
            long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
            boolean ready = false;
            while (System.nanoTime() < deadline && server.isAlive()) {
                if (monitor.isCanceled()) throw new java.io.InterruptedIOException("Debug session cancelled.");
                if (isListening(params.port())) { ready = true; break; }
                Thread.sleep(100);
            }
            if (!ready || !server.isAlive()) {
                session.awaitLogDrain(1500);
                Integer exitCode = server.isAlive() ? null : server.exitValue();
                throw new IOException(session.diagnose(params.plan().board(), params.port(), exitCode));
            }
            session.register();
            // Superclass initializes a true GdbLaunch with Eclipse breakpoints,
            // registers, variables, call stacks, memory and source stepping.
            super.launch(configuration, mode, launch, monitor);
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

    private static boolean isListening(int port) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 250);
            return true;
        } catch (IOException ignored) { return false; }
    }

    private static CoreException failure(String message, Throwable cause) {
        return new CoreException(new Status(IStatus.ERROR, Activator.ID, message, cause));
    }

    private static final class ManagedServer implements ILaunchesListener2, AutoCloseable {
        private final Process process;
        private final MessageConsoleStream stream;
        private final TargetOperationGuard.Lease lease;
        private final ILaunch launch;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final DebugServerDiagnostics diagnostics = new DebugServerDiagnostics();
        private volatile Thread logThread;

        String diagnose(String board, int port, Integer exitCode) {
            return diagnostics.describe(board, port, exitCode);
        }

        void awaitLogDrain(long millis) {
            Thread t = logThread;
            if (t == null || t == Thread.currentThread()) return;
            try { t.join(millis); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }

        ManagedServer(Process process, MessageConsoleStream stream, TargetOperationGuard.Lease lease, ILaunch launch) {
            this.process = process; this.stream = stream; this.lease = lease; this.launch = launch;
        }
        void startLogPump() {
            var reader = new Thread(() -> {
                try (var input = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = input.readLine()) != null) {
                        diagnostics.accept(line);
                        if (!closed.get()) stream.println(line);
                    }
                } catch (IOException ignored) { /* exit/terminate closes the stream */ }
            }, "zew-owned-openocd-output");
            reader.setDaemon(true);
            logThread = reader;
            reader.start();
        }
        void register() {
            DebugPlugin.getDefault().getLaunchManager().addLaunchListener(this);
        }
        @Override public void launchesAdded(ILaunch[] added) {}
        @Override public void launchesChanged(ILaunch[] changed) {}
        @Override public void launchesRemoved(ILaunch[] removed) { check(removed); }
        @Override public void launchesTerminated(ILaunch[] terminated) { check(terminated); }
        private void check(ILaunch[] launches) {
            for (ILaunch item : launches) if (item == launch) { close(); return; }
        }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            DebugPlugin.getDefault().getLaunchManager().removeLaunchListener(this);
            process.destroy();
            try { if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) process.destroyForcibly(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); process.destroyForcibly();
            }
            try { stream.close(); } catch (IOException ignored) {}
            lease.close();
        }
    }
}
