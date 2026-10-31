// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Explains GDB local-variable availability without inventing target measurements.
 * The effective generated Kconfig indicates the requested optimization choice;
 * it does not prove the final compiler command line or that a local is live at PC.
 */
public final class DebugVariableAdvice {
    private DebugVariableAdvice() {}

    private static final Map<String, String> OPTIMIZATIONS = new LinkedHashMap<>();
    static {
        OPTIMIZATIONS.put("CONFIG_NO_OPTIMIZATIONS", "-O0");
        OPTIMIZATIONS.put("CONFIG_DEBUG_OPTIMIZATIONS", "-Og");
        OPTIMIZATIONS.put("CONFIG_SIZE_OPTIMIZATIONS", "-Os");
        OPTIMIZATIONS.put("CONFIG_SIZE_OPTIMIZATIONS_AGGRESSIVE", "-Oz");
        OPTIMIZATIONS.put("CONFIG_SPEED_OPTIMIZATIONS", "-O2");
    }

    public static String describe(String selectedProfile, Map<String, String> effectiveConfig) {
        Map<String, String> config = effectiveConfig == null ? Map.of() : effectiveConfig;
        var selected = OPTIMIZATIONS.entrySet().stream()
                .filter(option -> "y".equals(config.get(option.getKey()))).toList();
        String optimization;
        if (selected.size() == 1) {
            var choice = selected.get(0);
            optimization = "Generated Zephyr Kconfig: " + choice.getKey() + "=y (normally "
                    + choice.getValue() + "). Compiler overrides have not been inspected.";
        } else if (selected.isEmpty()) {
            optimization = "Generated Zephyr Kconfig does not identify an optimization choice; "
                    + "compiler optimization is UNKNOWN.";
        } else {
            optimization = "Generated Zephyr Kconfig contains conflicting optimization choices; "
                    + "compiler optimization is UNKNOWN.";
        }
        String profile = selectedProfile == null || selectedProfile.isBlank()
                ? "default" : selectedProfile;
        StringBuilder advice = new StringBuilder("Debug variable availability | selected build profile: ")
                .append(profile).append(". ").append(optimization)
                .append("\nGDB <optimized out> means this suspended frame has no usable variable value. ")
                .append("The C type can still be shown without a valid pointer; expanded members are not proven values. ")
                .append("For a local, stop before its last use; even -Og can omit a dead local. ")
                .append("For a file-scope/global symbol such as gpio_dev, do not assume a local-lifetime cause. ")
                .append("Use Zephyr > Inspect GDB Symbol (Read-only) with the correct suspended frame ")
                .append("to check both its value and &symbol; then inspect ELF symbols, DWARF, ")
                .append("actual compile flags and flashed-image identity if unavailable.");
        if (selected.size() != 1
                || !selected.get(0).getKey().equals("CONFIG_DEBUG_OPTIMIZATIONS")) {
            advice.append("\nFor source-level variable inspection: Project > Properties > Zephyr > ")
                    .append("Build configuration > Debug (-Og), then Build, Flash the matching image, and Debug. ")
                    .append("The IDE will not silently change your project profile or firmware.");
        }
        return advice.toString();
    }
}
