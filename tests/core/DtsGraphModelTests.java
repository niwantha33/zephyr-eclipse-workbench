// SPDX-License-Identifier: Apache-2.0
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import io.github.niwantha33.zew.ui.DtsGraphModel;

/** Evidence safety and focused layout without an Eclipse installation. */
public final class DtsGraphModelTests {
    private static final String SHA = "a".repeat(64);
    private static void check(boolean truth, String message) {
        if (!truth) throw new AssertionError("DTS GRAPH: " + message);
        System.out.println("PASS DTS GRAPH: " + message);
    }
    private static String line(String kind, String... fields) {
        StringBuilder result = new StringBuilder(kind);
        for (String field : fields) {
            result.append("\t").append(Base64.getEncoder()
                    .encodeToString(field.getBytes(StandardCharsets.UTF_8)));
        }
        return result.append("\n").toString();
    }
    private static String fixture() {
        return "ZEW-DTSGRAPH-1\n"
                + line("S", "built", SHA, "real dtlib/edtlib")
                + line("N", "/", "/", "okay (default)", "", "", "1")
                + line("N", "/soc", "soc", "okay (default)", "", "", "4")
                + line("N", "/soc/gpio@4000", "gpio@4000", "okay", "ti,mspm0-gpio",
                        "gpioa", "19")
                + line("N", "/leds", "leds", "okay", "gpio-leds", "", "44")
                + line("N", "/leds/my_red_led", "my_red_led", "okay", "",
                        "red_led", "51")
                + line("E", "/leds/my_red_led", "/soc/gpio@4000", "gpio", "gpios",
                        "[0] pin=22");
    }
    public static void main(String[] args) {
        var source = Path.of("/fake/zephyr/zephyr.dts");
        var snapshot = DtsGraphModel.parse(fixture(), source);
        check(snapshot.observed() && snapshot.nodes().size() == 5
                && snapshot.edges().size() == 1, "verified observation is accepted");
        check(snapshot.generated().equals(source) && SHA.equals(snapshot.sha256()),
                "observed graph retains exact generated source and SHA identity");
        var layout = DtsGraphModel.focus(snapshot, "/leds/my_red_led");
        check("/leds/my_red_led".equals(layout.focus())
                && layout.cards().stream().anyMatch(c -> c.node().path().equals("/soc/gpio@4000")),
                "focused red LED includes its real GPIO controller");
        check(layout.edges().stream().anyMatch(e -> e.kind().equals("gpio")
                && e.source().equals("/leds/my_red_led")
                && e.target().equals("/soc/gpio@4000")),
                "dashed GPIO reference uses exactly resolved target, not arbitrary numeric cells");
        check(layout.edges().stream().anyMatch(e -> e.kind().equals("hierarchy")
                && e.source().equals("/leds") && e.target().equals("/leds/my_red_led")),
                "solid hierarchy edge is independently derived from observed paths");
        check(DtsGraphModel.focus(snapshot, "/unknown").cards().size() > 0,
                "missing focus is safe and uses actual existing root/SoC node");
        try {
            snapshot.nodes().clear();
            throw new AssertionError("mutable DTS evidence");
        } catch (UnsupportedOperationException expected) {
            check(true, "observations are immutable");
        }
        String unknown = fixture().replace(line("E", "/leds/my_red_led",
                "/soc/gpio@4000", "gpio", "gpios", "[0] pin=22"),
                line("E", "/leds/my_red_led", "/soc/gpio@ffff", "gpio", "gpios", "[0] pin=22"));
        check(!DtsGraphModel.parse(unknown, source).observed(),
                "unresolved phandle destinations fail closed");
        check(!DtsGraphModel.parse(fixture().replace(line("S", "built", SHA, "real dtlib/edtlib"),
                line("S", "unavailable", "", "not built")), source).observed(),
                "no-build result cannot contain claimed nodes");
        check(!DtsGraphModel.parse(fixture().replace(
                line("E", "/leds/my_red_led", "/soc/gpio@4000", "gpio", "gpios",
                        "[0] pin=22"),
                line("E", "/leds/my_red_led", "/soc/gpio@4000", "fiction", "gpios",
                        "[0] pin=22")), source).observed(),
                "unrecognized relationship types cannot enter hardware graph");
        check(!DtsGraphModel.parse(fixture() + line("N", "/soc", "soc", "okay",
                "", "", "4"), source).observed(),
                "duplicate node paths reject inconsistent snapshots");
        var noBuild = DtsGraphModel.parse("ZEW-DTSGRAPH-1\n"
                + line("S", "no-build", "", "Build required"), source);
        check(!noBuild.observed() && DtsGraphModel.focus(noBuild, "/").cards().isEmpty(),
                "no-build always renders a truthful empty graph");
    }
}
