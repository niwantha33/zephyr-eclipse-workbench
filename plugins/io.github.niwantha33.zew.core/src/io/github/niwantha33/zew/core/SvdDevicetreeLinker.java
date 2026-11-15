// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Conservative cross-reference from a verified SVD address to the *built* zephyr.dts. */
public final class SvdDevicetreeLinker {
    private SvdDevicetreeLinker() {}

    public enum State { ENABLED_IN_DTS, DISABLED_IN_DTS, UNKNOWN, AMBIGUOUS }
    public record Link(State state, String nodePath, String explanation) {}
    private record Region(DevicetreeHardware.Device node, BigInteger base, BigInteger size) {}
    private static final Pattern CELL = Pattern.compile("0[xX][0-9a-fA-F]+|[0-9]+");
    private static final Pattern GROUP = Pattern.compile("<([^<>]{1,128})>");

    /**
     * Link only confidently decoded single or 64-bit address/size cell pairs.
     * For anything else, mark UNKNOWN and do not falsely claim hardware enabled.
     */
    public static Map<String, Link> link(SvdDevice svd,
            List<DevicetreeHardware.Device> generated) {
        if (svd == null || generated == null) return Map.of();
        if (svd.registers().size() > 100_000 || generated.size() > 10_000)
            throw new IllegalArgumentException("SVD/DTS map exceeds bounded limits");
        var byPath = new HashMap<String, DevicetreeHardware.Device>();
        for (var node : generated) byPath.put(node.path(), node);
        var regions = new ArrayList<Region>();
        boolean malformedReg = false;
        boolean anyReg = false;
        for (var node : generated) {
            if (node.reg().isBlank()) continue;
            anyReg = true;
            var decoded = regions(node);
            if (decoded.isEmpty()) malformedReg = true;
            regions.addAll(decoded);
        }
        var byPeripheral = new HashMap<String, BigInteger>();
        for (var reg : svd.registers())
            byPeripheral.merge(reg.peripheral(), reg.address(), BigInteger::min);
        var result = new HashMap<String, Link>();
        for (var entry : byPeripheral.entrySet()) {
            BigInteger address = entry.getValue();
            var matches = regions.stream()
                    .filter(r -> address.compareTo(r.base()) >= 0
                            && address.subtract(r.base()).compareTo(r.size()) < 0)
                    .sorted(java.util.Comparator.comparing(Region::size)).toList();
            if (matches.isEmpty()) {
                result.put(entry.getKey(), new Link(State.UNKNOWN, "",
                        generated.isEmpty() ? "No generated build/zephyr.dts nodes available"
                        : !anyReg ? "Generated Devicetree has no reg nodes"
                        : malformedReg ? "No matching reg; some generated reg cells are unparseable"
                        : "No generated Devicetree reg region covers this SVD address"));
                continue;
            }
            Region closest = matches.get(0);
            if (matches.size() > 1 && matches.get(1).size().equals(closest.size())) {
                result.put(entry.getKey(), new Link(State.AMBIGUOUS, "",
                        "Multiple generated Devicetree regions match this SVD address"));
                continue;
            }
            boolean enabled = true;
            String path = closest.node().path();
            for (String ancestor = path; !ancestor.isBlank();) {
                var node = byPath.get(ancestor);
                if (node != null && !node.enabled()) { enabled = false; break; }
                int slash = ancestor.lastIndexOf('/');
                ancestor = slash <= 0 ? "" : ancestor.substring(0, slash);
            }
            result.put(entry.getKey(), new Link(
                    enabled ? State.ENABLED_IN_DTS : State.DISABLED_IN_DTS,
                    path, enabled ? "Enabled in generated Devicetree"
                            : "Disabled by node or parent in generated Devicetree"));
        }
        return Map.copyOf(result);
    }

    private static List<Region> regions(DevicetreeHardware.Device node) {
        String raw = node.reg().trim();
        if (raw.isEmpty() || raw.length() > 1024 || raw.contains("&")
                || raw.contains("...")) return List.of();
        // Bounded one- to eight-region reg property, preserving the number
        // of cell pairs. Reject unrecognised separators and symbolic cells.
        var groups = GROUP.matcher(raw);
        var result = new ArrayList<Region>();
        int position = 0;
        while (groups.find()) {
            if (result.size() >= 8) return List.of();
            String separator = raw.substring(position, groups.start()).strip();
            if (!separator.isEmpty() && !(result.size() > 0 && separator.equals(",")))
                return List.of();
            String cells = groups.group(1).trim();
            if (!CELL.matcher(cells).replaceAll("").replaceAll("\\s+", "").isEmpty())
                return List.of();
            var tokens = new ArrayList<BigInteger>();
            var matcher = CELL.matcher(cells);
            while (matcher.find()) {
                String token = matcher.group();
                try {
                    BigInteger word = token.startsWith("0x") || token.startsWith("0X")
                            ? new BigInteger(token.substring(2), 16) : new BigInteger(token);
                    if (word.signum() < 0 || word.bitLength() > 32) return List.of();
                    tokens.add(word);
                } catch (RuntimeException invalid) { return List.of(); }
            }
            BigInteger address, size;
            if (tokens.size() == 2) {
                address = tokens.get(0); size = tokens.get(1);
            } else if (tokens.size() == 4 && tokens.get(0).signum() == 0
                    && tokens.get(2).signum() == 0) {
                address = tokens.get(0).shiftLeft(32).or(tokens.get(1));
                size = tokens.get(2).shiftLeft(32).or(tokens.get(3));
            } else return List.of();
            if (size.signum() <= 0 || size.bitLength() > 48) return List.of();
            result.add(new Region(node, address, size));
            position = groups.end();
        }
        return result.isEmpty() || !raw.substring(position).isBlank()
                ? List.of() : List.copyOf(result);
    }
}
