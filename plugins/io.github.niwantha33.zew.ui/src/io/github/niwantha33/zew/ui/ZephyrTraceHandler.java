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
    // One authoritative list used to build the isolated temporary capture tool.
    // These Python modules are transitive imports of capture_snapshot.py.
    static final List<String> CAPTURE_FILES = List.of(
            "capture_snapshot.py", "analyze_trace.py", "cpu_analysis.py",
            "timing_analysis.py", "semantic_trace.py", "thread_event_log.py",
            "investigation.py", "trace_compass_bridge.py", "Zephyr_RTOS_Timeline.xml");

    /**
     * Validate the exact bundled Python tool closure *before* starting OpenOCD.
     * Running --help imports every eager Python dependency but never connects
     * to GDB or touches the target. It is safe to abort on any missing module.
     */
    private static Path stageCaptureTool(String python, java.util.Properties settings,
            IProgressMonitor monitor, java.util.function.Consumer<String> report) throws Exception {
        Path stage = Files.createTempDirectory("zew-trace-tool-");
        try {
            for (String name : CAPTURE_FILES) {
                var resource = Activator.getDefault().getBundle()
                        .getEntry("tools/rtos_analysis/" + name);
                if (resource == null) throw new IOException(
                        "Installed Workbench is missing its bundled RTOS trace helper: " + name
                                + ". Update or reinstall Zephyr Workbench before capture.");
                Path target = stage.resolve(name);
                try (var input = resource.openStream()) {
                    Files.copy(input, target);
                }
                if (!Files.isRegularFile(target) || Files.size(target) == 0)
                    throw new IOException("Bundled trace helper was empty or unavailable: " + name);
            }
            StringBuilder diagnostics = new StringBuilder();
            try {
                new ProcessRunner().run(
                        List.of(python, stage.resolve("capture_snapshot.py").toString(), "--help"),
                        stage, Duration.ofSeconds(12), monitor::isCanceled,
                        line -> {
                            if (diagnostics.length() < 2048)
                                diagnostics.append(line, 0, Math.min(line.length(),
                                        2048 - diagnostics.length())).append('\n');
                        }, ProjectContext.environment(settings)).requireSuccess();
            } catch (Exception error) {
                String details = diagnostics.toString().strip();
                if (!details.isBlank()) report.accept("CTF helper import diagnostic: " + details);
                throw new IOException("CTF Python helpers failed their import check before "
                        + "starting OpenOCD. Check the installed Workbench package and its "
                        + "managed Python interpreter. " + error.getMessage(), error);
            }
            report.accept("Verified bundled CTF capture Python imports before connecting to OpenOCD. "
                    + "Workbench bundle version: " + Activator.getDefault().getBundle().getVersion());
            return stage;
        } catch (Exception error) {
            try (var files = Files.walk(stage)) {
                for (var file : files.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(file);
            } catch (IOException ignored) { /* do not conceal the underlying failure */ }
            throw error;
        }
    }

    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        capture(SelectedProject.from(event), HandlerUtil.getActiveShell(event));
        return null;
    }

    static void capture(IProject project, Shell shell) {
        if (project == null || !project.isAccessible() || shell == null) {
            MessageDialog.openInformation(shell, "RTOS Trace", "Select an open Zephyr project first.");
            return;
        }
        final String expectedBoard;
        final String expectedProfile;
        try {
            var settings = ProjectContext.settings(project);
            expectedBoard = settings.getProperty("board");
            expectedProfile = settings.getProperty("zew.buildConfiguration");
        } catch (Exception error) {
            MessageDialog.openError(shell, "RTOS Trace", "Project setup is incomplete: " + error.getMessage());
            return;
        }
        if (!MessageDialog.openConfirm(shell, "Capture RTOS trace",
                "Project: " + project.getName() + "\nBoard: " + expectedBoard
                + "\nConfiguration: " + expectedProfile + "\n\n"
                + "First build and flash this exact configuration. The capture resets the target, "
                + "runs it briefly and leaves it halted. Firmware identity cannot yet be verified.\n\n"
                + "This preview uses the board's OpenOCD runner and needs CTF RAM tracing enabled.\n"                + "If standalone Trace Compass is installed, Workbench requests opening the saved trace.")) return;
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
                java.util.function.Consumer<String> report = line -> {
                    stream.println(line);
                    ZephyrTraceConsoleView.append(project, "RAM CTF", line);
                };
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
                    // Package imports must be verifiable before we take ownership
                    // of an XDS110 or start an OpenOCD process.
                    staging = stageCaptureTool(settings.getProperty("python"), settings, monitor, report);
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
                                diagnostics.accept(line); report.accept(line);
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
                    Path out = project.getLocation().toFile().toPath().resolve(".zew-traces");
                    Path resultFile = staging.resolve("capture-location.txt");
                    var command = List.of(settings.getProperty("python"), staging.resolve("capture_snapshot.py").toString(),
                            "--gdb", gdb, "--build", build.toString(),
                            "--zephyr", Path.of(settings.getProperty("workspace"), "zephyr").toString(),
                            "--output", out.toString(), "--port", Integer.toString(ports.gdb()),
                            "--result-file", resultFile.toString());
                    new ProcessRunner().run(command, Path.of(settings.getProperty("workspace")), Duration.ofSeconds(45),
                            monitor::isCanceled, report, ProjectContext.environment(settings)).requireSuccess();
                    if (!Files.isRegularFile(resultFile))
                        throw new IOException("Capture did not report its saved directory.");
                    Path captureDir = Path.of(Files.readString(resultFile).trim()).toAbsolutePath().normalize();
                    if (!captureDir.startsWith(out.toAbsolutePath().normalize())
                            || !Files.isRegularFile(captureDir.resolve("report.html")))
                        throw new IOException("Capture report missing or outside selected project output.");
                    stream.println("Snapshot includes capture.json and diagnostics.json with "
                            + "offline provenance checks, CPU/ISR accounting and guided corrective actions.");
                    stream.println("CTF metadata identity: see capture.json metadata_provenance and trace_build_options.");
                    // --cli --open is supported by the Trace Compass standalone RCP.
                    // No viewer/failed viewer must never discard a saved capture.
                    try {
                        var viewer = List.of(settings.getProperty("python"),
                                staging.resolve("trace_compass_bridge.py").toString(),
                                "--capture", captureDir.toString(), "--open");
                        new ProcessRunner().run(viewer, Path.of(settings.getProperty("workspace")),
                                Duration.ofSeconds(10), monitor::isCanceled, report,
                                ProjectContext.environment(settings));
                    } catch (Exception viewerError) {
                        stream.println("Standalone Trace Compass handoff unavailable: " + viewerError.getMessage());
                    }
                    stream.println("If not opened automatically: import ctf/ as Generic CTF. "
                            + "Import Zephyr_RTOS_Timeline.xml once using Tracing > XML Analyses.");
                    if (!shell.isDisposed()) shell.getDisplay().asyncExec(() -> {
                        if (!shell.isDisposed()) {
                            // Explicit capture succeeded and the saved CTF files are
                            // validated. Hand off to already open offline views,
                            // never attach a second probe or run on view startup.
                            TraceCaptureWorkflow.captured(project, captureDir);
                            MessageDialog.openInformation(shell, "RTOS trace captured",
                                    "Snapshot saved in:\n" + captureDir + "\n\nIf standalone Trace Compass is installed, an open request was attempted. " + "Otherwise import the ctf folder manually. "
                                    + "The supplied XML analysis must be imported once. Open report.html for CPU/ISR "
                                    + "composition and guided findings, or diagnostics.json for machine-readable evidence. "
                                    + "Firmware identity is not verified.");
                            Program.launch(captureDir.resolve("report.html").toString());
                        }
                    });
                    return Status.OK_STATUS;
                } catch (java.io.InterruptedIOException error) {
                    return Status.CANCEL_STATUS;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt(); return Status.CANCEL_STATUS;
                } catch (Exception error) {
                    // The Eclipse Job dialog already displays ERROR status.
                    // Avoid a second modal error popup for the same failure.
                    report.accept("Trace capture failed: " + error.getMessage());
                    return new Status(IStatus.ERROR, Activator.ID, "Trace capture failed: "
                            + error.getMessage(), error);
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
        return;
    }
}
