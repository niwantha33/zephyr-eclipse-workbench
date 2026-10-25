// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;

import io.github.niwantha33.zew.core.EspressifFlashSafety;
import io.github.niwantha33.zew.core.ProcessRunner;

/**
 * Explicitly choose one serial interface, then interrogate its ROM read-only.
 * No speculative scanning or flashing unknown devices on other COM ports.
 */
final class EspressifSerialProbe {
    private EspressifSerialProbe() {}

    static String selectAndVerify(
            Shell shell, String board, Properties settings, BooleanSupplier cancelled,
            Consumer<String> log) throws IOException, InterruptedException {
        var python = settings.getProperty("python");
        var workspace = Path.of(settings.getProperty("workspace"));
        var environment = ProjectContext.environment(settings);
        String script = "from serial.tools import list_ports; "
                + "[print(p.device + chr(9) + (p.description or 'Serial device').replace(chr(9), ' ')) "
                + "for p in list_ports.comports()]";
        String output = new ProcessRunner().run(List.of(python, "-c", script),
                workspace, Duration.ofSeconds(15), cancelled, log, environment).requireCompleteOutput();
        Map<String, String> choices = new LinkedHashMap<>();
        for (String line : output.lines().toList()) {
            String[] item = line.split("\\t", 2);
            if (item.length < 1) continue;
            try {
                String port = EspressifFlashSafety.checkedPort(item[0].trim());
                choices.put(port + " — " + (item.length == 2 ? item[1].trim() : "Serial interface"), port);
            } catch (IOException ignored) { /* not a supported local serial port */ }
        }
        if (choices.isEmpty())
            throw new IOException("No serial ports detected. Connect the ESP32 board and confirm Windows Device Manager shows its COM port.");
        if (cancelled.getAsBoolean()) throw new java.io.InterruptedIOException("Flash cancelled");
        if (shell == null || shell.isDisposed())
            throw new IOException("No active Eclipse window to confirm the physical serial port.");

        AtomicReference<String> selected = new AtomicReference<>();
        shell.getDisplay().syncExec(() -> {
            if (shell.isDisposed()) return;
            var dialog = new ElementListSelectionDialog(shell, new LabelProvider());
            dialog.setTitle("Select ESP32 serial port — " + board);
            dialog.setMessage("Select the physical board to program. Unselected serial ports will not be accessed.");
            dialog.setElements(choices.keySet().toArray(String[]::new));
            dialog.setMultipleSelection(false);
            if (dialog.open() == Window.OK && dialog.getFirstResult() instanceof String label)
                selected.set(choices.get(label));
        });
        String port = selected.get();
        if (port == null) throw new java.io.InterruptedIOException("User cancelled serial-port selection.");
        if (cancelled.getAsBoolean()) throw new java.io.InterruptedIOException("Flash cancelled");
        log.accept("Non-programming chip identity check on " + port + " (ROM read-mac)...");
        String probe;
        try {
            probe = new ProcessRunner().run(
                    List.of(python, "-m", "esptool", "--port", port,
                            "--baud", "115200", "--no-stub", "read-mac"),
                    workspace, Duration.ofSeconds(30), cancelled, log, environment)
                    .requireCompleteOutput();
        } catch (IOException failure) {
            throw new IOException("Cannot safely identify ESP32 chip on " + port
                    + ". Check BOOT/RESET, USB cable, Device Manager and close other serial monitors. "
                    + "No firmware was written. Probe details: " + failure.getMessage(), failure);
        }
        EspressifFlashSafety.requireMatch(board, probe);
        log.accept("Pre-flash chip match VERIFIED: " + EspressifFlashSafety.expectedChip(board)
                + " on " + port + ". Only this port will be used.");
        return EspressifFlashSafety.checkedPort(port);
    }
}
