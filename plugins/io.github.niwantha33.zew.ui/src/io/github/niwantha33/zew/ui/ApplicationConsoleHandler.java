// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.handlers.HandlerUtil;
import io.github.niwantha33.zew.core.ProcessRunner;

/** Explicit, bounded UART-only application console capture. No probe access. */
public final class ApplicationConsoleHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);
        var project = SelectedProject.from(event);
        if (shell == null || shell.isDisposed()) return null;
        if (project == null || !project.isAccessible()) {
            MessageDialog.openInformation(shell, "Application console",
                    "Select an open Zephyr project first. This action captures a separately named UART/USB serial port.");
            return null;
        }
        var portInput = new InputDialog(shell, "UART/USB application console",
                "Serial port name (e.g. COM8 or /dev/ttyACM0). Only use a port NOT owned by GDB, OpenOCD, RTT or another terminal.",
                "", value -> value == null || value.isBlank() || value.length() > 128
                        || value.contains("\n") || value.contains("\r")
                        ? "Enter one existing port name." : null);
        if (portInput.open() != Window.OK) return null;
        String port = portInput.getValue().trim();
        var baudInput = new InputDialog(shell, "UART baud rate",
                "Set the exact baud rate configured by the firmware.", "115200", value -> {
                    try {
                        int rate = Integer.parseInt(value);
                        return rate >= 1200 && rate <= 4000000 ? null : "Use 1200 to 4000000.";
                    } catch (NumberFormatException invalid) { return "Enter an integer baud rate."; }
                });
        if (baudInput.open() != Window.OK) return null;
        int baud = Integer.parseInt(baudInput.getValue());
        var secondsInput = new InputDialog(shell, "Application console duration",
                "Capture duration in seconds (1–3600); cancellation stops capture and closes the port.",
                "30", value -> {
                    try {
                        int seconds = Integer.parseInt(value);
                        return seconds >= 1 && seconds <= 3600 ? null : "Use 1 to 3600 seconds.";
                    } catch (NumberFormatException invalid) { return "Enter whole seconds."; }
                });
        if (secondsInput.open() != Window.OK) return null;
        int seconds = Integer.parseInt(secondsInput.getValue());
        if (!MessageDialog.openConfirm(shell, "Capture UART application output",
                "Open only " + port + " at " + baud + " baud for up to " + seconds
                + " seconds?\n\nThis port must be free. Serial data is saved locally; raw binary CTF must "
                + "not be treated as text. Host timestamps are NOT target timestamps. "
                + "No flash, halt, reset or debug command is sent.")) return null;
        Job job = new Job("Capture Zephyr UART application log — " + project.getName()) {
            @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                var console = new MessageConsole("Zephyr UART (" + port + ") — " + project.getName(), null);
                ConsolePlugin.getDefault().getConsoleManager().addConsoles(new IConsole[] {console});
                ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
                Path helper = null;
                try (var stream = console.newMessageStream()) {
                    var settings = ProjectContext.settings(project);
                    String python = settings.getProperty("python");
                    if (python == null || python.isBlank())
                        throw new IOException("Managed Python unavailable. Check the project setup.");
                    var resource = Activator.getDefault().getBundle().getEntry(
                            "tools/rtos_analysis/serial_console.py");
                    if (resource == null) throw new IOException("Serial console component missing; repair Workbench.");
                    helper = Files.createTempFile("zew-serial-", ".py");
                    try (var input = resource.openStream()) {
                        Files.copy(input, helper, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    Path output = project.getLocation().append(".zew-uart").toFile().toPath();
                    var args = new ArrayList<>(List.of(python, helper.toString(),
                            "--port", port, "--baud", Integer.toString(baud),
                            "--seconds", Integer.toString(seconds), "--output", output.toString()));
                    stream.println("UART application output: host receive timestamps ONLY; no assumed thread or MCU clock.");
                    stream.println("Saved raw bytes and JSON lines under " + output);
                    new ProcessRunner().run(args, project.getLocation().toFile().toPath(),
                            Duration.ofSeconds(seconds + 20L), monitor::isCanceled,
                            stream::println, ProjectContext.environment(settings)).requireSuccess();
                    return Status.OK_STATUS;
                } catch (java.io.InterruptedIOException cancelled) {
                    return Status.CANCEL_STATUS;
                } catch (Exception error) {
                    if (!shell.isDisposed()) shell.getDisplay().asyncExec(() -> {
                        if (!shell.isDisposed()) MessageDialog.openError(shell, "UART console", error.getMessage());
                    });
                    return new Status(IStatus.ERROR, Activator.ID,
                            "UART capture failed: " + error.getMessage(), error);
                } finally {
                    if (helper != null) try { Files.deleteIfExists(helper); }
                    catch (IOException ignored) { /* Windows may briefly retain a temporary file. */ }
                }
            }
        };
        job.setUser(true);
        job.schedule();
        return null;
    }
}
