// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.EspressifOpenOcd;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Offline tests for official Espressif EIM/IDF tools layout; no hardware used. */
public final class EspressifOpenOcdTests {
    private static int checks;
    private static void check(boolean success, String message) {
        if (!success) throw new AssertionError(message);
        checks++;
    }

    static Path createTool(Path toolsRoot, String version, boolean scripts) throws IOException {
        Path product = toolsRoot.resolve("tools/openocd-esp32/" + version + "/openocd-esp32");
        Path exe = Files.createDirectories(product.resolve("bin")).resolve("openocd.exe");
        Files.writeString(exe, "fake vendor debug tool");
        if (scripts) {
            Path root = product.resolve("share/openocd/scripts");
            Files.createDirectories(root.resolve("interface"));
            Files.createDirectories(root.resolve("target"));
            Files.writeString(root.resolve("interface/esp_usb_jtag.cfg"), "interface test");
            Files.writeString(root.resolve("target/esp32s3.cfg"), "target test");
        }
        return exe;
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("zew-espressif-debug-");
        try {
            check(EspressifOpenOcd.appliesTo("esp32s3_devkitc/esp32s3/procpu"),
                    "ESP32-S3 uses vendor OpenOCD");
            check(!EspressifOpenOcd.appliesTo("nucleo_f446re/stm32f446xx"),
                    "STM32 should remain vendor-neutral");
            check(EspressifOpenOcd.discover(home, null).isEmpty(),
                    "Installing EIM GUI alone is not an OpenOCD installation");
            Path broken = createTool(home.resolve(".espressif"), "v0.12.0-esp32-20251215", false);
            check(EspressifOpenOcd.discover(home, null).isEmpty(),
                    "Missing matching JTAG scripts must be rejected");
            Path good = createTool(home.resolve(".espressif"), "v0.12.0-esp32-20260831", true);
            var found = EspressifOpenOcd.discover(home, null).orElseThrow();
            check(found.executable().equals(good.toRealPath()), "Discover full vendor executable");
            check(Files.isRegularFile(found.scripts().resolve("target/esp32s3.cfg")),
                    "Discover matching Espressif scripts");
            check(!found.executable().equals(broken), "Reject broken installation");
            Path override = home.resolve("eim-custom");
            Path custom = createTool(override, "v0.12.0-esp32-20260930", true);
            check(EspressifOpenOcd.discover(home, override).orElseThrow()
                    .executable().equals(custom.toRealPath()), "IDF_TOOLS_PATH custom location");
            check(EspressifOpenOcd.missingTools("esp32s3_devkitc/esp32s3/procpu")
                    .getMessage().contains("Installation Manager (EIM) GUI alone"),
                    "Must guide users to install tools, not just GUI");
            System.out.println("EspressifOpenOcdTests PASS: " + checks + " checks");
        } finally {
            try (var paths = Files.walk(home)) {
                for (var item : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(item);
            }
        }
    }
}
