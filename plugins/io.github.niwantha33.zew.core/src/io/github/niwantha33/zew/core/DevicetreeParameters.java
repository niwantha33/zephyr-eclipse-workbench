// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.*;
import java.util.regex.Pattern;

/** Exact generated-DTS properties and pin routes; never a package-pin decoder. */
public final class DevicetreeParameters {
    private DevicetreeParameters() {}
    public record Property(String path, String name, String value, int line) {
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
            if (cells.find()) phandles.put(Long.decode(cells.group()), p.path());
        }
        var routes = new ArrayList<PinRoute>();
        var visited = new HashSet<String>();
        for (var p : forDevice(properties, owner)) {
            if (p.name().matches("pinctrl-[0-9]+")) {
                boolean resolved = false;
                var cells = CELLS.matcher(p.value());
                while (cells.find()) {
                    String state = phandles.get(Long.decode(cells.group()));
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
    private static boolean pinProperty(String name) {
        return Set.of("pinmux", "pins", "psels", "gpios").contains(name) || name.endsWith("-gpios");
    }

    /** A draft for review in an application overlay; no generated file is written. */
    public static String overlayRequest(Property property, String value) {
        if (!property.path().matches("/(?:[A-Za-z0-9_,.@+-]+(?:/[A-Za-z0-9_,.@+-]+)*)?")
                || !property.name().matches("[A-Za-z_#][A-Za-z0-9_,#.+-]*"))
            throw new IllegalArgumentException("Invalid Devicetree property target");
        if (value == null || value.length() > 8192 || value.matches("(?s).*[;{}\\r\\n\\x00].*"))
            throw new IllegalArgumentException("Enter a single property value without semicolons or braces");
        return "/* Review against this device's binding, then rebuild. */\n&{" + property.path() + "} {\n    "
                + property.name() + (value.isBlank() ? "" : " = " + value.strip()) + ";\n};\n";
    }
}
