// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.handlers.HandlerUtil;

import io.github.niwantha33.zew.core.ProcessRunner;
import io.github.niwantha33.zew.core.WestCommands;
import io.github.niwantha33.zew.core.WorkbenchConfig;

public final class WorkbenchHandler extends AbstractHandler {
    private static final AtomicReference<Job> CURRENT = new AtomicReference<>();
    private static MessageConsole console;

    public static boolean isOperationActive() { return CURRENT.get() != null; }

    public static void cancelCurrent() {
        var job = CURRENT.get();
        if (job != null) job.cancel();
    }

    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        String operation = event.getCommand().getId().substring("io.github.niwantha33.zew.".length());
        if (operation.equals("cancel")) { cancelCurrent(); return null; }
        var shell = HandlerUtil.getActiveShell(event);
        if (CURRENT.get() != null || ToolsDialog.isOperationActive() || SetupWizard.isOperationActive()) {
            MessageDialog.openInformation(shell, "Zephyr operation active", "Wait for the current operation or use Zephyr > Cancel Operation.");
            return null;
        }
        WorkbenchConfig config;
        try {
            var store = Activator.getDefault().getPreferenceStore();
            String python = store.getString(WorkbenchPreferences.PYTHON);
            String workspace = store.getString(WorkbenchPreferences.WORKSPACE);
            String app = store.getString(WorkbenchPreferences.APPLICATION);
            String board = store.getString(WorkbenchPreferences.BOARD);
            if (python.isBlank() || workspace.isBlank()) throw new IllegalArgumentException("Set Python and West workspace in Window > Preferences > Zephyr Workbench.");
            boolean building = operation.equals("build") || operation.equals("rebuild");
            if (building && app.isBlank()) throw new IllegalArgumentException("Set the application folder in Zephyr Workbench preferences.");
            config = new WorkbenchConfig(Path.of(python), Path.of(workspace), Path.of(app.isBlank() ? workspace : app),
                    !building && board.isBlank() ? "unused" : board);
            if (building) config.validateApplication(); else config.validateEnvironment();
        } catch (RuntimeException error) {
            MessageDialog.openError(shell, "Zephyr settings", error.getMessage());
            return null;
        }
        if (console == null) {
            console = new MessageConsole("Zephyr Workbench", null);
            console.setWaterMarks(200_000, 400_000);
            ConsolePlugin.getDefault().getConsoleManager().addConsoles(new IConsole[] {console});
        }
        ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
        var stream = console.newMessageStream();
        Job job = new Job("Zephyr: " + operation) {
            @Override protected IStatus run(IProgressMonitor monitor) {
                try (stream) {
                    stream.println("\nZephyr " + operation + " | workspace: " + config.workspace());
                    stream.println("Zephyr Eclipse Workbench: " + Activator.getDefault().getBundle().getVersion());
                    if (operation.equals("diagnostics")) {
                        runCommand(List.of(config.python().toString(), "--version"), config, monitor, stream, Duration.ofSeconds(30));
                        runCommand(WestCommands.west(config, "--version"), config, monitor, stream, Duration.ofSeconds(30));
                        var root = runCommand(WestCommands.west(config, "topdir"), config, monitor, stream, Duration.ofSeconds(30));
                        if (!Path.of(root.trim()).toRealPath().equals(config.workspace().toRealPath())) throw new IOException("Selected workspace differs from West topdir.");
                        var path = WestCommands.parseZephyrPath(runCommand(WestCommands.west(config, "list", "zephyr", "--format", "{abspath}"), config, monitor, stream, Duration.ofSeconds(30)));
                        if (!Files.isRegularFile(path.resolve("VERSION"))) throw new IOException("Zephyr VERSION file not found at " + path);
                        stream.println("Zephyr source: " + path);
                        stream.println(Files.readString(path.resolve("VERSION")));
                        runCommand(List.of(config.python().toString(), "-c", "import shutil; tools=('cmake','ninja','git'); print(*[t + ': ' + (shutil.which(t) or 'MISSING') for t in tools], sep=chr(10)); raise SystemExit(any(not shutil.which(t) for t in tools))"), config, monitor, stream, Duration.ofSeconds(30));
                        stream.println("SDK/compiler compatibility is checked by the actual build. No dependencies were installed.");
                    } else if (operation.equals("boards")) {
                        var result = runCommand(WestCommands.boards(config), config, monitor, stream, Duration.ofMinutes(2));
                        var boards = WestCommands.parseBoards(result);
                        if (boards.isEmpty()) throw new IOException("West returned no board names. Check its output above.");
                        stream.println("Discovered " + boards.size() + " board names. This does not establish build/flash/debug or licence eligibility.");
                        PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
                            if (shell.isDisposed()) return;
                            var selection = new ElementListSelectionDialog(shell, new LabelProvider());
                            selection.setTitle("Select Zephyr board");
                            selection.setMessage("Board names from your checkout. Enter required core/SoC qualifiers next.");
                            selection.setElements(boards.toArray());
                            if (selection.open() != Window.OK) return;
                            String name = (String) selection.getFirstResult();
                            var target = new InputDialog(shell, "Exact board target", "Add required SoC/core qualifiers, for example esp32s3_devkitc/esp32s3/procpu.", name,
                                    text -> text.matches("[A-Za-z0-9][A-Za-z0-9_./@+\\-]*") && !text.contains("..") ? null : "Enter a valid exact board target.");
                            if (target.open() == Window.OK) Activator.getDefault().getPreferenceStore().setValue(WorkbenchPreferences.BOARD, target.getValue().trim());
                        });
                    } else {
                        stream.println("Application: " + config.application());
                        stream.println("Board: " + config.board());
                        stream.println("Build output: " + WestCommands.buildDirectory(config));
                        new ProcessRunner().run(WestCommands.build(config, operation.equals("rebuild")), config.workspace(),
                                Duration.ofMinutes(30), monitor::isCanceled, stream::println, ToolEnvironment.overrides()).requireSuccess();
                    }
                    stream.println("Completed successfully.");
                    return Status.OK_STATUS;
                } catch (InterruptedIOException error) {
                    logFailure("Cancelled: " + error.getMessage());
                    return Status.CANCEL_STATUS;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    logFailure("Interrupted.");
                    return Status.CANCEL_STATUS;
                } catch (Exception error) {
                    logFailure("FAILED: " + error.getMessage());
                    return new Status(IStatus.ERROR, Activator.ID, error.getMessage(), error);
                } finally { CURRENT.compareAndSet(this, null); }
            }
        };
        if (!CURRENT.compareAndSet(null, job)) { try { stream.close(); } catch (IOException ignored) {} return null; }
        job.addJobChangeListener(new JobChangeAdapter() {
            @Override public void done(IJobChangeEvent change) {
                CURRENT.compareAndSet(change.getJob(), null);
                try { stream.close(); } catch (IOException ignored) {}
            }
        });
        job.setUser(true);
        job.schedule();
        return null;
    }

    private static String runCommand(List<String> command, WorkbenchConfig config, IProgressMonitor monitor,
            MessageConsoleStream stream, Duration timeout) throws IOException, InterruptedException {
        return new ProcessRunner().run(command, config.workspace(), timeout, monitor::isCanceled, stream::println, ToolEnvironment.overrides()).requireCompleteOutput();
    }

    private static void logFailure(String message) {
        if (console == null) return;
        try (var stream = console.newMessageStream()) { stream.println(message); }
        catch (IOException ignored) { /* Eclipse shutdown may already have disposed the console. */ }
    }
}
