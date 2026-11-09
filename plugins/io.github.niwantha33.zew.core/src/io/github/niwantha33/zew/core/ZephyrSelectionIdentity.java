// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.nio.file.Path;
import java.util.Objects;

/** Project/build/node selection identity, not a claim of runtime or physical evidence. */
public record ZephyrSelectionIdentity(String projectName, Path buildDirectory, String nodePath) {
    public ZephyrSelectionIdentity {
        if (projectName == null || projectName.isBlank())
            throw new IllegalArgumentException("An exact project is required");
        Objects.requireNonNull(buildDirectory, "buildDirectory");
        buildDirectory = buildDirectory.toAbsolutePath().normalize();
        if (nodePath == null) nodePath = "";
        if (!nodePath.isEmpty() && (!nodePath.startsWith("/")
                || nodePath.length() > 500 || nodePath.contains("//")
                || nodePath.contains("/../") || nodePath.contains("/./")
                || nodePath.endsWith("/..") || nodePath.endsWith("/.")))
            throw new IllegalArgumentException("Expected an exact DTS node path");
    }

    /** Equality of project and selected build, independent of the selected node. */
    public boolean sameBuild(ZephyrSelectionIdentity other) {
        return other != null && projectName.equals(other.projectName)
                && buildDirectory.equals(other.buildDirectory);
    }
}
