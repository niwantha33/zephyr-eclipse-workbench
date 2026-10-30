// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.*;
import java.util.regex.Pattern;

/** Exact generated-DTS properties and pin routes; never a package-pin decoder. */
public final class DevicetreeParameters {
    private DevicetreeParameters() {}
    public enum ValueKind { BOOLEAN, STRING, CELLS, BYTES, REFERENCE, UNSUPPORTED }
    public record Property(String path, String name, String value, int line) {
        /** Shape from generated syntax, not a substitute for the device's binding type. */
        public ValueKind kind() { return valueKind(value); }
        public String displayValue() { return value.isEmpty() ? "Present (boolean)" : value; }
        public String title() {
            return switch (name) {
                case "reg" -> path.contains("/channel@") ? "Channel number" : "Register / address";
                case "zephyr,gain" -> "ADC gain";
                case "zephyr,reference" -> "ADC reference";
                case "zephyr,resolution" -> "ADC resolution";
                case "zephyr,acquisition-time" -> "ADC acquisition time";
                case "zephyr,input-positive" -> "ADC positive input";
                case "zephyr,input-negative" -> "ADC negative input";
                case "zephyr,vref-mv" -> "Reference voltage (mV)";
                case "zephyr,oversampling" -> "ADC oversampling";
                default -> name;
            };
        }
    }
    public record PinRoute(String owner, String path, String property, String value, int line) {}
    private static final Pattern PROPERTY = Pattern.compile("^([A-Za-z_#][A-Za-z0-9_,#.+-]*)\\s*(?:=\\s*(.*?))?;\\s*$", Pattern.DOTALL);
    private static final Pattern CELLS = Pattern.compile("(?<![A-Za-z_0-9])(?:0[xX][0-9a-fA-F]+|[0-9]+)(?![A-Za-z_0-9])");

    public static List<Property> inspect(String source) {
        if (source == null || source.isBlank()) return List.of();
        if (source.length() > 2_000_000) throw new IllegalArgumentException("Generated Devicetree exceeds 2 MB");
        var nodes = DevicetreeHardware.inspect(source);
        var starts = new HashMap<Integer, String>();
        for (var node : nodes) starts.put(node.line(), node.path());
        String clean = withoutComments(source);
        var stack = new ArrayDeque<String>();
        var result = new ArrayList<Property>();
        StringBuilder pending = new StringBuilder();
        int firstLine = 0, number = 0;
        for (String raw : clean.split("\\R", -1)) {
            number++;
            String row = raw.strip();
            if (starts.containsKey(number)) {
                stack.push(starts.get(number)); pending.setLength(0); continue;
            }
            if (row.startsWith("};") || row.equals("}")) {
                if (!stack.isEmpty()) stack.pop();
                pending.setLength(0); continue;
            }
            if (stack.isEmpty() || row.isEmpty()) continue;
            if (pending.isEmpty()) firstLine = number;
            else pending.append(' ');
            pending.append(row);
            if (pending.length() > 8192) { pending.setLength(0); continue; }
            if (!row.endsWith(";")) continue;
            var match = PROPERTY.matcher(pending);
            if (match.matches()) result.add(new Property(stack.peek(), match.group(1),
                    match.group(2) == null ? "" : match.group(2).strip(), firstLine));
            pending.setLength(0);
        }
        return List.copyOf(result);
    }

    /** Strip comments while retaining line numbers and quoted string contents. */
    private static String withoutComments(String source) {
        var out = new StringBuilder(source.length());
        boolean block = false, line = false, quoted = false, escape = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i), next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (line) { if (c == '\n' || c == '\r') { line = false; out.append(c); } else out.append(' '); }
            else if (block) {
                if (c == '*' && next == '/') { block = false; out.append("  "); i++; }
                else out.append(c == '\n' || c == '\r' ? c : ' ');
            } else if (quoted) {
                out.append(c);
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') quoted = false;
            } else if (c == '/' && next == '*') { block = true; out.append("  "); i++; }
            else if (c == '/' && next == '/') { line = true; out.append("  "); i++; }
            else { out.append(c); if (c == '"') quoted = true; }
        }
        return out.toString();
    }

    public static List<Property> forDevice(List<Property> properties, String path) {
        return properties.stream().filter(p -> p.path().equals(path) || p.path().startsWith(path.equals("/") ? "/" : path + "/"))
                .filter(p -> !Set.of("phandle", "linux,phandle", "#address-cells", "#size-cells").contains(p.name())).toList();
    }

    /** Resolve only pinctrl state phandles. GPIO specifier cells remain raw. */
    public static List<PinRoute> pinRoutes(List<Property> properties, String owner) {
        var phandles = new HashMap<Long, String>();
        for (var p : properties) if (p.name().equals("phandle")) {
            var cells = CELLS.matcher(p.value());
            if (cells.find()) {
                Long handle = phandle(cells.group());
                if (handle != null) phandles.put(handle, p.path());
            }
        }
        var routes = new ArrayList<PinRoute>();
        var visited = new HashSet<String>();
        for (var p : forDevice(properties, owner)) {
            if (p.name().matches("pinctrl-[0-9]+")) {
                boolean resolved = false;
                var cells = CELLS.matcher(p.value());
                while (cells.find()) {
                    String state = phandles.get(phandle(cells.group()));
                    if (state == null || !visited.add(state)) continue;
                    for (var route : forDevice(properties, state)) if (pinProperty(route.name())) {
                        routes.add(new PinRoute(owner, route.path(), route.name(), route.value(), route.line()));
                        resolved = true;
                    }
                }
                if (!resolved) routes.add(new PinRoute(owner, p.path(), p.name(), p.value(), p.line()));
            } else if (pinProperty(p.name())) routes.add(new PinRoute(owner, p.path(), p.name(), p.value(), p.line()));
        }
        return List.copyOf(routes);
    }
    private static Long phandle(String value) {
        try {
            long result = value.startsWith("0x") || value.startsWith("0X")
                    ? Long.parseLong(value.substring(2), 16) : Long.parseLong(value, 10);
            return result > 0 && result <= 0xffff_ffffL ? result : null;
        } catch (NumberFormatException invalid) { return null; }
    }
    private static boolean pinProperty(String name) {
        return Set.of("pinmux", "pins", "psels", "gpios").contains(name) || name.endsWith("-gpios");
    }

    private static ValueKind valueKind(String value) {
        String v = value.strip();
        if (v.isEmpty()) return ValueKind.BOOLEAN;
        return switch (v.charAt(0)) {
            case '"' -> ValueKind.STRING;
            case '<' -> ValueKind.CELLS;
            case '[' -> ValueKind.BYTES;
            case '&' -> ValueKind.REFERENCE;
            default -> ValueKind.UNSUPPORTED;
        };
    }

    /** Validate a conservative editable subset; Zephyr still validates binding semantics on rebuild. */
    private static void validateDraftValue(Property property, String value) {
        if (Set.of("phandle", "linux,phandle").contains(property.name()))
            throw new IllegalArgumentException("Phandles are generated identities; edit a symbolic source reference instead");
        if (value == null || value.length() > 8192 || value.matches("(?s).*[;{}\\r\\n\\x00].*"))
            throw new IllegalArgumentException("Enter a single property value without semicolons, braces or line breaks");
        ValueKind kind = valueKind(value);
        if (property.kind() == ValueKind.UNSUPPORTED || kind == ValueKind.UNSUPPORTED)
            throw new IllegalArgumentException("This value syntax requires the source overlay editor");
        if (kind != property.kind())
            throw new IllegalArgumentException("Keep the built value shape: " + property.kind()
                    + ". This check does not validate the device binding.");
        if (kind == ValueKind.BOOLEAN) return;
        String v = value.strip();
        if (referenceProperty(property.name()) && kind == ValueKind.CELLS
                && !Pattern.compile("&[A-Za-z_][A-Za-z0-9_]*").matcher(v).find())
            throw new IllegalArgumentException("Generated numeric phandles are not portable; use the source node's &label reference");
        if (property.name().matches("pinctrl-[0-9]+")
                && !v.replaceAll("&[A-Za-z_][A-Za-z0-9_]*", "").matches("[<>,\\s]*"))
            throw new IllegalArgumentException("Every pinctrl state must use a symbolic &label, not a generated numeric phandle");
        int at = 0;
        while (at < v.length()) {
            int start = at;
            if (kind == ValueKind.STRING) {
                if (v.charAt(at++) != '"') throw new IllegalArgumentException("Expected a quoted string");
                boolean closed = false;
                while (at < v.length()) {
                    char c = v.charAt(at++);
                    if (c == '\\') { if (at == v.length()) break; at++; }
                    else if (c == '"') { closed = true; break; }
                }
                if (!closed) throw new IllegalArgumentException("Unterminated quoted string");
            } else if (kind == ValueKind.REFERENCE) {
                var reference = Pattern.compile("&[A-Za-z_][A-Za-z0-9_]*").matcher(v);
                reference.region(at, v.length());
                if (!reference.lookingAt()) throw new IllegalArgumentException("Use a symbolic &node_label reference");
                at = reference.end();
            } else {
                char open = kind == ValueKind.CELLS ? '<' : '[';
                char close = kind == ValueKind.CELLS ? '>' : ']';
                if (v.charAt(at++) != open) throw new IllegalArgumentException("Expected " + open + " value group");
                int content = at, parentheses = 0;
                while (at < v.length()) {
                    char c = v.charAt(at);
                    if (c == close && parentheses == 0) break;
                    if (c == '(' && kind == ValueKind.CELLS) parentheses++;
                    else if (c == ')' && kind == ValueKind.CELLS && --parentheses < 0)
                        throw new IllegalArgumentException("Unbalanced cell expression");
                    else if (c == '"' || c == '[' || c == ']' || (c == '<' && parentheses == 0))
                        throw new IllegalArgumentException("Unexpected delimiter inside value group");
                    at++;
                }
                if (at == v.length() || parentheses != 0) throw new IllegalArgumentException("Unterminated value group");
                String body = v.substring(content, at++).strip();
                if (body.contains("/*") || body.contains("//") || body.contains("*/"))
                    throw new IllegalArgumentException("Comments are not supported inside draft values");
                if (kind == ValueKind.BYTES && !body.matches("(?:[0-9a-fA-F]{2}(?:\\s+|$))*"))
                    throw new IllegalArgumentException("Byte arrays require whitespace-separated two-digit hexadecimal bytes");
                if (kind == ValueKind.CELLS && !body.matches("[A-Za-z0-9_\\s&()+*/%|~^!<>=?:.\\-]*"))
                    throw new IllegalArgumentException("Unsupported cell expression; use the source overlay editor");
            }
            if (at <= start) throw new IllegalArgumentException("Invalid property value");
            while (at < v.length() && Character.isWhitespace(v.charAt(at))) at++;
            if (at == v.length()) break;
            if (v.charAt(at++) != ',') throw new IllegalArgumentException("Separate value groups with a comma");
            while (at < v.length() && Character.isWhitespace(v.charAt(at))) at++;
            if (at == v.length()) throw new IllegalArgumentException("Missing value after comma");
        }
    }

    private static boolean referenceProperty(String name) {
        return name.matches("pinctrl-[0-9]+") || name.equals("gpios") || name.endsWith("-gpios")
                || name.endsWith("-supply") || Set.of("io-channels", "pwms", "clocks", "resets", "dmas",
                        "interrupt-parent", "interrupts-extended", "memory-region").contains(name);
    }

    /** A draft for review in an application overlay; no generated file is written. */
    public static String overlayRequest(Property property, String value) {
        if (!property.path().matches("/(?:[A-Za-z0-9_,.@+-]+(?:/[A-Za-z0-9_,.@+-]+)*)?")
                || !property.name().matches("[A-Za-z_#][A-Za-z0-9_,#.+-]*"))
            throw new IllegalArgumentException("Invalid Devicetree property target");
        validateDraftValue(property, value);
        return "/* Review against this device's binding, then rebuild. */\n&{" + property.path() + "} {\n    "
                + property.name() + (value.isBlank() ? "" : " = " + value.strip()) + ";\n};\n";
    }
}
