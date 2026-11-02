// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.DevicetreeSourceExplorer;
import io.github.niwantha33.zew.core.DevicetreeSourceTrace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class DevicetreeSourceTraceTests {
    private static int count;
    private static void ok(boolean v, String why) {
        if (!v) throw new AssertionError(why);
        count++;
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("zew-node-trace-");
        try {
            Path app = root.resolve("app");
            Path zephyr = root.resolve("zephyr");
            Path build = app.resolve("build");
            Files.createDirectories(zephyr.resolve("dts"));
            Files.createDirectories(app);
            Files.createDirectories(build.resolve("zephyr"));
            Path soc = zephyr.resolve("dts/part.dtsi");
            Path board = zephyr.resolve("board.dts");
            Path overlay = app.resolve("app.overlay");
            String generated = """
                    /dts-v1/;
                    / {
                        soc {
                            adc0: adc@4000 {
                                compatible = "vendor,adc";
                                status = "okay";
                            };
                        };
                    };
                    """;
            Files.writeString(soc, """
                    / {
                        soc {
                            adc0: adc@4000 {
                                status = "disabled";
                            };
                        };
                    };
                    """);
            Files.writeString(board, """
                    &adc0 {
                        status = "okay";
                    };
                    """);
            Files.writeString(overlay, """
                    &{/soc/adc@4000} {
                        status = "disabled";
                    };
                    """);
            String deps = build.resolve("zephyr/zephyr.dts.pre") + ": "
                    + soc + " " + board + "\n";
            Files.writeString(build.resolve("zephyr/zephyr.dts.d"), deps);
            var inventory = DevicetreeSourceExplorer.inspect(app, build);
            var report = DevicetreeSourceTrace.inspect(generated, inventory,
                    "/soc/adc@4000", "status");
            ok(report.builtPropertyPresent() && report.builtValue().equals("\"okay\""),
                    "generated winning value remains separate from source declaration");
            ok(report.hits().size() == 3, "source DTSI, label override and inactive app overlay shown");
            ok(report.hits().stream().filter(h -> h.source().buildInput()).count() == 2,
                    "only actual build dependencies are marked active");
            ok(report.hits().stream().anyMatch(h -> !h.source().buildInput()
                    && h.source().applicationOverlay()), "inactive app overlay is candidate only");
            Path canonicalSoc = soc.toRealPath();
            ok(report.hits().stream().anyMatch(h -> h.source().path().equals(canonicalSoc)
                    && h.sourceValue().equals("\"disabled\"") && h.line() == 4),
                    "actual source file and line with overridden value");
            ok(report.notice().contains("not a proven winning"), "no invented property provenance");
            ok(DevicetreeSourceTrace.inspect(generated, inventory, "/soc/adc@4000", "reg")
                    .builtPropertyPresent() == false, "missing property not invented");
            ok(DevicetreeSourceTrace.inspect(generated, inventory, "/soc/not-a-node", "status")
                    .hits().isEmpty(), "missing built node prevents misleading source match");
            Files.writeString(board, """
                    &unknown_vendor_label {
                        status = "okay";
                    };
                    """);
            var unresolved = DevicetreeSourceTrace.inspect(generated, inventory,
                    "/soc/adc@4000", "status");
            ok(unresolved.hits().size() == 2,
                    "unknown label never mapped speculatively to a node");
            Files.delete(build.resolve("zephyr/zephyr.dts.d"));
            var stale = DevicetreeSourceExplorer.inspect(app, build);
            ok(stale.sources().size() == 1 && !stale.sources().get(0).buildInput(),
                    "no stale dependency evidence after build metadata disappears");
            System.out.println("PASS integrated Devicetree source trace: " + count);
        } finally {
            try (var files = Files.walk(root)) {
                for (var f : files.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(f);
            }
        }
    }
}
