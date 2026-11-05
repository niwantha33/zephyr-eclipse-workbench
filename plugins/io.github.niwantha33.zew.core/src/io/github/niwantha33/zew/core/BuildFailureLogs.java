// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/** Failed attempt receipts scoped to one exact build directory, outside pristine output. */
public final class BuildFailureLogs {
    private BuildFailureLogs() {}
    public static Path directory(Path application, Path build) throws IOException {
        try {
            String identity = build.toAbsolutePath().normalize().toString();
            String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
            return application.resolve(".zew/local-build-logs").resolve(key);
        } catch (java.security.NoSuchAlgorithmException error) { throw new IOException(error); }
    }
    public static Path create(Path application, Path build) throws IOException {
        Path directory = directory(application, build);
        Files.createDirectories(directory);
        return Files.createTempFile(directory, "build-", ".log");
    }
    public static void failed(Path log, String report) throws IOException {
        Files.writeString(receipt(log), report, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
    public static String report(Path log) throws IOException {
        Path receipt = receipt(log);
        if (!Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("No regular failed-attempt receipt is available.");
        try (var input = Files.newInputStream(receipt)) {
            byte[] bytes = input.readNBytes(256 * 1024 + 1);
            if (bytes.length > 256 * 1024) throw new IOException("Failed-attempt receipt exceeds inspection limit.");
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        }
    }
    private static Path receipt(Path log) { return log.resolveSibling(log.getFileName() + ".failure.txt"); }
    public static Optional<Path> latestFailure(Path application, Path build) throws IOException {
        Path directory = directory(application, build);
        if (!Files.isDirectory(directory)) return Optional.empty();
        Path latest = null;
        long latestTime = Long.MIN_VALUE;
        try (var files = Files.newDirectoryStream(directory, "*.log")) {
            for (Path log : files) {
                if (!Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)
                        || !Files.isRegularFile(receipt(log), LinkOption.NOFOLLOW_LINKS)) continue;
                long time = Files.getLastModifiedTime(receipt(log), LinkOption.NOFOLLOW_LINKS).toMillis();
                if (time > latestTime || (time == latestTime && (latest == null || log.toString().compareTo(latest.toString()) > 0))) {
                    latest = log;
                    latestTime = time;
                }
            }
        }
        return Optional.ofNullable(latest);
    }
}
