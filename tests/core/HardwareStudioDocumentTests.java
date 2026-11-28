// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.ui.DtsGraphModel;
import io.github.niwantha33.zew.ui.HardwareStudioDocument;
import java.nio.file.Path;
import java.util.List;
import io.github.niwantha33.zew.core.ConfigurationEvidenceReceipt;
import io.github.niwantha33.zew.core.ConfigurationEvidenceReceipt.State;

/** Pure Java browser handoff contract: no SWT, browser, probe or network. */
public final class HardwareStudioDocumentTests {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void rejects(Runnable code) {
        try { code.run(); throw new AssertionError("Expected fail-closed document"); }
        catch (IllegalArgumentException expected) { /* invalid template */ }
    }
    public static void main(String[] args) {
        final String template = "<!doctype html><script>const DATA = __HARDWARE_STUDIO_DATA__;</script>";
        final String q = Character.toString((char) 34);
        String malicious = "</script><script>alert('x')</script>\n\\\"";
        var nodes = List.of(
                new DtsGraphModel.Node("/", "/", "okay", "", "", 1),
                new DtsGraphModel.Node("/soc", "soc", "okay", "", "", 2),
                new DtsGraphModel.Node("/soc/gpio@1000", malicious, "okay",
                        "vendor,gpio", "gpio0", 3),
                new DtsGraphModel.Node("/leds", "leds", "okay", "gpio-leds", "", 4),
                new DtsGraphModel.Node("/leds/blue", "blue", "disabled",
                        "", "blue_led", 5));
        var links = List.of(new DtsGraphModel.Edge(
                "/leds/blue", "/soc/gpio@1000", "gpio", "gpios", "pin=17"));
        var result = new DtsGraphModel.Snapshot("built", "verified",
                "a".repeat(64), Path.of("/app/.zew-build/a/zephyr/zephyr.dts"),
                nodes, links);
        String html = HardwareStudioDocument.render(template, result,
                "project <>", "build\\path", "/leds/blue", false);
        check(html.startsWith("<!doctype html>") && !html.contains("__HARDWARE_STUDIO_DATA__"),
                "fixed offline resource interpolates exactly once");
        check(html.contains(q+"observed"+q+":true") && html.contains(q+"stale"+q+":false"),
                "observed and stale statuses reflect the snapshot");
        check(html.contains(q+"kind"+q+":"+q+"gpio"+q)
                && html.contains(q+"line"+q+":5"),
                "typed verified graph references and source lines survive");
        check(!html.contains(malicious) && !html.contains("</script><script>alert")
                && html.contains("\\u003c/script\\u003e"),
                "untrusted node text is inert JSON, never executable script");
        check(!html.contains("project <>") && !html.contains("build\\path"),
                "project context is escaped as data");
        check(html.contains(q+"limited"+q+":false"), "bounded focus evidence declared");
        var lightHtml = HardwareStudioDocument.render(template, result,
                "project", "build", "/soc", false, "light");
        check(lightHtml.contains(q+"theme"+q+":"+q+"light"+q),
                "the browser theme survives exact-build rerenders");
        var untrustedTheme = HardwareStudioDocument.render(template, result,
                "project", "build", "/soc", false, "javascript:alert(1)");
        check(untrustedTheme.contains(q+"theme"+q+":"+q+"dark"+q),
                "the theme bridge is an exact allowlist");
        var unavailable = HardwareStudioDocument.render(template,
                DtsGraphModel.unavailable("Build first"), "", "", "", false);
        check(unavailable.contains(q+"observed"+q+":false")
                && unavailable.contains(q+"nodes"+q+": []".replace(" ",""))
                && unavailable.contains("Build first"), "empty evidence is not fabricated");
        var current = new ConfigurationEvidenceReceipt.Result(State.CURRENT, "matched", 4,
                "b".repeat(64), result.sha256());
        var mismatched = new ConfigurationEvidenceReceipt.Result(State.CURRENT, "matched", 4,
                "b".repeat(64), "c".repeat(64));
        check(HardwareStudioDocument.freshness(result, current).current(), "current receipt matches observed DTS");
        check(HardwareStudioDocument.freshness(result, mismatched).state().equals("changed"),
                "different observed SHA cannot be current");
        check(HardwareStudioDocument.freshness(result, null).state().equals("unverified"),
                "missing receipt cannot certify freshness");
        for (State state : List.of(State.CHANGED, State.UNVERIFIED, State.NO_BUILD)) {
            var receipt = new ConfigurationEvidenceReceipt.Result(state, malicious, 0, "", "");
            var freshness = HardwareStudioDocument.freshness(result, receipt);
            check(!freshness.current(), "non-current receipt cannot certify freshness");
            var historical = HardwareStudioDocument.render(template, result, "project", "build", "/soc",
                    true, "dark", "board", "debug", true, freshness);
            check(historical.contains(q+"stale"+q+":true") && historical.contains(q+"freshness"+q)
                    && !historical.contains(malicious), "historical state and escaped receipt detail");
        }
        rejects(() -> HardwareStudioDocument.render("<html>missing</html>",
                result, "", "", "", false));
        rejects(() -> HardwareStudioDocument.render(
                "__HARDWARE_STUDIO_DATA__ __HARDWARE_STUDIO_DATA__",
                result, "", "", "", false));
        System.out.println("HardwareStudioDocumentTests PASS");
    }
}
