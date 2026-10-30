// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.LaunchConfigurationDelegate;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Properties;
import java.util.Map;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.IConsole;
import io.github.niwantha33.zew.core.TargetOperations;
import io.github.niwantha33.zew.core.EspressifFlashSafety;

/** Native Run Configurations. Eclipse owns console output and process termination. */
public final class ZephyrLaunchDelegate extends LaunchConfigurationDelegate {
    public static final String TYPE = Activator.ID + ".launchType";
    public static final String PROJECT = Activator.ID + ".launch.project";
    public static final String ACTION = Activator.ID + ".launch.action";
    public static final String DEBUG_AFTER_FLASH = Activator.ID + ".launch.debugAfterFlash";

    @Override public boolean buildForLaunch(ILaunchConfiguration configuration, String mode,
                                            IProgressMonitor monitor) throws CoreException {
        // Flash preflight explicitly builds the selected project exactly once below.
        // Suppress Eclipse's additional workspace-wide pre-launch build.
        if ("flash".equals(configuration.getAttribute(ACTION, "run"))) return false;
        return super.buildForLaunch(configuration, mode, monitor);
    }

    @Override public void launch(ILaunchConfiguration configuration, String mode, ILaunch launch,
                                  IProgressMonitor monitor) throws CoreException {
        if (!ILaunchManager.RUN_MODE.equals(mode)) {
            throw failure("Use Debug mode with Zephyr Application for native CDT source-level debugging.", null);
        }
        String name = configuration.getAttribute(PROJECT, "").trim();
        String action = configuration.getAttribute(ACTION, "run");
        if (!List.of("run", "flash", "debugserver").contains(action)) throw failure("Unsupported Zephyr runner action.", null);
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        if (!project.isAccessible()) throw failure("Select an open imported Zephyr project.", null);
        try {
            var settings = ProjectContext.settings(project);
            if (monitor.isCanceled()) return;
            var lease = TargetOperationGuard.acquire(action);
            Process process = null;
            boolean handedOff = false;
            io.github.niwantha33.zew.core.FlashDebugBuild debugBuild = null;
            try {
                List<String> command;
                if ("flash".equals(action)) {
                    boolean debugAfterFlash = configuration.getAttribute(DEBUG_AFTER_FLASH, false);
                    var prepared = prepareFlash(project, settings, monitor, debugAfterFlash);
                    if (prepared == null) return;
                    settings = prepared.settings();
                    command = prepared.command();
                    if (debugAfterFlash) debugBuild = io.github.niwantha33.zew.core.FlashDebugBuild.capture(
                            ProjectContext.buildDirectory(project, settings), settings.getProperty("board"),
                            settings.getProperty("zew.buildConfiguration", "default"));
                } else {
                    Path output = ProjectContext.buildDirectory(project, settings);
                    var plan = TargetOperations.prepare(
                            output, settings.getProperty("board"), action, "");
                    if ("debugserver".equals(action)) plan = SharedDebugTools.bindDebugTool(plan, settings);
                    command = plan.command(settings.getProperty("python"));
                }
                if (monitor.isCanceled()) return;
                var builder = new ProcessBuilder(command);
                builder.directory(Path.of(settings.getProperty("workspace")).toFile());
                builder.environment().putAll(ProjectContext.environment(settings));
                builder.redirectErrorStream(true);
                process = builder.start();
                if (DebugPlugin.newProcess(launch, process, "Zephyr " + action + " — " + project.getName()) == null)
                    throw new IOException("Eclipse could not register the runner process.");
                var cleanup = watchOwnedProcess(process, lease, "flash".equals(action));
                handedOff = true;
                if ("flash".equals(action)) {
                    awaitFlash(process, monitor);
                    // Release the programmer's owned children/lease before a new
                    // debugserver attempts to acquire the same physical operation.
                    try { cleanup.get(5, TimeUnit.SECONDS); }
                    catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException error) {
                        throw new IOException("Flash finished but programmer cleanup was not confirmed; Debug was not started.", error);
                    }
                    if (debugBuild != null && !monitor.isCanceled()) scheduleDebug(project, debugBuild, monitor);
                }
            } finally {
                if (!handedOff) {
                    if (process != null) {
                        process.descendants().forEach(ProcessHandle::destroyForcibly);
                        process.destroyForcibly();
                    }
                    lease.close();
                }
            }
        } catch (CoreException error) {
            throw error;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CoreException(Status.CANCEL_STATUS);
        } catch (InterruptedIOException cancelled) {
            throw new CoreException(Status.CANCEL_STATUS);
        } catch (Exception error) {
            throw failure(error.getMessage(), error);
        }
    }

    private record FlashPlan(List<String> command, Properties settings) {}

    /** Starting a programmer process is not a completed Flash operation. */
    static void awaitFlash(Process process, IProgressMonitor monitor) throws IOException, InterruptedException {
        try {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (monitor.isCanceled()) {
                    process.destroy(); // lifecycle watcher also reaps this operation's children
                    throw new InterruptedIOException("Flash cancelled");
                }
            }
        } catch (InterruptedException interrupted) {
            process.destroy();
            throw interrupted;
        }
        if (monitor.isCanceled()) throw new InterruptedIOException("Flash cancelled");
        if (process.exitValue() != 0)
            throw new IOException("Flash programming or readback verification failed (West exit " + process.exitValue()
                    + "). See the Zephyr Flash console. Firmware was not verified; Debug was not started.");
    }

    /** Every flash entry, including repeated native Run launches, uses the same preflight. */
    private FlashPlan prepareFlash(IProject project, Properties settings,
                                  IProgressMonitor monitor, boolean debugAfterFlash) throws Exception {
        String board = settings.getProperty("board");
        String buildConfiguration = settings.getProperty("zew.buildConfiguration");
        var shell = new AtomicReference<Shell>();
        var confirmed = new AtomicBoolean();
        PlatformUI.getWorkbench().getDisplay().syncExec(() -> {
            var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null) return;
            shell.set(window.getShell());
            confirmed.set(MessageDialog.openConfirm(shell.get(),
                    (debugAfterFlash ? "Flash and Debug — " : "Build and Flash — ") + project.getName(),
                    "Build and program the selected board:\n\n" + board
                            + "\nConfiguration: " + buildConfiguration
                            + "\n\nThis writes firmware using Zephyr's board runner."
                            + (debugAfterFlash ? " Native Debug starts only after programming and verification succeed." : "")
                            + " Continue?"));
        });
        if (!confirmed.get() || monitor.isCanceled()) return null;
        var console = new MessageConsole("Zephyr Flash preparation — " + project.getName(), null);
        ConsolePlugin.getDefault().getConsoleManager().addConsoles(
                new IConsole[] {console});
        ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
        try (var stream = console.newMessageStream()) {
            stream.println("Preparing Flash: " + project.getName() + " | " + board);
            project.build(IncrementalProjectBuilder.FULL_BUILD,
                    Activator.ID + ".zephyrBuilder", Map.of(), monitor);
            if (monitor.isCanceled()) return null;
            settings = ProjectContext.settings(project);
            if (!board.equals(settings.getProperty("board"))
                    || !buildConfiguration.equals(settings.getProperty("zew.buildConfiguration")))
                throw new IOException("Board or build configuration changed during preparation; Flash cancelled.");
            var plan = TargetOperations.prepare(
                    ProjectContext.buildDirectory(project, settings), board, "flash", "");
            io.github.niwantha33.zew.core.BuildConfiguration.fromId(buildConfiguration).verify(plan.buildDirectory());
            plan = OpenOcdFlashPreparation.prepare(plan, settings, monitor, stream::println);
            var command = new ArrayList<>(plan.command(settings.getProperty("python")));
            if (EspressifFlashSafety.isEsp32Runner(plan.buildDirectory())) {
                String port = EspressifSerialProbe.selectAndVerify(shell.get(), board, settings,
                        monitor::isCanceled, stream::println);
                command.addAll(List.of("--", "--esp-device", port));
            }
            stream.println("Verified firmware: " + plan.buildDirectory());
            return new FlashPlan(command, settings);
        } catch (EspressifFlashSafety.ChipMismatchException mismatch) {
            shell.get().getDisplay().asyncExec(() -> {
                if (!shell.get().isDisposed()) ZephyrChipMismatchRecovery.offer(shell.get(), project, mismatch);
            });
            return null;
        } catch (InterruptedIOException cancelled) {
            return null;
        }
    }

    private static void scheduleDebug(IProject project, io.github.niwantha33.zew.core.FlashDebugBuild flashed, IProgressMonitor monitor) throws Exception {
        // Hashing stays on the launch worker, never Eclipse's display thread.
        var verified = ProjectContext.settings(project);
        Path build = ProjectContext.buildDirectory(project, verified).toRealPath();
        String board = verified.getProperty("board");
        String profile = verified.getProperty("zew.buildConfiguration", "default");
        flashed.verify(build, board, profile);
        PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
            var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null || monitor.isCanceled()) return;
            try {
                var current = ProjectContext.settings(project);
                if (!board.equals(current.getProperty("board"))
                        || !profile.equals(current.getProperty("zew.buildConfiguration", "default"))
                        || !build.equals(ProjectContext.buildDirectory(project, current).toRealPath()))
                    throw new IOException("Target or build profile changed after Flash; Debug was not started.");
                // No second build: it could replace the image just verified by Flash.
                ZephyrDebugHandler.openDebugConfiguration(project, window.getShell(), flashed.attributes());
            } catch (Exception error) {
                MessageDialog.openError(window.getShell(), "Flash and Debug", error.getMessage());
            }
        });
    }

    private static java.util.concurrent.CompletableFuture<Void> watchOwnedProcess(Process process, TargetOperationGuard.Lease lease, boolean flash) {
        var completion = new java.util.concurrent.CompletableFuture<Void>();
        var watcher = new Thread(() -> {
            var children = new HashSet<ProcessHandle>();
            long deadline = System.nanoTime() + java.time.Duration.ofMinutes(15).toNanos();
            try {
                do {
                    children.addAll(process.descendants().toList());
                    if (flash && System.nanoTime() >= deadline) {
                        Activator.getDefault().getLog().log(new Status(IStatus.ERROR, Activator.ID,
                                "Flash runner timed out after 15 minutes; terminating the owned process."));
                        process.destroyForcibly();
                        break;
                    }
                } while (!process.waitFor(100, TimeUnit.MILLISECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            } finally {
                for (var child : children) if (child.isAlive()) child.destroyForcibly();
                Exception cleanupFailure = null;
                long reapDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                try {
                    for (var child : children) if (child.isAlive())
                        child.onExit().get(Math.max(1, reapDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    if (process.isAlive() && !process.waitFor(1, TimeUnit.SECONDS))
                        throw new IOException("Programmer process did not terminate; Debug was not started.");
                } catch (Exception error) {
                    cleanupFailure = error;
                    if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                } finally { lease.close(); }
                if (cleanupFailure == null) completion.complete(null);
                else completion.completeExceptionally(cleanupFailure);
            }
        }, "zew-owned-runner-lifecycle");
        watcher.setDaemon(true);
        watcher.start();
        return completion;
    }

    private static CoreException failure(String message, Throwable cause) {
        return new CoreException(new Status(IStatus.ERROR, Activator.ID, message, cause));
    }
}
