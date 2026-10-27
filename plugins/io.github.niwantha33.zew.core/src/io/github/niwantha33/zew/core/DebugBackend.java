// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.util.regex.Pattern;

/** Native GDB transports with explicit West runner contracts. */
public final class DebugBackend {
    private DebugBackend() {}
    public static String select(String yaml, String requested) throws IOException {
        String runner = requested == null ? "" : requested.trim();
        if (runner.isEmpty()) {
            var match = Pattern.compile("(?m)^debug-runner:[ \t]*['\"]?([a-zA-Z0-9_+.-]+)['\"]?[ \t]*$").matcher(yaml);
            if (!match.find()) throw new IOException("Build has no default debug runner. Select a supported runner.");
            runner = match.group(1);
        }
        if (!java.util.Set.of("openocd", "jlink", "pyocd").contains(runner))
            throw new IOException("Native debugging supports openocd, jlink and pyocd; selected: " + runner);
        // Generated runner argument sections identify board-supported backends.
        if (!Pattern.compile("(?m)^  " + Pattern.quote(runner) + ":[ \t]*$").matcher(yaml).find())
            throw new IOException("This build does not declare runner " + runner + ". Reconfigure a board supporting this probe.");
        return runner;
    }
}
