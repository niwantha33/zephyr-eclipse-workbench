// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Path;
import java.util.List;

/** Exact user regression: do not silently accept a bare root or invented SoC controller. */
public final class DevicetreeEditorDiagnosticsTests {
    private static int count;
    private static void check(boolean success, String description) {
        if (!success) throw new AssertionError(description);
        count++;
    }
    private static DevicetreeIndex.Snapshot built() {
        return new DevicetreeIndex.Snapshot("built", "fixture", Path.of("zephyr.dts"),
                List.of(new DevicetreeIndex.Node("", "/", "okay", "", 1),
                        new DevicetreeIndex.Node("", "/soc", "okay", "", 2),
                        new DevicetreeIndex.Node("adc0", "/soc/adc@4000", "disabled", "vendor,adc", 8)),
                List.of(), List.of());
    }
    public static void main(String[] args) {
        var index = built();
        String actual = """
                /* Application hardware overlay. Validate against this board's bindings. */

                {

                    soc {

                            adc:adc0@0{

                                };

                                };

                                };
                """;
        var issues = DevicetreeEditorDiagnostics.inspect(actual, index, true);
        check(issues.stream().anyMatch(p -> p.severity().equals("error")
                && p.message().contains("Bare '{'")), "Must reject user's bare root");
        check(issues.stream().anyMatch(p -> p.severity().equals("warning")
                && p.message().contains("new SoC node")), "Must explain new ADC node is not a board controller");
        String valid = """
                /* actual controller selected from generated DTS */
                &adc0 {
                    status = "okay";
                };
                """;
        check(DevicetreeEditorDiagnostics.inspect(valid, index, true).isEmpty(),
                "Valid reference to actual controller produces no bogus diagnostics");
        String named = """
                / {
                    soc {
                        adc: adc0@0 {
                        };
                    };
                };
                """;
        check(DevicetreeEditorDiagnostics.inspect(named, index, true).stream()
                .anyMatch(p -> p.message().contains("new SoC node")),
                "Even with root fixed, fabricated SoC controller needs warning");
        check(DevicetreeEditorDiagnostics.inspect(named, index, false).isEmpty(),
                "Source DTS declarations must not be treated as erroneous overlays");
        String comments = """
                /* { unmatched } */
                // adc:adc0@0 { }
                &adc0 {
                    label = "{ }";
                };
                """;
        check(DevicetreeEditorDiagnostics.inspect(comments, index, true).isEmpty(),
                "Ignore comments and braces inside strings");
        check(DevicetreeEditorDiagnostics.inspect("&unlisted { status = \"okay\"; };",
                index, true).stream().anyMatch(p -> p.message().contains("not present")),
                "Unresolved reference must be actionable when build index is available");
        check(DevicetreeEditorDiagnostics.inspect("&unlisted { status = \"okay\"; };",
                DevicetreeIndex.empty("no-build", "none"), true).isEmpty(),
                "No unsupported absence claims without build evidence");
        check(DevicetreeEditorDiagnostics.inspect("&{/soc/adc@4000} { status = \"okay\"; };",
                index, true).isEmpty(), "Real absolute path references parse correctly");
        check(DevicetreeEditorDiagnostics.inspect("&adc0 { status = \"okay\"; }",
                index, true).stream().anyMatch(p -> p.message().contains("Missing ';'")),
                "Missing terminator detected");
        System.out.println("Passed " + count + " Devicetree editor diagnostic checks.");
    }
}
