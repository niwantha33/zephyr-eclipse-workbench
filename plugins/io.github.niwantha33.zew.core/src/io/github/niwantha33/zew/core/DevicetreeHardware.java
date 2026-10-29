// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.ArrayList;
import java.util.List;
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
            "^\\s*(?:(?:[A-Za-z_][\\w,-]*):\\s*)?([A-Za-z_][\\w,.-]*(?:@[0-9a-fA-F,]+)?|/)\\s*\\{\\s*(?://.*)?$");
    private static final Pattern PROP = Pattern.compile(
            "^\\s*(compatible|status|label|reg)\\s*=\\s*(.*?);\\s*$");
    private static final int LIMIT = 500;
    private record Open(String path, String name, int line) {}
    public static List<Device> inspect(String generatedDts) {
        if (generatedDts == null || generatedDts.isBlank()) return List.of();
        var result = new ArrayList<Device>();
        var stack = new ArrayList<Open>();
        String compatible = "", status = "", label = "", reg = "";
        // The generated DTS has ordinary braced nodes. Only a top-level
        // property on a device node is associated with that node.
        for (String raw : generatedDts.split("\\R", -1)) {
            String line = raw.strip();
            var node = NODE.matcher(line);
            if (node.matches()) {
                String name = node.group(1);
                String parent = stack.isEmpty() ? "" : stack.get(stack.size() - 1).path();
                String path = "/".equals(name) ? "/" : ("/".equals(parent) ? "/" : parent + "/") + name;
                stack.add(new Open(path, name, result.size()));
                result.add(new Device(path, name, "", "", "", "", result.size() + 1));
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
            if (result.size() > LIMIT) break;
        }
        return List.copyOf(result);
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