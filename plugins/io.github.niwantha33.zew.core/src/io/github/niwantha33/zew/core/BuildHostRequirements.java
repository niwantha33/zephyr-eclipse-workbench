// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Detect missing host executables in *generated build commands*, not in
 * CMakeCache where optional, unused tools may legitimately be NOTFOUND.
 * No vendor table or guessed target architecture is involved.
 */
public final class BuildHostRequirements {
    private static final Pattern MISSING =
        Pattern.compile("(?<![A-Za-z0-9_])([A-Za-z][A-Za-z0-9_]*)-NOTFOUND(?![A-Za-z0-9_])");
    private static final long MAX_NINJA_BYTES = 32L * 1024L * 1024L;

    private BuildHostRequirements() {}

    /** Ninja -t commands traverses the default build graph without executing it. */
    public static Set<String> missingInCommands(String commands) throws IOException {
        if (commands.length() > MAX_NINJA_BYTES) throw new IOException("Generated commands exceed inspection limit.");
        var missing = new LinkedHashSet<String>();
        var matcher = MISSING.matcher(commands);
        while (matcher.find()) missing.add(matcher.group(1).toUpperCase(java.util.Locale.ROOT));
        return Set.copyOf(missing);
    }

    private static void inspect(Path file, Path root, Set<String> missing, Set<Path> seen) throws IOException {
        Path real = file.toRealPath();
        if (!real.startsWith(root) || seen.size() >= 64)
            throw new IOException("Unsafe or excessive generated Ninja include: " + file);
        if (!seen.add(real)) return;
        if (Files.size(real) > MAX_NINJA_BYTES) throw new IOException("Generated Ninja include exceeds inspection limit: " + file);
        for (String line : Files.readAllLines(real, StandardCharsets.UTF_8)) {
            if (line.stripLeading().startsWith("#")) continue;
            var matcher = MISSING.matcher(line);
            while (matcher.find()) missing.add(matcher.group(1).toUpperCase(java.util.Locale.ROOT));
            String stripped = line.strip();
            if (stripped.startsWith("include ") || stripped.startsWith("subninja ")) {
                String relative = stripped.substring(stripped.indexOf(' ') + 1).strip();
                if (relative.contains("$") || Path.of(relative).isAbsolute() || relative.contains(".."))
                    throw new IOException("Unsupported generated Ninja include path: " + relative);
                // Ninja resolves includes relative to its build working directory.
                inspect(root.resolve(relative), root, missing, seen);
            }
        }
    }

    public static Set<String> missingInGeneratedBuild(Path buildDirectory) throws IOException {
        Path ninja = buildDirectory.resolve("build.ninja");
        if (!Files.isRegularFile(ninja)) {
            throw new IOException("Zephyr configure-only preflight did not produce build.ninja: " + ninja);
        }
        if (Files.size(ninja) > MAX_NINJA_BYTES) {
            throw new IOException("Zephyr generated build.ninja exceeds the preflight inspection limit: " + ninja);
        }
        var missing = new LinkedHashSet<String>();
        inspect(ninja, buildDirectory.toRealPath(), missing, new LinkedHashSet<>());
        return Set.copyOf(missing);
    }
}
