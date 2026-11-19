// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.ui.DtsGraphModel;
import io.github.niwantha33.zew.ui.HardwareStudioDocument;
import java.nio.file.Path;
import java.util.List;

/** Pure Java browser-handoff contract: no SWT, browser, probe or network. */
public final class HardwareStudioDocumentTests {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void rejects(Runnable code) {
        try { code.run(); throw new AssertionError("Expected fail-closed document"); }
        catch (IllegalArgumentException expected) { /* invalid template or payload */ }
    }
    public static void main(String[] args) {
        final String template = "<!doctype html><script>const DATA = __HARDWARE_STUDIO_DATA__;</script>";
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
        check(html.contains("\\"observed\\":true") && html.contains("\\"stale\\":false"),
                "observed and stale statuses reflect the snapshot");
        check(html.contains("\\"kind\\":\\"gpio\\"") && html.contains("\\"line\\":5"),
                "typed verified graph references and source lines survive");
        check(!html.contains(malicious) && !html.contains("</script><script>alert")
                && html.contains("\\u003c/script\\u003e"),
                "untrusted node text is inert JSON, never executable script");
        check(!html.contains("project <>") && !html.contains("build\\path"),
                "project context is escaped as data");
        check(html.contains("\\"limited\\":false"), "bounded focus evidence declared");
        var unavailable = HardwareStudioDocument.render(template,
                DtsGraphModel.unavailable("Build first"),
                "","", "", false);
        check(unavailable.contains("\\"observed\\":false")
                && unavailable.contains("\\"nodes\\":[]")
                && unavailable.contains("Build first"), "empty evidence is not fabricated");
        rejects(() -> HardwareStudioDocument.render("<html>missing</html>",
                result, "", "", "", false));
        rejects(() -> HardwareStudioDocument.render(
                "__HARDWARE_STUDIO_DATA__ __HARDWARE_STUDIO_DATA__",
                result, "", "", "", false));
        System.out.println("HardwareStudioDocumentTests PASS");
    }
}
