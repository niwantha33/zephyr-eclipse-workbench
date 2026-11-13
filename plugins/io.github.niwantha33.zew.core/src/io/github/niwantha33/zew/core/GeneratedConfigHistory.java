// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Build-local comparison baseline. Never modifies Zephyr's generated .config. */
public final class GeneratedConfigHistory {
    private GeneratedConfigHistory() {}

    public static Path baseline(Path build) { return build.resolve("zephyr/.config.previous"); }

    /** Capture before west configure/pristine can replace the generated file. */
    public static String capture(Path build) {
        if (build == null) return null;
        try { return GeneratedConfigDiff.read(build.resolve("zephyr/.config")); }
        catch (IOException unavailable) { return null; }
    }

    /** Call only after a successful, verified build; never on failed/cancelled/clean operations. */
    public static void commit(Path build, String previous) throws IOException {
        Path destination = baseline(build);
        if (previous == null) {
            Files.deleteIfExists(destination);
            return;
        }
        if (previous.getBytes(StandardCharsets.UTF_8).length > GeneratedConfigDiff.MAX_BYTES)
            throw new IOException("Previous configuration exceeds history limit.");
        Path parent = destination.getParent();
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Verified build's Zephyr directory is unavailable.");
        Path temporary = Files.createTempFile(parent, ".zew-config-history-", ".tmp");
        try {
            Files.writeString(temporary, previous, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
