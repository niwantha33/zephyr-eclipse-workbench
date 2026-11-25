// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.List;

/**
 * Pure-JDK, one-way, bounded JSON model for the locally bundled Hardware Studio
 * webview. The observer and DtsGraphModel remain authoritative. Never turn a
 * DTS value into browser script, a URL, a filesystem request or an HTML fragment.
 */
public final class HardwareStudioDocument {
    private static final String TOKEN = "__HARDWARE_STUDIO_DATA__";

    private HardwareStudioDocument() {}

    public static String render(String template, DtsGraphModel.Snapshot observation,
                                String project, String build, String focus, boolean stale) {
        return render(template, observation, project, build, focus, stale, "dark");
    }

    public static String render(String template, DtsGraphModel.Snapshot observation,
                                String project, String build, String focus, boolean stale,
                                String selectedTheme) {
        return render(template, observation, project, build, focus, stale,
                selectedTheme, "", "");
    }

    public static String render(String template, DtsGraphModel.Snapshot observation,
                                String project, String build, String focus, boolean stale,
                                String selectedTheme, String board, String profile) {
        return render(template, observation, project, build, focus, stale, selectedTheme, board, profile, false);
    }

    public static String render(String template, DtsGraphModel.Snapshot observation,
                                String project, String build, String focus, boolean stale,
                                String selectedTheme, String board, String profile, boolean physical) {
        if (template == null || template.length() > 180_000
                || template.indexOf(TOKEN) < 0
                || template.indexOf(TOKEN) != template.lastIndexOf(TOKEN))
            throw new IllegalArgumentException("Missing or duplicate Hardware Studio data slot");
        DtsGraphModel.Snapshot snapshot = observation == null
                ? DtsGraphModel.unavailable("Select a configured Zephyr project.") : observation;
        DtsGraphModel.Layout layout = DtsGraphModel.focus(snapshot, focus);
        StringBuilder json = new StringBuilder(512 + snapshot.nodes().size() * 125
                + snapshot.edges().size() * 90);
        json.append("{\"project\":"); quote(json, project);
        json.append(",\"build\":"); quote(json, build);
        json.append(",\"theme\":"); quote(json, "light".equals(selectedTheme) ? "light" : "dark");
        json.append(",\"board\":"); quote(json, board);
        json.append(",\"profile\":"); quote(json, profile);
        json.append(",\"state\":"); quote(json, snapshot.state());
        json.append(",\"reason\":"); quote(json, snapshot.explanation());
        json.append(",\"sha256\":"); quote(json, snapshot.sha256());
        json.append(",\"observed\":").append(snapshot.observed());
        json.append(",\"stale\":").append(stale);
        json.append(",\"focus\":"); quote(json, layout.focus());
        json.append(",\"message\":"); quote(json, layout.message());
        json.append(",\"limited\":").append(layout.limited());
        json.append(",\"width\":").append(layout.width());
        json.append(",\"height\":").append(layout.height());
        json.append(",\"nodes\":[");
        for (int i = 0; i < snapshot.nodes().size(); i++) {
            if (i > 0) json.append(',');
            node(json, snapshot.nodes().get(i));
        }
        json.append("],\"cards\":[");
        for (int i = 0; i < layout.cards().size(); i++) {
            if (i > 0) json.append(',');
            var card = layout.cards().get(i);
            json.append("{\"path\":"); quote(json, card.node().path());
            json.append(",\"x\":").append(card.x())
                .append(",\"y\":").append(card.y()).append('}');
        }
        json.append("],\"edges\":[");
        var edges = physical ? snapshot.edges() : layout.edges();
        for (int i = 0; i < edges.size(); i++) {
            if (i > 0) json.append(',');
            var edge = edges.get(i);
            json.append("{\"source\":"); quote(json, edge.source());
            json.append(",\"target\":"); quote(json, edge.target());
            json.append(",\"kind\":"); quote(json, edge.kind());
            json.append(",\"property\":"); quote(json, edge.property());
            json.append(",\"detail\":"); quote(json, edge.detail());
            json.append('}');
        }
        json.append("]}");
        if (json.length() > 3_500_000)
            throw new IllegalArgumentException("Hardware Studio data exceeds size limit");
        return template.replace(TOKEN, json.toString());
    }

    private static void node(StringBuilder json, DtsGraphModel.Node value) {
        json.append("{\"path\":"); quote(json, value.path());
        json.append(",\"name\":"); quote(json, value.name());
        json.append(",\"status\":"); quote(json, value.status());
        json.append(",\"compatible\":"); quote(json, value.compatible());
        json.append(",\"labels\":"); quote(json, value.labels());
        json.append(",\"line\":").append(value.line()).append('}');
    }

    /**
     * Escape JSON AND HTML raw-script end tags: even a malicious generated DTS
     * with </script> cannot escape the fixed inline script data expression.
     */
    static void quote(StringBuilder out, String value) {
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
