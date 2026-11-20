// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Native Zephyr Kconfig optimization modes. Legacy IDs and output caches are stable. */
public enum BuildConfiguration {
    DEFAULT("default", "Application settings", ""),
    NO_OPTIMIZATION("debug-o0", "No optimisation (-O0)", "CONFIG_NO_OPTIMIZATIONS"),
    DEBUG("debug", "Debug (-Og)", "CONFIG_DEBUG_OPTIMIZATIONS"),
    SIZE("size-os", "Optimise for size (-Os)", "CONFIG_SIZE_OPTIMIZATIONS"),
    RELEASE("release", "Optimise for speed (-O2)", "CONFIG_SPEED_OPTIMIZATIONS"),
    AGGRESSIVE_SIZE("size-oz", "Aggressive size (-Oz)", "CONFIG_SIZE_OPTIMIZATIONS_AGGRESSIVE");

    private final String id;
    private final String label;
    private final String option;
    BuildConfiguration(String id, String label, String option) {
        this.id = id; this.label = label; this.option = option;
    }
    public String id() { return id; }
    public String label() { return label; }
    public List<String> cmakeArguments() {
        return option.isEmpty() ? List.of() : List.of("-D" + option + "=y");
    }
    public void verify(Path build) throws IOException {
        if (!option.isEmpty() && !"y".equals(BuildArtifacts.configuration(build).get(option)))
            throw new IOException("The selected " + label + " configuration could not enable "
                    + option + ". Check the Zephyr console for Kconfig conflicts.");
    }
    public static BuildConfiguration fromId(String value) throws IOException {
        if (value == null || value.isBlank()) return DEFAULT;
        for (var profile : values()) if (profile.id.equals(value)) return profile;
        throw new IOException("Unknown build configuration '" + value + "'. Choose one in Project Properties > Zephyr.");
    }
}
