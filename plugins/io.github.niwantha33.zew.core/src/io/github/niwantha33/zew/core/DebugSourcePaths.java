// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

/** Source substitutions for proven filesystem aliases, never guessed remote paths. */
public final class DebugSourcePaths {
    private DebugSourcePaths() {}
    public record Mapping(String compilationPath, String localPath) {}

    public static List<Mapping> inspect(Path build, Path application, Path zephyr) throws IOException {
        Path cache = build.resolve("CMakeCache.txt");
        if (!Files.isRegularFile(cache)) return List.of();
        if (Files.size(cache) > 8L * 1024 * 1024) throw new IOException("CMake source mapping cache exceeds 8 MiB.");
        var result = new LinkedHashMap<String, Mapping>();
        for (String line : Files.readAllLines(cache)) {
            int type = line.indexOf(':'), equals = line.indexOf('=');
            if (type < 0 || equals < type) continue;
            String key = line.substring(0, type);
            Path expected = switch (key) {
                case "APPLICATION_SOURCE_DIR", "CMAKE_HOME_DIRECTORY" -> application;
                case "ZEPHYR_BASE" -> zephyr;
                default -> null;
            };
            if (expected == null || !Files.isDirectory(expected)) continue;
            String original = line.substring(equals + 1);
            if (original.isBlank() || original.indexOf('\0') >= 0 || original.indexOf('\r') >= 0 || original.indexOf('\n') >= 0) continue;
            final Path compiled;
            try { compiled = Path.of(original); }
            catch (java.nio.file.InvalidPathException invalid) { continue; }
            if (!compiled.isAbsolute() || !Files.isDirectory(compiled)) continue;
            Path local = expected.toRealPath();
            if (!Files.isSameFile(compiled, local)) continue;
            String from = original.replace('\\', '/');
            String to = local.toString().replace('\\', '/');
            if (!from.equals(to)) result.putIfAbsent(from, new Mapping(from, to));
        }
        return List.copyOf(result.values());
    }
}
