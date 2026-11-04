// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only map of memory and addressable peripherals from the selected
 * generated zephyr.dts, not a claim about the complete silicon address space.
 *
 * reg uses PARENT address/size cells. Descendant buses require an explicit
 * empty (identity) or explicit mapped ranges property before any CPU address
 * can be shown. Fixed flash partitions use offsets, never guessed addresses.
 * This deliberately does not turn a unit-address, reg=ID, or external
 * SPI/I2C chip-select into a CPU memory address.
 */
public final class DevicetreeMemoryMap {
    private DevicetreeMemoryMap() {}

    public enum Kind { RAM, FLASH, RESERVED, PARTITION, PERIPHERAL, OTHER }
    public record Region(String path, String node, Kind kind, String compatible,
                         String status, int line, String registerName,
                         BigInteger localAddress, BigInteger cpuAddress,
                         BigInteger size, String addressSpace, String evidence) {
        public String localHex() { return hex(localAddress); }
        public String cpuHex() { return cpuAddress == null ? "Unresolved" : hex(cpuAddress); }
        public String endHex() { return cpuAddress == null ? "Unresolved" : hex(cpuAddress.add(size)); }
        public String sizeLabel() { return humanSize(size); }
        public boolean cpuMapped() { return cpuAddress != null; }
        public String addressLabel() {
            return cpuMapped() ? cpuHex() + " – " + endHex() + " (end exclusive)"
                    : localHex() + " (relative to " + addressSpace + ")";
        }
    }

    public record Report(List<Region> regions, int unmappable, int omitted,
                         String notice) {
        public Report { regions = List.copyOf(regions); }
        public List<Region> cpuRegions() {
            return regions.stream().filter(Region::cpuMapped)
                    .sorted(Comparator.comparing(Region::cpuAddress)
                            .thenComparing(Region::size).thenComparing(Region::path)).toList();
        }
        public List<Region> relativeRegions() {
            return regions.stream().filter(r -> !r.cpuMapped())
                    .sorted(Comparator.comparing(Region::kind)
                            .thenComparing(Region::path)).toList();
        }
    }

    private record Node(String path, DevicetreeHardware.Device device,
                        Map<String, String> properties) {
        String property(String key) { return properties.get(key); }
    }
    private record Address(BigInteger value, String space, String evidence) {}
    private static final Pattern GROUP = Pattern.compile("<([^<>]*)>");
    private static final Pattern NUMERIC = Pattern.compile("(?:0[xX][0-9a-fA-F]+|[0-9]+)");
    private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final BigInteger MAX64 = BigInteger.ONE.shiftLeft(64);
    private static final int MAX_NODES = 10_000, MAX_REGIONS = 4_000;

    public static Report inspect(String generatedDts) {
        if (generatedDts == null || generatedDts.isBlank())
            return new Report(List.of(), 0, 0,
                    "No generated zephyr.dts for this selected board/build. Build first; "
                    + "no addresses or peripherals have been inferred.");
        var devices = DevicetreeHardware.inspect(generatedDts);
        var props = DevicetreeParameters.inspect(generatedDts);
        var all = new LinkedHashMap<String, Node>();
        Map<String, Map<String, String>> grouped = new HashMap<>();
        for (var p : props)
            grouped.computeIfAbsent(p.path(), ignored -> new LinkedHashMap<>())
                    .put(p.name(), p.value());
        for (var d : devices)
            all.put(d.path(), new Node(d.path(), d,
                    grouped.getOrDefault(d.path(), Map.of())));
        var result = new ArrayList<Region>();
        int omitted = 0, unresolved = 0;
        for (var node : all.values()) {
            if (result.size() >= MAX_REGIONS || all.size() > MAX_NODES) {
                omitted++;
                break;
            }
            String raw = node.property("reg");
            if (raw == null) continue;
            String parent = parent(node.path());
            int aCells = cellCount(all.get(parent), "#address-cells", 2);
            int sCells = cellCount(all.get(parent), "#size-cells", 1);
            if (aCells < 1 || aCells > 2 || sCells < 1 || sCells > 2) {
                omitted++;
                continue;
            }
            List<BigInteger> cells = cells(raw);
            int tuple = aCells + sCells;
            if (cells == null || cells.isEmpty() || cells.size() % tuple != 0) {
                omitted++;
                continue;
            }
            var names = quoted(node.property("reg-names"));
            boolean partition = isPartition(node, all);
            Kind kind = classify(node, all);
            for (int i = 0; i < cells.size() && result.size() < MAX_REGIONS; i += tuple) {
                BigInteger local = combine(cells, i, aCells);
                BigInteger size = combine(cells, i + aCells, sCells);
                if (local == null || size == null || size.signum() <= 0) {
                    omitted++;
                    continue;
                }
                int index = i / tuple;
                String name = index < names.size() ? names.get(index) : "";
                String space = partition ? "flash partition offset" : parent;
                BigInteger cpu = null;
                String evidence;
                if (partition) {
                    // Zephyr mapped-partition requires ranges all the way from
                    // partition -> grouping -> NVM -> controller -> CPU. Do not
                    // bypass a missing ranges mapping by adding a guessed base.
                    boolean mapped = node.device().compatible().contains("zephyr,mapped-partition");
                    if (mapped) {
                        Address translated = translate(all, parent, local, size);
                        if (translated != null) {
                            cpu = translated.value();
                            evidence = "Mapped partition offset translated through explicit "
                                    + "Devicetree ranges to CPU address; nested in Flash.";
                        } else evidence = "zephyr,mapped-partition offset only; missing/incomplete "
                                    + "NVM or parent ranges mapping prevents a proven CPU address.";
                    } else {
                        // Classic fixed-partitions are offsets within the
                        // containing flash, not standalone CPU address nodes.
                        String flash = parent(parent);
                        var base = result.stream()
                                .filter(r -> r.path().equals(flash)
                                        && r.kind() == Kind.FLASH && r.cpuMapped())
                                .findFirst().orElse(null);
                        if (base != null && local.add(size).compareTo(base.size()) <= 0) {
                            cpu = base.cpuAddress().add(local);
                            evidence = "CPU location derived from the single containing "
                                    + "CPU-mapped flash block plus fixed-partition offset.";
                        } else evidence = "Fixed partition is a flash-relative offset; "
                                    + "its containing flash has no proven single CPU range.";
                    }
                } else {
                    Address translated = translate(all, parent, local, size);
                    if (translated != null) {
                        cpu = translated.value();
                        space = translated.space();
                        evidence = translated.evidence();
                    } else evidence = "No complete bus-to-CPU ranges mapping; local reg "
                            + "address must NOT be presented as a physical CPU address.";
                }
                if (cpu == null) unresolved++;
                String status = inheritedStatus(node, all);
                result.add(new Region(node.path(), node.device().name(), kind,
                        node.device().compatible(), status, node.device().line(),
                        name, local, cpu, size, space, evidence));
            }
        }
        result.sort(Comparator.comparing((Region r) -> !r.cpuMapped())
                .thenComparing(r -> r.cpuMapped() ? r.cpuAddress() : BigInteger.ZERO)
                .thenComparing(Region::kind).thenComparing(Region::path));
        return new Report(result, unresolved, omitted,
                "Addresses and sizes are extracted only from the selected built zephyr.dts. "
                + "CPU addresses are shown only when parent-bus mapping is established. "
                + "Flash partitions can overlap their parent flash. Register ranges can also overlap. "
                + "The map is NOT the vendor's full silicon map, proof of available package pins, "
                + "memory capacity, allocation, live register state or runtime peripheral usage. "
                + "Missing nodes, unsupported address cells and complex buses are not guessed."
                + (omitted > 0 ? " Some unusable/zero-length reg entries were omitted: "
                        + omitted + "." : ""));
    }

    private static Address translate(Map<String, Node> nodes, String parentPath,
                                     BigInteger local, BigInteger size) {
        String bus = parentPath;
        BigInteger current = local;
        while (!"/".equals(bus)) {
            var node = nodes.get(bus);
            if (node == null) return null;
            String ranges = node.property("ranges");
            if (ranges == null) return null;
            if (!ranges.isBlank()) {
                String upstream = parent(bus);
                int childCells = cellCount(node, "#address-cells", 2);
                int parentCells = cellCount(nodes.get(upstream), "#address-cells", 2);
                int sizeCells = cellCount(node, "#size-cells", 1);
                if (childCells < 1 || childCells > 2 || parentCells < 1
                        || parentCells > 2 || sizeCells < 1 || sizeCells > 2) return null;
                int entryCells = childCells + parentCells + sizeCells;
                var blocks = cells(ranges);
                if (blocks == null || blocks.size() % entryCells != 0) return null;
                boolean found = false;
                for (int i = 0; i < blocks.size(); i += entryCells) {
                    BigInteger child = combine(blocks, i, childCells);
                    BigInteger upstreamAddress = combine(blocks, i + childCells, parentCells);
                    BigInteger length = combine(blocks, i + childCells + parentCells, sizeCells);
                    if (child == null || upstreamAddress == null || length == null) return null;
                    if (current.compareTo(child) >= 0
                            && current.add(size).compareTo(child.add(length)) <= 0) {
                        current = upstreamAddress.add(current.subtract(child));
                        found = true;
                        break;
                    }
                }
                if (!found) return null;
            }
            bus = parent(bus);
        }
        return current.add(size).compareTo(MAX64) > 0 ? null
                : new Address(current, "/", "CPU address translated through explicit "
                    + "or identity parent ranges in generated DTS.");
    }

    /** Null for malformed, macro-based, or nonnumeric generated cells. */
    private static List<BigInteger> cells(String raw) {
        if (raw == null) return null;
        var matches = GROUP.matcher(raw);
        var numbers = new ArrayList<BigInteger>();
        int last = 0;
        while (matches.find()) {
            if (!raw.substring(last, matches.start()).matches("[\\s,]*")) return null;
            String body = matches.group(1).strip();
            for (var token : body.split("\\s+")) {
                if (!NUMERIC.matcher(token).matches()) return null;
                try {
                    BigInteger value = token.startsWith("0x") || token.startsWith("0X")
                            ? new BigInteger(token.substring(2), 16) : new BigInteger(token);
                    if (value.signum() < 0 || value.bitLength() > 32) return null;
                    numbers.add(value);
                } catch (NumberFormatException invalid) { return null; }
            }
            last = matches.end();
        }
        if (last == 0 || !raw.substring(last).matches("[\\s,]*")) return null;
        return numbers;
    }

    private static BigInteger combine(List<BigInteger> numbers, int from, int count) {
        if (count < 1 || count > 2 || from + count > numbers.size()) return null;
        BigInteger sum = BigInteger.ZERO;
        for (int i = 0; i < count; i++) sum = sum.shiftLeft(32).or(numbers.get(from + i));
        return sum.compareTo(MAX64) >= 0 ? null : sum;
    }

    private static int cellCount(Node node, String key, int fallback) {
        if (node == null || node.property(key) == null) return fallback;
        var parts = cells(node.property(key));
        return parts == null || parts.size() != 1 || parts.get(0).bitLength() > 31
                ? -1 : parts.get(0).intValue();
    }

    private static List<String> quoted(String value) {
        if (value == null) return List.of();
        var out = new ArrayList<String>();
        Matcher names = STRING.matcher(value);
        while (names.find()) out.add(names.group(1));
        return List.copyOf(out);
    }

    private static boolean isPartition(Node node, Map<String, Node> all) {
        if (node.device().compatible().contains("zephyr,mapped-partition")) return true;
        var p = all.get(parent(node.path()));
        return p != null && p.property("compatible") != null
                && p.property("compatible").contains("\"fixed-partitions\"")
                && node.device().name().startsWith("partition@");
    }

    private static Kind classify(Node node, Map<String, Node> all) {
        if (isPartition(node, all)) return Kind.PARTITION;
        String path = node.path().toLowerCase(Locale.ROOT);
        String name = node.device().name().toLowerCase(Locale.ROOT);
        String compatible = node.device().compatible().toLowerCase(Locale.ROOT);
        if (path.startsWith("/reserved-memory/")) return Kind.RESERVED;
        if (name.startsWith("flash@") || compatible.contains("soc-nv-flash")
                || compatible.contains("spi-nor") || compatible.contains("cfi-flash"))
            return Kind.FLASH;
        if (name.startsWith("memory@") || name.startsWith("sram@")
                || name.startsWith("ram@") || name.startsWith("dtcm@")
                || name.startsWith("itcm@") || compatible.contains("mmio-sram")
                || compatible.contains("zephyr,memory-region"))
            return Kind.RAM;
        if (path.startsWith("/soc/") || path.contains("/pin-controller@"))
            return Kind.PERIPHERAL;
        return Kind.OTHER;
    }

    private static String inheritedStatus(Node node, Map<String, Node> all) {
        String status = node.device().status();
        if (status.isBlank()) status = "okay/default";
        String path = parent(node.path());
        while (!"/".equals(path)) {
            Node ancestor = all.get(path);
            if (ancestor != null && ancestor.device().status().equals("disabled"))
                return status + " (parent disabled: " + path + ")";
            path = parent(path);
        }
        return status;
    }

    private static String parent(String path) {
        if (path == null || path.isBlank() || path.equals("/")) return "/";
        int idx = path.lastIndexOf('/');
        return idx <= 0 ? "/" : path.substring(0, idx);
    }

    public static String hex(BigInteger v) {
        if (v == null) return "?";
        String s = v.toString(16).toUpperCase(Locale.ROOT);
        return "0x" + "0".repeat(Math.max(0, 8 - s.length())) + s;
    }

    public static String humanSize(BigInteger size) {
        if (size == null || size.signum() <= 0) return "unknown size";
        BigInteger kib = BigInteger.valueOf(1024);
        BigInteger mib = kib.multiply(kib);
        if (size.mod(mib).equals(BigInteger.ZERO)) return size.divide(mib) + " MiB";
        if (size.mod(kib).equals(BigInteger.ZERO)) return size.divide(kib) + " KiB";
        return size + " bytes";
    }
}
