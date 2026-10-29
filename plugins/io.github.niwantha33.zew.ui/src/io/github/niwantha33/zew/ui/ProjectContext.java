// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.QualifiedName;
import io.github.niwantha33.zew.core.ProjectProfile;
import io.github.niwantha33.zew.core.WestCommands;

final class ProjectContext {
    static final QualifiedName BUILD_CONFIGURATION = new QualifiedName(Activator.ID, "buildConfiguration");

    static Properties settings(IProject project) throws Exception {
        String root = project.getPersistentProperty(new QualifiedName(Activator.ID, "setupRoot"));
        if (root == null) throw new java.io.IOException("Select a ready environment in Project Properties > Zephyr.");
        Properties settings = load(Path.of(root));
        settings.setProperty("zew.setupRoot", root);
        settings.setProperty("zew.buildConfiguration", io.github.niwantha33.zew.core.BuildConfiguration
                .fromId(project.getPersistentProperty(BUILD_CONFIGURATION)).id());
        Path application = project.getLocation().toFile().toPath();
        if (Files.isRegularFile(application.resolve(".zew/project.json"))) {
            settings.setProperty("board", ProjectProfile.read(application).board());
            settings.setProperty("application", application.toString());
            // Never carry a no-modules flag over to a non-x86 SoC.
            settings.setProperty("zew.modules.none", settings.getProperty("board").equals("qemu_x86/atom") ? "true" : "false");
        } else if (Files.isRegularFile(application.resolve(".zew/project.properties"))) {
            settings.setProperty("board", io.github.niwantha33.zew.core.ZephyrProjectFiles.readBoard(application));
            settings.setProperty("application", application.toString());
            settings.setProperty("zew.modules.none", settings.getProperty("board").equals("qemu_x86/atom") ? "true" : "false");
        } else if (settings.getProperty("application", "").isBlank()
                || !Files.exists(Path.of(settings.getProperty("application")))
                || !application.toRealPath().equals(Path.of(settings.getProperty("application")).toRealPath())) {
            throw new java.io.IOException("Select an exact board / SoC / CPU in Project Properties > Zephyr.");
        }
        // Imported legacy projects may inherit a starter environment setting.
        // Only the original qemu_x86/atom starter intentionally bypasses modules.
        settings.setProperty("zew.modules.none",
                "qemu_x86/atom".equals(settings.getProperty("board")) ? "true" : "false");
        return settings;
    }
    static Path buildDirectory(IProject project, Properties settings) throws Exception {
        Path application = project.getLocation().toFile().toPath();
        var profile = io.github.niwantha33.zew.core.BuildConfiguration.fromId(settings.getProperty("zew.buildConfiguration"));
        if (profile == io.github.niwantha33.zew.core.BuildConfiguration.DEFAULT
                && !Files.isRegularFile(application.resolve(".zew/project.json"))
                && Files.isRegularFile(application.resolve(".zew-build/first/CMakeCache.txt"))) return application.resolve(".zew-build/first");
        return WestCommands.buildDirectory(new io.github.niwantha33.zew.core.WorkbenchConfig(
                Path.of(settings.getProperty("python")), Path.of(settings.getProperty("workspace")), application, settings.getProperty("board")), profile);
    }
    static Properties load(Path root) throws Exception {
        if (!Files.isRegularFile(root.resolve(".zew-ready"))) throw new java.io.IOException("Complete Set Up Zephyr or select a ready environment.");
        Properties settings = new Properties();
        try (var reader = Files.newBufferedReader(root.resolve("environment.properties"), StandardCharsets.UTF_8)) { settings.load(reader); }
        for (String key : List.of("python", "workspace", "tools.sdk", "tools.cmake", "tools.ninja", "tools.git"))
            if (settings.getProperty(key, "").isBlank()) throw new java.io.IOException("Environment is missing " + key);
        return settings;
    }
    static Map<String, String> environment(Properties settings) {
        var values = new LinkedHashMap<String, String>();
        var paths = new LinkedHashSet<String>();
        for (String key : List.of("tools.cmake", "tools.ninja", "tools.git", "python")) paths.add(Path.of(settings.getProperty(key)).getParent().toString());
        String dtc = settings.getProperty("tools.dtc", "");
        if (!dtc.isBlank()) paths.add(Path.of(dtc).getParent().toString());
        paths.add(Path.of(settings.getProperty("tools.sdk"), "hosttools/bin").toString());
        paths.add(Path.of(settings.getProperty("tools.sdk"), "hosttools/usr/bin").toString());
        String managedRoot = settings.getProperty("zew.setupRoot", "");
        if (!managedRoot.isBlank()) {
            paths.add(Path.of(managedRoot, "board-python/esp32s3/Scripts").toString());
            paths.add(Path.of(managedRoot, "board-python/esp32s3/bin").toString());
        }
        String pathKey = System.getenv().keySet().stream().filter(k -> k.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
        values.put(pathKey, String.join(java.io.File.pathSeparator, paths) + java.io.File.pathSeparator + System.getenv().getOrDefault(pathKey, ""));
        values.put("ZEPHYR_BASE", WestCommands.cmakePath(Path.of(settings.getProperty("workspace"), "zephyr").toString()));
        values.put("ZEPHYR_SDK_INSTALL_DIR", WestCommands.cmakePath(settings.getProperty("tools.sdk")));
        values.put("ZEPHYR_TOOLCHAIN_VARIANT", "zephyr");
        return values;
    }
}

