// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Tool search path for subprocesses using a selected Zephyr project environment. */
public final class ProjectToolPath {
    private ProjectToolPath() {}

    public static String resolve(Properties settings, Map<String, String> host, boolean isolatedWindowsBuild) {
        var paths = new LinkedHashSet<String>();
        for (String key : List.of("tools.cmake", "tools.ninja", "tools.git", "python", "tools.dtc")) {
            String executable = settings.getProperty(key, "");
            if (!executable.isBlank()) paths.add(Path.of(executable).getParent().toString());
        }
        Path sdk = Path.of(settings.getProperty("tools.sdk"));
        paths.add(sdk.resolve("hosttools/bin").toString());
        paths.add(sdk.resolve("hosttools/usr/bin").toString());
        String root = settings.getProperty("zew.setupRoot", "");
        if (!root.isBlank()) {
            paths.add(Path.of(root, "board-python/esp32s3/Scripts").toString());
            paths.add(Path.of(root, "board-python/esp32s3/bin").toString());
        }
        if (isolatedWindowsBuild) {
            String systemRoot = environmentValue(host, "SystemRoot");
            if (!systemRoot.isBlank() && Path.of(systemRoot).isAbsolute()) {
                Path windows = Path.of(systemRoot);
                paths.add(windows.resolve("System32").toString());
                paths.add(windows.toString());
            }
        } else {
            String inherited = environmentValue(host, "PATH");
            if (!inherited.isBlank()) paths.add(inherited);
        }
        return String.join(File.pathSeparator, paths);
    }

    private static String environmentValue(Map<String, String> host, String name) {
        return host.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).findFirst().orElse("");
    }
}
