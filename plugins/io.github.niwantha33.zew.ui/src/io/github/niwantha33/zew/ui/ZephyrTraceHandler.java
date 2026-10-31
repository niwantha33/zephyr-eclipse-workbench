// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.swt.program.Program;
import io.github.niwantha33.zew.core.BuildArtifacts;
import io.github.niwantha33.zew.core.DebugBackend;
import io.github.niwantha33.zew.core.DebugPorts;
import io.github.niwantha33.zew.core.DebugServerDiagnostics;
import io.github.niwantha33.zew.core.ProcessRunner;
import io.github.niwantha33.zew.core.TargetOperations;

/** Experimental RAM snapshot for an already flashed, matching CTF build. */
public final class ZephyrTraceHandler extends AbstractHandler {
    private static final Pattern GDB = Pattern.compile("(?m)^\\s+gdb:\\s*(.*?)\\s*$");

    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        IProject project = SelectedProject.from(event);
        Shell shell = HandlerUtil.getActiveShell(event);
        if (project == null || !project.isAccessible() || shell == null) {
            MessageDialog.openInformation(shell, "RTOS Trace", "Select an open Zephyr project first.");
            return null;
        }
        final String expectedBoard;
        final String expectedProfile;
        try {
            var settings = ProjectContext.settings(project);
            expectedBoard = settings.getProperty("board");
            expectedProfile = settings.getProperty("zew.buildConfiguration");
        } catch (Exception error) {
            MessageDialog.openError(shell, "RTOS Trace", "Project setup is incomplete: " + error.getMessage());
            return null;
        }
        if (!MessageDialog.openConfirm(shell, "Capture RTOS trace",
                "Project: " + project.getName() + "\nBoard: " + expectedBoard
                + "\nConfiguration: " + expectedProfile + "\n\n"
                + "First build and flash this exact configuration. The capture resets the target, "
                + "runs it briefly and leaves it halted. Firmware identity cannot yet be verified.\n\n"
                + "This preview uses the board's OpenOCD runner and needs CTF RAM tracing enabled.")) return null;
        Job job = new Job("Capture RTOS trace — " + project.getName()) {
            @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                var console = new MessageConsole("RTOS trace — " + project.getName(), null);
                ConsolePlugin.getDefault().getConsoleManager().addConsoles(new IConsole[] {console});
                ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
                Process server = null;
                Path staging = null;
                Thread pump = null;
                TargetOperationGuard.Lease lease = null;
                var ownedChildren = java.util.concurrent.ConcurrentHashMap.<ProcessHandle>newKeySet();
                var stream = console.newMessageStream();
                try {
                    lease = TargetOperationGuard.acquire("debugserver");
                    monitor.beginTask("Capture Zephyr RTOS snapshot", IProgressMonitor.UNKNOWN);
                    var settings = ProjectContext.settings(project);
                    if (!expectedBoard.equals(settings.getProperty("board"))
                            || !expectedProfile.equals(settings.getProperty("zew.buildConfiguration")))
                        throw new IOException("Board or configuration changed; choose Capture again.");
                    Path build = ProjectContext.buildDirectory(project, settings);
                    var config = BuildArtifacts.configuration(build);
                    for (String key : List.of("CONFIG_TRACING", "CONFIG_TRACING_CTF", "CONFIG_TRACING_BACKEND_RAM")) {
                        if (!"y".equals(config.get(key))) throw new IOException("This build does not enable " + key
                                + ". Enable CTF RAM tracing in prj.conf, build and flash this configuration first.");
                    }
                    var plan = TargetOperations.prepare(build, settings.getProperty("board"), "debugserver", "");
                    String yaml = Files.readString(build.resolve("zephyr/runners.yaml"), StandardCharsets.UTF_8);
                    String runner = DebugBackend.select(yaml, "");
                    if (!"openocd".equals(runner)) throw new IOException("This preview supports an OpenOCD debug server only. "
                            + "The selected board uses " + runner + "; use a supported runner or wait for its capture adapter.");
                    plan = SharedDebugTools.bindDebugTool(TargetOperations.prepare(build, plan.board(), "debugserver", runner), settings);
                    var matcher = GDB.matcher(yaml);
                    if (!matcher.find()) throw new IOException("No GDB is recorded in the selected build. Rebuild the target.");
                    String gdb = matcher.group(1).trim();
                    if (gdb.length() >= 2 && ((gdb.startsWith("\"") && gdb.endsWith("\""))
                            || (gdb.startsWith("'") && gdb.endsWith("'")))) gdb = gdb.substring(1, gdb.length() - 1);
                    if (!Files.isRegularFile(Path.of(gdb))) throw new IOException("The build's GDB is missing: " + gdb + ". Repair the Zephyr SDK.");
                    var ports = DebugPorts.select(3335);
                    plan = ports.apply(plan, runner);
                    var builder = new ProcessBuilder(plan.command(settings.getProperty("python")));
                    builder.directory(Path.of(settings.getProperty("workspace")).toFile());
                    builder.environment().putAll(ProjectContext.environment(settings));
                    builder.redirectErrorStream(true);
                    server = builder.start();
                    Process owned = server;
                    var diagnostics = new DebugServerDiagnostics(ports.gdb(), runner);
                    pump = new Thread(() -> {
                        try (var input = new BufferedReader(new InputStreamReader(owned.getInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = input.readLine()) != null) {
                                ownedChildren.addAll(owned.descendants().toList());
                                diagnostics.accept(line); stream.println(line);
                            }
                        } catch (IOException ignored) { /* process termination closes pipe */ }
                    }, "zew-trace-server-output");
                    pump.setDaemon(true); pump.start();
                    long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
                    while (owned.isAlive() && !diagnostics.isListening(ports.gdb()) && System.nanoTime() < deadline) {
                        if (monitor.isCanceled()) throw new java.io.InterruptedIOException("Capture cancelled.");
                        Thread.sleep(100);
                    }
                    if (!owned.isAlive() || !diagnostics.isListening(ports.gdb()))
                        throw new IOException(diagnostics.describe(plan.board(), ports.gdb(), owned.isAlive() ? null : owned.exitValue()));
                    staging = Files.createTempDirectory("zew-trace-tool-");
                    for (String name : List.of("capture_snapshot.py", "analyze_trace.py", "cpu_analysis.py",
                            "investigation.py", "Zephyr_RTOS_Timeline.xml")) {
                        var resource = Activator.getDefault().getBundle().getEntry("tools/rtos_analysis/" + name);
                        if (resource == null) throw new IOException("Trace component missing: " + name + ". Reinstall Workbench.");
                        try (var input = resource.openStream()) { Files.copy(input, staging.resolve(name)); }
                    }
                    Path out = project.getLocation().toFile().toPath().resolve(".zew-traces");
                    var command = List.of(settings.getProperty("python"), staging.resolve("capture_snapshot.py").toString(),
                            "--gdb", gdb, "--build", build.toString(),
                            "--zephyr", Path.of(settings.getProperty("workspace"), "zephyr").toString(),
                            "--output", out.toString(), "--port", Integer.toString(ports.gdb()));
                    new ProcessRunner().run(command, Path.of(settings.getProperty("workspace")), Duration.ofSeconds(45),
                            monitor::isCanceled, stream::println, ProjectContext.environment(settings)).requireSuccess();
                    stream.println("Snapshot includes capture.json and diagnostics.json with "
                            + "offline provenance checks, CPU/ISR accounting and guided corrective actions.");
                    stream.println("Import the ctf folder in Trace Compass; the supplied XML timeline may need to be imported once.");
                    if (!shell.isDisposed()) shell.getDisplay().asyncExec(() -> {
                        if (!shell.isDisposed()) {
                            MessageDialog.openInformation(shell, "RTOS trace captured",
                                    "Snapshot saved in:\n" + out + "\n\nImport the ctf folder inside the newest capture in Trace Compass. "
                                    + "The supplied XML analysis must be imported once. Open diagnostics.json for guided checks "
                                    + "and capture.json for CPU/ISR and buffer evidence. Firmware identity is not verified.");
                            Program.launch(out.toString());
                        }
                    });
                    return Status.OK_STATUS;
                } catch (java.io.InterruptedIOException error) {
                    return Status.CANCEL_STATUS;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt(); return Status.CANCEL_STATUS;
                } catch (Exception error) {
                    if (!shell.isDisposed()) shell.getDisplay().asyncExec(() -> {
                        if (!shell.isDisposed()) MessageDialog.openError(shell, "RTOS trace", error.getMessage());
                    });
                    return new Status(IStatus.ERROR, Activator.ID, "Trace capture failed: " + error.getMessage(), error);
                } finally {
                    if (server != null) {
                        ownedChildren.addAll(server.descendants().toList());
                        ownedChildren.forEach(child -> { if (child.isAlive()) child.destroy(); });
                        server.destroy();
                        try { if (!server.waitFor(2, TimeUnit.SECONDS)) server.destroyForcibly(); }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); server.destroyForcibly(); }
                        ownedChildren.forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
                    }
                    if (pump != null) {
                        try { pump.join(2000); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                    }
                    try { stream.close(); } catch (IOException ignored) {}
                    if (lease != null) lease.close();
                    if (staging != null) {
                        try (var files = Files.walk(staging)) {
                            for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
                        } catch (IOException ignored) { /* temporary files may be locked on Windows */ }
                    }
                    monitor.done();
                }
            }
        };
        job.setUser(true); job.schedule();
        return null;
    }
}
