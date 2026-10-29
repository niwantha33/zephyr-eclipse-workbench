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
        if (relative == null || relative.isBlank()
                || relative.startsWith("/") || relative.contains("\\")
                || relative.contains("..")) return false;
        if (relative.equals("prj.conf") || relative.equals(".project")
                || relative.endsWith(".prj")) return true;
        return relative.equals("app.overlay") || relative.endsWith(".overlay");
    }
    public static boolean hardware(String relative) {
        return relative != null && relative.endsWith(".overlay");
    }
    public static boolean projectSettings(String relative) {
        return "prj.conf".equals(relative) || relative != null && relative.endsWith(".prj");
    }
}
