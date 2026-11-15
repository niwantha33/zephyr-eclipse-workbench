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

    /**
     * Effective-value check against ONLY the generated zephyr/.config, not
     * prj.conf or Kconfig's unproven requested values. This verifies selected
     * values, NOT build freshness: combine with a CURRENT
     * ConfigurationEvidenceReceipt and matching ELF before any use as a gate.
     */
    public record EffectiveReceipt(State state, String configSha256,
                                   List<String> diagnostics) {
        public EffectiveReceipt { diagnostics = List.copyOf(diagnostics); }
        public boolean matched() { return state == State.OBSERVED; }
    }

    public static EffectiveReceipt inspectGenerated(Path build, java.util.Map<String, String> expected) {
        if (expected == null || expected.isEmpty() || expected.size() > 64)
            return new EffectiveReceipt(State.UNKNOWN, "", List.of("No bounded set of exact symbols was provided."));
        for (var entry : expected.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().matches("[A-Z][A-Z0-9_]{0,159}")
                    || entry.getValue() == null || !entry.getValue().matches("(?:y|m|n|[0-9]{1,8})"))
                return new EffectiveReceipt(State.UNKNOWN, "", List.of("Invalid requested Kconfig symbol/value."));
        }
        if (build == null || Files.isSymbolicLink(build)
                || Files.isSymbolicLink(build.resolve("zephyr")))
            return new EffectiveReceipt(State.MISSING, "", List.of("Selected build path is absent or unsafe."));
        Path config = build.resolve("zephyr/.config");
        if (!regular(config))
            return new EffectiveReceipt(State.MISSING, "", List.of("Generated zephyr/.config is missing."));
        try {
            long beforeSize = Files.size(config);
            var beforeTime = Files.getLastModifiedTime(config);
            if (beforeSize == 0 || beforeSize > LIMIT)
                return new EffectiveReceipt(State.UNKNOWN, "", List.of("Generated .config is empty or oversized."));
            String raw = Files.readString(config, StandardCharsets.UTF_8);
            if (Files.size(config) != beforeSize
                    || !Files.getLastModifiedTime(config).equals(beforeTime)
                    || Files.isSymbolicLink(config))
                return new EffectiveReceipt(State.UNKNOWN, "", List.of("Generated .config changed during inspection."));
            var found = new java.util.HashMap<String, String>();
            var duplicates = new java.util.HashSet<String>();
            for (String line : raw.split("\\R")) {
                String key = null;
                String value = null;
                if (line.startsWith("CONFIG_")) {
                    int eq = line.indexOf('=');
                    if (eq > 7) {
                        key = line.substring(7, eq);
                        value = line.substring(eq + 1);
                    }
                } else if (line.startsWith("# CONFIG_") && line.endsWith(" is not set")) {
                    key = line.substring(9, line.length() - " is not set".length());
                    value = "n";
                }
                if (key != null && expected.containsKey(key) && found.put(key, value) != null)
                    duplicates.add(key);
            }
            var issues = new ArrayList<String>();
            for (var entry : expected.entrySet()) {
                String key = entry.getKey();
                if (duplicates.contains(key))
                    issues.add("CONFIG_" + key + " occurs more than once.");
                else if (!found.containsKey(key))
                    issues.add("CONFIG_" + key + " is absent from generated .config.");
                else if (!entry.getValue().equals(found.get(key)))
                    issues.add("CONFIG_" + key + " does not equal the requested effective value.");
            }
            return new EffectiveReceipt(issues.isEmpty() ? State.OBSERVED : State.UNKNOWN,
                    digest(raw), issues.isEmpty()
                            ? List.of("Requested values observed in generated .config; "
                                + "not proof of build freshness, flashed identity, or transport safety.")
                            : issues);
        } catch (IOException | SecurityException failed) {
            return new EffectiveReceipt(State.UNKNOWN, "", List.of("Generated .config could not be inspected safely."));
        }
    }
}
