// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Conservative source-to-built-property navigator for the selected Zephyr build.
 *
 * Dependency membership proves a source was supplied to the preprocessor, NOT
 * that a particular assignment won override precedence. The only effective
 * values are those in the generated zephyr.dts. This does not run the C
 * preprocessor, expand bindings, invent addresses, or edit upstream files.
 */
public final class DevicetreeSourceTrace {
    private DevicetreeSourceTrace() {}

    public record Hit(DevicetreeSourceExplorer.Source source, int line,
                      String node, String property, String sourceValue) {
        public String evidence() {
            return source.buildInput() ? "Build input; declaration candidate"
                    : "Unverified overlay candidate; not a built input";
        }
    }

    public record Report(String node, String property, String builtValue,
                         boolean builtPropertyPresent, List<Hit> hits, String notice) {
        public Report { hits = List.copyOf(hits); }
    }

    private static final Pattern NODE = Pattern.compile(
            "^(?:(?:[A-Za-z_][A-Za-z0-9_,-]*):\\s*)*"
            + "(&\\{/[A-Za-z0-9_.,+@/\\-]*\\}|&[A-Za-z_][A-Za-z0-9_,-]*"
            + "|/|[A-Za-z_][A-Za-z0-9_.,-]*(?:@[A-Za-z0-9,]+)?)\\s*\\{\\s*$");
    private static final Pattern PROPERTY = Pattern.compile(
            "^([A-Za-z_#][A-Za-z0-9_#,+.\\-]*)\\s*(?:=\\s*(.*))?;\\s*$",
            Pattern.DOTALL);
    private static final int MAX_FILES = 64;
    private static final int MAX_HITS = 128;

    public static Report inspect(String generated, DevicetreeSourceExplorer.Inventory inventory,
                                 String node, String property) throws IOException {
        if (generated == null || generated.isBlank() || inventory == null)
            return new Report(node, property, "", false, List.of(),
                    "Build/configure the selected target before tracing properties.");
        if (node == null || property == null
                || !node.matches("/(?:[A-Za-z0-9_.,+@-]+(?:/[A-Za-z0-9_.,+@-]+)*)?")
                || !property.matches("[A-Za-z_#][A-Za-z0-9_#,+.\\-]*"))
            throw new IllegalArgumentException("Select an exact generated node and property.");
        boolean exists = DevicetreeHardware.inspect(generated).stream()
                .anyMatch(d -> node.equals(d.path()));
        if (!exists)
            return new Report(node, property, "", false, List.of(),
                    "This node is absent from the selected generated DTS; no source-to-build claim.");
        DevicetreeParameters.Property built = DevicetreeParameters.inspect(generated).stream()
                .filter(p -> node.equals(p.path()) && property.equals(p.name()))
                .findFirst().orElse(null);
        List<Hit> hits = new ArrayList<>();
        Map<String, String> labels = DevicetreeHardware.nodeLabels(generated);
        List<DevicetreeSourceExplorer.Source> sources = inventory.sources();
        int files = Math.min(MAX_FILES, sources.size());
        for (int i = 0; i < files && hits.size() < MAX_HITS; i++) {
            var source = sources.get(i);
            // The existing source browser enforces regular files, no symlinks,
            // and a two-megabyte read cap; never bypass its ownership checks.
            String text;
            try { text = DevicetreeSourceExplorer.sourceText(source); }
            catch (IOException unreadable) { continue; }
            scan(source, text, labels, node, property, hits);
        }
        String note = "Built value comes ONLY from generated zephyr.dts. "
                + "Matching lines are possible source declarations, not a proven winning "
                + "assignment or include-order trace. Build dependencies indicate inputs, "
                + "not their precedence. Macro-generated/inline declarations may be absent."
                + (sources.size() > MAX_FILES || hits.size() >= MAX_HITS
                        ? " Trace truncated at the bounded source/match limit." : "")
                + (built == null ? " Selected property is absent from generated DTS." : "")
                + "\n" + inventory.notice();
        return new Report(node, property, built == null ? "" : built.displayValue(),
                built != null, hits, note);
    }

    private static void scan(DevicetreeSourceExplorer.Source source, String text,
                             Map<String, String> labels, String wantedPath,
                             String wantedProperty, List<Hit> output) {
        String[] rows = DevicetreeParameters.withoutComments(text).split("\\R", -1);
        var scope = new ArrayList<String>();
        StringBuilder pending = new StringBuilder();
        int firstLine = 0;
        for (int i = 0; i < rows.length && output.size() < MAX_HITS; i++) {
            String row = rows[i].strip();
            if (row.isEmpty() || row.startsWith("#") || row.startsWith("/include/")) continue;
            var header = NODE.matcher(row);
            if (header.matches()) {
                String token = header.group(1);
                String parent = scope.isEmpty() ? "" : scope.get(scope.size() - 1);
                String path;
                if (token.equals("/")) path = "/";
                else if (token.startsWith("&{")) path = token.substring(2, token.length() - 1);
                else if (token.startsWith("&")) path = labels.getOrDefault(token.substring(1), "");
                else if (parent.isEmpty() && !scope.isEmpty()) path = "";
                else path = (parent.isEmpty() || parent.equals("/") ? "/" : parent + "/") + token;
                scope.add(path);
                pending.setLength(0);
                continue;
            }
            if (row.equals("};") || row.equals("}")) {
                if (!scope.isEmpty()) scope.remove(scope.size() - 1);
                pending.setLength(0);
                continue;
            }
            if (scope.isEmpty() || !wantedPath.equals(scope.get(scope.size() - 1)))
                continue;
            if (pending.isEmpty()) firstLine = i + 1;
            else pending.append(' ');
            pending.append(row);
            if (pending.length() > 8192) { pending.setLength(0); continue; }
            if (!row.endsWith(";")) continue;
            var parsed = PROPERTY.matcher(pending);
            if (parsed.matches() && wantedProperty.equals(parsed.group(1))) {
                output.add(new Hit(source, firstLine, wantedPath, wantedProperty,
                        parsed.group(2) == null ? "(boolean present)"
                                : parsed.group(2).strip()));
            }
            pending.setLength(0);
        }
    }
}
