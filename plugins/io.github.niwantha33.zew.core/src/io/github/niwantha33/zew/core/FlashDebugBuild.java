// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * An in-memory identity for an explicit Flash and Debug operation. This binds
 * the following Debug to the same build artifacts; it is not a flash receipt
 * or evidence that programming succeeded. The caller must require Flash success.
 */
public final class FlashDebugBuild {
    private static final long ELF_LIMIT = 512L * 1024 * 1024;
    private static final long CONFIG_LIMIT = 8L * 1024 * 1024;
    private static final long RUNNERS_LIMIT = 1024L * 1024;
    private final Path directory;
    private final String board;
    private final String profile;
    private final Path elf;
    private final String elfDigest;
    private final String runnersDigest;
    private final String configDigest;

    private FlashDebugBuild(Path directory, String board, String profile, Path elf,
            String elfDigest, String runnersDigest, String configDigest) {
        this.directory = directory;
        this.board = board;
        this.profile = profile;
        this.elf = elf;
        this.elfDigest = elfDigest;
        this.runnersDigest = runnersDigest;
        this.configDigest = configDigest;
    }

    public static FlashDebugBuild capture(Path build, String board, String profile) throws IOException {
        requireLabel(board, "board");
        requireLabel(profile, "build profile");
        Path directory = build.toRealPath();
        if (!Files.isDirectory(directory)) throw new IOException("Flash and Debug requires an existing build directory.");
        Path runners = directory.resolve("zephyr/runners.yaml");
        Path config = directory.resolve("zephyr/.config");
        String runnersDigest = digest(directory, runners, RUNNERS_LIMIT);
        Path elf = BuildArtifacts.debugElf(directory);
        String elfDigest = digest(directory, elf, ELF_LIMIT);
        String configDigest = digest(directory, config, CONFIG_LIMIT);
        // The resolver reads runners.yaml separately. Do not bind an ELF selected
        // from runner metadata that changed while this snapshot was collected.
        if (!runnersDigest.equals(digest(directory, runners, RUNNERS_LIMIT)))
            throw new IOException("Runner configuration changed while preparing Flash and Debug. Retry this operation.");
        return new FlashDebugBuild(directory, board, profile, elf, elfDigest, runnersDigest, configDigest);
    }

    /** Fail before Debug if the user/build system changed the selected build since Flash started. */
    public void verify(Path build, String board, String profile) throws IOException {
        if (!directory.equals(build.toRealPath()) || !this.board.equals(board) || !this.profile.equals(profile))
            throw new IOException("Flash and Debug selection changed after Flash started. Select the same board, build and profile and retry.");
        FlashDebugBuild current = capture(build, board, profile);
        if (!elf.equals(current.elf) || !elfDigest.equals(current.elfDigest)
                || !runnersDigest.equals(current.runnersDigest) || !configDigest.equals(current.configDigest))
            throw new IOException("Build artifacts changed after Flash started; Debug was not launched. Run Flash and Debug again for the current build.");
    }

    /** Launch attributes contain hashes/identities only; no supplied paths are opened on import. */
    public java.util.Map<String, String> attributes() {
        return java.util.Map.of("directory", directory.toString(), "board", board, "profile", profile,
                "elf", elf.toString(), "elf-sha256", elfDigest, "runners-sha256", runnersDigest,
                "config-sha256", configDigest);
    }

    public static void verifyAttributes(java.util.Map<String, String> expected, Path build,
            String board, String profile) throws IOException {
        if (!capture(build, board, profile).attributes().equals(expected))
            throw new IOException("Build identity changed after Flash; Debug was not started. Use Flash and Debug for the current build.");
    }

    public Path debugElf() { return elf; }

    private static void requireLabel(String value, String name) throws IOException {
        if (value == null || value.isBlank() || value.length() > 4096 || value.chars().anyMatch(Character::isISOControl))
            throw new IOException("Invalid " + name + " for Flash and Debug.");
    }

    private static String digest(Path directory, Path file, long limit) throws IOException {
        Path real = file.toRealPath();
        if (!real.startsWith(directory)) throw new IOException("Flash and Debug artifact escapes the selected build: " + file);
        BasicFileAttributes before = Files.readAttributes(real, BasicFileAttributes.class);
        if (!before.isRegularFile() || before.size() == 0 || before.size() > limit)
            throw new IOException("Missing, empty or oversized Flash and Debug artifact: " + file);
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        long count = 0;
        try (var input = Files.newInputStream(real)) {
            byte[] buffer = new byte[64 * 1024];
            int length;
            while ((length = input.read(buffer)) != -1) {
                count += length;
                if (count > limit) throw new IOException("Flash and Debug artifact exceeded its size limit while reading: " + file);
                digest.update(buffer, 0, length);
            }
        }
        BasicFileAttributes after = Files.readAttributes(real, BasicFileAttributes.class);
        if (count != before.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || before.size() != after.size() || !Objects.equals(before.fileKey(), after.fileKey())
                || !real.equals(file.toRealPath()))
            throw new IOException("Build artifact changed while preparing Flash and Debug: " + file);
        return HexFormat.of().formatHex(digest.digest());
    }
}
