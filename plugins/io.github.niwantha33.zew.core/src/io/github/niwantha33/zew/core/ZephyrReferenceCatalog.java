// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An intentionally small allowlist of official documentation destinations.
 * A link is navigation, not evidence that a board, API or revision is supported.
 * No network access, probing, downloads or execution occurs here.
 */
public final class ZephyrReferenceCatalog {
    private ZephyrReferenceCatalog() {}

    public record Link(String title, String category, String url, String qualification) {}

    /** Read only the selected checkout's bounded VERSION file; never guess a release. */
    public static String releaseFromWorkspace(Path westWorkspace) {
        if (westWorkspace == null) return "";
        try {
            Path file = westWorkspace.resolve("zephyr").resolve("VERSION");
            if (!Files.isRegularFile(file) || Files.size(file) > 4096) return "";
            Map<String, String> values = new LinkedHashMap<>();
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.strip();
                if (trimmed.startsWith("#") || !trimmed.contains("=")) continue;
                String key = trimmed.substring(0, trimmed.indexOf('=')).strip();
                String value = trimmed.substring(trimmed.indexOf('=') + 1).split("#", 2)[0].strip();
                if (List.of("VERSION_MAJOR", "VERSION_MINOR", "PATCHLEVEL",
                        "VERSION_TWEAK", "EXTRAVERSION").contains(key)) values.put(key, value);
            }
            String major = values.getOrDefault("VERSION_MAJOR", "");
            String minor = values.getOrDefault("VERSION_MINOR", "");
            String patch = values.getOrDefault("PATCHLEVEL", "");
            String tweak = values.getOrDefault("VERSION_TWEAK", "0");
            if (!major.matches("[0-9]{1,2}") || !minor.matches("[0-9]{1,2}")
                    || !patch.matches("[0-9]{1,2}") || !tweak.matches("[0-9]{1,8}")
                    || !values.getOrDefault("EXTRAVERSION", "").isBlank()
                    || Integer.parseInt(tweak) != 0 || Integer.parseInt(patch) >= 90) return "";
            return Integer.parseInt(major) + "." + Integer.parseInt(minor)
                    + "." + Integer.parseInt(patch);
        } catch (IOException | RuntimeException unavailable) {
            return "";
        }
    }

    public static List<Link> forTarget(String exactBoardTarget, String release) {
        // Never interpolate untrusted strings into browser destinations.
        String version = release != null && release.matches("[0-9]{1,2}\\.[0-9]{1,2}\\.[0-9]{1,2}")
                ? release : "";
        String base = "https://docs.zephyrproject.org/"
                + (version.isEmpty() ? "latest" : version) + "/";
        String qualification = version.isEmpty()
                ? "Latest development docs; not matched to the selected checkout"
                : "Documentation path for Zephyr " + version + "; link not checked online";
        var result = new ArrayList<Link>();
        result.add(new Link("Zephyr documentation", "Zephyr", base, qualification));
        result.add(new Link("C API reference", "Zephyr", base + "doxygen/html/", qualification));
        result.add(new Link("Kernel and RTOS services", "Zephyr", base + "kernel/index.html", qualification));
        result.add(new Link("Devicetree guide", "Zephyr", base + "build/dts/index.html", qualification));
        result.add(new Link("Devicetree bindings index", "Zephyr",
                base + "build/dts/api/bindings.html", qualification));
        result.add(new Link("Kconfig reference", "Zephyr",
                base + "build/kconfig/index.html", qualification));
        result.add(new Link("GPIO driver API", "Zephyr",
                base + "hardware/peripherals/gpio.html", qualification));
        result.add(new Link("Supported boards", "Zephyr", base + "boards/index.html",
                qualification));

        // Manufacturer documentation portals apply to every project, regardless
        // of whether the selected board belongs to a recognised vendor. They
        // are navigation indexes, NEVER exact-part TRM matches.
        String portalNote = "Manufacturer reference portal; choose the exact part, silicon revision, and board";
        result.add(new Link("TI device technical documents", "Manufacturer portal",
                "https://www.ti.com/microcontrollers-mcus-processors/microcontrollers/overview.html", portalNote));
        result.add(new Link("STMicroelectronics MCU documentation", "Manufacturer portal",
                "https://www.st.com/en/microcontrollers-microprocessors/documentation.html", portalNote));
        result.add(new Link("Nordic Semiconductor documentation", "Manufacturer portal",
                "https://docs.nordicsemi.com/", portalNote));
        result.add(new Link("Espressif hardware technical documents", "Manufacturer portal",
                "https://www.espressif.com/en/support/documents/technical-documents", portalNote));
        result.add(new Link("NXP MCUXpresso documentation", "Manufacturer portal",
                "https://mcuxpresso.nxp.com/docs", portalNote));
        result.add(new Link("Renesas official documentation", "Manufacturer portal",
                "https://www.renesas.com/en/support/document-search", portalNote));
        result.add(new Link("Microchip onlinedocs", "Manufacturer portal",
                "https://onlinedocs.microchip.com/", portalNote));
        result.add(new Link("Silicon Labs documentation", "Manufacturer portal",
                "https://docs.silabs.com/", portalNote));
        result.add(new Link("Raspberry Pi microcontroller documentation", "Manufacturer portal",
                "https://www.raspberrypi.com/documentation/microcontrollers/", portalNote));
        result.add(new Link("Analog Devices technical documents", "Manufacturer portal",
                "https://www.analog.com/en/resources.html", portalNote));
        result.add(new Link("Infineon documentation", "Manufacturer portal",
                "https://www.infineon.com/", portalNote));
        result.add(new Link("Other manufacturer / unsupported catalogue", "Manufacturer portal",
                "https://docs.zephyrproject.org/latest/boards/index.html",
                "Browse the official Zephyr board index; vendor-specific TRM is not resolved"));

        // Exact board-family mappings only. Unknown targets do not inherit a
        // superficially similar chip's TRM, schematic or board documentation.
        String board = exactBoardTarget == null ? "" : exactBoardTarget.strip();
        if (board.equals("lp_mspm0g3507") || board.startsWith("lp_mspm0g3507/")) {
            result.add(new Link("MSPM0G3507 Zephyr board guide", "Board",
                    "https://docs.zephyrproject.org/latest/boards/ti/lp_mspm0g3507/doc/index.html",
                    "Latest board documentation; may differ from selected checkout"));
            result.add(new Link("TI MSPM0 G-Series technical reference manual (PDF)",
                    "Manufacturer", "https://www.ti.com/lit/pdf/slau846",
                    "TI G-Series TRM; confirm peripheral and document revision"));
            result.add(new Link("TI MSPM0G3507 datasheet and errata",
                    "Manufacturer", "https://www.ti.com/product/MSPM0G3507",
                    "Official device page; check package, revision and errata"));
            result.add(new Link("TI LP-MSPM0G3507 board guide and design files",
                    "Board", "https://www.ti.com/tool/LP-MSPM0G3507",
                    "Official board page; confirm board revision and schematic"));
        }
        return List.copyOf(result);
    }
}
