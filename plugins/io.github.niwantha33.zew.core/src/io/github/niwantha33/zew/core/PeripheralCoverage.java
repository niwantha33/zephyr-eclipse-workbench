// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only account of what a specific generated Zephyr DTS actually describes.
 * ABSENT is not "not on chip": peripheral silicon may exist outside Zephyr's
 * version/board DTS support. Also not proof of firmware driver compilation.
 */
public final class PeripheralCoverage {
    private PeripheralCoverage() {}

    public record Count(int present, int enabled, int disabled) {
        public Count { if (present != enabled + disabled) throw new IllegalArgumentException("count"); }
    }
    public record Summary(String category, int totalNodes, Count categoryCount,
                          int matching, boolean stale, String explanation) {}

    /** A child inherits a disabled ancestor even if its local status is absent. */
    public static boolean effectiveEnabled(DevicetreeHardware.Device device,
                                           List<DevicetreeHardware.Device> all) {
        if (!device.enabled()) return false;
        String path = device.path();
        for (var parent : all) {
            if (!parent.enabled() && !parent.path().equals("/")
                    && (path.equals(parent.path()) || path.startsWith(parent.path() + "/")))
                return false;
        }
        return true;
    }

    public static Count count(List<DevicetreeHardware.Device> devices, String category) {
        int found = 0, enabled = 0;
        for (var device : devices) {
            if (!HardwarePeripheralIndex.matches(device, category)) continue;
            if (HardwarePeripheralIndex.category(device).equals("Other")
                    && !HardwarePeripheralIndex.ALL.equals(category)) {
                // "Other" is a navigation category, not a peripheral tally.
                continue;
            }
            found++;
            if (effectiveEnabled(device, devices)) enabled++;
        }
        return new Count(found, enabled, found - enabled);
    }

    public static Map<String, Count> allCounts(List<DevicetreeHardware.Device> devices) {
        var result = new java.util.LinkedHashMap<String, Count>();
        for (String category : HardwarePeripheralIndex.FILTERS) {
            if (HardwarePeripheralIndex.ALL.equals(category)) continue;
            result.put(category, count(devices, category));
        }
        return Map.copyOf(result);
    }

    public static Summary explain(List<DevicetreeHardware.Device> devices,
                                  String category, String state, String search, boolean stale) {
        if (category == null || category.isBlank()) category = HardwarePeripheralIndex.ALL;
        Count count = count(devices, category);
        int matching = 0;
        for (var device : devices)
            if (HardwarePeripheralIndex.matches(device, category)
                    && stateMatch(device, devices, state)
                    && textMatch(device, search)) matching++;
        String prefix = stale ? "BUILD REQUIRED — last generated DTS may be stale. " : "";
        String explanation;
        if (devices.isEmpty()) {
            explanation = "NO GENERATED DTS. Select the exact Zephyr board/profile and build. "
                    + "No peripheral or silicon claims can be made.";
        } else if (count.present() == 0) {
            explanation = prefix + "NOT DESCRIBED IN SELECTED BUILD DTS: " + category + ". "
                    + "This does not mean the MCU lacks the peripheral. "
                    + "The selected Zephyr version/SoC DTS may omit its nodes or driver integration. "
                    + "Inspect the exact vendor SVD separately; do not invent an overlay node or pin assignment.";
        } else if (matching == 0) {
            explanation = prefix + category + " is described in the build (" + count.present()
                    + "; DTS okay/default " + count.enabled() + ", disabled " + count.disabled()
                    + "), but search/status filters hide all matches. Select All nodes and clear search.";
        } else {
            explanation = prefix + category + " in generated DTS: " + count.present()
                    + " (DTS okay/default " + count.enabled() + ", disabled " + count.disabled()
                    + "). DTS okay is not proof that the driver was compiled or hardware is configured.";
        }
        return new Summary(category, devices.size(), count, matching, stale, explanation);
    }

    private static boolean stateMatch(DevicetreeHardware.Device device,
                                      List<DevicetreeHardware.Device> all, String state) {
        if ("Okay/default".equals(state)) return effectiveEnabled(device, all);
        if ("Disabled".equals(state)) return !effectiveEnabled(device, all);
        return true;
    }

    private static boolean textMatch(DevicetreeHardware.Device device, String search) {
        String haystack = (device.path() + " " + device.name() + " "
                + device.compatible() + " " + device.label() + " "
                + device.reg() + " " + device.status()).toLowerCase(Locale.ROOT);
        if (search == null || search.isBlank()) return true;
        for (String term : search.toLowerCase(Locale.ROOT).strip().split("\\s+"))
            if (!haystack.contains(term)) return false;
        return true;
    }

    public static boolean matches(DevicetreeHardware.Device device,
                                  List<DevicetreeHardware.Device> all,
                                  String category, String state, String search) {
        return HardwarePeripheralIndex.matches(device, category)
                && stateMatch(device, all, state) && textMatch(device, search);
    }
}
