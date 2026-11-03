// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.handlers.HandlerUtil;
import io.github.niwantha33.zew.core.ProcessRunner;

/** Project source pinning and non-destructive cross-version upgrade preview. */
public final class ProjectVersionHandler extends AbstractHandler {
    private static final String SCRIPT = "tools/project_versions.py";

    static void operate(IProject project, String action, Path candidateRoot,
                        IProgressMonitor monitor, Consumer<String> log) throws Exception {
        Properties settings = ProjectContext.settings(project);
        Path script = Path.of(FileLocator.toFileURL(
                Activator.getDefault().getBundle().getEntry(SCRIPT)).toURI());
        Path application = project.getLocation().toFile().toPath();
        Path workspace = Path.of(settings.getProperty("workspace"));
        var command = new ArrayList<String>(List.of(
                settings.getProperty("python"), script.toString(), action,
                "--application", application.toString(),
                "--workspace", workspace.toString(),
                "--python", settings.getProperty("python"),
                "--git", settings.getProperty("tools.git"),
                "--sdk", settings.getProperty("tools.sdk"),
                "--cmake", settings.getProperty("tools.cmake"),
                "--ninja", settings.getProperty("tools.ninja"),
                "--board", settings.getProperty("board")));
        if (action.equals("plan")) {
            if (candidateRoot == null)
                throw new IllegalArgumentException("Select a separate, ready candidate setup.");
            // Only a completed environment is accepted, not an arbitrary Git
            // checkout or a guessed new-version SDK.
            Properties candidate = ProjectContext.load(candidateRoot);
            command.addAll(List.of("--candidate-workspace", candidate.getProperty("workspace"),
                    "--candidate-sdk", candidate.getProperty("tools.sdk")));
        }
        var result = new ProcessRunner().run(command, workspace, Duration.ofMinutes(5),
                monitor::isCanceled, log, ProjectContext.environment(settings));
        result.requireSuccess();
    }

    /** Prevent a locked application from silently compiling against a newer setup. */
    static void verifyBeforeBuild(IProject project, IProgressMonitor monitor,
                                  Consumer<String> log) throws Exception {
        if (!Files.exists(project.getLocation().toFile().toPath().resolve(".zew/project-lock.json")))
            return;
        operate(project, "verify-env", null, monitor, log);
        log.accept("Project lock: matching original Zephyr, modules, SDK and host tools.");
    }

    /** Called only after successful firmware verification; never overwrites an existing lock. */
    static void snapshotOnFirstBuild(IProject project, IProgressMonitor monitor,
                                     Consumer<String> log) {
        if (Files.exists(project.getLocation().toFile().toPath().resolve(".zew/project-lock.json")))
            return;
        try {
            operate(project, "snapshot", null, monitor, log);
            log.accept("Project environment recorded in .zew/project-lock.json and .zew/west-frozen.yml.");
        } catch (Exception error) {
            // Incomplete West modules must not turn a working build into a
            // failure, nor be misreported as a complete reproducible lock.
            log.accept("Project environment lock NOT created: " + error.getMessage());
            log.accept("Open Zephyr > Snapshot Project Environment for details.");
        }
    }

    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        IProject project = SelectedProject.from(event);
        if (project == null || !ZephyrProjectLifecycle.canInspect(project)) {
            MessageDialog.openInformation(HandlerUtil.getActiveShell(event),
                    "Zephyr Project Versions", "Select an existing Zephyr application in Project Explorer.");
            return null;
        }
        String id = event.getCommand().getId();
        String action = id.endsWith(".snapshotProject") ? "snapshot"
                : id.endsWith(".verifyProject") ? "verify"
                : id.endsWith(".planProjectUpgrade") ? "plan" : "";
        if (action.isEmpty()) return null;
        Path candidateRoot = null;
        if (action.equals("plan")) {
            var picker = new DirectoryDialog(HandlerUtil.getActiveShell(event));
            picker.setText("Choose the separate candidate Zephyr setup");
            picker.setMessage("Select a completed setup for a newer Zephyr version. The existing project will not change.");
            String selected = picker.open();
            if (selected == null) return null;
            candidateRoot = Path.of(selected).toAbsolutePath().normalize();
        }
        Path selectedCandidate = candidateRoot;
        var job = new Job("Zephyr " + action + " — " + project.getName()) {
            @Override protected IStatus run(IProgressMonitor monitor) {
                var console = new MessageConsole("Zephyr Project Versions — " + project.getName(), null);
                ConsolePlugin.getDefault().getConsoleManager().addConsoles(new IConsole[] {console});
                try (var stream = console.newMessageStream()) {
                    operate(project, action, selectedCandidate, monitor, stream::println);
                    project.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE, monitor);
                    if (!monitor.isCanceled()) Display.getDefault().asyncExec(() ->
                        MessageDialog.openInformation(Display.getDefault().getActiveShell(),
                            "Zephyr Project Versions",
                            action.equals("snapshot") ? "The project lock has been captured. See .zew/project-lock.json."
                            : action.equals("verify") ? "No environment drift detected. Details are in the Zephyr Project Versions console."
                            : "Upgrade comparison is available in the Zephyr Project Versions console. No project or setup files were changed."));
                    return monitor.isCanceled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return Status.CANCEL_STATUS;
                } catch (Exception error) {
                    console.newMessageStream().println("Version operation failed: " + error.getMessage());
                    Display.getDefault().asyncExec(() ->
                        MessageDialog.openError(Display.getDefault().getActiveShell(),
                            "Zephyr Project Versions", error.getMessage()));
                    return new Status(IStatus.ERROR, Activator.ID, error.getMessage(), error);
                }
            }
        };
        job.setUser(true);
        job.schedule();
        return null;
    }
}
