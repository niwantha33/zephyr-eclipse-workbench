// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.ConfigurationStudioFiles;
import io.github.niwantha33.zew.core.FocusedConfigurationModel;
import io.github.niwantha33.zew.core.KconfigInspector;
import io.github.niwantha33.zew.ui.ConfigurationStudioVisualDocument;
import java.util.List;
import java.nio.file.Path;

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
        var source = new ConfigurationStudioFiles.Snapshot(List.of(
                new ConfigurationStudioFiles.Option("GPIO","y","n",3),
                new ConfigurationStudioFiles.Option("BROKEN",null,"y",6),
                new ConfigurationStudioFiles.Option("STRING","\"hello\"",null,0)),
                "", "debug", Path.of("/workspace/application/prj.conf"));
        String linked = ConfigurationStudioVisualDocument.render(template,
                "project", "board", "debug", "build", overview, "", "", false,
                "dark", "software", source);
        check(linked.contains("\"symbol\":\"CONFIG_GPIO\",\"value\":\"y\",\"requested\":\"n\"")
                        && linked.contains("\"toggle\":true")
                        && linked.contains("\"requestedLine\":3"),
                "effective and requested state and project line remain distinguishable");
        check(linked.contains("\"symbol\":\"CONFIG_BROKEN\",\"value\":\"Not resolved\",\"requested\":\"y\"")
                        && linked.contains("\"unresolved\":true"),
                "unresolved project request remains visible for repair");
        check(linked.contains("\"symbol\":\"CONFIG_STRING\"")
                        && linked.contains("\"requested\":null")
                        && linked.contains("\"toggle\":false"),
                "inherited nonboolean values never acquire a graphical toggle");
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
        for (int i = 0; i < 5200; i++) longConfig.append("CONFIG_FIXTURE_").append(i).append("=y\n");
        String bounded = ConfigurationStudioVisualDocument.render(template,
                "", "", "", "", overview, longConfig.toString(), "", false, "dark", "overview");
        check(bounded.contains("\"settingsLimited\":true")
                        && !bounded.contains("\"symbol\":\"CONFIG_FIXTURE_5199\""),
                "large generated configuration is explicitly bounded");
        var more = new java.util.ArrayList<ConfigurationStudioFiles.Option>();
        for (int i = 0; i < 5100; i++)
            more.add(new ConfigurationStudioFiles.Option("GENERATED_" + i, "n", null, 0));
        more.add(new ConfigurationStudioFiles.Option("UNRESOLVED_REPAIR", null, "y", 14));
        var sourceOverflow = new ConfigurationStudioFiles.Snapshot(
                more, "", "debug", Path.of("/workspace/application/prj.conf"));
        String overflowHtml = ConfigurationStudioVisualDocument.render(template,
                "", "", "", "", overview, "", "", false, "dark", "software", sourceOverflow);
        check(overflowHtml.contains("\"symbol\":\"CONFIG_UNRESOLVED_REPAIR\"")
                        && overflowHtml.contains("\"settingsLimited\":true"),
                "project-owned unresolved requests remain visible even when generated settings exceed cap");
        String focused = ConfigurationStudioVisualDocument.render(template,
                "project", "board", "debug", "build", overview, "", "", false,
                "dark", "hardware", source, "/soc/adc@4000");
        check(focused.contains("\"focus\":\"/soc/adc@4000\""),
                "exact observed controller focus restored");
        String notObserved = ConfigurationStudioVisualDocument.render(template,
                "project", "board", "debug", "build", overview, "", "", false,
                "dark", "hardware", source, "/soc/unobserved@bad");
        check(notObserved.contains("\"focus\":\"\""),
                "arbitrary hardware focus must never be serialised");

        var details = new KconfigInspector.Details("GPIO", "bool", "n", "y",
                "n,y", "SPI && GPIO", "n", "Use GPIO",
                "drivers/gpio/Kconfig:12", "ENABLE_LOG", "ENABLE_PRINTK", hostile);
        String upstream = ConfigurationStudioVisualDocument.kconfigInspectorPayload(
                "CONFIG_GPIO", new KconfigInspector.Result(details, ""),
                "CURRENT_SNAPSHOT");
        check(upstream.contains("\"available\":true")
                && upstream.contains("\"type\":\"bool\"")
                && upstream.contains("\"effective\":\"n\"")
                && upstream.contains("\"visibility\":\"y\"")
                && upstream.contains("\"assignable\":\"n,y\""),
                "real Kconfig type, value, visibility and assignability retained");
        // This payload is embedded in HTML: ampersands must remain escaped in
        // its JSON representation. JSON.parse restores the original && text.
        check(upstream.contains("\"dependencies\":\"SPI \\u0026\\u0026 GPIO\"")
                && upstream.contains("\"dependencyState\":\"n\"")
                && upstream.contains("\"prompt\":\"Use GPIO\"")
                && upstream.contains("\"definitions\":\"drivers/gpio/Kconfig:12\"")
                && upstream.contains("\"selects\":\"ENABLE_LOG\"")
                && upstream.contains("\"implies\":\"ENABLE_PRINTK\""),
                "HTML-safe Kconfig dependency, definition and select/imply evidence retained");
        check(!upstream.contains(hostile) && upstream.contains("\\u003c/script\\u003e"),
                "upstream Kconfig help cannot inject a script");
        String staleUpstream = ConfigurationStudioVisualDocument.kconfigInspectorPayload(
                "CONFIG_GPIO", new KconfigInspector.Result(details, ""),
                "STALE_SNAPSHOT");
        check(staleUpstream.contains("\"available\":false")
                && !staleUpstream.contains("\"selects\""),
                "stale dependency data is never represented as validated");
        String differentSymbol = ConfigurationStudioVisualDocument.kconfigInspectorPayload(
                "CONFIG_ADC", new KconfigInspector.Result(details, ""),
                "CURRENT_SNAPSHOT");
        check(differentSymbol.contains("\"available\":false"),
                "another symbol's Kconfig evidence cannot be reused");
        rejects(() -> ConfigurationStudioVisualDocument.kconfigInspectorPayload(
                "CONFIG_BAD;exit", new KconfigInspector.Result(details, ""), "CURRENT_SNAPSHOT"));
        System.out.println("ConfigurationStudioVisualDocumentTests PASS");
    }
}
