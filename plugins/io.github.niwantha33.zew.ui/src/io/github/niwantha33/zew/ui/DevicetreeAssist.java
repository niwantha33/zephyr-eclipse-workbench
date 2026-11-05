// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.*;
import java.util.regex.*;

/** Tolerant cursor-local scanner. All hardware data comes from DevicetreeIndex. */
final class DevicetreeAssist {
    record Suggestion(String replacement, String label, String help) {}
    record Result(int start, int length, List<Suggestion> suggestions) {}
    private static final Pattern PROPERTY = Pattern.compile("([#A-Za-z][A-Za-z0-9,._+?#-]*)\\s*=\\s*([^;{}]*)$");
    private static final Pattern REFERENCE_NODE = Pattern.compile("&([A-Za-z_][A-Za-z0-9_]*)\\s*\\{");
    private static final Pattern REFERENCE_PATH = Pattern.compile("&\\{([^}]*)\\}\\s*\\{");
    private DevicetreeAssist() {}

    static Result suggest(String document, int caret, DevicetreeIndex.Snapshot snapshot) {
        if (document == null) document = "";
        caret = Math.min(Math.max(0, caret), document.length());
        String before = document.substring(0, caret);
        if (insideComment(before)) return new Result(caret, 0, List.of());
        // Configuration Studio creates app.overlay with only an explanatory
        // block comment. Treat that as empty at the end of the comment, so
        // first-time users see the five starting actions on Ctrl+Space.
        String sourceOnly = document.replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//[^\\r\\n]*", "").trim();
        // Replace only the invalid brace, never invent a peripheral.
        Matcher bareRoot = Pattern.compile(
                "(?s)^\\s*(?:/\\*.*?\\*/\\s*)*(\\{)").matcher(document);
        if (bareRoot.find() && caret <= bareRoot.end(1)) {
            return new Result(bareRoot.start(1), 1, List.of(
                    new Suggestion("/ {", "Fix root syntax: / {",
                            "An overlay root must use '/ {'. Use & then Ctrl+Space to select a real controller.")));
        }
        if (sourceOnly.isEmpty()) {
            var start = new ArrayList<Suggestion>();
            start.add(new Suggestion("&", "Configure an existing controller…",
                    "Type & then Ctrl+Space to choose a real board node; no hardware is invented."));
            start.add(new Suggestion("/ {\n    aliases {\n        /* name = &existing_node; */\n    };\n};\n",
                    "Add an alias…", "Choose an existing label; the commented placeholder is not a valid alias."));
            start.add(new Suggestion("/ {\n    zephyr,user {\n        /* Add a binding-aware application reference. */\n    };\n};\n",
                    "Add an application reference…", "Edit a real property and phandle; no invented wiring."));
            start.add(new Suggestion("&", "Add a child on a real bus…",
                    "Choose an existing I2C/SPI controller, then add a child only for your actual device."));
            start.add(new Suggestion("&", "Browse available controllers…",
                    "Type & then Ctrl+Space to inspect labels and status in the selected generated DTS."));
            return new Result(caret, 0, List.copyOf(start));
        }
        int pathStart = before.lastIndexOf("&{");
        if (pathStart >= 0 && before.indexOf('}', pathStart) < 0) {
            int start = pathStart + 2;
            String prefixPath = before.substring(start);
            var paths = new ArrayList<Suggestion>();
            var seen = new HashSet<String>();
            for (var node : snapshot.nodes()) {
                if (node.path().startsWith(prefixPath) && seen.add(node.path()))
                    paths.add(new Suggestion(node.path(), node.path() + "  [" + node.status() + "]",
                            "Exact generated node path; snapshot only, not runtime evidence."));
            }
            paths.sort(Comparator.comparing(Suggestion::label));
            return new Result(start, caret - start, List.copyOf(paths));
        }
        int tokenStart = caret;
        while (tokenStart > 0 && isWord(before.charAt(tokenStart - 1))) tokenStart--;
        String prefix = before.substring(tokenStart);
        int amp = tokenStart - 1;
        boolean pathReference = before.substring(0, tokenStart).endsWith("&{");
        if (pathReference || amp >= 0 && before.charAt(amp) == '&') {
            var proposals = new ArrayList<Suggestion>();
            for (var node : snapshot.nodes()) {
                String key = pathReference ? node.path() : node.label();
                if (key.isBlank() || !key.startsWith(prefix)) continue;
                String help = "Real " + node.path() + " — generated status: " + node.status()
                        + (node.compatible().isBlank() ? "" : " — " + node.compatible())
                        + ". Built snapshot, not runtime activity.";
                proposals.add(new Suggestion(key, key + "  [" + node.status() + "]", help));
            }
            proposals.sort(Comparator.comparing(Suggestion::label));
            return new Result(tokenStart, caret - tokenStart, List.copyOf(proposals));
        }
        String nodePath = enclosingNodePath(before, snapshot);
        // Determine an assignment on the current line/statement, not in a comment.
        int statement = Math.max(before.lastIndexOf(';'), before.lastIndexOf('{'));
        String fragment = before.substring(statement + 1);
        Matcher assignment = PROPERTY.matcher(fragment);
        if (assignment.find()) {
            String name = assignment.group(1);
            var spec = snapshot.properties(nodePath).stream()
                    .filter(p -> p.name().equals(name)).findFirst().orElse(null);
            String current = assignment.group(2);
            boolean insideQuote = current.stripLeading().startsWith("\"");
            boolean insideCells = current.stripLeading().startsWith("<");
            String typed = insideQuote ? current.substring(current.indexOf('"') + 1).replace("\"", "") : current.strip();
            int start = insideQuote ? caret - typed.length() : caret - current.length();
            if (insideCells) {
                String cellText = current.substring(current.indexOf('<') + 1);
                int space = Math.max(cellText.lastIndexOf(' '), Math.max(cellText.lastIndexOf('\t'),
                        cellText.lastIndexOf('\n')));
                typed = cellText.substring(space + 1).replace(">", "");
                start = caret - typed.length();
            }
            List<Suggestion> choices = values(name, spec, snapshot, insideQuote, insideCells, typed);
            return new Result(start, caret - start, choices);
        }
        if (insideString(before)) return new Result(caret, 0, List.of());
        var existing = new HashSet<String>();
        Matcher present = Pattern.compile("(?m)([#A-Za-z][A-Za-z0-9,._+?#-]*)\\s*(?:=|;)").matcher(before.substring(Math.max(0, before.lastIndexOf('{') + 1)));
        while (present.find()) existing.add(present.group(1));
        var proposals = new ArrayList<Suggestion>();
        if (nodePath != null) for (var property : snapshot.properties(nodePath)) {
            if (!property.name().startsWith(prefix) || existing.contains(property.name())) continue;
            String insert = property.type().equals("boolean") ? property.name() + ";"
                    : property.name() + " = ";
            proposals.add(new Suggestion(insert, property.name() + "  (" + property.type() + ")"
                    + (property.required() ? " • required" : ""),
                    property.description() + "\nBinding: " + property.source()
                    + (property.deprecated() ? "\nDeprecated" : "")
                    + "\nA value is required unless this is boolean."));
        }
        if ((nodePath != null || before.stripTrailing().endsWith("{")) && !existing.contains("status")
                && "status".startsWith(prefix) && proposals.stream().noneMatch(p -> p.replacement().startsWith("status"))) {
            proposals.add(new Suggestion("status = ", "status  (standard property)",
                    "Node status: okay, disabled, reserved, fail or fail-sss. Enabled DTS is not proof of driver/runtime use."));
        }
        proposals.sort(Comparator.comparing(Suggestion::label));
        return new Result(tokenStart, caret - tokenStart, List.copyOf(proposals));
    }

    private static List<Suggestion> values(String name, DevicetreeIndex.Property property,
            DevicetreeIndex.Snapshot snapshot, boolean insideQuote, boolean insideCells, String prefix) {
        var result = new ArrayList<Suggestion>();
        if ("status".equals(name)) {
            for (String value : List.of("okay", "disabled", "reserved", "fail", "fail-sss"))
                addString(result, value, insideQuote, prefix,
                        "Standard Devicetree status; not proof of an active driver.");
        } else if ("compatible".equals(name)) {
            for (var compatible : snapshot.compatibles())
                addString(result, compatible.name(), insideQuote, prefix,
                        "Binding " + compatible.description() + " (on-bus: " +
                        (compatible.bus().isBlank() ? "unspecified" : compatible.bus()) + "). Not driver evidence.");
        } else if (property != null) {
            // Binding type, not a universal "<...>" or a universal string.
            for (var value : property.choices())
                addTyped(result, value, property.type(), insideQuote, insideCells, prefix,
                        property.description() + " — binding enum");
            if (!property.constant().isBlank())
                addTyped(result, property.constant(), property.type(), insideQuote, insideCells, prefix,
                        "Binding constant: " + property.source());
            // Boolean properties are a bare "name;" with no '='. Do not
            // offer a misleading replacement that leaves the '=' behind.
            if (property.type().startsWith("phandle") && !insideQuote)
                for (var node : snapshot.nodes()) {
                    if (node.label().isBlank() || !("&" + node.label()).startsWith(prefix)) continue;
                    if ("phandle-array".equals(property.type()) && !insideCells) continue;
                    result.add(new Suggestion(insideCells ? "&" + node.label() : "<&" + node.label() + ">",
                            "&" + node.label() + " — " + node.status(),
                            "Real provider: " + node.path() + ". Additional specifier cells, if applicable,"
                                    + " require the selected provider binding; not validated by this suggestion."));
                }
        }
        result.sort(Comparator.comparing(Suggestion::label));
        return List.copyOf(result);
    }

    private static void addTyped(List<Suggestion> result, String value, String type,
            boolean insideQuote, boolean insideCells, String prefix, String help) {
        switch (type) {
            case "string", "string-array" -> addString(result, value, insideQuote, prefix, help);
            case "int", "array" -> {
                // Only numeric scalars are safe without inspecting array shapes.
                // A YAML list rendered as text is never a Devicetree cell literal.
                if (value.matches("-?(?:0[xX][0-9a-fA-F]+|[0-9]+)") && value.startsWith(prefix))
                    result.add(new Suggestion(insideCells ? value : "<" + value + ">",
                            value, help + " — numeric cell, not a quoted string"));
            }
            default -> { /* Unknown shape: no fabricated value completion. */ }
        }
    }
    private static void addString(List<Suggestion> result, String value,
            boolean insideQuote, String prefix, String help) {
        if (!value.startsWith(prefix)) return;
        result.add(new Suggestion(insideQuote ? value : "\"" + value + "\"", value, help));
    }

    private static String enclosingNodePath(String before, DevicetreeIndex.Snapshot snapshot) {
        // Walk braces while ignoring comments and strings. Never reuse a parent's
        // binding for an unknown child node: better no suggestions than wrong ones.
        var opens = new ArrayDeque<Integer>();
        boolean quote = false, block = false, line = false, escape = false;
        for (int i = 0; i < before.length(); i++) {
            char c = before.charAt(i), n = i + 1 < before.length() ? before.charAt(i + 1) : 0;
            if (line) { if (c == '\n') line = false; continue; }
            if (block) { if (c == '*' && n == '/') { block = false; i++; } continue; }
            if (quote) { if (!escape && c == '"') quote = false; escape = !escape && c == '\\'; continue; }
            if (c == '/' && n == '*') { block = true; i++; continue; }
            if (c == '/' && n == '/') { line = true; i++; continue; }
            if (c == '"') { quote = true; continue; }
            if (c == '{') opens.push(i);
            else if (c == '}' && !opens.isEmpty()) opens.pop();
        }
        if (opens.isEmpty()) return null;
        int opening = opens.peek();
        int boundary = Math.max(before.lastIndexOf(';', opening - 1),
                                before.lastIndexOf('{', opening - 1));
        String header = before.substring(boundary + 1, opening).trim();
        Matcher label = REFERENCE_NODE.matcher(header + "{");
        if (label.matches()) {
            var node = snapshot.label(label.group(1));
            return node == null ? null : node.path();
        }
        Matcher path = REFERENCE_PATH.matcher(header + "{");
        return path.matches() ? path.group(1) : null;
    }
    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == ',' || c == '.' || c == '#';
    }
    private static boolean insideComment(String source) {
        boolean block = false, line = false, string = false, escape = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i), n = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (line) { if (c == '\n') line = false; continue; }
            if (block) { if (c == '*' && n == '/') { block = false; i++; } continue; }
            if (string) { if (!escape && c == '"') string = false; escape = !escape && c == '\\'; continue; }
            if (c == '"') { string = true; continue; }
            if (c == '/' && n == '*') { block = true; i++; } else if (c == '/' && n == '/') { line = true; i++; }
        }
        return block || line;
    }
    private static boolean insideString(String source) {
        boolean quote = false, escape = false;
        for (char c : source.toCharArray()) {
            if (c == '"' && !escape) quote = !quote;
            escape = c == '\\' && !escape;
        }
        return quote;
    }
}
