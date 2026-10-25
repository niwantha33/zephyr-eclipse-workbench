// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Shared, read-only OpenOCD tool registry for all managed Zephyr projects.
 * A provider is selected per board, not a single system-global binary.
 *
 * Uses installed tools only; executable presence and script completeness do
 * not constitute physical JTAG or hardware support. Tool installers/drivers
 * are independent, approved workflows.
 */
public final class OpenOcdRegistry {
    public enum Provider { GENERAL, ESPRESSIF, TI }

    public record Installation(Provider provider, Path executable, Path scripts, String origin) {
        public Installation {
            Objects.requireNonNull(provider);
            Objects.requireNonNull(executable);
            Objects.requireNonNull(scripts);
            Objects.requireNonNull(origin);
            executable = executable.toAbsolutePath().normalize();
            scripts = scripts.toAbsolutePath().normalize();
        }
    }

    public record Locations(Path sdk, Path home, Path idfToolsRoot,
                            Path generalOverride, Path espressifOverride, Path tiOverride,
                            Path windowsProgramFiles) {}

    private OpenOcdRegistry() {}

    /** Board/SoC identity chooses a compatible distribution, never the COM port. */
    public static Provider providerFor(String board) {
        String b = board == null ? "" : board.toLowerCase(Locale.ROOT);
        // Board qualifiers contain chip identity in upstream Zephyr names.
        if (b.matches(".*(?:esp32|esp32s[23]|esp32c[2356]|esp32h2|esp32p4).*"))
            return Provider.ESPRESSIF;
        if (b.matches(".*(?:mspm0|cc13[0-9]|cc26[0-9]|cc23[0-9]|xds110).*"))
            return Provider.TI;
        return Provider.GENERAL;
    }

    /**
     * No recursive scan of PATH, disks or the user's whole home directory.
     * Every candidate is bounded to an explicit tool installation root.
     */
    public static Optional<Installation> select(String board, Locations locations) {
        Provider provider = providerFor(board);
        if (locations == null) return Optional.empty();
        if (provider == Provider.ESPRESSIF) {
            Optional<Installation> manual = fromExecutable(provider, locations.espressifOverride(), "Configured Espressif OpenOCD");
            if (manual.isPresent()) return manual;
            return EspressifOpenOcd.discover(locations.home(), locations.idfToolsRoot())
                    .map(found -> new Installation(Provider.ESPRESSIF,
                            found.executable(), found.scripts(), "Espressif EIM / IDF tools"));
        }
        if (provider == Provider.TI) {
            // Some TI targets require a TI fork/probe transport. Do NOT fall
            // back to generic OpenOCD merely because an executable exists.
            return fromExecutable(Provider.TI, locations.tiOverride(), "Configured TI OpenOCD");
        }

        var custom = fromExecutable(provider, locations.generalOverride(), "Configured general OpenOCD");
        if (custom.isPresent()) return custom;

        var options = new ArrayList<Path>();
        if (locations.sdk() != null) {
            Path sdk = locations.sdk();
            options.add(sdk.resolve("hosttools/bin/openocd.exe"));
            options.add(sdk.resolve("hosttools/usr/bin/openocd.exe"));
            options.add(sdk.resolve("bin/openocd.exe"));
        }
        if (locations.windowsProgramFiles() != null) {
            options.add(locations.windowsProgramFiles().resolve("OpenOCD/bin/openocd.exe"));
            options.add(locations.windowsProgramFiles().resolve("xPack/OpenOCD/bin/openocd.exe"));
        }
        for (Path candidate : options) {
            var found = fromExecutable(provider, candidate, "Shared Zephyr/OpenOCD installation");
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    /**
     * The selected script directory belongs to this exact executable.
     * Treat symlinked executables and scripts as untrusted for automatic
     * selection and preserve the entire install tree unmodified.
     */
    public static Optional<Installation> fromExecutable(Provider provider, Path executable, String origin) {
        if (executable == null) return Optional.empty();
        try {
            if (!Files.isRegularFile(executable) || Files.isSymbolicLink(executable)
                    || Files.size(executable) == 0) return Optional.empty();
            Path exe = executable.toRealPath();
            Path bin = exe.getParent();
            if (bin == null || bin.getParent() == null) return Optional.empty();
            Path product = bin.getParent();
            for (Path scripts : List.of(
                    product.resolve("share/openocd/scripts"), product.resolve("scripts"),
                    product.resolve("share/scripts"))) {
                if (!Files.isDirectory(scripts) || Files.isSymbolicLink(scripts)) continue;
                if (!Files.isDirectory(scripts.resolve("interface"))
                        || !Files.isDirectory(scripts.resolve("target"))) continue;
                if (provider == Provider.ESPRESSIF
                        && (!Files.isRegularFile(scripts.resolve("interface/esp_usb_jtag.cfg"))
                        || !Files.isRegularFile(scripts.resolve("target/esp32s3.cfg")))) continue;
                return Optional.of(new Installation(provider, exe, scripts.toRealPath(), origin));
            }
        } catch (IOException | SecurityException ignored) {
            // Broken installation or inaccessible root is not selected.
        }
        return Optional.empty();
    }

    public static IOException missing(String board) {
        Provider provider = providerFor(board);
        if (provider == Provider.ESPRESSIF) {
            return EspressifOpenOcd.missingTools(board);
        }
        if (provider == Provider.TI) {
            return new IOException("No TI-compatible OpenOCD distribution is configured for " + board
                    + ". Open Zephyr > Install / Manage Tools and select a reviewed TI OpenOCD"
                    + " executable with matching interface/target scripts. Do not substitute"
                    + " generic OpenOCD for an XDS110 or TI-specific runner.");
        }
        return new IOException("OpenOCD is not installed for " + board + ". "
                + "Open Zephyr > Install / Manage Tools to select one global OpenOCD "
                + "installation, or install the supported Zephyr SDK OpenOCD host tools. "
                + "Projects reuse the same provider; no global PATH changes are required.");
    }

    /** Native runner metadata must refer to the same selected install. */
    public static boolean recordedInRunnerYaml(Installation selected, String yaml) {
        if (yaml == null) return false;
        String executable = WestCommands.cmakePath(selected.executable().toString());
        String scripts = WestCommands.cmakePath(selected.scripts().toString());
        return yaml.lines().map(String::trim).anyMatch(x -> x.equals("openocd: " + executable))
            && yaml.lines().map(String::trim).anyMatch(x -> x.equals("- " + scripts));
    }
}
