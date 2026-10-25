// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.DebugServerDiagnostics;
import java.util.stream.IntStream;

/** Offline regression: report the actual failure reason, never claim hardware worked. */
public final class DebugServerDiagnosticsTests {
    private static int checks;
    private static void yes(boolean success, String reason) {
        if (!success) throw new AssertionError(reason);
        checks++;
    }
    public static void main(String[] args) {
        var readiness = new DebugServerDiagnostics();
        readiness.accept("-- runners.openocd: OpenOCD GDB server running on port 3333; no thread info available");
        yes(!readiness.isListening(3333), "West announcement does not prove OpenOCD is listening");
        readiness.accept("Info : Listening on port 6333 for tcl connections");
        yes(!readiness.isListening(6333), "TCL endpoint is not GDB readiness");
        readiness.accept("Info : Listening on port 3333 for gdb connections");
        yes(readiness.isListening(3333), "Owned OpenOCD GDB readiness without a dummy connection");
        yes(!readiness.isListening(4444), "Readiness must match configured port");
        for (int i = 0; i < 100; ++i) readiness.accept("later output");
        yes(readiness.isListening(3333), "Readiness survives diagnostic ring eviction");

        var missingJtag = new DebugServerDiagnostics();
        missingJtag.accept("Error: libusb_error_not_found");
        String result = missingJtag.describe("esp32s3_devkitc/esp32s3/procpu", 3333, 1);
        yes(result.contains("ESP32-S3"), "Must identify JTAG-specific recovery");
        yes(result.contains("libusb_error_not_found"), "Retain actual OpenOCD root cause");
        yes(result.contains("West exited 1"), "Retain actual exit status");
        yes(result.contains("No flash was requested"), "No false flashing claim");

        var wrongOpenOcd = new DebugServerDiagnostics();
        wrongOpenOcd.accept("Error: couldn't find interface/esp_usb_jtag.cfg");
        yes(wrongOpenOcd.describe("esp32s3_devkitc/esp32s3/procpu", 3333, 1)
                .contains("Espressif-compatible OpenOCD"), "Explains incompatible OpenOCD script");

        var noExecutable = new DebugServerDiagnostics();
        noExecutable.accept("FATAL ERROR: required program openocd not found; install it or add its location to PATH");
        yes(noExecutable.describe("esp32s3_devkitc/esp32s3/procpu", 3333, 1)
                .contains("Installing the EIM GUI alone is insufficient"),
                "Missing OpenOCD is a tool-install problem, not a JTAG wiring failure");

        var port = new DebugServerDiagnostics();
        port.accept("Error: address already in use");
        yes(port.describe("stm32f4_disco", 3333, 1).contains("GDB TCP port is occupied"),
                "Port conflict must not be mistaken for driver failure");
        var noLogs = new DebugServerDiagnostics();
        yes(noLogs.describe("any_board", 3333, null).contains("No diagnostic output was captured"),
                "No logs must not manufacture a cause");
        var bounded = new DebugServerDiagnostics();
        for (int i = 0; i < 1000; ++i) bounded.accept("line-" + i + ":" + "x".repeat(900));
        String text = bounded.describe("board", 3333, 2);
        yes(text.length() < 3700, "Large OpenOCD output must remain bounded");
        yes(text.contains("line-999"), "Capture must keep last available evidence");
        yes(!text.contains("line-10:"), "Drop oldest lines");
        System.out.println("DebugServerDiagnosticsTests PASS: " + checks + " checks");
    }
}
