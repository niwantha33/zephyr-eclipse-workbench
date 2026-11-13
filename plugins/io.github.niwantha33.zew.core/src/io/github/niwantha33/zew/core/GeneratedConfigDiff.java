// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Read-only projection and exact-symbol comparison of generated Zephyr .config files. */
public final class GeneratedConfigDiff {
    private GeneratedConfigDiff() {}

    public static final int MAX_BYTES = 2_000_000;
    private static final Pattern SET = Pattern.compile("^CONFIG_([A-Z0-9_]+)=(.*)$");
    private static final Pattern UNSET = Pattern.compile("^# CONFIG_([A-Z0-9_]+) is not set$");

    public enum Change { ADDED, CHANGED, REMOVED, UNCHANGED }
    public record Entry(String symbol, String previous, String current, Change change) {}
    public record Section(String heading, List<Entry> entries) {
        public Section { entries = List.copyOf(entries); }
    }
    public record Comparison(List<Section> sections, boolean hasBaseline,
            int added, int changed, int removed, int unchanged) {
        public Comparison { sections = List.copyOf(sections); }
        public int differences() { return added + changed + removed; }
    }
    private record Value(String value, String section) {}

    /** Bounded, no-follow reader; absence is never confused with CONFIG_X=n. */
    public static String read(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Generated configuration is not a regular file.");
        if (Files.size(file) > MAX_BYTES)
            throw new IOException("Generated configuration exceeds the 2 MB viewer limit.");
        String value = Files.readString(file, StandardCharsets.UTF_8);
        if (value.length() > MAX_BYTES)
            throw new IOException("Generated configuration exceeds the viewer limit.");
        return value;
    }

    public static Comparison compare(String previous, String current) {
        if (current == null) throw new IllegalArgumentException("Current .config is required.");
        Map<String, Value> now = parse(current);
        boolean hasBaseline = previous != null;
        Map<String, Value> before = hasBaseline ? parse(previous) : Map.of();
        Map<String, List<Entry>> sections = new LinkedHashMap<>();
        int added = 0, changed = 0, removed = 0, unchanged = 0;
        for (var setting : now.entrySet()) {
            String key = setting.getKey();
            Value after = setting.getValue();
            Value old = before.get(key);
            Change change = !hasBaseline ? Change.UNCHANGED
                    : old == null ? Change.ADDED
                    : old.value().equals(after.value()) ? Change.UNCHANGED : Change.CHANGED;
            switch (change) {
                case ADDED -> added++;
                case CHANGED -> changed++;
                case UNCHANGED -> unchanged++;
                default -> throw new IllegalStateException();
            }
            sections.computeIfAbsent(after.section(), unused -> new ArrayList<>())
                    .add(new Entry("CONFIG_" + key, old == null ? null : old.value(), after.value(), change));
        }
        for (var setting : before.entrySet()) {
            if (now.containsKey(setting.getKey())) continue;
            removed++;
            Value old = setting.getValue();
            sections.computeIfAbsent(old.section(), unused -> new ArrayList<>())
                    .add(new Entry("CONFIG_" + setting.getKey(), old.value(), null, Change.REMOVED));
        }
        var groups = new ArrayList<Section>();
        for (var section : sections.entrySet())
            groups.add(new Section(section.getKey(), section.getValue()));
        return new Comparison(groups, hasBaseline, added, changed, removed, unchanged);
    }

    private static Map<String, Value> parse(String input) {
        if (input.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException(".config exceeds the 2 MB viewer limit.");
        Map<String, Value> settings = new LinkedHashMap<>();
        String section = "Other settings";
        boolean separator = false;
        int lineNumber = 0;
        for (String line : input.split("\\R", -1)) {
            lineNumber++;
            if (line.equals("#")) { separator = true; continue; }
            var unset = UNSET.matcher(line);
            var set = SET.matcher(line);
            String name;
            String value;
            if (unset.matches()) {
                name = unset.group(1); value = "n";
            } else if (set.matches()) {
                name = set.group(1); value = set.group(2);
            } else {
                // Kconfig writes section comments between # separators. These
                // are display-only groups, NOT a reconstructed menu hierarchy.
                if (separator && line.startsWith("# ") && !line.contains("Automatically generated")) {
                    String heading = line.substring(2).trim();
                    if (!heading.isEmpty()) section = heading;
                }
                if (!line.isBlank() && !line.startsWith("#")) separator = false;
                continue;
            }
            separator = false;
            if (settings.putIfAbsent(name, new Value(value, section)) != null)
                throw new IllegalArgumentException("Duplicate CONFIG_" + name
                        + " in generated configuration (line " + lineNumber + ").");
        }
        return settings;
    }
}
