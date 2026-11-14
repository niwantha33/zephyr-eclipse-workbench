// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Validate a previously chosen SVD before reusing it. The absolute path and
 * digest are stored by the Eclipse UI (not in a project's tracked sources).
 * A family wildcard or a changed file never silently enables MCU register reads.
 */
public final class SvdPreferredSource {
    private SvdPreferredSource() {}

    public record Verified(Path path, String sha256, String model, int registers) {}

    public static Verified inspect(String qualifiedBoard, Path file) throws Exception {
        String part = SvdAutoDiscovery.partFromBoard(qualifiedBoard);
        if (part.isBlank() || file == null || Files.isSymbolicLink(file)
                || !Files.isRegularFile(file) || Files.size(file) == 0
                || Files.size(file) > SvdAutoDiscovery.MAX_SVD_BYTES) return null;
        byte[] bytes = Files.readAllBytes(file);
        var model = SvdDevice.read(new java.io.ByteArrayInputStream(bytes));
        if (!SvdPartCompatibility.matches(part, model.name())) return null;
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return new Verified(file.toAbsolutePath().normalize(), sha,
                model.name(), model.registers().size());
    }

    /** Never trust a stale, modified, missing or differently named device source. */
    public static Verified reopen(String qualifiedBoard, Path file, String expectedSha256) {
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-fA-F]{64}"))
            return null;
        try {
            Verified valid = inspect(qualifiedBoard, file);
            return valid != null && valid.sha256().equalsIgnoreCase(expectedSha256)
                    ? valid : null;
        } catch (IOException | RuntimeException malformed) {
            return null;
        } catch (Exception invalid) {
            return null;
        }
    }
}
