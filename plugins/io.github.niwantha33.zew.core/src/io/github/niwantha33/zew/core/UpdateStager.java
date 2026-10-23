// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Downloads immutable files into a private new directory and verifies every byte before p2 sees it. */
public final class UpdateStager {
    @FunctionalInterface public interface Fetch {
        byte[] get(ReleaseManifest.Artifact artifact) throws IOException, InterruptedException;
    }
    public Path stage(ReleaseManifest release, Path cache, Fetch fetch, BooleanSupplier cancelled,
            Consumer<String> progress) throws IOException, InterruptedException {
        Files.createDirectories(cache);
        Path staging = Files.createTempDirectory(cache, "download-");
        boolean complete = false;
        try {
            for (var artifact : release.artifacts()) {
                if (cancelled.getAsBoolean()) throw new InterruptedIOException("Update cancelled.");
                progress.accept("Downloading and verifying " + artifact.path());
                byte[] bytes = fetch.get(artifact);
                if (bytes.length != artifact.size() || !sha256(bytes).equals(artifact.sha256())) throw new IOException("Integrity check failed for " + artifact.path() + ". Nothing was installed.");
                Path destination = staging.resolve(artifact.path()).normalize();
                if (!destination.startsWith(staging)) throw new IOException("Unsafe update destination.");
                Files.createDirectories(destination.getParent());
                Files.write(destination, bytes);
            }
            if (cancelled.getAsBoolean()) throw new InterruptedIOException("Update cancelled.");
            Path ready = cache.resolve("ready-" + release.version() + "-" + java.util.UUID.randomUUID());
            Files.move(staging, ready, StandardCopyOption.ATOMIC_MOVE);
            complete = true;
            return ready;
        } finally {
            if (!complete && Files.exists(staging)) {
                try (var files = Files.walk(staging)) {
                    for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
                }
            }
        }
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
