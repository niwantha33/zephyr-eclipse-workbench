// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

/**
 * Resource navigation rules independent of SWT/Eclipse. Only a verified
 * Zephyr project may contribute the context menu.
 */
public final class ConfigurationStudioResources {
    private ConfigurationStudioResources() {}

    public static boolean contextTarget(String relative, boolean projectRoot) {
        if (projectRoot) return true;
        if (!safeRelative(relative)) return false;
        if (relative.equals("prj.conf") || relative.equals(".project")
                || relative.endsWith(".prj")) return true;
        return hardware(relative);
    }

    public static boolean hardware(String relative) {
        return safeRelative(relative) && !relative.startsWith(".zew-build/")
                && (relative.endsWith(".overlay") || relative.endsWith(".dts") || relative.endsWith(".dtsi"));
    }

    private static boolean safeRelative(String relative) {
        return relative != null && !relative.isBlank() && !relative.startsWith("/")
                && !relative.contains("\\") && !relative.contains("..");
    }

    public static boolean projectSettings(String relative) {
        return safeRelative(relative) && ("prj.conf".equals(relative) || relative.endsWith(".prj"));
    }
}
