// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.ConfigurationStudioFiles;
import java.nio.file.*;
import java.util.Comparator;

public final class ConfigurationStudioTests {
    private static void ok(boolean yes, String why) {
        if (!yes) throw new AssertionError(why);
    }
    private static void rejects(Throwing action) throws Exception {
        try { action.run(); } catch (java.io.IOException expected) { return; }
        throw new AssertionError("Unsafe operation succeeded");
    }
    private interface Throwing { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("zew-studio space ");
        try {
            Path app = root.resolve("existing application");
            Path build = app.resolve(".zew-build/debug-target");
            Files.createDirectories(build.resolve("zephyr"));
            Files.writeString(app.resolve("prj.conf"),
                    "# Original notes\r\nCONFIG_LOG=y\r\nCONFIG_LOG=y\r\nCONFIG_DEBUG_INFO=y\r\n");
            Files.writeString(build.resolve("zephyr/.config"),
                    "CONFIG_LOG=y\n# CONFIG_DEBUG_THREAD_INFO is not set\n");
            Files.writeString(build.resolve("zephyr/zephyr.dts"), "/ { model = \"Board\"; };\n");
            var snapshot = ConfigurationStudioFiles.inspect(app, build, "Debug (-Og)");
            ok(snapshot.options().stream().anyMatch(o -> o.symbol().equals("DEBUG_INFO")
                    && o.unresolved() && o.requestedLine() == 4),
                    "unknown requested symbol is visible even after failed configure");
            ok(snapshot.options().stream().anyMatch(o -> o.symbol().equals("LOG")
                    && "y".equals(o.effective())), "effective value from generated .config");
            ok(snapshot.devicetree().contains("Board"), "compiled devicetree available read-only");
            var remove = ConfigurationStudioFiles.plan(app, build, "DEBUG_INFO", null);
            ok(remove.updated().contains("# Original notes"), "comments retained");
            ok(!remove.updated().contains("CONFIG_DEBUG_INFO"), "problematic line removed from proposed diff");
            var backup = ConfigurationStudioFiles.commit(app, remove);
            ok(Files.readString(backup).contains("CONFIG_DEBUG_INFO=y"), "original file backed up");
            ok(!Files.readString(app.resolve("prj.conf")).contains("CONFIG_DEBUG_INFO"), "repair persisted");
            var before = Files.readString(app.resolve("prj.conf"));
            var changes = ConfigurationStudioFiles.plan(app, build, "LOG", "n");
            Files.writeString(app.resolve("prj.conf"), before + "# external editor change\r\n");
            rejects(() -> ConfigurationStudioFiles.commit(app, changes));
            ok(Files.readString(app.resolve("prj.conf")).contains("external editor change"),
                    "optimistic locking preserves concurrent edits");
            Files.writeString(app.resolve("prj.conf"), before);
            rejects(() -> ConfigurationStudioFiles.plan(app, build, "NOT_A_SYMBOL", "y"));
            rejects(() -> ConfigurationStudioFiles.plan(app, build, "LOG", "y\nCONFIG_INJECT=y"));
            rejects(() -> ConfigurationStudioFiles.plan(app, build, "../CONFIG_LOG", "y"));
            var changed = ConfigurationStudioFiles.plan(app, build, "LOG", "n");
            ok(changed.updated().contains("CONFIG_LOG=n"), "valid effective option editable");
            ok(!changed.updated().contains("CONFIG_LOG=y"), "duplicate requested lines replaced");
            ConfigurationStudioFiles.commit(app, changed);
            ok(Files.readString(app.resolve("prj.conf")).contains("CONFIG_LOG=n"),
                    "edited configuration persists");
            ok(Files.readString(build.resolve("zephyr/.config")).contains("CONFIG_LOG=y"),
                    "build-generated output is never written by GUI");
            Path overlay = app.resolve("app.overlay");
            Files.writeString(overlay, "/* user overlay */\n");
            ConfigurationStudioFiles.inspect(app, build, "Release");
            ok(Files.readString(overlay).equals("/* user overlay */\n"),
                    "hardware configuration is never automatically changed");
            Path zephyr = root.resolve("workspace/zephyr/subsys/logging");
            Files.createDirectories(zephyr);
            Files.writeString(zephyr.resolve("Kconfig"), "menu \"Logging\"\n"
                    + "config LOG\n    bool \"Logging enabled\"\n"
                    + "    depends on PRINTK\n    help\n      Enables subsystem logging.\n"
                    + "config OTHER\n    bool \"Other\"\n");
            var sourceHelp = io.github.niwantha33.zew.core.KconfigSourceHelp.firstDefinition(
                    root.resolve("workspace"), "LOG");
            ok(sourceHelp != null && sourceHelp.line() == 2
                    && sourceHelp.sourceExcerpt().contains("depends on PRINTK")
                    && !sourceHelp.sourceExcerpt().contains("config OTHER"),
                    "definition lookup gives source help without including next symbol");
            ok(io.github.niwantha33.zew.core.KconfigSourceHelp.firstDefinition(
                    root.resolve("workspace"), "NONEXISTENT") == null,
                    "missing metadata must not pretend to evaluate Kconfig availability");
            rejects(() -> io.github.niwantha33.zew.core.KconfigSourceHelp.firstDefinition(
                    root.resolve("workspace"), "BAD/NAME"));
            var presentation = io.github.niwantha33.zew.core.ConfigurationStudioPresentation.class;
            var overridden = new io.github.niwantha33.zew.core.ConfigurationStudioFiles.Option("LOG", "y", "y", 3);
            var invalid = new io.github.niwantha33.zew.core.ConfigurationStudioFiles.Option("DEBUG_INFO", null, "y", 2);
            var inherited = new io.github.niwantha33.zew.core.ConfigurationStudioFiles.Option("GPIO", "n", null, 0);
            ok(io.github.niwantha33.zew.core.ConfigurationStudioPresentation.title("LOG").equals("System logging"),
                    "user-friendly setting title");
            ok(io.github.niwantha33.zew.core.ConfigurationStudioPresentation.current(overridden).equals("Enabled"),
                    "boolean y is displayed as Enabled");
            ok(io.github.niwantha33.zew.core.ConfigurationStudioPresentation.current(inherited).equals("Disabled"),
                    "boolean n is displayed as Disabled");
            ok(io.github.niwantha33.zew.core.ConfigurationStudioPresentation.matches(overridden,
                    io.github.niwantha33.zew.core.ConfigurationStudioPresentation.MINE, "logging"),
                    "search by plain English finds project choices");
            ok(io.github.niwantha33.zew.core.ConfigurationStudioPresentation.matches(invalid,
                    io.github.niwantha33.zew.core.ConfigurationStudioPresentation.REVIEW, "debug"),
                    "unresolved symbol appears under Needs review");
            ok(!io.github.niwantha33.zew.core.ConfigurationStudioPresentation.matches(inherited,
                    io.github.niwantha33.zew.core.ConfigurationStudioPresentation.MINE, ""),
                    "inherited setting must not appear under My changes");
            ok(io.github.niwantha33.zew.core.ConfigurationStudioPresentation.category("GPIO").equals(
                    io.github.niwantha33.zew.core.ConfigurationStudioPresentation.DRIVERS),
                    "drivers category is correct");
            System.out.println("ConfigurationStudioTests PASS");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
}
