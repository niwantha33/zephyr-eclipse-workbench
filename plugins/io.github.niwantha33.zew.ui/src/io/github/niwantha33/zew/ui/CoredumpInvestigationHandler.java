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
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.handlers.HandlerUtil;
import io.github.niwantha33.zew.core.ProcessRunner;

/** Analyze an explicitly chosen binary core dump without connecting to the MCU. */
public final class CoredumpInvestigationHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        var shell = HandlerUtil.getActiveShell(event);
        if (shell == null || shell.isDisposed()) return null;
        var project = SelectedProject.from(event);
        if (project == null || !project.isAccessible()) {
            MessageDialog.openInformation(shell, "Zephyr core dump",
                    "Select a Zephyr project containing the build of the failed image.");
            return null;
        }
        final java.util.Properties settings;
        final Path elf;
        final Path zephyr;
        try {
            settings = ProjectContext.settings(project);
            elf = ProjectContext.buildDirectory(project, settings).resolve("zephyr/zephyr.elf");
            zephyr = Path.of(settings.getProperty("workspace"), "zephyr");
            if (!Files.isRegularFile(elf))
                throw new IOException("Selected debug build lacks zephyr/zephyr.elf.");
            if (!Files.isRegularFile(zephyr.resolve("scripts/coredump/coredump_gdbserver.py")))
                throw new IOException("Selected Zephyr checkout lacks its matching core dump GDB server.");
        } catch (Exception error) {
            MessageDialog.openError(shell, "Zephyr core dump",
                    "Build/Zephyr selection incomplete: " + error.getMessage());
            return null;
        }
        var dumpDialog = new FileDialog(shell, SWT.OPEN);
        dumpDialog.setText("Select raw Zephyr binary core dump (not a serial text log)");
        dumpDialog.setFilterExtensions(new String[] {"*.bin", "*"});
        String dump = dumpDialog.open();
        if (dump == null) return null;
        var gdbDialog = new FileDialog(shell, SWT.OPEN);
        gdbDialog.setText("Select trusted SDK GDB for the exact target architecture");
        String gdb = gdbDialog.open();
        if (gdb == null) return null;
        if (!MessageDialog.openConfirm(shell, "Confirm core dump and ELF identity",
                "You must independently verify that this ELF matches the firmware which produced the dump."
                + "\n\nELF: " + elf
                + "\nDump: " + dump + "\nGDB: " + gdb
                + "\n\nProceed with OFFLINE GDB through the selected Zephyr core dump stub?"
                + "\nThis uses no network-listening debug server, reset, flash or target memory access."
                + "\nA backtrace may be incomplete or wrong if the ELF is mismatched.")) return null;
        Job job = new Job("Investigate Zephyr binary core dump — " + project.getName()) {
            @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                var console = new MessageConsole("Offline Zephyr core dump — " + project.getName(), null);
                ConsolePlugin.getDefault().getConsoleManager().addConsoles(new IConsole[] {console});
                ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
                Path helper = null;
                try (var stream = console.newMessageStream()) {
                    var resource = Activator.getDefault().getBundle().getEntry(
                            "tools/rtos_analysis/coredump_offline.py");
                    if (resource == null) throw new IOException("Core dump component unavailable. Reinstall Workbench.");
                    helper = Files.createTempFile("zew-coredump-", ".py");
                    try (var input = resource.openStream()) {
                        Files.copy(input, helper, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    String python = settings.getProperty("python");
                    if (python == null || python.isBlank()) throw new IOException("Managed Python not configured.");
                    stream.println("OFFLINE core dump / user-confirmed ELF only; no MCU, probe or debug session is accessed.");
                    new ProcessRunner().run(List.of(python, helper.toString(),
                            "--python", python, "--gdb", gdb, "--elf", elf.toString(),
                            "--dump", dump, "--zephyr", zephyr.toString(),
                            "--confirm-elf", "--timeout", "40"), project.getLocation().toFile().toPath(),
                            Duration.ofSeconds(55), monitor::isCanceled,
                            stream::println, ProjectContext.environment(settings)).requireSuccess();
                    return Status.OK_STATUS;
                } catch (java.io.InterruptedIOException cancelled) {
                    return Status.CANCEL_STATUS;
                } catch (Exception error) {
                    if (!shell.isDisposed()) shell.getDisplay().asyncExec(() -> {
                        if (!shell.isDisposed()) MessageDialog.openError(shell,
                                "Offline Zephyr core dump", error.getMessage());
                    });
                    return new Status(IStatus.ERROR, Activator.ID,
                            "Core dump analysis incomplete: " + error.getMessage(), error);
                } finally {
                    if (helper != null) try { Files.deleteIfExists(helper); }
                    catch (IOException ignored) { /* Best-effort temporary tool cleanup. */ }
                }
            }
        };
        job.setUser(true);
        job.schedule();
        return null;
    }
}
