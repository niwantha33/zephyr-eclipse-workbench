// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Explicit opt-in policy for managed subprocesses. Ordinary maps retain the
 * existing external-tool environment behavior; the policy is never exported
 * as a magic environment variable to child processes.
 */
public final class ProcessEnvironment extends LinkedHashMap<String, String> {
    private static final long serialVersionUID = 1L;
    private final boolean isolated;
    private static final Set<String> AMBIENT = Set.of(
            "PYTHONHOME", "PYTHONPATH", "PYTHONUSERBASE", "PYTHONSTARTUP",
            "OPENOCD_SCRIPTS", "PYOCD_DAPARG",
            "VIRTUAL_ENV", "CONDA_PREFIX", "CONDA_DEFAULT_ENV",
            "ZEPHYR_BASE", "ZEPHYR_SDK_INSTALL_DIR", "ZEPHYR_TOOLCHAIN_VARIANT",
            "ZEPHYR_MODULES", "ZEPHYR_EXTRA_MODULES", "ZEPHYR_TOOLCHAIN_PATH",
            "GNUARMEMB_TOOLCHAIN_PATH", "CROSS_COMPILE",
            "BOARD", "BOARD_ROOT", "SOC_ROOT", "DTS_ROOT", "ARCH_ROOT",
            "CONF_FILE", "EXTRA_CONF_FILE", "DTC_OVERLAY_FILE", "EXTRA_DTC_OVERLAY_FILE",
            "CMAKE_PREFIX_PATH", "CMAKE_PROGRAM_PATH", "CMAKE_TOOLCHAIN_FILE",
            "CMAKE_GENERATOR", "CMAKE_GENERATOR_PLATFORM", "CMAKE_GENERATOR_TOOLSET",
            "CMAKE_GENERATOR_INSTANCE", "CMAKE_MAKE_PROGRAM", "CMAKE_PROJECT_INCLUDE",
            "CMAKE_PROJECT_INCLUDE_BEFORE", "CMAKE_PROJECT_TOP_LEVEL_INCLUDES",
            "CC", "CXX", "AS", "AR", "LD", "CFLAGS", "CXXFLAGS", "CPPFLAGS", "LDFLAGS",
            // OpenOCD receives the verified provider scripts via --openocd-search.
            // Do not allow an inherited installation to add another search root.
            "OPENOCD_SCRIPTS",
            "WEST_CONFIG_LOCAL", "WEST_CONFIG_GLOBAL", "WEST_CONFIG_SYSTEM",
            "GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR",
            "GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES",
            "GIT_CONFIG", "GIT_CONFIG_GLOBAL", "GIT_CONFIG_SYSTEM",
            "GIT_CONFIG_NOSYSTEM", "GIT_CONFIG_PARAMETERS", "GIT_CONFIG_COUNT");

    public ProcessEnvironment(boolean isolated) { this.isolated = isolated; }

    private static boolean ambient(String key) {
        String upper = key.toUpperCase(Locale.ROOT);
        return AMBIENT.contains(upper) || upper.startsWith("GIT_CONFIG_KEY_")
                || upper.startsWith("GIT_CONFIG_VALUE_");
    }

    public static void apply(Map<String, String> target, Map<String, String> overrides) {
        if (overrides instanceof ProcessEnvironment policy && policy.isolated)
            target.keySet().removeIf(ProcessEnvironment::ambient);
        overrides.forEach((key, value) -> {
            String actual = target.keySet().stream().filter(k -> k.equalsIgnoreCase(key))
                    .findFirst().orElse(key);
            target.put(actual, value);
        });
    }
}
