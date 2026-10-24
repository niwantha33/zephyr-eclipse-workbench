// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Commands are argument vectors, never shell command strings. */
public final class WestCommands {
    private WestCommands() {}

    /** CMake macros reparse Windows backslashes as escapes. */
    public static String cmakePath(String path) {
        return path.replace('\\', '/');
    }

    public static List<String> west(WorkbenchConfig config, String... arguments) {
        var command = new ArrayList<String>();
        command.add(config.python().toString());
        command.add("-m");
        command.add("west");
        command.addAll(List.of(arguments));
        return List.copyOf(command);
    }

    public static Path buildDirectory(WorkbenchConfig config) {
        try {
            var bytes = MessageDigest.getInstance("SHA-256").digest(config.board().getBytes(StandardCharsets.UTF_8));
            var key = HexFormat.of().formatHex(bytes).substring(0, 16);
            return config.application().resolve(".zew-build").resolve(key);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static List<String> build(WorkbenchConfig config, boolean pristine) {
        var args = new ArrayList<String>(List.of("build", "--build-dir", buildDirectory(config).toString(),
                "--board", config.board()));
        if (pristine) args.addAll(List.of("--pristine", "always"));
        args.add(config.application().toString());
        return west(config, args.toArray(String[]::new));
    }

    public static List<String> boards(WorkbenchConfig config) {
        return west(config, "boards", "--format", "{name}");
    }

    /** Lists board names only. Exact core/variant targets remain an explicit setting in this preview. */
    public static List<String> parseBoards(String output) {
        return output.lines().map(String::trim)
                .filter(s -> s.matches("[A-Za-z0-9][A-Za-z0-9_\\-]*"))
                .distinct().sorted().toList();
    }

    public static Path parseZephyrPath(String output) {
        var lines = output.lines().map(String::trim).filter(s -> !s.isBlank()).toList();
        if (lines.size() != 1) throw new IllegalArgumentException("West returned an unexpected Zephyr project path.");
        return Path.of(lines.getFirst()).toAbsolutePath().normalize();
    }
}

