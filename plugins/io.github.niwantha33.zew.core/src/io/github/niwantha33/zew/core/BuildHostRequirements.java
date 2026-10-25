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
        Pattern.compile("(?<![A-Za-z0-9_])([A-Z][A-Z0-9_]*)-NOTFOUND(?![A-Za-z0-9_])");
    private static final long MAX_NINJA_BYTES = 32L * 1024L * 1024L;

    private BuildHostRequirements() {}

    public static Set<String> missingInGeneratedBuild(Path buildDirectory) throws IOException {
        Path ninja = buildDirectory.resolve("build.ninja");
        if (!Files.isRegularFile(ninja)) {
            throw new IOException("Zephyr configure-only preflight did not produce build.ninja: " + ninja);
        }
        if (Files.size(ninja) > MAX_NINJA_BYTES) {
            throw new IOException("Zephyr generated build.ninja exceeds the preflight inspection limit: " + ninja);
        }
        var matcher = MISSING.matcher(Files.readString(ninja, StandardCharsets.UTF_8));
        var missing = new LinkedHashSet<String>();
        while (matcher.find()) missing.add(matcher.group(1));
        return Set.copyOf(missing);
    }
}
