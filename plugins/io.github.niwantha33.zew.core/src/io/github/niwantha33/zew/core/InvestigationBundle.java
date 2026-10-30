// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Explicit configuration evidence export; never recursively collects a project or extracts archives. */
public final class InvestigationBundle {
    private InvestigationBundle() {}
    private static final int LIMIT = 2_000_000;
    private static final Set<String> NAMES = Set.of("source/prj.conf", "generated/.config",
            "generated/zephyr.dts", "diagnosis.txt");
    public record Preview(Map<String, byte[]> entries) {
        public Preview { entries = copy(entries); }
        @Override public Map<String, byte[]> entries() { return copy(entries); }
        public String display() {
            StringBuilder text = new StringBuilder("Configuration evidence package\n");
            entries.forEach((name, bytes) -> text.append(name).append(" — ").append(bytes.length).append(" bytes\n"));
            return text.append("\nIncludes configuration values and diagnostic paths. Review them for sensitive information.\n")
                    .append("No source code, ELF, credentials, logs or directory trees are automatically collected.\n")
                    .append("Configuration evidence does not prove runtime or electrical correctness.\n").toString();
        }
    }
    private static Map<String, byte[]> copy(Map<String, byte[]> input) {
        Map<String, byte[]> result = new TreeMap<>();
        input.forEach((key, value) -> result.put(key, value.clone()));
        return Collections.unmodifiableMap(result);
    }
    public static Preview collect(Path app, Path build) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        add(entries, "source/prj.conf", app.resolve("prj.conf"));
        if (build != null) {
            add(entries, "generated/.config", build.resolve("zephyr/.config"));
            add(entries, "generated/zephyr.dts", build.resolve("zephyr/zephyr.dts"));
        }
        entries.put("diagnosis.txt", ConfigurationDiagnosis.inspect(app, build, null).display().getBytes(StandardCharsets.UTF_8));
        return new Preview(entries);
    }
    private static void add(Map<String, byte[]> entries, String name, Path file) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular evidence file: " + file);
        try (var stream = Files.newInputStream(file)) {
            byte[] bytes = stream.readNBytes(LIMIT + 1);
            if (bytes.length > LIMIT) throw new IOException("Evidence file exceeds 2 MB: " + file);
            entries.put(name, bytes);
        }
    }
    public static void write(Path destination, Preview preview) throws IOException {
        // CREATE_NEW preserves existing exports and all source files.
        boolean created = false;
        try (var raw = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
            created = true;
            try (var zip = new ZipOutputStream(raw, StandardCharsets.UTF_8)) {
                Properties manifest = new Properties();
                manifest.setProperty("format", "zew-configuration-evidence-1");
                manifest.setProperty("captured", java.time.Instant.now().toString());
                for (var entry : preview.entries().entrySet()) {
                    if (!NAMES.contains(entry.getKey()) || entry.getValue().length > LIMIT)
                        throw new IOException("Unsupported evidence entry: " + entry.getKey());
                    put(zip, entry.getKey(), entry.getValue());
                    manifest.setProperty("sha256." + entry.getKey(), hash(entry.getValue()));
                }
                var bytes = new java.io.ByteArrayOutputStream();
                manifest.store(bytes, "Captured configuration evidence; hashes detect corruption, not authenticity");
                put(zip, "manifest.properties", bytes.toByteArray());
            }
        } catch (IOException error) {
            if (created) Files.deleteIfExists(destination);
            throw error;
        }
    }
    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry();
    }
    public static Preview read(Path archive) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        byte[] metadata = null;
        try (var zip = new ZipInputStream(Files.newInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if ((!NAMES.contains(name) && !name.equals("manifest.properties")) || entry.isDirectory())
                    throw new IOException("Unsupported archive entry: " + name);
                if (entries.containsKey(name) || name.equals("manifest.properties") && metadata != null)
                    throw new IOException("Duplicate archive entry: " + name);
                byte[] bytes = zip.readNBytes(LIMIT + 1);
                if (bytes.length > LIMIT) throw new IOException("Archive entry exceeds 2 MB: " + name);
                if (name.equals("manifest.properties")) metadata = bytes;
                else entries.put(name, bytes);
            }
        }
        if (metadata == null || !entries.containsKey("diagnosis.txt")) throw new IOException("Missing evidence manifest or diagnosis.");
        Properties manifest = new Properties(); manifest.load(new java.io.ByteArrayInputStream(metadata));
        if (!"zew-configuration-evidence-1".equals(manifest.getProperty("format"))) throw new IOException("Unsupported evidence version.");
        for (var entry : entries.entrySet())
            if (!hash(entry.getValue()).equals(manifest.getProperty("sha256." + entry.getKey())))
                throw new IOException("Evidence checksum mismatch: " + entry.getKey());
        for (String key : manifest.stringPropertyNames())
            if (key.startsWith("sha256.") && !entries.containsKey(key.substring(7))) throw new IOException("Missing manifest entry: " + key);
        return new Preview(entries);
    }
    public static String inspect(Preview preview) {
        StringBuilder result = new StringBuilder(preview.display());
        preview.entries().forEach((name, bytes) -> result.append("\n--- ").append(name).append(" ---\n")
                .append(new String(bytes, StandardCharsets.UTF_8)));
        return result.toString();
    }
    public static String compare(Preview before, Preview after) {
        StringBuilder result = new StringBuilder("CONFIGURATION EVIDENCE COMPARISON\nFirst package → second package. Differences do not establish causation.\n");
        Set<String> names = new TreeSet<>(before.entries().keySet()); names.addAll(after.entries().keySet());
        for (String name : names) {
            byte[] a = before.entries().get(name), b = after.entries().get(name);
            if (Arrays.equals(a, b)) continue;
            result.append("\n").append(name).append(": ");
            if (a == null || b == null) { result.append(a == null ? "added" : "removed").append('\n'); continue; }
            if (name.endsWith(".config") || name.endsWith("prj.conf")) {
                Map<String, String> old = settings(a), current = settings(b);
                Set<String> symbols = new TreeSet<>(old.keySet()); symbols.addAll(current.keySet());
                int differences = 0;
                for (String symbol : symbols) if (!Objects.equals(old.get(symbol), current.get(symbol))) {
                    differences++;
                    if (differences <= 200) result.append('\n').append(symbol).append(": ").append(old.getOrDefault(symbol, "<absent>"))
                            .append(" → ").append(current.getOrDefault(symbol, "<absent>"));
                }
                result.append("\n").append(differences).append(" setting differences (first 200 shown).\n");
            } else result.append("content changed; inspect both packages for details.\n");
        }
        return result.toString();
    }
    private static Map<String, String> settings(byte[] bytes) {
        Map<String, String> values = new TreeMap<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
            var set = java.util.regex.Pattern.compile("^\\s*(CONFIG_[A-Z0-9_]+)=(.*)$").matcher(line);
            var unset = java.util.regex.Pattern.compile("^\\s*# (CONFIG_[A-Z0-9_]+) is not set\\s*$").matcher(line);
            if (set.matches()) values.put(set.group(1), set.group(2));
            else if (unset.matches()) values.put(unset.group(1), "n");
        }
        return values;
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
