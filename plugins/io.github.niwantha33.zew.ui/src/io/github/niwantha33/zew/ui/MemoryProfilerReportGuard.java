// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Pure-Java integrity gate for a per-build linker-map report.
 * A SHA binds the report to observed local map bytes, not target/ELF identity.
 */
public final class MemoryProfilerReportGuard {
    public static final String NAME = ".zew-memory-profile.json";
    private static final long MAX_MAP_BYTES = 64L * 1024 * 1024;
    public static final long MAX_JSON_BYTES = 8L * 1024 * 1024;

    private MemoryProfilerReportGuard() {}

    public static Path map(Path build) throws IOException {
        Path folder = build.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(folder) || Files.isSymbolicLink(folder.resolve("zephyr")))
            throw new IOException("Linked build directory or zephyr folder is not supported");
        Path map = folder.resolve("zephyr/zephyr.map");
        if (!Files.isRegularFile(map, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("No regular zephyr/zephyr.map in the selected build");
        return map;
    }

    public static Path output(Path build) {
        return build.toAbsolutePath().normalize().resolve(NAME);
    }

    public static String hashMap(Path map) throws IOException {
        if (!Files.isRegularFile(map, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Map file missing or replaced with a symlink");
        long before = Files.size(map);
        if (before > MAX_MAP_BYTES) throw new IOException("Map exceeds 64 MiB");
        MessageDigest md;
        try { md = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        long read = 0;
        try (InputStream in = Files.newInputStream(map)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) {
                read += n;
                if (read > MAX_MAP_BYTES) throw new IOException("Map exceeds 64 MiB");
                md.update(buffer, 0, n);
            }
        }
        if (read != before || Files.size(map) != before)
            throw new IOException("Map changed during hash read");
        return HexFormat.of().formatHex(md.digest());
    }

    public static void verifyReport(String json, String expectedMapSha) throws IOException {
        try {
            var diagram = MemoryProfilerLayout.fromRows(MemoryProfilerJson.parse(json));
            if (!diagram.mapDigest().equalsIgnoreCase(expectedMapSha))
                throw new IOException("JSON report does not match selected linker map");
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid memory profiler JSON: " + ex.getMessage(), ex);
        }
    }

    /**
     * Reject changed map bytes and refuse symlink destinations. The temporary
     * file is created by our caller within the same managed build directory.
     */
    public static void publish(Path temporary, Path destination, Path map,
                               String observedMapSha) throws IOException {
        if (!Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)
                || Files.size(temporary) > MAX_JSON_BYTES)
            throw new IOException("Missing or oversized generated JSON report");
        if (Files.isSymbolicLink(destination))
            throw new IOException("Refusing to replace symlinked profiler report");
        String json = Files.readString(temporary, java.nio.charset.StandardCharsets.UTF_8);
        verifyReport(json, observedMapSha);
        if (!hashMap(map).equalsIgnoreCase(observedMapSha))
            throw new IOException("Build's linker map changed during profiler capture; report not published");
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
