// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** A bounded migration of the original supplied demo, only after Zephyr rejects its obsolete symbol. */
public final class DemoConfigurationCompatibility {
    private static final String LEGACY_CONFIG = "3102fc893a2e354faacb1134fb91afaf5b7d288b53ee867cd81cacd0e3edc6be";
    private static final String DEMO_CMAKE = "12646ebac277c4f5963b373549f674bffb808177ea33a6a5363230bf237b5643";
    private DemoConfigurationCompatibility() { }
    public static boolean migrate(Path application, String diagnostics) throws IOException {
        if (!diagnostics.contains("undefined symbol DEBUG_INFO")) return false;
        Path config = application.resolve("prj.conf"), cmake = application.resolve("CMakeLists.txt");
        if (!Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(cmake, LinkOption.NOFOLLOW_LINKS)
                || Files.size(config) > 4096 || Files.size(cmake) > 4096) return false;
        String original = Files.readString(config);
        if (!LEGACY_CONFIG.equals(hash(original)) || !DEMO_CMAKE.equals(hash(Files.readString(cmake)))) return false;
        Path directory = application.resolve(".zew"), backup = directory.resolve("config-backup");
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(backup)) throw new IOException("Demo configuration backup is a filesystem link.");
        Files.createDirectories(backup);
        Path saved = backup.resolve("prj.conf.before-debug-info-migration");
        if (Files.isSymbolicLink(saved)) throw new IOException("Demo configuration backup is a filesystem link.");
        if (!Files.exists(saved)) Files.writeString(saved, original, StandardOpenOption.CREATE_NEW);
        else if (!Files.readString(saved).equals(original)) throw new IOException("An earlier demo configuration backup differs; source was not changed.");
        String updated = original.replaceAll("(?m)^CONFIG_DEBUG_INFO=y(?=\\r?$)",
                "# Zephyr emits debug information by default; select Debug (-Og) in Workbench.");
        Path temporary = Files.createTempFile(application, ".prj-compat-", ".tmp");
        try {
            Files.writeString(temporary, updated);
            if (!Files.readString(config).equals(original)) throw new IOException("Demo configuration changed during migration; reopen it before building.");
            Files.move(temporary, config, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        return true;
    }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
