// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.DevicetreeOverlaySupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Portable, no-Zephyr-installation regression for exact-build overlay evidence. */
public final class DevicetreeOverlaySupportTests {
    private static void ok(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        System.out.println("PASS overlay: " + message);
    }
    private static void rejected(Throwing action, String message) throws Exception {
        try { action.run(); } catch (IOException expected) {
            ok(true, message);
            return;
        }
        throw new AssertionError("Expected safe rejection: " + message);
    }
    private interface Throwing { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("zew-overlay-context-Ω ");
        try {
            Path app = Files.createDirectories(root.resolve("application with spaces"));
            Path zephyr = Files.createDirectories(root.resolve("west/zephyr"));
            Path build = Files.createDirectories(app.resolve(".zew-build/test"));
            Path generated = Files.createDirectories(build.resolve("zephyr"));
            Path board = Files.createDirectories(zephyr.resolve("boards/vendor"))
                    .resolve("real board.dts");
            Path soc = Files.createDirectories(zephyr.resolve("dts/arm/vendor"))
                    .resolve("soc.dtsi");
            Files.writeString(board, "/dts-v1/; / {};");
            Files.writeString(soc, "/ { soc {}; };");
            Path selected = Files.writeString(Files.createDirectories(app.resolve("boards"))
                    .resolve("extra.overlay"), "&uart0 { status = \"okay\"; };");
            Path conventional = Files.writeString(app.resolve("app.overlay"),
                    "&uart1 { status = \"disabled\"; };");
            Path cache = build.resolve("CMakeCache.txt");
            Path deps = generated.resolve("zephyr.dts.d");
            ok(!DevicetreeOverlaySupport.inspect(app, build, zephyr).verified(),
                    "missing CMake cache is unknown, never assumed active");
            Files.writeString(cache, "ZEPHYR_BASE:PATH=" + zephyr + "\nBOARD:STRING=board\n");
            ok(!DevicetreeOverlaySupport.inspect(app, build, zephyr).verified(),
                    "no dependency file must not invent active overlays");
            Files.writeString(deps, generated.resolve("zephyr.dts.pre") + ": "
                    + board.toString().replace(" ", "\\ ") + " "
                    + soc.toString().replace(" ", "\\ ") + " " + selected.toString().replace(" ", "\\ ") + "\n");
            var context = DevicetreeOverlaySupport.inspect(app, build, zephyr);
            ok(context.verified() && context.active(selected) && !context.active(conventional),
                    "actual dependency marks extra overlay active but not the unused app.overlay");
            ok(context.baseDts().equals(board.toRealPath())
                    && context.sourceFiles().contains(soc.toRealPath()),
                    "board and SoC source paths retained from exact dependency evidence");
            ok(context.explanation(conventional).contains("NOT in the selected build"),
                    "inactive overlay explanation gives actionable configure guidance");
            Path info = build.resolve("build_info.yml");
            Files.writeString(info, """
                    version: 1
                    devicetree:
                      include-dirs:
                        - "%s"
                      bindings-dirs:
                        - "%s"
                    """.formatted(zephyr.resolve("include"), zephyr.resolve("dts/bindings")));
            var refreshed = DevicetreeOverlaySupport.inspect(app, build, zephyr);
            ok(refreshed.includeDirs().size() == 1 && refreshed.bindingDirs().size() == 1,
                    "build_info.yml include and binding metadata refreshes cached context");
            Files.writeString(deps, generated.resolve("zephyr.dts.pre") + ": "
                    + board.toString().replace(" ", "\\ ") + " "
                    + soc.toString().replace(" ", "\\ ") + " " + conventional.toString().replace(" ", "\\ ") + "\n");
            var changed = DevicetreeOverlaySupport.inspect(app, build, zephyr);
            ok(changed.active(conventional) && !changed.active(selected),
                    "editing dependency evidence invalidates the cached overlay set");
            Path selectedConfig = Files.createDirectories(app.resolve(".zew"))
                    .resolve("overlay-selection.properties");
            ok(DevicetreeOverlaySupport.extraCmakeArguments(app).isEmpty(),
                    "projects without selection file retain Zephyr automatic discovery");
            Files.writeString(selectedConfig, "extra=boards/extra.overlay\n");
            List<String> arguments = DevicetreeOverlaySupport.extraCmakeArguments(app);
            ok(arguments.size() == 1 && arguments.get(0).startsWith("-DEXTRA_DTC_OVERLAY_FILE:STRING=")
                    && arguments.get(0).contains("boards/extra.overlay"),
                    "explicit extra overlay enters West CMake args without replacing primary overlay");
            Files.writeString(selectedConfig, "extra=../outside.overlay\n");
            rejected(() -> DevicetreeOverlaySupport.selectedExtras(app),
                    "relative traversal cannot inject an external overlay");
            Files.writeString(selectedConfig, "extra=boards/extra.overlay;boards/extra.overlay\n");
            rejected(() -> DevicetreeOverlaySupport.selectedExtras(app),
                    "duplicate extra-overlay selection rejected");
            Files.writeString(selectedConfig, "extra=boards/missing.overlay\n");
            rejected(() -> DevicetreeOverlaySupport.selectedExtras(app),
                    "missing selected overlay fails before configure");
            Files.writeString(selectedConfig, "extra=\n");
            ok(DevicetreeOverlaySupport.extraCmakeArguments(app).isEmpty(),
                    "empty extra selection makes no additional build arguments");
            Files.writeString(cache, "ZEPHYR_BASE:PATH=/another/zephyr\n");
            ok("mismatch".equals(DevicetreeOverlaySupport.inspect(app, build, zephyr).state()),
                    "foreign build cache cannot be treated as project evidence");
        } finally {
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }
    }
}
