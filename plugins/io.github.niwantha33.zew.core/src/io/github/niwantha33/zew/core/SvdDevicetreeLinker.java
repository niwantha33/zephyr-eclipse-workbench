// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Strict, read-only address cross-reference to the generated Zephyr Devicetree. */
public final class SvdDevicetreeLinker {
    private SvdDevicetreeLinker() {}
    public enum State { ENABLED_IN_DTS, DISABLED_IN_DTS, UNKNOWN, AMBIGUOUS }
    public record Link(State state, String nodePath, String explanation) {}
    private record Region(DevicetreeHardware.Device node, BigInteger base, BigInteger size) {}
    private record Parsed(List<Region> regions, boolean unparseable) {}
    private static final Pattern CELL = Pattern.compile("0[xX][0-9a-fA-F]+|[0-9]+");
    private static final Pattern GROUP = Pattern.compile("<([^<>]{1,300})>");

    public static Map<String, Link> unknown(SvdDevice svd, String reason) {
        if (svd == null) return Map.of();
        var result = new HashMap<String, Link>();
        for (var register : svd.registers())
            result.put(register.peripheral(), new Link(State.UNKNOWN, "", reason));
        return Map.copyOf(result);
    }

    public static Map<String, Link> link(SvdDevice svd,
            List<DevicetreeHardware.Device> generated) {
        if (svd == null) return Map.of();
        if (generated == null || generated.isEmpty())
            return unknown(svd, "No generated build/zephyr.dts nodes available");
        if (svd.registers().size() > 100_000 || generated.size() > 10_000)
            throw new IllegalArgumentException("SVD/DTS map exceeds bounded limits");
        var byPath = new HashMap<String, DevicetreeHardware.Device>();
        for (var node : generated) byPath.put(node.path(), node);
        var regions = new ArrayList<Region>();
        boolean unparseable = false;
        boolean anyReg = false;
        for (var node : generated) {
            if (node.reg().isBlank()) continue;
            anyReg = true;
            var parsed = regions(node);
            if (parsed.unparseable()) unparseable = true;
            else regions.addAll(parsed.regions());
        }
        var byPeripheral = new HashMap<String, BigInteger>();
        for (var register : svd.registers())
            byPeripheral.merge(register.peripheral(), register.address(), BigInteger::min);
        var results = new HashMap<String, Link>();
        for (var entry : byPeripheral.entrySet()) {
            BigInteger address = entry.getValue();
            var matches = regions.stream()
                    .filter(region -> address.compareTo(region.base()) >= 0
                            && address.subtract(region.base()).compareTo(region.size()) < 0)
                    .sorted(java.util.Comparator.comparing(Region::size)).toList();
            if (matches.isEmpty()) {
                String reason = !anyReg ? "No reg properties in generated Devicetree"
                        : unparseable
                            ? "No matching reg; some Devicetree reg cells were unparseable"
                            : "No generated Devicetree reg range matches 0x" + address.toString(16);
                results.put(entry.getKey(), new Link(State.UNKNOWN, "", reason));
                continue;
            }
            Region nearest = matches.get(0);
            if (matches.size() > 1 && matches.get(1).size().equals(nearest.size())) {
                results.put(entry.getKey(), new Link(State.AMBIGUOUS, "",
                        "Multiple equal-specificity generated Devicetree MMIO ranges match"));
                continue;
            }
            boolean enabled = true;
            String nodePath = nearest.node().path();
            for (String ancestor = nodePath; !ancestor.isBlank();) {
                var node = byPath.get(ancestor);
                if (node != null && !node.enabled()) { enabled = false; break; }
                int slash = ancestor.lastIndexOf('/');
                ancestor = slash <= 0 ? "" : ancestor.substring(0, slash);
            }
            results.put(entry.getKey(), new Link(
                    enabled ? State.ENABLED_IN_DTS : State.DISABLED_IN_DTS,
                    nodePath, enabled ? "Enabled in generated Devicetree"
                            : "Disabled by node/parent in generated Devicetree"));
        }
        return Map.copyOf(results);
    }

    /** Up to eight explicit angle-bracket groups, each a strict 32/64-bit address-size pair. */
    private static Parsed regions(DevicetreeHardware.Device node) {
        String raw = node.reg().strip();
        if (raw.length() > 1024 || raw.contains("&") || raw.contains("..."))
            return new Parsed(List.of(), true);
        var groups = GROUP.matcher(raw);
        var result = new ArrayList<Region>();
        int offset = 0;
        while (groups.find()) {
            String separator = raw.substring(offset, groups.start()).trim();
            if (!(result.isEmpty() ? separator.isEmpty() : ",".equals(separator))
                    || result.size() >= 8) return new Parsed(List.of(), true);
            String source = groups.group(1);
            if (!CELL.matcher(source).replaceAll("").replaceAll("\\s+", "").isEmpty())
                return new Parsed(List.of(), true);
            var cells = new ArrayList<BigInteger>();
            var tokens = CELL.matcher(source);
            while (tokens.find()) {
                String token = tokens.group();
                try {
                    BigInteger value = token.startsWith("0x") || token.startsWith("0X")
                            ? new BigInteger(token.substring(2), 16) : new BigInteger(token);
                    if (value.signum() < 0 || value.bitLength() > 32)
                        return new Parsed(List.of(), true);
                    cells.add(value);
                } catch (RuntimeException invalid) { return new Parsed(List.of(), true); }
            }
            BigInteger address, size;
            if (cells.size() == 2) {
                address = cells.get(0);
                size = cells.get(1);
            } else if (cells.size() == 4 && cells.get(0).signum() == 0
                    && cells.get(2).signum() == 0) {
                // Only unambiguous 64-bit pairs: arbitrary four cells could be two regions.
                address = cells.get(0).shiftLeft(32).or(cells.get(1));
                size = cells.get(2).shiftLeft(32).or(cells.get(3));
            } else return new Parsed(List.of(), true);
            if (size.signum() <= 0 || size.bitLength() > 48)
                return new Parsed(List.of(), true);
            result.add(new Region(node, address, size));
            offset = groups.end();
        }
        if (result.isEmpty() || !raw.substring(offset).trim().isEmpty())
            return new Parsed(List.of(), true);
        return new Parsed(List.copyOf(result), false);
    }
}
