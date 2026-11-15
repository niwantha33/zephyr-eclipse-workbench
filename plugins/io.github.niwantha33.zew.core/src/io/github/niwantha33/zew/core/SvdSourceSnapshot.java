// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * A single bounded offline SVD source snapshot. Parse and fingerprint the
 * identical bytes; a changed file must never pair one device map with the
 * digest of a different revision.
 */
public final class SvdSourceSnapshot {
    private SvdSourceSnapshot() {}
    public static final int MAX_BYTES = 32 * 1024 * 1024;

    public record Loaded(SvdDevice device, String sha256) {}

    public static Loaded read(Path file) throws Exception {
        if (file == null || Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("SVD must be a regular non-symlink file");
        final byte[] bytes;
        // NOFOLLOW_LINKS also guards a symlink substituted after the metadata check.
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length == 0 || bytes.length > MAX_BYTES)
            throw new IllegalArgumentException("SVD is empty or exceeds 32 MiB");
        SvdDevice device = SvdDevice.read(new ByteArrayInputStream(bytes));
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return new Loaded(device, sha);
    }
}
