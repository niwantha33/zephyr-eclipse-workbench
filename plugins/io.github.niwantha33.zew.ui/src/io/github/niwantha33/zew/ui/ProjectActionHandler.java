// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.handlers.HandlerUtil;
import io.github.niwantha33.zew.core.ProcessRunner;
import io.github.niwantha33.zew.core.TargetOperations;

/**
 * One project-aware operation: prepare dependencies, build exactly the current
 * project, validate its firmware, then call Zephyr's default West runner.
 * Only Flash programs hardware, and it is explicitly confirmed beforehand.
 */
public final class ProjectActionHandler extends AbstractHandler {
    public static final Object FAMILY = new Object();

    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        IProject project = SelectedProject.from(event);
        if (project == null || !project.isAccessible()) {
            MessageDialog.openInformation(HandlerUtil.getActiveShell(event), "Zephyr",
                    "Select an imported Zephyr project in Project Explorer or open one of its source files.");
            return null;
        }
        String action = event.getCommand().getId();
        action = action.substring(action.lastIndexOf('.') + 1);
        if (!Set.of("run", "flash", "debugserver").contains(action)) return null;
        if ("flash".equals(action)) {
            ZephyrFlashShortcut.launchProject(project, HandlerUtil.getActiveShell(event));
            return null;
        }
        final String operation = action;
        final String originalBoard;
        try {
            originalBoard = ProjectContext.settings(project).getProperty("board");
        } catch (Exception error) {
            MessageDialog.openError(HandlerUtil.getActiveShell(event), "Zephyr " + operation,
                    "Project setup is missing: " + error.getMessage());
            return null;
        }

        Job job = new Job("Zephyr Build & " + operation + " — " + project.getName()) {
            @Override protected IStatus run(IProgressMonitor monitor) {
                var console = new MessageConsole("Zephyr " + operation + " — " + project.getName(), null);
                ConsolePlugin.getDefault().getConsoleManager().addConsoles(new IConsole[] {console});
                try (var stream = console.newMessageStream();
                     var lease = TargetOperationGuard.acquire(operation)) {
                    monitor.beginTask("Prepare, build and " + operation, IProgressMonitor.UNKNOWN);
                    stream.println("Selected project: " + project.getName());
                    stream.println("Exact board: " + originalBoard);
                    stream.println("Preparing SDK/modules and compiling the latest project automatically...");
                    // Calls ZephyrProjectBuilder, which already owns dependency setup,
                    // managed paths and final firmware verification. No parallel private
                    // build implementation or home-grown flashing protocol.
                    project.build(IncrementalProjectBuilder.FULL_BUILD, Activator.ID + ".zephyrBuilder", Map.of(), monitor);
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    var settings = ProjectContext.settings(project);
                    String board = settings.getProperty("board");
                    if (!originalBoard.equals(board))
                        throw new java.io.IOException("Board changed during build. Operation cancelled; select the exact target again.");
                    Path output = ProjectContext.buildDirectory(project, settings);
                    var plan = TargetOperations.prepare(output, board, operation, "");
                    ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
                    stream.println("Verified firmware directory: " + plan.buildDirectory());
                    stream.println("Runner: board-defined West default; firmware preflight required before programming.");
                    if (operation.equals("debugserver"))
                        stream.println("External GDB server only. Use Debug As > Zephyr Application for source breakpoints.");
                    var command = plan.command(settings.getProperty("python"));
                    new ProcessRunner().run(command,
                        Path.of(settings.getProperty("workspace")),
                        operation.equals("debugserver") ? Duration.ofHours(8) : Duration.ofMinutes(15),
                        monitor::isCanceled, stream::println,
                        ProjectContext.environment(settings)).requireSuccess();
                    stream.println("West " + operation + " completed.");
                    return Status.OK_STATUS;
                } catch (java.io.InterruptedIOException error) {
                    return Status.CANCEL_STATUS;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return Status.CANCEL_STATUS;
                } catch (Exception error) {
                    return new Status(IStatus.ERROR, Activator.ID,
                        "Zephyr " + operation + " failed: " + error.getMessage(), error);
                } finally { monitor.done(); }
            }
        };
        job.setUser(true);
        job.schedule();
        return null;
    }
}
