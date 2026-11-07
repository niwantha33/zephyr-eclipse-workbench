// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only presentation model for Zephyr HWMv2 board discovery.
 *
 * Consumes the SEVEN-column output of the existing hardware_catalog.py helper,
 * which delegates board/SoC selection to the selected Zephyr checkout.
 * It does not maintain an independent board database or make build, driver,
 * physical-probe, silicon-package or vendor-certification claims.
 */
public final class EmbeddedHubBoardCatalogue {
    private static final int MAX_ROWS = 40_000;
    private static final int MAX_FIELD_LENGTH = 256;
    private static final String TARGET_PATTERN = "[A-Za-z0-9_][A-Za-z0-9_.@/+-]{0,255}";

    private EmbeddedHubBoardCatalogue() {}

    public record Entry(String chipVendor, String boardManufacturer, String soc,
                        String board, String revision, String qualifier, String exactTarget) {
        public Entry {
            for (String value : List.of(chipVendor, boardManufacturer, soc, board,
                    revision, qualifier, exactTarget)) {
                if (value.isBlank() || value.length() > MAX_FIELD_LENGTH || hasControl(value))
                    throw new IllegalArgumentException("Unsafe board catalogue metadata");
            }
            String derived = board + ("default".equals(revision) ? "" : "@" + revision)
                    + ("-".equals(qualifier) ? "" : "/" + qualifier);
            if (!exactTarget.equals(derived) || !exactTarget.matches(TARGET_PATTERN)
                    || exactTarget.contains("..") || exactTarget.contains("//")
                    || exactTarget.endsWith("/"))
                throw new IllegalArgumentException("Inconsistent exact Zephyr target");
        }

        public String label() {
            return board + " — " + soc + " (" + exactTarget + ")";
        }
    }

    private static boolean hasControl(String s) {
        for (int i = 0; i < s.length(); i++)
            if (Character.isISOControl(s.charAt(i))) return true;
        return false;
    }

    public static Entry parseLine(String line) throws IOException {
        if (line == null || line.length() > 2048)
            throw new IOException("Board catalogue row exceeds limit");
        String[] fields = line.split("\t", -1);
        if (fields.length != 7) throw new IOException("Expected seven upstream HWMv2 columns");
        try {
            return new Entry(fields[0], fields[1], fields[2], fields[3],
                    fields[4], fields[5], fields[6]);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid selected-checkout hardware catalogue row", error);
        }
    }

    public static List<Entry> parseLines(Iterable<String> lines) throws IOException {
        Objects.requireNonNull(lines, "lines");
        List<Entry> result = new ArrayList<>();
        for (String line : lines) {
            if (result.size() >= MAX_ROWS) throw new IOException("Board catalogue exceeds safe row count");
            result.add(parseLine(line));
        }
        return List.copyOf(result);
    }

    public static List<Entry> filter(List<Entry> entries, String query) {
        Objects.requireNonNull(entries, "entries");
        String term = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (term.isEmpty()) return List.copyOf(entries);
        return entries.stream().filter(entry -> List.of(entry.chipVendor(),
                entry.boardManufacturer(), entry.soc(), entry.board(), entry.revision(),
                entry.qualifier(), entry.exactTarget()).stream()
                .anyMatch(s -> s.toLowerCase(Locale.ROOT).contains(term))).toList();
    }

    public static Optional<Entry> exact(List<Entry> entries, String target) {
        if (target == null || target.isBlank()) return Optional.empty();
        return entries.stream().filter(entry -> entry.exactTarget().equals(target)).findFirst();
    }

    /** No hardware state, CI success or physical compatibility is inferred. */
    public static String statusFor(Entry entry, String selectedTarget) {
        if (entry.exactTarget().equals(selectedTarget))
            return "Selected project target; build/flash/debug not verified here";
        return "Discovered in selected checkout; compatibility not verified";
    }
}
