// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fail-closed, read-only ESP32 flash preflight. Never infer the target chip
 * from a USB VID/PID, COM number, image filename, or an old project preference.
 * The build selects the expected SoC; the ROM detection selects the actual SoC.
 */
public final class EspressifFlashSafety {
    private static final Pattern FLASH_RUNNER =
            Pattern.compile("(?m)^\\s*flash-runner:\\s*[\"']?([A-Za-z0-9_.+-]+)[\"']?\\s*$");
    private static final Pattern DETECTED =
            Pattern.compile("(?im)^(?:\\s*Connected to|\\s*Chip type:)\\s+(ESP32(?:-[A-Z0-9]+)?)(?:\\b|\\s).*");
    private static final Pattern WIN_PORT = Pattern.compile("(?i)COM[1-9][0-9]{0,3}");
    private static final Pattern UNIX_PORT = Pattern.compile("/dev/(?:tty[A-Za-z0-9_.-]+|cu\\.[A-Za-z0-9_.-]+)");

    private EspressifFlashSafety() {}

    /** Only ESP32's serial flash runner is eligible for this preflight. */
    public static boolean isEsp32Runner(Path build) throws IOException {
        Path path = build.resolve("zephyr/runners.yaml");
        if (!Files.isRegularFile(path) || Files.size(path) > 1_048_576)
            throw new IOException("Missing or oversized Zephyr runner configuration.");
        Matcher match = FLASH_RUNNER.matcher(Files.readString(path));
        if (!match.find()) throw new IOException("Missing Zephyr default flash runner.");
        return match.group(1).equalsIgnoreCase("esp32");
    }

    /** Match the board's SoC qualifier, never a board-vendor guess. */
    public static String expectedChip(String exactBoard) throws IOException {
        if (exactBoard == null || exactBoard.isBlank())
            throw new IOException("Missing exact board/SoC qualifier.");
        String[] parts = exactBoard.toLowerCase(Locale.ROOT).split("/");
        String soc = parts.length >= 2 ? parts[1] : parts[0];
        soc = soc.split("@", 2)[0];
        String token = soc.startsWith("esp32") ? soc : parts[0].split("_", 2)[0];
        if (!token.matches("esp32(?:s[23]|c[2356]|c61|h2|p4)?"))
            throw new IOException("Cannot establish ESP32 chip family from exact board " + exactBoard
                + "; select a board with an explicit SoC qualifier before flashing.");
        return token.toUpperCase(Locale.ROOT).replaceFirst("^ESP32(?=.)", "ESP32-");
    }

    public static String normalizeDetectedChip(String probeOutput) throws IOException {
        Matcher match = DETECTED.matcher(probeOutput);
        String chip = null;
        while (match.find()) {
            String found = match.group(1).toUpperCase(Locale.ROOT);
            if (chip != null && !chip.equals(found))
                throw new IOException("Conflicting ESP chip identities in serial probe output.");
            chip = found;
        }
        if (chip == null)
            throw new IOException("The selected serial port did not produce a verified ESP32 chip identity.");
        return chip;
    }

    /** Must run only after the chosen port has completed a read-only probe. */
    public static void requireMatch(String board, String probeOutput) throws IOException {
        String expected = expectedChip(board);
        String connected = normalizeDetectedChip(probeOutput);
        if (!expected.equals(connected))
            throw new IOException("Flash blocked: project builds for " + expected
                + " but the connected board reports " + connected
                + ". Select the actual board/CPU in Project Properties > Zephyr and rebuild."
                + " Firmware was NOT written. Never use esptool --force.");
    }

    /** Validate before passing a port beyond West's '--' runner boundary. */
    public static String checkedPort(String selected) throws IOException {
        if (selected == null || !(WIN_PORT.matcher(selected).matches() || UNIX_PORT.matcher(selected).matches()))
            throw new IOException("Invalid explicit serial port. Select a detected COM or tty device.");
        return selected;
    }
}
