// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Machine-local settings. Never serialized into an application repository. */
public record WorkbenchConfig(Path python, Path workspace, Path application, String board) {
    public WorkbenchConfig {
        python = absolute(python);
        workspace = absolute(workspace);
        application = absolute(application);
        board = Objects.requireNonNull(board).trim();
        if (!board.matches("[A-Za-z0-9][A-Za-z0-9_./@+\\-]*") || board.contains("..")) {
            throw new IllegalArgumentException("Enter an exact Zephyr board target, including qualifiers when required.");
        }
    }

    private static Path absolute(Path path) {
        return Objects.requireNonNull(path).toAbsolutePath().normalize();
    }

    public void validateEnvironment() {
        if (!Files.isRegularFile(python)) {
            throw new IllegalArgumentException("Select the Python executable inside the workspace virtual environment.");
        }
        if (!Files.isDirectory(workspace.resolve(".west"))) {
            throw new IllegalArgumentException("Workspace must contain .west. Select the West workspace root.");
        }
    }

    public void validateApplication() {
        validateEnvironment();
        if (!Files.isRegularFile(application.resolve("CMakeLists.txt"))) {
            throw new IllegalArgumentException("Application folder must contain CMakeLists.txt.");
        }
    }
}
