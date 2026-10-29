// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import io.github.niwantha33.zew.core.OpenOcdRegistry;

/** Shared workspace settings: user paths are not stored in project source. */
final class SharedDebugTools {
    static final String GENERAL = "tools.openocd.general";
    static final String ESPRESSIF = "tools.openocd.espressif";
    static final String TI = "tools.openocd.ti";

    private SharedDebugTools() {}

    static Optional<OpenOcdRegistry.Installation> select(String board, Properties settings) {
        var store = Activator.getDefault().getPreferenceStore();
        return OpenOcdRegistry.select(board, new OpenOcdRegistry.Locations(
                path(settings.getProperty("tools.sdk", "")),
                path(System.getProperty("user.home", "")),
                path(System.getenv("IDF_TOOLS_PATH")),
                path(store.getString(GENERAL)),
                path(store.getString(ESPRESSIF)),
                path(store.getString(TI)),
                path(System.getenv("ProgramFiles"))));
    }

    /** Apply the same provider policy to native and external OpenOCD debug launches. */
    static io.github.niwantha33.zew.core.TargetOperations.Plan bindDebugTool(
            io.github.niwantha33.zew.core.TargetOperations.Plan plan, Properties settings)
            throws java.io.IOException {
        String yaml = java.nio.file.Files.readString(plan.buildDirectory().resolve("zephyr/runners.yaml"));
        if (!io.github.niwantha33.zew.core.TargetOperations.usesOpenOcdDebugServer(plan, yaml)) return plan;
        var selected = select(plan.board(), settings)
                .orElseThrow(() -> OpenOcdRegistry.missing(plan.board()));
        return io.github.niwantha33.zew.core.TargetOperations.withOpenOcd(plan, selected);
    }

    static io.github.niwantha33.zew.core.TargetOperations.Plan bindFlashTool(
            io.github.niwantha33.zew.core.TargetOperations.Plan plan, Properties settings) throws java.io.IOException {
        String yaml = java.nio.file.Files.readString(plan.buildDirectory().resolve("zephyr/runners.yaml"));
        if (!io.github.niwantha33.zew.core.TargetOperations.usesOpenOcdFlash(plan, yaml)) return plan;
        var selected = select(plan.board(), settings).orElseThrow(() -> OpenOcdRegistry.missing(plan.board()));
        return io.github.niwantha33.zew.core.TargetOperations.withOpenOcd(plan, selected);
    }

    private static Path path(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Path.of(value).toAbsolutePath().normalize(); }
        catch (RuntimeException ignored) { return null; }
    }
}
