// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Read-only consumer of Zephyr's UPSTREAM traceconfig Markdown report.
 * Does not simulate Kconfig evaluation, load unsafe Python pickles, invoke
 * Ninja or claim the report came from a pristine build.
 */
public final class KconfigTraceEvidence {
    private KconfigTraceEvidence() {}

    public enum State { OBSERVED, STALE, MISSING, UNKNOWN }
    public record Result(State state, String symbol, String origin, String location,
                         String traceSha256, String detail) {
        public String describe() {
            return "CONFIG_" + symbol + " — " + state
                    + (origin.isBlank() ? "" : "\nZephyr-reported origin: " + origin)
                    + (location.isBlank() ? "" : "\nOrigin detail: " + location)
                    + "\n" + detail;
        }
    }
    private static final long LIMIT = 4L * 1024 * 1024;
    private static final String[] KINDS =
            {"assigned", "default", "selected", "implied"};

    public static Result inspect(Path build, String requestedSymbol) {
        String symbol = requestedSymbol == null ? "" : requestedSymbol.strip();
        if (symbol.startsWith("CONFIG_")) symbol = symbol.substring(7);
        if (!symbol.matches("[A-Z0-9_]{1,160}"))
            return new Result(State.UNKNOWN, "INVALID", "", "", "",
                    "Select a valid exact CONFIG_ symbol name; no source search was performed.");

        if (build == null)
            return missing(symbol, "No selected build directory.");
        Path trace = build.resolve("zephyr/kconfig-trace.md");
        Path config = build.resolve("zephyr/.config");
        if (!regular(config))
            return missing(symbol, "Generated zephyr/.config is missing. "
                    + "Reconfigure the exact board/profile first.");
        if (!regular(trace))
            return missing(symbol, "No upstream zephyr/kconfig-trace.md in this build. "
                    + "On a clean configured build, explicitly run 'west build -t traceconfig'. "
                    + "The Workbench does not run it automatically.");

        try {
            if (Files.size(trace) > LIMIT || Files.size(config) > LIMIT)
                return unknown(symbol, "Trace or effective Kconfig exceeds the safe inspection limit.");
            String raw = Files.readString(trace, StandardCharsets.UTF_8);
            String effective = Files.readString(config, StandardCharsets.UTF_8);
            String fingerprint = digest(raw);
            boolean outdated = Files.getLastModifiedTime(trace)
                    .compareTo(Files.getLastModifiedTime(config)) < 0;
            String tagged = ((char) 96) + "CONFIG_" + symbol + ((char) 96);
            for (String line : raw.split("\\R")) {
                var fields = columns(line);
                if (fields.size() < 6) continue;
                String displayed = fields.get(2);
                if (!displayed.equals(tagged) && !displayed.equals(tagged + " (h)"))
                    continue;
                String origin = fields.get(4).toLowerCase(Locale.ROOT);
                if (java.util.Arrays.stream(KINDS).noneMatch(origin::equals))
                    return unknown(symbol, "Symbol has an unfamiliar upstream trace origin.");
                String value = fields.get(3);
                String effectiveValue = effectiveValue(effective, symbol);
                if (effectiveValue == null)
                    return new Result(State.UNKNOWN, symbol, origin, fields.get(5),
                            fingerprint, "Trace symbol is not observed in the current generated .config.");
                // Boolean/tristate values can be compared directly. Do NOT
                // expose raw string/numeric values, which may contain secrets.
                if (List.of("y", "m", "n").contains(value)
                        && !value.equals(effectiveValue)) outdated = true;
                return new Result(outdated ? State.STALE : State.OBSERVED,
                        symbol, origin, bounded(fields.get(5)), fingerprint,
                        outdated ? "Reported value or trace timestamp differs from generated .config. "
                                + "Regenerate a clean build and rerun upstream traceconfig."
                            : "Observed in the selected Zephyr traceconfig report. "
                                + "Whether traceconfig was generated from a pristine build "
                                + "is NOT established; this is not a confirmed "
                                + "full origin/driver causal explanation.");
            }
            for (String line : raw.split("\\R"))
                if (line.strip().equals("# CONFIG_" + symbol + " is not set"))
                    return new Result(State.UNKNOWN, symbol, "not set", "", fingerprint,
                            "Zephyr lists this symbol as unset; it has no reported origin.");
            return new Result(State.UNKNOWN, symbol, "", "", fingerprint,
                    "Exact symbol is not present in this Zephyr trace. "
                    + "Do not infer that the symbol is unsupported from absence alone.");
        } catch (IOException failure) {
            return unknown(symbol, "Could not read a safe, complete trace from this build.");
        }
    }

    private static String effectiveValue(String text, String symbol) {
        String key = "CONFIG_" + symbol;
        for (String line : text.split("\\R")) {
            if (line.startsWith(key + "=")) return line.substring(key.length() + 1);
            if (line.equals("# " + key + " is not set")) return "n";
        }
        return null;
    }

    /** Markdown pipe cells respecting the escaped-pipe syntax in origins. */
    private static List<String> columns(String line) {
        if (!line.startsWith("|")) return List.of();
        var result = new ArrayList<String>();
        var value = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length() && line.charAt(i + 1) == '|') {
                value.append('|'); i++; continue;
            }
            if (c == '|') {
                result.add(value.toString().strip());
                value.setLength(0);
            } else value.append(c);
        }
        result.add(value.toString().strip());
        return result;
    }

    private static boolean regular(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(path);
    }

    private static String bounded(String location) {
        return location.length() <= 512 ? location : location.substring(0, 512) + "…";
    }
    private static String digest(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    private static Result missing(String symbol, String detail) {
        return new Result(State.MISSING, symbol, "", "", "", detail);
    }
    private static Result unknown(String symbol, String detail) {
        return new Result(State.UNKNOWN, symbol, "", "", "", detail);
    }
}
