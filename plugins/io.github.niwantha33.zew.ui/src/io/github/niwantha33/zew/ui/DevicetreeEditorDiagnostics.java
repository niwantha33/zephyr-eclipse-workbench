// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative, unsaved-document syntax hints for the native Eclipse editor.
 *
 * This is deliberately not a replacement for Zephyr's dtlib/edtlib, C
 * preprocessor or an actual configure/build. Hardware observations are used
 * only when the selected build index is available; unknown does NOT mean
 * missing hardware. Diagnostics never modify the application.
 */
final class DevicetreeEditorDiagnostics {
    record Problem(int offset, int length, String severity, String message) {}
    private record OpenNode(String path, String header) {}
    private static final int MAX_TEXT = 256 * 1024;
    private static final int MAX_PROBLEMS = 64;
    private static final Pattern NAMED_NODE = Pattern.compile(
            "(?:(?:[A-Za-z_][A-Za-z0-9_]*):\\s*)?([A-Za-z0-9,._+@-]+)");
    private static final Pattern LABEL_REF = Pattern.compile("&([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern PATH_REF = Pattern.compile("&\\{(/[^}]*)\\}");

    private DevicetreeEditorDiagnostics() {}

    static List<Problem> inspect(String document, DevicetreeIndex.Snapshot snapshot,
            boolean applicationOverlay) {
        if (document == null || document.isBlank()) return List.of();
        if (document.length() > MAX_TEXT) return List.of(new Problem(0, 1, "warning",
                "This editor's live syntax analysis is limited to 256 KiB; use the Zephyr build for validation."));
        char[] chars = maskCommentsAndStrings(document);
        var problems = new ArrayList<Problem>();
        var nodes = new ArrayDeque<OpenNode>();
        Set<String> knownPaths = new HashSet<>();
        if (snapshot != null && snapshot.built()) {
            for (var node : snapshot.nodes()) knownPaths.add(node.path());
        }
        int statementStart = 0;
        boolean pathReference = false;
        for (int i = 0; i < chars.length && problems.size() < MAX_PROBLEMS; i++) {
            char ch = chars[i];
            if (pathReference) {
                if (ch == '}') pathReference = false;
                continue;
            }
            if (ch == '{' && i > 0 && chars[i - 1] == '&') {
                pathReference = true;
                continue;
            }
            if (ch == '{') {
                String header = cleanHeader(chars, statementStart, i);
                if (header.isEmpty()) {
                    problems.add(new Problem(i, 1, "error",
                            "Bare '{' is not a valid Devicetree root. Write '/ {' or reference a real node using &label { ... };"));
                } else if (nodes.isEmpty() && !header.equals("/") && !header.startsWith("&")) {
                    problems.add(new Problem(Math.max(statementStart, i - header.length()),
                            Math.max(1, header.length()), "error",
                            "Top-level node must be '/ {' or a reference to an existing node ('&label {')."));
                }
                String path = resolvePath(header, nodes);
                if (snapshot != null && snapshot.built()) {
                    Matcher ref = LABEL_REF.matcher(header);
                    if (ref.matches() && snapshot.label(ref.group(1)) == null) {
                        problems.add(new Problem(Math.max(0, i - header.length()), header.length(), "warning",
                                "This node label is not present in the selected generated DTS. "
                                + "Use Ctrl+Space after '&' to select a real label; unsaved sources are not indexed."));
                    }
                    Matcher pathRef = PATH_REF.matcher(header);
                    if (pathRef.matches() && !knownPaths.contains(pathRef.group(1))) {
                        problems.add(new Problem(Math.max(0, i - header.length()), header.length(), "warning",
                                "This path is not in the last selected generated DTS. Rebuild or choose a verified path."));
                    }
                }
                if (applicationOverlay && nodes.size() > 0 && "/soc".equals(nodes.peek().path())
                        && path != null && !knownPaths.contains(path)
                        && NAMED_NODE.matcher(header).matches()) {
                    problems.add(new Problem(Math.max(0, i - header.length()), header.length(), "warning",
                            "This declares a new SoC node; it does not configure an existing controller. "
                            + "For a board peripheral, use Ctrl+Space after '&' and choose its real DTS label/path."));
                }
                nodes.push(new OpenNode(path, header));
                statementStart = i + 1;
            } else if (ch == '}') {
                if (nodes.isEmpty()) {
                    problems.add(new Problem(i, 1, "error", "Unexpected closing '}' with no matching '{'."));
                } else {
                    nodes.pop();
                    int j = i + 1;
                    while (j < chars.length && Character.isWhitespace(chars[j])) j++;
                    if (j < chars.length && chars[j] != ';' && chars[j] != '}') {
                        problems.add(new Problem(i, 1, "error",
                                "A Devicetree node definition must end with '};'."));
                    } else if (j == chars.length) {
                        problems.add(new Problem(i, 1, "error",
                                "Missing ';' after closing node. Write '};'."));
                    }
                }
                statementStart = i + 1;
            } else if (ch == ';') {
                statementStart = i + 1;
            }
        }
        // While the user is entering an incomplete node, do not generate
        // cascade errors or pretend a full build validator has run.
        return List.copyOf(problems);
    }

    private static String resolvePath(String header, ArrayDeque<OpenNode> nodes) {
        if (header.equals("/")) return "/";
        Matcher ref = PATH_REF.matcher(header);
        if (ref.matches()) return ref.group(1);
        Matcher named = NAMED_NODE.matcher(header);
        if (!named.matches()) return null;
        String parent = nodes.isEmpty() ? null : nodes.peek().path();
        if (parent == null) return null;
        return (parent.equals("/") ? "" : parent) + "/" + named.group(1);
    }

    private static String cleanHeader(char[] chars, int start, int end) {
        String raw = new String(chars, Math.max(0, start), Math.max(0, end - start)).trim();
        // Preprocessor includes may precede a DTS statement without ';'.
        int lastDirective = raw.lastIndexOf('\n');
        if (raw.startsWith("#include") && lastDirective >= 0)
            raw = raw.substring(lastDirective + 1).trim();
        return raw;
    }

    private static char[] maskCommentsAndStrings(String document) {
        char[] result = document.toCharArray();
        boolean comment = false, line = false, quoted = false, escape = false;
        for (int i = 0; i < result.length; i++) {
            char c = document.charAt(i);
            char n = i + 1 < result.length ? document.charAt(i + 1) : 0;
            if (comment) {
                if (c == '*' && n == '/') {
                    result[i] = result[i + 1] = ' ';
                    comment = false;
                    i++;
                } else if (c != '\n') result[i] = ' ';
                continue;
            }
            if (line) {
                if (c == '\n') line = false;
                else result[i] = ' ';
                continue;
            }
            if (quoted) {
                if (c != '\n') result[i] = ' ';
                if (c == '"' && !escape) quoted = false;
                escape = c == '\\' && !escape;
                continue;
            }
            if (c == '/' && n == '*') {
                result[i] = result[i + 1] = ' ';
                comment = true;
                i++;
            } else if (c == '/' && n == '/') {
                result[i] = result[i + 1] = ' ';
                line = true;
                i++;
            } else if (c == '"') {
                result[i] = ' ';
                quoted = true;
            }
        }
        return result;
    }
}
