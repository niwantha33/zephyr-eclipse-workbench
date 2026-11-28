// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Explicit host process environment for the GDB client in a managed Windows setup. */
public final class ManagedGdbEnvironment {
    private ManagedGdbEnvironment() {}

    // Only ordinary OS/user-directory context is carried forward. CDT exposes
    // the GDB process environment in its properties UI, so do not copy arbitrary
    // host variables, credentials, compiler overrides or CDT build settings.
    private static final Set<String> OS_CONTEXT = Set.of("SYSTEMDRIVE", "COMSPEC",
            "TEMP", "TMP", "USERPROFILE", "HOME", "HOMEDRIVE", "HOMEPATH",
            "APPDATA", "LOCALAPPDATA", "PROGRAMDATA", "USERNAME", "USERDOMAIN",
            "LANG", "LC_ALL", "LC_CTYPE");

    public static String[] create(Path setup, Path sdk, Path gdb, Map<String, String> host)
            throws IOException {
        Path root = setup.toRealPath();
        Path selectedSdk = sdk.toRealPath();
        if (!setup.isAbsolute() || !sdk.isAbsolute() || !gdb.isAbsolute()
                || !Files.isDirectory(root) || !Files.isRegularFile(root.resolve(".zew-managed"))
                || !Files.isDirectory(selectedSdk) || !selectedSdk.startsWith(root)
                || selectedSdk.equals(root))
            throw new IOException("Managed debug requires the SDK inside the selected setup.");
        Path debugger = gdb.toRealPath();
        if (!Files.isRegularFile(debugger) || !debugger.startsWith(selectedSdk))
            throw new IOException("GDB is outside the selected managed SDK. Run Pristine Rebuild.");

        String systemRoot = host.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase("SystemRoot"))
                .map(Map.Entry::getValue).findFirst().orElse("");
        if (systemRoot.isBlank() || !Path.of(systemRoot).isAbsolute())
            throw new IOException("Windows SystemRoot is missing or invalid for managed debug.");
        Path windows = Path.of(systemRoot).toRealPath();
        if (!Files.isDirectory(windows))
            throw new IOException("Windows SystemRoot is not a directory.");

        var paths = new LinkedHashSet<String>();
        paths.add(debugger.getParent().toString());
        for (String relative : new String[] {"hosttools/bin", "hosttools/usr/bin"}) {
            Path directory = selectedSdk.resolve(relative);
            if (Files.notExists(directory)) continue;
            Path real = directory.toRealPath();
            if (!Files.isDirectory(real) || !real.startsWith(selectedSdk))
                throw new IOException("SDK debugger support directory escapes the selected SDK.");
            paths.add(real.toString());
        }
        paths.add(windows.resolve("System32").toString());
        paths.add(windows.toString());
        // A path containing the delimiter cannot be represented as a single
        // PATH entry; reject instead of introducing an unintended search entry.
        for (String path : paths)
            if (path.indexOf(File.pathSeparatorChar) >= 0)
                throw new IOException("Managed debugger search path contains a PATH delimiter.");

        var environment = new TreeMap<String, String>();
        host.forEach((key, value) -> {
            String upper = key.toUpperCase(Locale.ROOT);
            if (OS_CONTEXT.contains(upper)) environment.put(upper, value);
        });
        environment.put("SystemRoot", windows.toString());
        environment.put("WINDIR", windows.toString());
        environment.put("PATH", String.join(File.pathSeparator, paths));
        environment.put("PYTHONNOUSERSITE", "1");
        environment.put("ZEPHYR_SDK_INSTALL_DIR", selectedSdk.toString());
        return environment.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new);
    }
}
