// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Read-only detection of the vendor-specific OpenOCD installed by EIM/ESP-IDF.
 * EIM GUI installation alone is not an OpenOCD installation. Never accept an
 * arbitrary OpenOCD from the user's PATH for an Espressif debug target.
 */
public final class EspressifOpenOcd {
    public record Installation(Path executable, Path scripts) {
        public Installation {
            executable = executable.toAbsolutePath().normalize();
            scripts = scripts.toAbsolutePath().normalize();
        }
    }

    private EspressifOpenOcd() {}

    public static boolean appliesTo(String board) {
        return board != null && board.toLowerCase(Locale.ROOT).contains("esp32");
    }

    /**
     * Search only the official IDF tools layout. Caller owns candidate roots;
     * this never recurses through the whole user profile or touches installations.
     */
    public static Optional<Installation> discover(Path home, Path configuredIdfToolsPath) {
        var roots = new ArrayList<Path>();
        if (configuredIdfToolsPath != null) roots.add(configuredIdfToolsPath);
        if (home != null) roots.add(home.resolve(".espressif"));
        var candidates = new ArrayList<Installation>();
        for (Path root : roots) {
            Path tools = root.resolve("tools/openocd-esp32");
            if (!Files.isDirectory(tools)) continue;
            try (var versions = Files.list(tools)) {
                for (Path version : versions.filter(Files::isDirectory).toList()) {
                    // Both the official openocd-esp32 release archive and
                    // ESP-IDF tool manager can contain one extra product root.
                    for (Path directory : List.of(version.resolve("openocd-esp32"), version)) {
                        var found = fromProductDirectory(directory);
                        found.ifPresent(candidates::add);
                    }
                }
            } catch (IOException ignored) {
                // A damaged or inaccessible EIM installation is not usable.
            }
        }
        return candidates.stream().max(Comparator.comparing(
            item -> item.executable().toString().toLowerCase(Locale.ROOT)));
    }

    private static Optional<Installation> fromProductDirectory(Path dir) {
        Path exe = dir.resolve("bin/openocd.exe");
        if (!Files.isRegularFile(exe) || Files.isSymbolicLink(exe)) return Optional.empty();
        for (Path scripts : List.of(dir.resolve("share/openocd/scripts"), dir.resolve("scripts"))) {
            if (!Files.isDirectory(scripts) || Files.isSymbolicLink(scripts)) continue;
            if (!Files.isRegularFile(scripts.resolve("interface/esp_usb_jtag.cfg"))
                    || !Files.isRegularFile(scripts.resolve("target/esp32s3.cfg"))) continue;
            try {
                if (Files.size(exe) == 0) continue;
                return Optional.of(new Installation(exe.toRealPath(), scripts.toRealPath()));
            } catch (IOException ignored) { /* try another installation */ }
        }
        return Optional.empty();
    }

    public static IOException missingTools(String board) {
        return new IOException("ESP32 native debugging requires Espressif OpenOCD with "
                + "interface/esp_usb_jtag.cfg and target/esp32s3.cfg, but no complete "
                + "installation was found. The Espressif Installation Manager (EIM) "
                + "GUI alone does not install OpenOCD. In EIM, complete an ESP-IDF "
                + "tools installation with OpenOCD selected. Expected tools under "
                + "%USERPROFILE%\\.espressif\\tools\\openocd-esp32 or IDF_TOOLS_PATH. "
                + "Then choose Build and Debug again. No firmware was changed. Board: " + board);
    }
}
