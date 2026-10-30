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
            "generated/zephyr.dts", "build/build-identity.txt", "diagnosis.txt");
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
            if (Files.isRegularFile(build.resolve("zephyr/.config"), LinkOption.NOFOLLOW_LINKS))
                entries.put("build/build-identity.txt", buildIdentity(build).getBytes(StandardCharsets.UTF_8));
        }
        entries.put("diagnosis.txt", ConfigurationDiagnosis.inspect(app, build, null).display().getBytes(StandardCharsets.UTF_8));
        return new Preview(entries);
    }
    /**
     * Export only allowlisted identity fields and a bounded ELF digest.
     * A build receipt and ELF hash identify snapshots, not authenticity or
     * physical flashing. Never export local SDK paths or the ELF itself.
     */
    private static String buildIdentity(Path build) throws IOException {
        var cfg = BuildArtifacts.configuration(build);
        StringBuilder out = new StringBuilder("Zephyr build evidence (not a hardware-verification certificate)\n");
        String target = cfg.getOrDefault("CONFIG_BOARD_TARGET", "(not reported)").replace("\"", "");
        String soc = cfg.getOrDefault("CONFIG_SOC", "(not reported)").replace("\"", "");
        out.append("CONFIG_BOARD_TARGET: ").append(target).append('\n')
                .append("CONFIG_SOC: ").append(soc).append('\n');
        for (String arch : List.of("ARM", "ARM64", "RISCV", "XTENSA", "X86", "ARC"))
            if ("y".equals(cfg.get("CONFIG_" + arch)))
                out.append("Architecture flag: CONFIG_").append(arch).append("=y\n");
        Path receipt = build.resolve(".zew-built-target");
        if (Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS) && Files.size(receipt) <= 256) {
            String recorded = Files.readString(receipt, StandardCharsets.UTF_8).strip();
            // No command line or filesystem paths are emitted in the package.
            if (recorded.matches("[A-Za-z0-9_][A-Za-z0-9_.@/+-]{0,255}")) {
                out.append("Recorded target: ").append(recorded).append('\n');
                out.append("Target agreement: ").append("(not reported)".equals(target)
                        ? "unverified (generated Kconfig target missing)"
                        : recorded.equals(target) ? "matches generated config" : "MISMATCH").append('\n');
            } else out.append("Recorded target: unavailable or invalid\n");
        } else out.append("Recorded target: unavailable; build identity unverified\n");
        String kernel = cfg.getOrDefault("CONFIG_KERNEL_BIN_NAME", "\"zephyr\"");
        if (kernel.length() >= 2 && kernel.startsWith("\"") && kernel.endsWith("\""))
            kernel = kernel.substring(1, kernel.length() - 1);
        if (!kernel.matches("[A-Za-z0-9_.-]+") || kernel.equals(".") || kernel.equals("..")) {
            out.append("ELF identity: invalid kernel output name in generated Kconfig\n");
            return out.toString();
        }
        Path elf = build.resolve("zephyr").resolve(kernel + ".elf");
        if (!Files.isRegularFile(elf, LinkOption.NOFOLLOW_LINKS)) {
            out.append("ELF identity: not available\n");
            return out.toString();
        }
        if (!elf.toRealPath().startsWith(build.toRealPath()))
            throw new IOException("ELF identity file escapes the selected build.");
        long size = Files.size(elf);
        if (size < 4 || size > 128L * 1024 * 1024) {
            out.append("ELF identity: unavailable (empty or exceeds 128 MiB)\n");
            return out.toString();
        }
        try (var input = Files.newInputStream(elf)) {
            if (!java.util.Arrays.equals(input.readNBytes(4), new byte[] {0x7f, 'E', 'L', 'F'})) {
                out.append("ELF identity: invalid ELF magic\n");
                return out.toString();
            }
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(elf)) {
                byte[] block = new byte[64 * 1024];
                for (int n; (n = input.read(block)) != -1;) digest.update(block, 0, n);
            }
            out.append("ELF file: ").append(elf.getFileName()).append('\n')
                    .append("ELF size: ").append(size).append(" bytes\n")
                    .append("ELF SHA-256: ").append(HexFormat.of().formatHex(digest.digest())).append('\n');
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        return out.toString();
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
