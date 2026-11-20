// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pure-JDK native Observatory handoff. No target I/O, shell invocation,
 * debugger connection, firmware action or implicit trace decoding.
 */
public final class ObservatoryDesktopLaunch {
    public record Plan(List<String> arguments, Path directory, boolean bridgeStatus) {}

    private ObservatoryDesktopLaunch() {}

    public static Plan plan(Map<String, String> environment) throws IOException {
        String configured = environment.get("RTOS_OBSERVATORY_EXE");
        if (configured == null || configured.isBlank())
            throw new IOException("Set RTOS_OBSERVATORY_EXE to the standalone RTOS Observatory desktop executable.");
        final Path executable;
        try {
            executable = Path.of(configured).toAbsolutePath().normalize();
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid RTOS_OBSERVATORY_EXE path", invalid);
        }
        if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
                || !Files.isExecutable(executable))
            throw new IOException("RTOS Observatory executable is missing or not executable: " + executable);

        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        boolean withStatus = false;
        String folder = environment.get("RTOS_OBSERVATORY_BRIDGE_DIR");
        if (folder != null && !folder.isBlank()) {
            final Path directory;
            try {
                directory = Path.of(folder).toAbsolutePath().normalize();
            } catch (RuntimeException invalid) {
                throw new IOException("Invalid RTOS_OBSERVATORY_BRIDGE_DIR path", invalid);
            }
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Bridge directory must be a real, existing local directory");
            Path status = directory.resolve("rtos-observatory-dsf-status.tsv");
            if (Files.exists(status, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(status, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(status) < 1 || Files.size(status) > 4096)
                    throw new IOException("Bridge status must be a non-symlink regular file of 1..4096 bytes");
                command.add("--bridge-status");
                command.add(status.toString());
                withStatus = true;
            }
        }
        return new Plan(List.copyOf(command), executable.getParent(), withStatus);
    }
}
