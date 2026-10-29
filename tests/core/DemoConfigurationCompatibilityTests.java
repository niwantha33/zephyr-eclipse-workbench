// SPDX-License-Identifier: Apache-2.0
import java.nio.file.*;
import io.github.niwantha33.zew.core.DemoConfigurationCompatibility;

public final class DemoConfigurationCompatibilityTests {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        System.out.println("PASS demo migration: " + message);
    }
    public static void main(String[] args) throws Exception {
        Path app = Files.createTempDirectory("legacy demo migration ");
        String original = Files.readString(Path.of("tests/fixtures/rtos_demo_legacy/prj.conf"));
        String cmake = Files.readString(Path.of("examples/rtos_analysis_demo/CMakeLists.txt"));
        try {
            Files.writeString(app.resolve("prj.conf"), original);
            Files.writeString(app.resolve("CMakeLists.txt"), cmake);
            check(!DemoConfigurationCompatibility.migrate(app, "another configure failure"), "no source changes without undefined-symbol diagnostic");
            Files.writeString(app.resolve("prj.conf"), original + "CONFIG_ASSERT=y\n");
            check(!DemoConfigurationCompatibility.migrate(app, "undefined symbol DEBUG_INFO"), "customized configuration is not rewritten");
            Files.writeString(app.resolve("prj.conf"), original);
            Files.writeString(app.resolve("CMakeLists.txt"), cmake.replace("rtos_analysis_demo", "other_application"));
            check(!DemoConfigurationCompatibility.migrate(app, "undefined symbol DEBUG_INFO"), "unrelated applications are not rewritten");
            Files.writeString(app.resolve("CMakeLists.txt"), cmake);
            String windows = original.replace("\n", "\r\n");
            Files.writeString(app.resolve("prj.conf"), windows);
            check(DemoConfigurationCompatibility.migrate(app, "warning: attempt to assign 'y' to undefined symbol DEBUG_INFO"), "original Windows demo migrated after verified diagnostic");
            check(Files.readString(app.resolve(".zew/config-backup/prj.conf.before-debug-info-migration")).equals(windows), "exact original configuration backed up");
            check(!Files.readString(app.resolve("prj.conf")).contains("\r\nCONFIG_DEBUG_INFO=y"), "only obsolete assignment removed");
            check(Files.readString(app.resolve("prj.conf")).replace("# Zephyr emits debug information by default; select Debug (-Og) in Workbench.", "CONFIG_DEBUG_INFO=y").equals(windows), "other options and line endings preserved");
            check(!DemoConfigurationCompatibility.migrate(app, "undefined symbol DEBUG_INFO"), "migration is idempotent");
        } finally {
            try (var files = Files.walk(app)) { for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file); }
        }
    }
}
