// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Read-only presentation of the actual generated zephyr.dts. No source mutation
 * and no speculative pin/ADC bindings. Zephyr remains the DTS authority.
 */
public final class DevicetreeHardware {
    private DevicetreeHardware() {}
    public record Device(String path, String name, String label, String compatible,
                         String status, String reg, int line) {
        public boolean enabled() { return status.isBlank() || "okay".equals(status) || "ok".equals(status); }
    }
    private static final Pattern NODE = Pattern.compile(
            "^\\s*(?:(?:[A-Za-z_][\\w,-]*):\\s*)*([A-Za-z_][\\w,.-]*(?:@[0-9a-fA-F,]+)?|/)\\s*\\{\\s*(?://.*)?$");
    private static final Pattern PROP = Pattern.compile(
            "^\\s*(compatible|status|label|reg)\\s*=\\s*(.*?);\\s*$");
    private static final int LIMIT = 10_000;
    private record Open(String path, String name, int line) {}
    public static List<Device> inspect(String generatedDts) {
        if (generatedDts == null || generatedDts.isBlank()) return List.of();
        if (generatedDts.length() > 2_000_000)
            throw new IllegalArgumentException("Generated Devicetree exceeds 2 MB");
        var result = new ArrayList<Device>();
        var stack = new ArrayList<Open>();
        int lineNumber = 0;
        // The generated DTS has ordinary braced nodes. Only a top-level
        // property on a device node is associated with that node.
        for (String raw : DevicetreeParameters.withoutComments(generatedDts).split("\\R", -1)) {
            lineNumber++;
            String line = raw.strip();
            var node = NODE.matcher(line);
            if (node.matches()) {
                String name = node.group(1);
                String parent = stack.isEmpty() ? "" : stack.get(stack.size() - 1).path();
                String path = "/".equals(name) ? "/" : ("/".equals(parent) ? "/" : parent + "/") + name;
                stack.add(new Open(path, name, result.size()));
                if (result.size() >= LIMIT)
                    throw new IllegalArgumentException("Generated Devicetree exceeds " + LIMIT + " nodes; no partial tree is returned");
                result.add(new Device(path, name, "", "", "", "", lineNumber));
                continue;
            }
            if (line.startsWith("};") || line.equals("}")) {
                if (!stack.isEmpty()) stack.remove(stack.size() - 1);
                continue;
            }
            var property = PROP.matcher(line);
            if (!stack.isEmpty() && property.matches()) {
                int i = result.size() - 1;
                String path = stack.get(stack.size() - 1).path();
                for (; i >= 0; --i) if (result.get(i).path().equals(path)) break;
                if (i < 0) continue;
                Device old = result.get(i);
                String value = property.group(2).trim();
                if (value.length() > 180) value = value.substring(0, 180) + "...";
                value = value.replace("\"", "");
                String key = property.group(1);
                result.set(i, new Device(old.path(), old.name(),
                        "label".equals(key) ? value : old.label(),
                        "compatible".equals(key) ? value : old.compatible(),
                        "status".equals(key) ? value : old.status(),
                        "reg".equals(key) ? value : old.reg(), old.line()));
            }
        }
        return List.copyOf(result);
    }
    /** Exact node-label identities from generated DTS, distinct from the label property. */
    public static java.util.Map<String, String> nodeLabels(String generatedDts) {
        var nodes = inspect(generatedDts);
        var lines = withoutCommentLines(generatedDts);
        var result = new java.util.LinkedHashMap<String, String>();
        var ambiguous = new java.util.HashSet<String>();
        var label = Pattern.compile("([A-Za-z_][\\w,-]*):\\s*");
        for (var node : nodes) {
            var header = lines[node.line() - 1];
            int brace = header.indexOf('{');
            var matcher = label.matcher(brace < 0 ? "" : header.substring(0, brace));
            while (matcher.find()) {
                String name = matcher.group(1);
                if (result.containsKey(name) && !result.get(name).equals(node.path())) ambiguous.add(name);
                else result.put(name, node.path());
            }
        }
        ambiguous.forEach(result::remove);
        return java.util.Map.copyOf(result);
    }
    private static String[] withoutCommentLines(String source) {
        return DevicetreeParameters.withoutComments(source == null ? "" : source).split("\\R", -1);
    }

    /** Search actual node metadata; keep hierarchy context separately in the view. */
    public static boolean matches(Device device, String query, String state) {
        if ("Okay/default".equals(state) && !device.enabled()) return false;
        if ("Disabled".equals(state) && !"disabled".equals(device.status())) return false;
        String haystack = (device.path() + " " + device.label() + " " + device.compatible()
                + " " + device.reg() + " " + device.status()).toLowerCase(Locale.ROOT);
        for (String term : (query == null ? "" : query.strip()).toLowerCase(Locale.ROOT).split("\\s+"))
            if (!haystack.contains(term)) return false;
        return true;
    }
    public static String explanation(Device device) {
        if (device == null) return "Select a device in the compiled Devicetree.";
        return "Device: " + device.path() +
                "\nState: " + (device.enabled() ? "enabled (or inherits default)" : device.status()) +
                "\nCompatible: " + (device.compatible().isBlank() ? "not specified" : device.compatible()) +
                "\nLabel: " + (device.label().isBlank() ? "not specified" : device.label()) +
                "\nRegister address: " + (device.reg().isBlank() ? "not shown" : device.reg()) +
                "\n\nRead-only: these values were extracted from the last generated zephyr.dts." +
                "\nUse the application overlay to request changes, then rebuild." +
                "\nPin and channel availability must be validated against the board bindings.";
    }
}
