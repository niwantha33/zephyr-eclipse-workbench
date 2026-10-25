// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.EspressifFlashSafety;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** No hardware required: regress the exact ESP32-to-S3 unsafe-flash incident. */
public final class EspressifFlashSafetyTests {
    private static int checks;
    private static void ok(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
    private static void rejects(Throwing callback, String label) throws Exception {
        try { callback.run(); }
        catch (IOException expected) { checks++; return; }
        throw new AssertionError(label);
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        String output = "esptool v5.2.0\nFound 5 serial ports...\n"
                + "Serial port COM11:\nConnecting...\nDetecting chip type... ESP32-S3\n"
                + "Connected to ESP32-S3 on COM11:\n"
                + "Chip type: ESP32-S3 (QFN56) (revision v0.1)\n";
        ok(EspressifFlashSafety.expectedChip("esp32_devkitc/esp32/procpu").equals("ESP32"),
                "ESP32 classic project SoC");
        ok(EspressifFlashSafety.expectedChip("esp32s3_devkitc/esp32s3/procpu").equals("ESP32-S3"),
                "ESP32-S3 SoC");
        ok(EspressifFlashSafety.expectedChip("esp32c3_devkitm/esp32c3").equals("ESP32-C3"),
                "ESP32-C3 target variant");
        rejects(() -> EspressifFlashSafety.requireMatch("esp32_devkitc/esp32/procpu", output),
                "Never program ESP32 classic image into ESP32-S3 on COM11");
        try {
            EspressifFlashSafety.requireMatch("esp32_devkitc/esp32/procpu", output);
            throw new AssertionError("Expected structured wrong-SoC error");
        } catch (EspressifFlashSafety.ChipMismatchException mismatch) {
            ok(mismatch.expectedChip().equals("ESP32")
                && mismatch.connectedChip().equals("ESP32-S3"),
                "Recovery receives exact expected and detected SoC");
            ok(mismatch.getMessage().contains("Firmware was NOT written"),
                "Fail-closed reason is explicit");
        }
        EspressifFlashSafety.requireMatch("esp32s3_devkitc/esp32s3/procpu", output);
        ok(true, "S3 identity match");
        rejects(() -> EspressifFlashSafety.requireMatch("esp32s3_devkitc/esp32s3/procpu",
                "Serial port COM11: no data received"), "Unknown probe identity blocks flash");
        rejects(() -> EspressifFlashSafety.requireMatch("esp32s3_devkitc/esp32s3/procpu",
                "Connected to ESP32-S3 on COM11\nChip type: ESP32-C3"), "Conflicting probe identities");
        rejects(() -> EspressifFlashSafety.expectedChip("unknown_chip_board"),
                "Unknown SoC fails closed");
        ok(EspressifFlashSafety.checkedPort("COM11").equals("COM11"), "Explicit COM11 permitted");
        rejects(() -> EspressifFlashSafety.checkedPort("COM11 --force"), "Reject option injection");
        rejects(() -> EspressifFlashSafety.checkedPort(""), "Reject empty auto-scan port");

        Path tmp = Files.createTempDirectory("zew-esp32-flash-test-");
        try {
            Path zephyr = Files.createDirectories(tmp.resolve("zephyr"));
            Files.writeString(zephyr.resolve("runners.yaml"), "flash-runner: esp32\ndebug-runner: openocd\n");
            ok(EspressifFlashSafety.isEsp32Runner(tmp), "ESP32 West runner selected");
            Files.writeString(zephyr.resolve("runners.yaml"), "flash-runner: openocd\n");
            ok(!EspressifFlashSafety.isEsp32Runner(tmp), "Other SoC runners unaffected");
            Files.writeString(zephyr.resolve("runners.yaml"), "# no runner\n");
            rejects(() -> EspressifFlashSafety.isEsp32Runner(tmp), "Unknown runner cannot guess");
        } finally {
            try (var paths = Files.walk(tmp)) {
                for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
        System.out.println("EspressifFlashSafetyTests PASS: " + checks + " checks");
    }
}
