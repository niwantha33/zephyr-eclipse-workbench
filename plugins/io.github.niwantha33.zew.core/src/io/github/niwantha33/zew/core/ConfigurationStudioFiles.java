// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Framework-independent, non-destructive configuration reader and writer.
 *
 * Only prj.conf is edited. Generated .config and zephyr.dts are authoritative
 * read-only build outputs. A configuration save is not equivalent to Kconfig
 * validation: the selected Zephyr configure must still resolve the new value.
 */
public final class ConfigurationStudioFiles {
    private ConfigurationStudioFiles() {}

    private static final int MAX_FILE = 2_000_000;
    private static final Pattern SYMBOL = Pattern.compile("[A-Z0-9_]{1,160}");
    private static final Pattern VALUE = Pattern.compile("[^\\r\\n\\u0000]{1,4096}");
    private static final Pattern ASSIGN = Pattern.compile("^\\s*CONFIG_([A-Z0-9_]+)=(.*)$");
    private static final Pattern NOT_SET = Pattern.compile("^\\s*# CONFIG_([A-Z0-9_]+) is not set\\s*$");

    public record Option(String symbol, String effective, String requested, int requestedLine) {
        public boolean unresolved() { return effective == null; }
        public boolean overridden() { return requested != null; }
    }
    public record Snapshot(List<Option> options, String devicetree, String profile, Path configFile) {
        public Snapshot { options = List.copyOf(options); }
    }
    public record Preview(Path file, String original, String updated, String fingerprint,
            String symbol, String before, String after) {
        public boolean changed() { return !original.equals(updated); }
    }

    public static Snapshot inspect(Path application, Path build, String profile) throws IOException {
        Path source = application.resolve("prj.conf");
        Map<String, String> actual = new LinkedHashMap<>();
        Path effective = build.resolve("zephyr/.config");
        if (Files.isRegularFile(effective)) {
            for (String line : bounded(effective).split("\\R")) {
                var setting = ASSIGN.matcher(line);
                var disabled = NOT_SET.matcher(line);
                if (setting.matches()) actual.put(setting.group(1), setting.group(2));
                else if (disabled.matches()) actual.put(disabled.group(1), "n");
            }
        }
        Map<String, String> wanted = new LinkedHashMap<>();
        Map<String, Integer> lines = new LinkedHashMap<>();
        if (Files.isRegularFile(source)) {
            int number = 0;
            for (String line : bounded(source).split("\\R")) {
                number++;
                var setting = ASSIGN.matcher(line);
                var disabled = NOT_SET.matcher(line);
                if (setting.matches()) {
                    wanted.put(setting.group(1), setting.group(2));
                    lines.put(setting.group(1), number);
                } else if (disabled.matches()) {
                    wanted.put(disabled.group(1), "n");
                    lines.put(disabled.group(1), number);
                }
            }
        }
        // Include both effective settings and invalid/obsolete requests so an
        // unconfigurable project can still be opened and repaired.
        Map<String, Option> options = new LinkedHashMap<>();
        for (var entry : actual.entrySet())
            options.put(entry.getKey(), new Option(entry.getKey(), entry.getValue(),
                    wanted.get(entry.getKey()), lines.getOrDefault(entry.getKey(), 0)));
        for (var entry : wanted.entrySet())
            options.putIfAbsent(entry.getKey(), new Option(entry.getKey(), null, entry.getValue(),
                    lines.getOrDefault(entry.getKey(), 0)));
        Path tree = build.resolve("zephyr/zephyr.dts");
        String dts = Files.isRegularFile(tree) ? bounded(tree) : "";
        return new Snapshot(new ArrayList<>(options.values()), dts, profile, source);
    }

    public static Preview plan(Path application, String symbol, String value) throws IOException {
        if (symbol == null || !SYMBOL.matcher(symbol).matches())
            throw new IOException("Invalid Kconfig symbol.");
        if (value != null && (!VALUE.matcher(value).matches() || !value.equals(value.strip())
                || value.indexOf('#') >= 0 && !value.startsWith("\"")))
            throw new IOException("Invalid single-line Kconfig value.");
        Path root = application.toRealPath();
        Path source = root.resolve("prj.conf");
        if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("The application prj.conf must be a regular file, not a symlink.");
        String original = bounded(source);
        String newline = original.contains("\r\n") ? "\r\n" : "\n";
        var rows = new ArrayList<>(List.of(original.split("\\R", -1)));
        int found = 0;
        String previous = null;
        for (int i = rows.size() - 1; i >= 0; i--) {
            var setting = ASSIGN.matcher(rows.get(i));
            var disabled = NOT_SET.matcher(rows.get(i));
            String name = setting.matches() ? setting.group(1) : disabled.matches() ? disabled.group(1) : null;
            if (!symbol.equals(name)) continue;
            found++;
            previous = setting.matches() ? setting.group(2) : "n";
            // Remove all redundant assignments. They are reported in the diff.
            rows.remove(i);
        }
        // Only known configuration inputs can be edited. Do not generate
        // guessed Kconfig symbols from an arbitrary user text entry.
        if (found == 0 && !knownInGeneratedConfig(root, symbol))
            throw new IOException("Symbol is not present in prj.conf or a verified generated .config: CONFIG_" + symbol);
        String updated = String.join(newline, rows);
        if (value != null) {
            if (!updated.isEmpty() && !updated.endsWith(newline)) updated += newline;
            updated += "CONFIG_" + symbol + "=" + value + newline;
        }
        return new Preview(source, original, updated, sha256(original),
                symbol, previous, value);
    }

    private static boolean knownInGeneratedConfig(Path application, String symbol) throws IOException {
        Path output = application.resolve(".zew-build");
        if (!Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) return false;
        // Conservative fallback: the UI normally edits a selected effective
        // symbol, but the writer must not trust an arbitrary symbol name.
        try (var folders = Files.list(output)) {
            for (Path dir : folders.filter(Files::isDirectory).toList()) {
                Path conf = dir.resolve("zephyr/.config");
                if (!Files.isRegularFile(conf)) continue;
                for (String line : bounded(conf).split("\\R")) {
                    var set = ASSIGN.matcher(line);
                    var disabled = NOT_SET.matcher(line);
                    if (set.matches() && symbol.equals(set.group(1))
                            || disabled.matches() && symbol.equals(disabled.group(1))) return true;
                }
            }
        }
        return false;
    }

    /** Optimistic compare-and-swap, timestamped backup, atomic replacement. */
    public static Path commit(Path application, Preview preview) throws IOException {
        Path root = application.toRealPath();
        Path source = root.resolve("prj.conf");
        if (!source.equals(preview.file()) || Files.isSymbolicLink(source))
            throw new IOException("Project configuration changed location.");
        if (!preview.changed()) return source;
        String current = bounded(source);
        if (!sha256(current).equals(preview.fingerprint()) || !current.equals(preview.original()))
            throw new IOException("prj.conf changed since preview. Reload before saving; no file was overwritten.");
        Path backups = root.resolve(".zew/config-backups");
        if (Files.isSymbolicLink(root.resolve(".zew")) || Files.isSymbolicLink(backups))
            throw new IOException("Refusing to save through a symlinked backup directory.");
        Files.createDirectories(backups);
        Path backup = backups.resolve(Instant.now().toEpochMilli() + "-" +
                preview.fingerprint().substring(0, 12) + "-prj.conf");
        Files.writeString(backup, current, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Path temporary = Files.createTempFile(root, ".zew-config-", ".tmp");
        try {
            Files.writeString(temporary, preview.updated(), StandardCharsets.UTF_8);
            if (!sha256(bounded(source)).equals(preview.fingerprint()))
                throw new IOException("prj.conf changed while saving. Reload and review again.");
            try {
                Files.move(temporary, source, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unavailable) {
                throw new IOException("Filesystem cannot atomically update prj.conf; original preserved.", unavailable);
            }
            return backup;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String bounded(Path path) throws IOException {
        if (Files.size(path) > MAX_FILE) throw new IOException("Configuration file exceeds 2 MB: " + path);
        return Files.readString(path, StandardCharsets.UTF_8);
    }
    private static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
