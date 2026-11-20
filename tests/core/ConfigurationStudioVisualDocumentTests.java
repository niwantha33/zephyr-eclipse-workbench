// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.FocusedConfigurationModel;
import io.github.niwantha33.zew.ui.ConfigurationStudioVisualDocument;
import java.util.List;

/** Pure Java safety/semantics tests; requires neither Eclipse nor a browser. */
public final class ConfigurationStudioVisualDocumentTests {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Runnable run) {
        try { run.run(); throw new AssertionError("Expected refusal"); }
        catch (IllegalArgumentException expected) { /* fail-closed */ }
    }
    public static void main(String[] args) {
        String template = "<!doctype html><script>const DATA = __CONFIGURATION_STUDIO_DATA__;</script>";
        String hostile = "</script><script>alert(1)</script>";
        var overview = new FocusedConfigurationModel.Overview(
                FocusedConfigurationModel.Freshness.UNVERIFIED_SNAPSHOT,
                "board", "debug",
                new FocusedConfigurationModel.Capacity("Flash","Unknown","Unverified"),
                new FocusedConfigurationModel.Capacity("RAM","Unknown","Unverified"),
                List.of(new FocusedConfigurationModel.Row(
                        "ADC", FocusedConfigurationModel.State.UNKNOWN,
                        "/soc/adc@4000", hostile, "Build exact board/profile")),
                List.of(new FocusedConfigurationModel.Issue(
                        "Provenance unverified", hostile, "Rebuild", "ADC")));
        String html = ConfigurationStudioVisualDocument.render(template,
                "project<>", "board", "debug", "C:\\workspace\\build",
                overview, "CONFIG_GPIO=y\n# CONFIG_ADC is not set\n"
                        + "CONFIG_QUOTED=\"\\\"hello\\\"\"\\n",
                hostile, true, "light", "software");
        check(html.contains("\"freshness\":\"UNVERIFIED_SNAPSHOT\""),
                "unverified receipt is not promoted");
        check(html.contains("\"dirty\":true") && html.contains("\"theme\":\"light\""),
                "pending edits and theme are preserved");
        check(html.contains("\"section\":\"software\""), "exact navigation preserved");
        check(html.contains("\"symbol\":\"CONFIG_ADC\",\"value\":\"n\""),
                "explicit not-set is shown, not inferred");
        check(html.contains("\"symbol\":\"CONFIG_GPIO\",\"value\":\"y\""),
                "observed generated setting is shown");
        check(html.contains("\"flash\":\"Unknown\""),
                "missing memory capacities are not fabricated");
        check(!html.contains(hostile) && !html.contains("project<>")
                        && html.contains("\\u003c/script\\u003e"),
                "hostile project and DTS text remains inert JSON");
        check(html.contains("\"state\":\"UNKNOWN\""),
                "peripheral state preserved from evidence");
        String unavailable = ConfigurationStudioVisualDocument.render(template, "", "", "",
                "", null, "", "", false, "bad-theme", "untrusted-section");
        check(unavailable.contains("\"hasEvidence\":false")
                        && unavailable.contains("\"freshness\":\"NO_BUILD\"")
                        && unavailable.contains("\"settings\":[]")
                        && unavailable.contains("\"theme\":\"dark\"")
                        && unavailable.contains("\"section\":\"overview\""),
                "missing evidence and untrusted selector fail closed");
        rejects(() -> ConfigurationStudioVisualDocument.render("<html></html>",
                "", "", "", "", null, "", "", false, "dark", "overview"));
        rejects(() -> ConfigurationStudioVisualDocument.render(
                "__CONFIGURATION_STUDIO_DATA__ __CONFIGURATION_STUDIO_DATA__",
                "", "", "", "", null, "", "", false, "dark", "overview"));
        StringBuilder longConfig = new StringBuilder();
        for (int i = 0; i < 2000; i++) longConfig.append("CONFIG_FIXTURE_").append(i).append("=y\n");
        String bounded = ConfigurationStudioVisualDocument.render(template,
                "", "", "", "", overview, longConfig.toString(), "", false, "dark", "overview");
        check(bounded.contains("\"settingsLimited\":true")
                        && !bounded.contains("\"symbol\":\"CONFIG_FIXTURE_1999\""),
                "large generated configuration is explicitly bounded");
        System.out.println("ConfigurationStudioVisualDocumentTests PASS");
    }
}
