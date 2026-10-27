// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Bounded, thread-safe diagnostic capture for an owned West debugserver.
 * Do not discard its exit reason or blame the firmware for missing USB/JTAG.
 * Never mark a debugger healthy just because an unrelated serial port exists.
 */
public final class DebugServerDiagnostics {
    private static final int LINES = 40;
    private static final int MAX_LINE = 500;
    private final java.util.Set<Integer> listeningPorts = new java.util.HashSet<>();
    private final ArrayDeque<String> recent = new ArrayDeque<>();

    private final int expectedPort;
    private final String runner;
    public DebugServerDiagnostics() { this(0, ""); }
    public DebugServerDiagnostics(int expectedPort, String runner) { this.expectedPort = expectedPort; this.runner = runner; }

    public synchronized void accept(String line) {
        if (line == null) return;
        var listening = java.util.regex.Pattern.compile(
                "^Info\\s*:\\s*Listening on port ([0-9]{1,5}) for gdb connections\\s*$").matcher(line);
        if (listening.matches()) listeningPorts.add(Integer.parseInt(listening.group(1)));
        // Match actual server readiness, never West's pre-start "running" announcement.
        if (runner.equals("jlink") && expectedPort > 0 && line.trim().matches("Waiting for GDB connection\\.\\.\\."))
            listeningPorts.add(expectedPort);
        var pyocd = java.util.regex.Pattern.compile(".*GDB server started at port ([0-9]{1,5}).*").matcher(line);
        if (runner.equals("pyocd") && pyocd.matches()) listeningPorts.add(Integer.parseInt(pyocd.group(1)));
        String clean = line.replace('\r', ' ').replace('\0', ' ');
        if (clean.length() > MAX_LINE) clean = clean.substring(0, MAX_LINE) + " [truncated]";
        if (recent.size() == LINES) recent.removeFirst();
        recent.addLast(clean);
    }

    /** Read the owned server's output; never consume its first GDB connection. */
    public synchronized boolean isListening(int port) {
        return listeningPorts.contains(port);
    }

    public synchronized String describe(String board, int port, Integer exitCode) {
        String log = String.join("\n", recent);
        String lower = log.toLowerCase(Locale.ROOT);
        String diagnosis;
        if (lower.contains("required program openocd not found")
                || lower.contains("openocd: command not found")
                || lower.contains("openocd is not recognized")) {
            diagnosis = "Espressif OpenOCD executable is not installed or cannot be located. "
                    + "Installing the EIM GUI alone is insufficient. Complete an ESP-IDF "
                    + "tools installation with OpenOCD selected. The Workbench can detect "
                    + "a complete installation under %USERPROFILE%\\\\.espressif\\\\tools "
                    + "or IDF_TOOLS_PATH after rebuilding the selected project.";
        } else if (lower.contains("libusb_error_access") || lower.contains("libusb_error_busy")) {
            diagnosis = "USB/JTAG access was denied or the interface is already claimed. "
                    + "Terminate existing Workbench Debug/External Debug Server sessions and check for "
                    + "a stale OpenOCD process for this board, including processes from another IDE or "
                    + "an earlier Eclipse instance. Stop only the identified stale server. Changing TCP "
                    + "ports does not release the physical probe. Reconnect the native USB/JTAG cable "
                    + "and retry; if access is still denied, verify the interface driver/permissions "
                    + "(ESP32-S3 on Windows requires the JTAG interface driver, separate from UART flashing).";
        } else if (lower.contains("libusb_error_not_found")
                || lower.contains("unable to open ftdi device") || lower.contains("no device found")
                || lower.contains("no matching usb device") || lower.contains("usb device not found")) {
            diagnosis = "OpenOCD could not access the JTAG USB interface. On ESP32-S3, "
                    + "connect the native USB Serial/JTAG interface (not only the UART bridge), "
                    + "check Windows JTAG/WinUSB drivers and close other debugger sessions.";
        } else if (lower.contains("esp_usb_jtag.cfg") || lower.contains("esp32s3.cfg")
                || lower.contains("invalid command name") || lower.contains("unknown interface driver")
                || lower.contains("can't find") || lower.contains("couldn't find")) {
            diagnosis = "OpenOCD script or command support is missing/incompatible. "
                    + "For ESP32 targets use an Espressif-compatible OpenOCD build and its matching scripts; "
                    + "the generic Zephyr SDK OpenOCD may not support the required target.";
        } else if (lower.contains("address already in use") || lower.contains("bind failed")
                || lower.contains("port already in use")) {
            diagnosis = "GDB TCP port is occupied or cannot be bound. "
                    + "Close any previous GDB server; do not attach to an unknown process.";
        } else if (lower.contains("unable to connect to the target") || lower.contains("jtag scan chain")
                || lower.contains("target examination failed") || lower.contains("no jtag devices found")) {
            diagnosis = "OpenOCD could not establish a JTAG session with the target. "
                    + "Check the debug probe/USB JTAG wiring, target power and board-specific configuration.";
        } else {
            diagnosis = "Debug server did not publish a usable GDB endpoint. "
                    + "Check its output for runner configuration, JTAG device access, and the correct OpenOCD installation.";
        }
        String status = "GDB startup failed for " + board + " on localhost:" + port
                + (exitCode == null ? " (server did not become ready)" : " (West exited " + exitCode + ")")
                + ". No flash was requested.\n" + diagnosis;
        if (!log.isBlank()) {
            // Show the most useful tail; preserve full output in the named Eclipse console.
            if (log.length() > 2600) log = "[earlier output omitted]\n" + log.substring(log.length() - 2600);
            status += "\n\nRecent debugserver output:\n" + log;
        } else status += "\nNo diagnostic output was captured; see the Zephyr DSF-GDB console.";
        return status;
    }
}
