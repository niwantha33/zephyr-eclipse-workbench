// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/** Detect tool bindings from a previous Windows setup before reusing its CMake cache. */
public final class ManagedBuildCache {
    private static final Map<String, String> TOOLS = Map.of(
            "CMAKE_COMMAND", "tools.cmake",
            "CMAKE_MAKE_PROGRAM", "tools.ninja",
            "Python3_EXECUTABLE", "python",
            "_Python3_EXECUTABLE", "python");

    private ManagedBuildCache() {}

    public static void verify(Path build, Properties settings) throws IOException {
        Path cache = build.resolve("CMakeCache.txt");
        if (!Files.isRegularFile(cache)) return;
        if (Files.isSymbolicLink(cache) || Files.size(cache) > 8 * 1024 * 1024)
            throw new IOException("Cannot verify the managed CMake cache. Use Zephyr > Pristine Rebuild.");
        try (var lines = Files.newBufferedReader(cache)) {
            for (String line; (line = lines.readLine()) != null;) {
                int type = line.indexOf(':');
                if (type < 1) continue;
                String name = line.substring(0, type);
                String property = TOOLS.get(name);
                if (property == null) continue;
                int value = line.indexOf('=', type + 1);
                if (value < 0 || !matches(line.substring(value + 1), settings.getProperty(property)))
                    throw new IOException("CMake cache tool " + name + " differs from the selected managed tool ("
                            + settings.getProperty(property) + "). Use Zephyr > Pristine Rebuild for this build profile.");
            }
        }
    }

    private static boolean matches(String value, String selected) throws IOException {
        final Path observed;
        try { observed = Path.of(value); }
        catch (java.nio.file.InvalidPathException invalid) { return false; }
        if (!observed.isAbsolute() || !Files.isRegularFile(observed)) return false;
        Path expected = Path.of(selected);
        return Files.isRegularFile(expected) && Files.isSameFile(observed, expected);
    }
}
