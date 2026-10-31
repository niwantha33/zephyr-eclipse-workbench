// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.handlers.HandlerUtil;
import io.github.niwantha33.zew.core.ProcessRunner;

/** Guided offline trace investigation; never starts a debug server or touches a target. */
public final class RtosInvestigationHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        var shell = HandlerUtil.getActiveShell(event);
        if (shell == null || shell.isDisposed()) return null;
        var project = SelectedProject.from(event);
        if (project == null || !project.isAccessible()) {
            MessageDialog.openInformation(shell, "RTOS Investigation",
                    "Select an open Zephyr project to use its managed Python interpreter. "
                    + "This command only reads a saved trace; no hardware is accessed.");
            return null;
        }
        final java.util.Properties settings;
        final Path build;
        try {
            settings = ProjectContext.settings(project);
            build = ProjectContext.buildDirectory(project, settings);
        } catch (Exception error) {
            MessageDialog.openError(shell, "RTOS Investigation",
                    "Unable to locate the selected build: " + error.getMessage());
            return null;
        }
        var folder = new DirectoryDialog(shell, SWT.OPEN);
        folder.setText("Select one saved trace-YYYYMMDD-HHMMSS-xxx directory");
        folder.setFilterPath(project.getLocation().append(".zew-traces").toOSString());
        String selected = folder.open();
        if (selected == null) return null;
        Path capture = Path.of(selected);
        String fatal = null;
        if (MessageDialog.openQuestion(shell, "Optional fault evidence",
                "Include a saved Zephyr fatal text log in this offline investigation? "
                + "This only records its evidence and limitations; it does not infer a common session.")) {
            var dialog = new FileDialog(shell, SWT.OPEN);
            dialog.setText("Select one Zephyr fatal log (UTF-8)");
            dialog.setFilterExtensions(new String[] {"*.log;*.txt", "*"});
            fatal = dialog.open();
            if (fatal == null) return null;
        }
        final String fatalLog = fatal;
        String uart = null;
        if (MessageDialog.openQuestion(shell, "Optional UART evidence",
                "Include a saved UART application-console session? "
                + "Host receive timestamps cannot be synchronized with CTF target timestamps without external evidence.")) {
            var serialFolder = new DirectoryDialog(shell, SWT.OPEN);
            serialFolder.setText("Select one .zew-uart/serial-* session containing receipt.json");
            serialFolder.setFilterPath(project.getLocation().append(".zew-uart").toOSString());
            uart = serialFolder.open();
            if (uart == null) return null;
        }
        final String uartSession = uart;
        if (!Files.isRegularFile(capture.resolve("capture.json"))) {
            MessageDialog.openError(shell, "RTOS Investigation",
                    "Select one capture directory containing capture.json and ctf/channel0_0.");
            return null;
        }
        Job job = new Job("Review saved RTOS trace — " + project.getName()) {
            @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                var console = new MessageConsole("RTOS evidence review — " + project.getName(), null);
                ConsolePlugin.getDefault().getConsoleManager().addConsoles(new IConsole[] {console});
                ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
                Path helper = null;
                try (var stream = console.newMessageStream()) {
                    var resource = Activator.getDefault().getBundle().getEntry(
                            "tools/rtos_analysis/investigation.py");
                    if (resource == null) throw new IOException("Offline analysis component is missing. Reinstall Workbench.");
                    helper = Files.createTempFile("zew-investigation-", ".py");
                    try (var input = resource.openStream()) { Files.copy(input, helper,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                    var args = new java.util.ArrayList<>(List.of(settings.getProperty("python"),
                            helper.toString(), "--capture", capture.toString()));
                    if (Files.isRegularFile(build.resolve("zephyr/zephyr.elf"))
                            && Files.isRegularFile(build.resolve("zephyr/.config"))) {
                        args.add("--build");
                        args.add(build.toString());
                    } else {
                        stream.println("No selected ELF/.config build snapshot; source identity remains unverified.");
                    }
                    if (fatalLog != null) {
                        args.add("--fault-log");
                        args.add(fatalLog);
                    }
                    if (uartSession != null) {
                        args.add("--uart-session");
                        args.add(uartSession);
                    }
                    stream.println("OFFLINE RTOS INVESTIGATION — no reset, flash, memory read or probe access.");
                    new ProcessRunner().run(args, project.getLocation().toFile().toPath(),
                            Duration.ofSeconds(25), monitor::isCanceled, stream::println,
                            ProjectContext.environment(settings)).requireSuccess();
                    return Status.OK_STATUS;
                } catch (java.io.InterruptedIOException error) {
                    return Status.CANCEL_STATUS;
                } catch (Exception error) {
                    if (!shell.isDisposed()) shell.getDisplay().asyncExec(() -> {
                        if (!shell.isDisposed())
                            MessageDialog.openError(shell, "RTOS Investigation", error.getMessage());
                    });
                    return new Status(IStatus.ERROR, Activator.ID,
                            "Offline RTOS review incomplete: " + error.getMessage(), error);
                } finally {
                    if (helper != null) try { Files.deleteIfExists(helper); }
                    catch (IOException ignored) { /* Windows antivirus can retain temporary files. */ }
                }
            }
        };
        job.setUser(true);
        job.schedule();
        return null;
    }
}
