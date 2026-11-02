// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.DevicetreeSourceExplorer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class DevicetreeSourceExplorerTests {
    private static void ok(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
    public static void run() throws Exception {
        Path root = Files.createTempDirectory("zew-dts-all-vendors-");
        try {
            Path app = Files.createDirectories(root.resolve("my application"));
            Path zephyr = Files.createDirectories(root.resolve("zephyr"));
            Path build = Files.createDirectories(root.resolve("build/zephyr"));
            Path board = Files.createDirectories(zephyr.resolve("boards/vendor"));
            Path soc = Files.createDirectories(zephyr.resolve("dts/arm/vendor"));
            Path boardFile = Files.writeString(board.resolve("device with space.dts"), "/dts-v1/;\n/ {};\n");
            Path socFile = Files.writeString(soc.resolve("soc.dtsi"), "/ { soc {}; };\n");
            Path candidate = Files.writeString(app.resolve("app.overlay"), "&uart0 { status = \"okay\"; };\n");
            var missing = DevicetreeSourceExplorer.inspect(app, build.getParent());
            ok(missing.sources().size() == 1 && !missing.sources().get(0).buildInput()
                    && missing.notice().contains("No selected-build"),
                    "source-only view never treats an overlay as an active input");
            String dependencies = build.resolve("zephyr.dts.pre") + ": "
                    + boardFile.toString().replace(" ", "\\ ") + " \\\n "
                    + socFile + " " + candidate.toString().replace(" ", "\\ ") + " " + boardFile.toString().replace(" ", "\\ ") + "\n";
            Files.writeString(build.resolve("zephyr.dts.d"), dependencies);
            var inventory = DevicetreeSourceExplorer.inspect(app, build.getParent());
            ok(inventory.sources().size() == 3, "multiple vendor files and overlay with deduplication");
            Path actualBoard = boardFile.toRealPath();
            Path actualSoc = socFile.toRealPath();
            Path actualOverlay = candidate.toRealPath();
            ok(inventory.sources().stream().anyMatch(s -> s.path().equals(actualBoard)
                    && s.kind().equals("Board / shield DTS") && s.buildInput()),
                    "board file discovered by dependency, not a selected vendor name; inventory=" + inventory.sources());
            ok(inventory.sources().stream().anyMatch(s -> s.path().equals(actualSoc)
                    && s.kind().equals("SoC / architecture DTSI") && s.buildInput()),
                    "SoC include discovered by dependency without vendor assumptions; inventory=" + inventory.sources());
            ok(inventory.sources().stream().anyMatch(s -> s.path().equals(actualOverlay)
                    && s.applicationOverlay() && s.buildInput()),
                    "active application overlay marked only with dependency evidence; inventory=" + inventory.sources());
            ok(DevicetreeSourceExplorer.sourceText(inventory.sources().get(0)).contains("/dts-v1/"),
                    "real file content displayed read-only");
            ok(DevicetreeSourceExplorer.dependencyPaths(
                    "C:\\build\\zephyr\\zephyr.dts.pre: C:\\zephyr\\boards\\board.dts C:\\zephyr\\dts\\soc.dtsi")
                    .size() == 2, "Windows drive prefixes do not split dependency target");
            Files.delete(build.resolve("zephyr.dts.d"));
            ok(!DevicetreeSourceExplorer.inspect(app, build.getParent()).sources().get(0).buildInput(),
                    "stale dependency discovery is not retained after a missing build");
            if (Files.exists(root.resolve("external"))) throw new AssertionError("unexpected fixture path");
        } finally {
            try (var stream = Files.walk(root)) {
                for (var path : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
