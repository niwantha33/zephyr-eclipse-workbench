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
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.handlers.HandlerUtil;
import io.github.niwantha33.zew.core.ProcessRunner;

/** Thin UI for the optional offline managed archive backend. No shell commands. */
final class EnvironmentArchiveActions {
    private static final String SCRIPT = "tools/environment_archive.py";

    private EnvironmentArchiveActions() {}

    static boolean available() {
        return Activator.getDefault().getBundle().getEntry(SCRIPT) != null;
    }

    private static Path file(Shell shell, String title, boolean save, String[] filters) {
        var dialog = new FileDialog(shell, save ? SWT.SAVE : SWT.OPEN);
        dialog.setText(title);
        dialog.setFilterExtensions(filters);
        String value = dialog.open();
        return value == null ? null : Path.of(value).toAbsolutePath().normalize();
    }

    private static Path trustedRunner(Shell shell) {
        var dialog = new DirectoryDialog(shell);
        dialog.setText("Select a trusted, currently installed Workbench environment");
        dialog.setMessage("Its Python runs the offline archive utility. This can be your latest setup; "
                + "the original setup does not need to exist.");
        String value = dialog.open();
        return value == null ? null : Path.of(value).toAbsolutePath().normalize();
    }

    static void open(ExecutionEvent event, IProject project) {
        if (!available()) return;
        Shell shell = HandlerUtil.getActiveShell(event);
        var menu = new MessageDialog(shell, "Private Environment Archive", null,
                "Archive an isolated setup, restore a retained archive, or compare saved and rebuilt firmware. "
                + "Save application source and production artifacts separately. "
                + "Archives are private, unsigned and not automatically sanitised.",
                MessageDialog.INFORMATION, new String[] {"Export", "Restore and Attach",
                        "Compare Firmware", "Verify Retained Archive", "Cancel"}, 4);
        switch (menu.open()) {
            case 0 -> export(shell, project);
            case 1 -> restore(shell, project);
            case 2 -> compare(shell, project);
            case 3 -> verify(shell, project);
            default -> { }
        }
    }

    private static void export(Shell shell, IProject project) {
        if (!Files.isRegularFile(project.getLocation().toFile().toPath().resolve(".zew/project-lock.json"))) {
            MessageDialog.openInformation(shell, "Project lock required",
                    "Build the project or create its lock first. Preserve the lock and application separately.");
            return;
        }
        Path archive = file(shell, "Save private setup archive", true, new String[] {"*.zewenv", "*.*"});
        if (archive == null) return;
        if (!MessageDialog.openConfirm(shell, "Private copy approval",
                "The entire managed setup can contain credentials, private source and restricted vendor assets. "
                + "Review it, confirm your retention rights, stop all other builds and setup changes, "
                + "and store the archive in an access-controlled location. "
                + "This is not a sanitised or encrypted export.\n\nCreate private archive at " + archive + "?")) return;
        schedule(project, "Export environment", (monitor, stream) -> {
            ProjectVersionHandler.operate(project, "verify-env", null, monitor, stream::println);
            Properties settings = ProjectContext.settings(project);
            invoke(settings, List.of("export", "--root", settings.getProperty("zew.setupRoot"),
                    "--archive", archive.toString(), "--private-copy"), monitor, stream, false);
            return "Archive exported. Save the SHA-256 in a separate trusted record (see console). "
                    + "The archive has not been clean-machine build qualified.";
        });
    }

    private static void restore(Shell shell, IProject project) {
        if (!Files.isRegularFile(project.getLocation().toFile().toPath().resolve(".zew/project-lock.json"))) {
            MessageDialog.openInformation(shell, "Project lock required",
                    "Restore the original application and its saved lock before attaching an environment.");
            return;
        }
        Path archive = file(shell, "Open private setup archive", false, new String[] {"*.zewenv", "*.*"});
        if (archive == null) return;
        Path runner = trustedRunner(shell);
        if (runner == null) return;
        var destination = new InputDialog(shell, "Original setup path",
                "Enter the exact original absolute setup path from your archive receipt. "
                + "The path must not exist; its parent must exist.", "", value -> {
                    try {
                        Path path = Path.of(value);
                        return path.isAbsolute() && !Files.exists(path) ? null
                                : "Enter an unused absolute path.";
                    } catch (RuntimeException error) { return "Invalid path."; }
                });
        if (destination.open() != Window.OK) return;
        Path root = Path.of(destination.getValue()).toAbsolutePath().normalize();
        var digest = new InputDialog(shell, "Trusted archive SHA-256",
                "Enter the 64-character digest retained separately from this archive.", "",
                value -> value.matches("[0-9a-fA-F]{64}") ? null : "Enter the retained SHA-256.");
        if (digest.open() != Window.OK) return;
        String expected = digest.getValue().toLowerCase(java.util.Locale.ROOT);
        if (!MessageDialog.openConfirm(shell, "Restore and verify original setup",
                "Trust the archived tools and the currently installed Python from " + runner + "?\n\n"
                + "Restore to " + root + " without overwriting any installation, then verify the saved project "
                + "lock and attach only if it matches. The next build regenerates selected build output. "
                + "Preserve original production firmware separately.")) return;
        schedule(project, "Restore environment", (monitor, stream) -> {
            Properties settings = runnerSettings(runner);
            invoke(settings, List.of("restore", "--archive", archive.toString(),
                    "--destination", root.toString(), "--expected-sha256", expected,
                    "--trusted-archive"), monitor, stream, false);
            stream.println("Archive bytes restored; attachment and firmware build remain unverified.");
            if (monitor.isCanceled()) throw new InterruptedIOException("Recovery cancelled before attachment");
            ProjectVersionHandler.operate(project, "attach", root, monitor, stream::println);
            return "Original environment restored and matching lock attached. Build the project to "
                    + "qualify the restored setup; binary reproducibility is still unverified.";
        });
    }

    private static void verify(Shell shell, IProject project) {
        Path archive = file(shell, "Select retained private archive", false, new String[] {"*.zewenv", "*.*"});
        if (archive == null) return;
        Path runner = trustedRunner(shell);
        if (runner == null) return;
        var digest = new InputDialog(shell, "Trusted archive SHA-256",
                "Enter the digest saved separately at export. This reads all archived bytes without restoring.",
                "", value -> value.matches("[0-9a-fA-F]{64}") ? null : "Enter the retained SHA-256.");
        if (digest.open() != Window.OK) return;
        String expected = digest.getValue().toLowerCase(java.util.Locale.ROOT);
        if (!MessageDialog.openConfirm(shell, "Verify retained archive",
                "Run the offline archive utility with trusted Python from " + runner
                + "? No archived code will run and no environment will be restored.")) return;
        schedule(project, "Verify retained environment archive", (monitor, stream) -> {
            invoke(runnerSettings(runner), List.of("verify", "--archive", archive.toString(),
                    "--expected-sha256", expected), monitor, stream, false);
            return "Retained digest and all archived payloads match. This is byte integrity evidence, not a successful offline build.";
        });
    }

    private static void compare(Shell shell, IProject project) {
        Path original = file(shell, "Select original retained firmware", false, new String[] {"*.*"});
        if (original == null) return;
        Path rebuilt = file(shell, "Select corresponding rebuilt firmware", false, new String[] {"*.*"});
        if (rebuilt == null) return;
        Path runner = trustedRunner(shell);
        if (runner == null || !MessageDialog.openConfirm(shell, "Compare firmware bytes",
                "Run the archive utility with trusted Python from " + runner + "?\n\n"
                + "Compare files only, not their hardware identity or build provenance.")) return;
        schedule(project, "Compare firmware", (monitor, stream) -> {
            int code = invoke(runnerSettings(runner), List.of("compare-firmware",
                    "--original", original.toString(), "--rebuilt", rebuilt.toString()),
                    monitor, stream, true);
            return code == 0 ? "The selected firmware files are byte-identical."
                    : "The selected firmware files differ. See the SHA-256 values in the console.";
        });
    }

    private static Properties runnerSettings(Path root) throws Exception {
        Path actual = root.toRealPath();
        Properties settings = ProjectContext.load(actual);
        settings.setProperty("zew.setupRoot", actual.toString());
        return settings;
    }

    private static int invoke(Properties settings, List<String> arguments, IProgressMonitor monitor,
                              org.eclipse.ui.console.MessageConsoleStream stream, boolean allowDifference)
            throws Exception {
        Path script = Path.of(FileLocator.toFileURL(
                Activator.getDefault().getBundle().getEntry(SCRIPT)).toURI());
        var command = new ArrayList<String>(List.of(settings.getProperty("python"), script.toString()));
        command.addAll(arguments);
        stream.println("CLI argument vector: " + command);
        var result = new ProcessRunner().run(command, script.getParent(), Duration.ofHours(4),
                monitor::isCanceled, stream::println, ProjectContext.buildEnvironment(settings));
        if (monitor.isCanceled()) throw new InterruptedIOException("Archive operation cancelled");
        if (result.exitCode() != 0 && !(allowDifference && result.exitCode() == 1)) {
            throw new IOException("Archive operation failed. See Zephyr Environment Archive console.");
        }
        return result.exitCode();
    }

    @FunctionalInterface
    private interface Work {
        String run(IProgressMonitor monitor, org.eclipse.ui.console.MessageConsoleStream stream) throws Exception;
    }

    private static void schedule(IProject project, String title, Work work) {
        var job = new Job(title + " — " + project.getName()) {
            @Override protected IStatus run(IProgressMonitor monitor) {
                var console = new MessageConsole("Zephyr Environment Archive — " + project.getName(), null);
                ConsolePlugin.getDefault().getConsoleManager().addConsoles(new IConsole[] {console});
                ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
                monitor.beginTask(title, IProgressMonitor.UNKNOWN);
                try (var stream = console.newMessageStream()) {
                    String message = work.run(monitor, stream);
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    Display.getDefault().asyncExec(() -> MessageDialog.openInformation(
                            Display.getDefault().getActiveShell(), "Project Environment Manager", message));
                    return Status.OK_STATUS;
                } catch (InterruptedIOException error) {
                    return Status.CANCEL_STATUS;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return Status.CANCEL_STATUS;
                } catch (Exception error) {
                    return new Status(IStatus.ERROR, Activator.ID,
                            error.getMessage() == null ? title + " failed" : error.getMessage(), error);
                } finally {
                    monitor.done();
                }
            }
        };
        job.setUser(true);
        job.setRule(project);
        job.schedule();
    }
}
