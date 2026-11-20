// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import io.github.niwantha33.zew.core.ConfigurationStudioFiles;
import io.github.niwantha33.zew.core.ConfigurationStudioPresentation;
import io.github.niwantha33.zew.core.FocusedConfigurationModel;
import io.github.niwantha33.zew.core.KconfigInspector;
import io.github.niwantha33.zew.core.FocusedConfigurationModel.Overview;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only, bounded, pure-Java document bridge for the offline Configuration Studio.
 * The existing focused evidence model remains authoritative; this class neither
 * resolves Kconfig dependencies nor applies source changes.
 */
public final class ConfigurationStudioVisualDocument {
    private static final String SLOT = "__CONFIGURATION_STUDIO_DATA__";
    private static final Pattern ASSIGNMENT =
            Pattern.compile("^(CONFIG_[A-Z0-9_]{1,160})=(.{1,4096})$");
    private static final Pattern NOT_SET =
            Pattern.compile("^# (CONFIG_[A-Z0-9_]{1,160}) is not set$");
    private static final int MAX_SETTINGS = 5000;
    private static final int MAX_ROWS = 80;
    private static final int MAX_ISSUES = 32;
    private static final int MAX_HTML = 180_000;
    private static final int MAX_JSON = 3_500_000;

    private ConfigurationStudioVisualDocument() {}

    public static String render(String template, String project, String board, String profile,
                                String build, Overview overview, String effectiveConfig,
                                String warning, boolean dirty, String theme, String section) {
        return render(template, project, board, profile, build, overview, effectiveConfig,
                warning, dirty, theme, section, null);
    }

    public static String render(String template, String project, String board, String profile,
                                String build, Overview overview, String effectiveConfig,
                                String warning, boolean dirty, String theme, String section,
                                ConfigurationStudioFiles.Snapshot source) {
        return render(template, project, board, profile, build, overview, effectiveConfig,
                warning, dirty, theme, section, source, "");
    }

    public static String render(String template, String project, String board, String profile,
                                String build, Overview overview, String effectiveConfig,
                                String warning, boolean dirty, String theme, String section,
                                ConfigurationStudioFiles.Snapshot source, String focusedNode) {
        if (template == null || template.length() > MAX_HTML
                || template.indexOf(SLOT) < 0
                || template.indexOf(SLOT) != template.lastIndexOf(SLOT))
            throw new IllegalArgumentException("Missing or duplicate Configuration Studio slot");
        StringBuilder json = new StringBuilder(2048);
        json.append("{\"project\":"); quote(json, project);
        json.append(",\"board\":"); quote(json, board);
        json.append(",\"profile\":"); quote(json, profile);
        json.append(",\"build\":"); quote(json, build);
        json.append(",\"warning\":"); quote(json, warning);
        json.append(",\"theme\":"); quote(json, "light".equals(theme) ? "light" : "dark");
        json.append(",\"section\":"); quote(json, List.of("overview", "hardware", "software", "system")
                .contains(section) ? section : "overview");
        json.append(",\"dirty\":").append(dirty);
        json.append(",\"hasEvidence\":").append(overview != null);
        json.append(",\"freshness\":"); quote(json, overview == null
                ? "NO_BUILD" : overview.freshness().name());
        json.append(",\"flash\":"); quote(json, overview == null ? "Unknown" : overview.flash().value());
        json.append(",\"ram\":"); quote(json, overview == null ? "Unknown" : overview.ram().value());

        json.append(",\"focus\":"); quote(json, overview != null && focusedNode != null
                && overview.peripherals().stream().anyMatch(row ->
                        !row.controller().isBlank() && row.controller().equals(focusedNode))
                ? focusedNode : "");
        json.append(",\"rows\":[");
        if (overview != null) {
            int limit = Math.min(MAX_ROWS, overview.peripherals().size());
            for (int i = 0; i < limit; i++) {
                var row = overview.peripherals().get(i);
                if (i != 0) json.append(',');
                json.append("{\"category\":"); quote(json, row.category());
                json.append(",\"state\":"); quote(json, row.state().name());
                json.append(",\"controller\":"); quote(json, row.controller());
                json.append(",\"explanation\":"); quote(json, row.explanation());
                json.append(",\"nextAction\":"); quote(json, row.nextAction());
                json.append('}');
            }
        }
        json.append("],\"issues\":[");
        if (overview != null) {
            int limit = Math.min(MAX_ISSUES, overview.issues().size());
            for (int i = 0; i < limit; i++) {
                var issue = overview.issues().get(i);
                if (i != 0) json.append(',');
                json.append("{\"title\":"); quote(json, issue.title());
                json.append(",\"detail\":"); quote(json, issue.detail());
                json.append(",\"nextAction\":"); quote(json, issue.nextAction());
                json.append(",\"category\":"); quote(json, issue.category());
                json.append('}');
            }
        }
        json.append("],\"settings\":[");
        List<ConfigurationStudioFiles.Option> options;
        if (source == null) options = observedValues(effectiveConfig);
        else {
            // Keep explicit and unresolved project requests visible even if
            // the generated Kconfig snapshot exceeds the display limit.
            var ordered = new java.util.ArrayList<>(source.options());
            ordered.sort(java.util.Comparator.comparing(
                    (ConfigurationStudioFiles.Option value) -> !value.overridden()));
            options = ordered;
        }
        int added = 0;
        boolean limited = false;
        for (var opt : options) {
            if (added >= MAX_SETTINGS) { limited = true; break; }
            if (!opt.symbol().matches("[A-Z0-9_]{1,160}")) continue;
            if (added++ > 0) json.append(',');
            json.append("{\"symbol\":"); quote(json, "CONFIG_" + opt.symbol());
            json.append(",\"value\":"); quote(json, displayValue(opt.effective()));
            json.append(",\"requested\":");
            if (opt.requested() == null) json.append("null");
            else quote(json, displayValue(opt.requested()));
            json.append(",\"unresolved\":").append(opt.unresolved());
            json.append(",\"toggle\":").append(ConfigurationStudioPresentation.isToggle(opt));
            json.append(",\"requestedLine\":").append(Math.max(0, opt.requestedLine()));
            json.append('}');
        }
        json.append("],\"settingsLimited\":").append(limited);
        json.append('}');
        if (json.length() > MAX_JSON)
            throw new IllegalArgumentException("Configuration Studio document exceeds size bound");
        return template.replace(SLOT, json.toString());
    }

    /**
     * A read-only, bounded message from the *selected build's* upstream
     * Kconfiglib result. Never interpret text fields as scripts or dependencies
     * for another board/profile. Unknown evidence remains explicitly unknown.
     */
    public static String kconfigInspectorPayload(String symbol, KconfigInspector.Result inspection,
                                                 String provenance) {
        if (symbol == null || !symbol.matches("CONFIG_[A-Z0-9_]{1,160}"))
            throw new IllegalArgumentException("Exact Kconfig symbol required");
        StringBuilder json = new StringBuilder(1024);
        json.append("{\"symbol\":"); quote(json, symbol);
        json.append(",\"provenance\":"); quote(json,
                "CURRENT_SNAPSHOT".equals(provenance) ? provenance : "UNVERIFIED_SNAPSHOT");
        boolean available = inspection != null && inspection.available()
                && symbol.equals("CONFIG_" + inspection.details().symbol())
                && "CURRENT_SNAPSHOT".equals(provenance);
        json.append(",\"available\":").append(available);
        if (available) {
            var d = inspection.details();
            json.append(",\"type\":"); quote(json, boundedInspectorText(d.type()));
            json.append(",\"effective\":"); quote(json, boundedInspectorText(d.effective()));
            json.append(",\"visibility\":"); quote(json, boundedInspectorText(d.visibility()));
            json.append(",\"assignable\":"); quote(json, boundedInspectorText(d.assignable()));
            json.append(",\"dependencies\":"); quote(json, boundedInspectorText(d.dependencies()));
            json.append(",\"dependencyState\":"); quote(json, boundedInspectorText(d.dependencyState()));
            json.append(",\"prompt\":"); quote(json, boundedInspectorText(d.prompt()));
            json.append(",\"definitions\":"); quote(json, boundedInspectorText(d.definitions()));
            json.append(",\"selects\":"); quote(json, boundedInspectorText(d.selects()));
            json.append(",\"implies\":"); quote(json, boundedInspectorText(d.implies()));
            json.append(",\"help\":"); quote(json, boundedInspectorText(d.help()));
        } else {
            json.append(",\"message\":");
            quote(json, boundedInspectorText(inspection == null
                    ? "No selected-build Kconfiglib result."
                    : inspection.available() && !"CURRENT_SNAPSHOT".equals(provenance)
                            ? "Selected build provenance is not current; rebuild before inspecting dependencies."
                            : inspection.available()
                                    ? "Kconfiglib returned metadata for a different symbol."
                                    : inspection.unavailable()));
        }
        json.append('}');
        return json.toString();
    }

    private static String boundedInspectorText(String source) {
        if (source == null) return "";
        // A single upstream field must never exhaust SWT Browser memory.
        return source.length() <= 5000 ? source : source.substring(0, 5000) + "\n[truncated]";
    }

    private static String displayValue(String value) {
        if (value == null) return "Not resolved";
        return value.length() > 512 ? "(value longer than display limit; open native editor)" : value;
    }

    private static List<ConfigurationStudioFiles.Option> observedValues(String effective) {
        if (effective == null || effective.length() > 2_000_000) return List.of();
        var options = new java.util.ArrayList<ConfigurationStudioFiles.Option>();
        for (String line : effective.split("\\R")) {
            Matcher assigned = ASSIGNMENT.matcher(line);
            Matcher unset = NOT_SET.matcher(line);
            if (assigned.matches())
                options.add(new ConfigurationStudioFiles.Option(
                        assigned.group(1).substring(7), assigned.group(2), null, 0));
            else if (unset.matches())
                options.add(new ConfigurationStudioFiles.Option(
                        unset.group(1).substring(7), "n", null, 0));
            if (options.size() > MAX_SETTINGS) break;
        }
        return List.copyOf(options);
    }

    /**
     * Escapes both JSON and HTML raw-script terminators, preventing any
     * project-supplied text from escaping the fixed script data expression.
     */
    private static void quote(StringBuilder out, String value) {
        out.append('"');
        if (value != null) for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '<' -> out.append("\\u003c");
                case '>' -> out.append("\\u003e");
                case '&' -> out.append("\\u0026");
                case '\u2028' -> out.append("\\u2028");
                case '\u2029' -> out.append("\\u2029");
                default -> {
                    if (c < 0x20) {
                        String hex = Integer.toHexString(c);
                        out.append("\\u").append("0".repeat(4 - hex.length())).append(hex);
                    } else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
