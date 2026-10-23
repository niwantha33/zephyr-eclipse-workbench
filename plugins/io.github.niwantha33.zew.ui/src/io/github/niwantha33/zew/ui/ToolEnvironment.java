// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/** Explicit local tools selected in the manager; never written into application source. */
final class ToolEnvironment {
    private ToolEnvironment() {}
    static Map<String, String> overrides() {
        var preferences = Activator.getDefault().getPreferenceStore();
        var result = new LinkedHashMap<String, String>();
        var paths = new LinkedHashSet<String>();
        for (String name : new String[] {"cmake", "ninja", "git", "dtc"}) {
            String selected = preferences.getString("tools." + name);
            if (!selected.isBlank()) paths.add(Path.of(selected).getParent().toString());
        }
        String python = preferences.getString(WorkbenchPreferences.PYTHON);
        if (!python.isBlank()) paths.add(Path.of(python).getParent().toString());
        if (!paths.isEmpty()) {
            String key = System.getenv().keySet().stream().filter(k -> k.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
            result.put(key, String.join(File.pathSeparator, paths) + File.pathSeparator + System.getenv().getOrDefault(key, ""));
        }
        String sdk = preferences.getString("tools.sdk");
        if (!sdk.isBlank()) {
            result.put("ZEPHYR_SDK_INSTALL_DIR", sdk);
            result.put("ZEPHYR_TOOLCHAIN_VARIANT", "zephyr");
        }
        return Map.copyOf(result);
    }
}
