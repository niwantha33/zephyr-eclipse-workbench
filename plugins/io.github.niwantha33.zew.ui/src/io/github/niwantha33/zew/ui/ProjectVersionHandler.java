// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.io.InterruptedIOException;
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

    /**
     * A trial target is another completed setup, not a different spelling of the
     * project's existing West workspace. Resolve junctions and symlinks before
     * scheduling work so a selection mistake never creates a failing Job.
     */
    static void requireSeparateWorkspace(Path current, Path candidate) throws IOException {
        Path original = current.toRealPath();
        Path selected = candidate.toRealPath();
        if (original.startsWith(selected) || selected.startsWith(original)) {
            throw new IOException("The selected setup uses the project's current West workspace. "
                + "An upgrade comparison needs a separately installed Zephyr environment. "
                + "Original: " + original + " | Selected: " + selected);
        }
    }

    private static String friendlyResult(ProcessRunner.Result result, String action) throws IOException {
        if (result.exitCode() == 0) return result.output();
        if (result.output().contains("\"status\": \"drift\"")) {
            throw new IOException("The installed Zephyr environment differs from the project's saved lock. "
                + "See the detailed differences in the Zephyr Project Versions console.");
        }
        String detail = result.output().strip();
        if (detail.startsWith("ERROR: ")) detail = detail.substring(7);
        if (detail.isBlank() || detail.length() > 1400)
            detail = "The " + action + " operation failed. See the Zephyr Project Versions console.";
        throw new IOException(detail);
    }

    static void operate(IProject project, String action, Path candidateRoot,
                        IProgressMonitor monitor, Consumer<String> log) throws Exception {
        Properties settings = ProjectContext.settings(project);
        Path script = Path.of(FileLocator.toFileURL(
                Activator.getDefault().getBundle().getEntry(SCRIPT)).toURI());
        Path application = project.getLocation().toFile().toPath();
        Path workspace = Path.of(settings.getProperty("workspace"));
        var command = new ArrayList<String>(List.of(
                settings.getProperty("python"), script.toString(), action, "--plain-errors",
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
            requireSeparateWorkspace(workspace, Path.of(candidate.getProperty("workspace")));
            command.addAll(List.of("--candidate-workspace", candidate.getProperty("workspace"),
                    "--candidate-sdk", candidate.getProperty("tools.sdk"),
                    "--candidate-python", candidate.getProperty("python"),
                    "--candidate-git", candidate.getProperty("tools.git"),
                    "--candidate-cmake", candidate.getProperty("tools.cmake"),
                    "--candidate-ninja", candidate.getProperty("tools.ninja")));
        }
        var result = new ProcessRunner().run(command, workspace, Duration.ofMinutes(5),
                monitor::isCanceled, log, ProjectContext.environment(settings));
        friendlyResult(result, action);
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
            try {
                Properties current = ProjectContext.settings(project);
                Path originalRoot = Path.of(current.getProperty("zew.setupRoot")).toRealPath();
                Path originalWorkspace = Path.of(current.getProperty("workspace")).toRealPath();
                if (!Files.isRegularFile(project.getLocation().toFile().toPath()
                        .resolve(".zew/project-lock.json"))) {
                    MessageDialog.openInformation(HandlerUtil.getActiveShell(event),
                        "Project snapshot required",
                        "Create the project's .zew/project-lock.json first using Zephyr > "
                        + "Snapshot Project Environment. The existing build and sources are unchanged.");
                    return null;
                }
                var picker = new DirectoryDialog(HandlerUtil.getActiveShell(event));
                picker.setText("Select another installed Zephyr setup (not the current one)");
                picker.setMessage("Current setup: " + originalRoot
                    + "\nChoose a DIFFERENT completed Workbench setup folder, such as a separately "
                    + "installed Zephyr 4.5 environment. This action compares versions; "
                    + "it does not download or upgrade Zephyr.");
                Path parent = originalRoot.getParent();
                if (parent != null) picker.setFilterPath(parent.toString());
                String selected = picker.open();
                if (selected == null) return null;
                candidateRoot = Path.of(selected).toAbsolutePath().normalize();
                if (Files.isSameFile(originalRoot, candidateRoot)) {
                    MessageDialog.openInformation(HandlerUtil.getActiveShell(event),
                        "A second Zephyr setup is needed",
                        "You selected the existing setup for this project. To compare 4.4 with 4.5, "
                        + "install 4.5 in a separate folder first, then select that new setup here. "
                        + "\n\nThe current managed installer is pinned to its tested release; "
                        + "this preview does not install a new Zephyr version. "
                        + "Your project and Zephyr 4.4 setup have not been changed.");
                    return null;
                }
                Properties candidate = ProjectContext.load(candidateRoot);
                requireSeparateWorkspace(originalWorkspace, Path.of(candidate.getProperty("workspace")));
            } catch (Exception error) {
                MessageDialog.openInformation(HandlerUtil.getActiveShell(event),
                    "Cannot preview an upgrade",
                    error.getMessage() + "\n\nSelect a different, completed Zephyr setup. "
                    + "No source files or installed tools were changed.");
                return null;
            }
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
                } catch (InterruptedIOException error) {
                    return Status.CANCEL_STATUS;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return Status.CANCEL_STATUS;
                } catch (Exception error) {
                    // A user Job with ERROR status already opens Eclipse's
                    // problem report. Do NOT also open an SWT error dialog.
                    try (var errorStream = console.newMessageStream()) {
                        errorStream.println("Version operation failed: " + error.getMessage());
                    }
                    return new Status(IStatus.ERROR, Activator.ID, error.getMessage(), error);
                }
            }
        };
        job.setUser(true);
        job.schedule();
        return null;
    }
}
