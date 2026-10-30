// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Read-only, conservative GPIO evidence from the *compiled* Zephyr Devicetree.
 *
 * A GPIO controller normally has no pinctrl routes of its own: consumers such
 * as LEDs refer to it through a phandle and a GPIO specifier. These are
 * observable build references, not runtime direction, bonded package pads,
 * available pin functions, or proof that a pin is electrically connected.
 */
public final class GpioBankEvidence {
    private GpioBankEvidence() {}

    public record Usage(int bit, String consumerPath, String property, String flags, int line) {}
    public record Bank(String controllerPath, String bankName, int bitPositions,
                       boolean exactDtsWidth, boolean resolvedPhandle,
                       List<Usage> uses, String provenance) {
        public Bank {
            uses = List.copyOf(uses);
        }
        public String bitLabel(int bit) { return bankName.isBlank() ? "DIO" + bit : bankName + bit; }
    }

    private static final Pattern GROUP = Pattern.compile("<([^<>]+)>");
    private static final Pattern CELL = Pattern.compile("(?:0[xX][0-9a-fA-F]+|[0-9]+)");
    private static final int MAX_BIT_POSITIONS = 64;

    public static Bank inspect(DevicetreeHardware.Device device, List<DevicetreeParameters.Property> properties) {
        if (device == null || properties == null
                || !HardwarePeripheralIndex.category(device).equals("GPIO")) return null;
        var own = properties.stream().filter(p -> p.path().equals(device.path())).toList();
        boolean controller = own.stream().anyMatch(p -> p.name().equals("gpio-controller"));
        if (!controller) return null;
        int cellsPerSpecifier = integer(own, "#gpio-cells");
        int explicitWidth = integer(own, "ngpios");
        boolean exactWidth = explicitWidth >= 1 && explicitWidth <= MAX_BIT_POSITIONS;
        String compatible = device.compatible().toLowerCase(Locale.ROOT);
        boolean ti = compatible.contains("ti,mspm0-gpio");
        // A TI MSPM0 GPIO peripheral has up to 32 *register bit positions*.
        // That does not mean 32 bonded, available, or enabled physical GPIOs.
        int width = exactWidth ? explicitWidth : ti ? 32 : 0;
        String bank = "";
        if (ti && device.name().equalsIgnoreCase("gpio@400a0000")) bank = "PA";
        else if (ti && device.name().equalsIgnoreCase("gpio@400a2000")) bank = "PB";
        Long handle = null;
        for (var p : own) if (p.name().equals("phandle")) {
            var numbers = numericGroups(p.value());
            if (numbers != null && numbers.size() == 1 && numbers.get(0) > 0) handle = numbers.get(0);
        }
        // A combined gpios list may contain multiple controllers with
        // different #gpio-cells. Accept it ONLY when the entire sequence can
        // be partitioned using known controller phandles and exact cell widths.
        Map<String, List<DevicetreeParameters.Property>> nodes = new HashMap<>();
        for (var p : properties) nodes.computeIfAbsent(p.path(), key -> new ArrayList<>()).add(p);
        Map<Long, Integer> widths = new HashMap<>();
        for (var node : nodes.values()) {
            if (node.stream().noneMatch(p -> p.name().equals("gpio-controller"))) continue;
            int count = integer(node, "#gpio-cells");
            if (count < 1 || count > 8) continue;
            for (var prop : node) if (prop.name().equals("phandle")) {
                var values = numericGroups(prop.value());
                if (values != null && values.size() == 1 && values.get(0) > 0)
                    widths.put(values.get(0), count);
            }
        }
        List<Usage> uses = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (handle != null && cellsPerSpecifier == 2) {
            for (var p : properties) {
                if (!(p.name().equals("gpios") || p.name().endsWith("-gpios"))) continue;
                if (p.path().equals(device.path())) continue;
                var numbers = numericGroups(p.value());
                if (numbers == null) continue;
                List<Usage> candidates = new ArrayList<>();
                int cursor = 0;
                boolean valid = true;
                while (cursor < numbers.size()) {
                    Long candidateHandle = numbers.get(cursor);
                    Integer cellCount = widths.get(candidateHandle);
                    if (cellCount == null || cursor + cellCount >= numbers.size()) {
                        valid = false;
                        break;
                    }
                    if (candidateHandle.equals(handle)) {
                        long bit = numbers.get(cursor + 1);
                        if (bit >= 0 && bit < MAX_BIT_POSITIONS
                                && (!exactWidth || bit < explicitWidth))
                            candidates.add(new Usage((int) bit, p.path(), p.name(),
                                    "0x" + Long.toHexString(numbers.get(cursor + 2)), p.line()));
                    }
                    cursor += cellCount + 1;
                }
                if (!valid) continue; // Never publish a partially parsed property.
                for (var usage : candidates) {
                    String key = usage.consumerPath() + ":" + usage.property() + ":" + usage.bit();
                    if (seen.add(key)) uses.add(usage);
                }
                if (uses.size() > 512) break; // Bounded inspector surface.
            }
        }
        uses.sort(Comparator.comparingInt(Usage::bit)
                .thenComparing(Usage::consumerPath).thenComparing(Usage::property));
        String provenance = exactWidth ? "ngpios from built DTS"
                : ti ? "TI MSPM0 register index capacity (up to 32); not package availability"
                : "line count not recorded in compiled DTS";
        if (cellsPerSpecifier != 2) provenance += "; unsupported or missing #gpio-cells";
        if (handle == null) provenance += "; numeric controller phandle missing";
        return new Bank(device.path(), bank, width, exactWidth, handle != null,
                uses, provenance);
    }

    private static int integer(List<DevicetreeParameters.Property> own, String key) {
        for (var p : own) if (p.name().equals(key)) {
            var values = numericGroups(p.value());
            if (values != null && values.size() == 1 && values.get(0) <= Integer.MAX_VALUE)
                return values.get(0).intValue();
        }
        return -1;
    }

    private static List<Long> numericGroups(String value) {
        var matcher = GROUP.matcher(value);
        List<Long> result = new ArrayList<>();
        int end = 0;
        boolean any = false;
        while (matcher.find()) {
            if (!value.substring(end, matcher.start()).matches("[,\\s]*")) return null;
            String[] words = matcher.group(1).trim().split("\\s+");
            for (String word : words) {
                if (!CELL.matcher(word).matches()) return null;
                try {
                    long number = word.startsWith("0x") || word.startsWith("0X")
                            ? Long.parseUnsignedLong(word.substring(2), 16) : Long.parseLong(word);
                    if (number < 0 || number > 0xffff_ffffL) return null;
                    result.add(number);
                } catch (NumberFormatException error) { return null; }
            }
            end = matcher.end();
            any = true;
        }
        return any && value.substring(end).matches("[,\\s]*") ? result : null;
    }
}
