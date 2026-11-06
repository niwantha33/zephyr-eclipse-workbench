// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Pure data/geometry model for the SWT memory diagram; never probes hardware. */
public final class MemoryProfilerLayout {
    public record Range(BigInteger start, BigInteger end) {
        public Range {
            if (start.signum() < 0 || end.compareTo(start) <= 0)
                throw new IllegalArgumentException("Invalid half-open address interval");
        }
        public BigInteger length() { return end.subtract(start); }
        public Range intersect(Range other) {
            BigInteger a = start.max(other.start), b = end.min(other.end);
            return a.compareTo(b) < 0 ? new Range(a, b) : null;
        }
    }
    public record Region(String name, String kind, String basis, String attributes,
                         Range address, BigInteger size) {}
    public record Section(String name, Range vma, Range lma, String allocation) {}
    public record MpuRegion(String name, Range address, String permissions) {}
    public record Diagram(List<Region> regions, List<Section> sections,
                          List<MpuRegion> mpu, String mpuStatus, String reportStatus,
                          String mapDigest) {
        public Diagram {
            regions = List.copyOf(regions);
            sections = List.copyOf(sections);
            mpu = List.copyOf(mpu);
        }
        public boolean hasMpuSnapshot() { return mpuStatus.equals("recorded-debugger-snapshot"); }
        public List<Range> overlaps(Region region) {
            if (!hasMpuSnapshot()) return List.of();
            List<Range> clipped = new ArrayList<>(), collisions = new ArrayList<>();
            for (MpuRegion m : mpu) {
                Range intersection = region.address.intersect(m.address);
                if (intersection != null) clipped.add(intersection);
            }
            for (int i = 0; i < clipped.size(); i++)
                for (int j = i + 1; j < clipped.size(); j++) {
                    Range overlap = clipped.get(i).intersect(clipped.get(j));
                    if (overlap != null) collisions.add(overlap);
                }
            return List.copyOf(collisions);
        }
    }

    private static final Pattern DIGEST = Pattern.compile("[a-fA-F0-9]{64}");
    private static final Pattern NUMBER = Pattern.compile("0|[1-9][0-9]{0,38}");
    private static final int MAX_REGIONS = 1024, MAX_SECTIONS = 20000, MAX_MPU = 128;

    private MemoryProfilerLayout() {}

    /** Map a subrange offset to device pixels without lossy long/double arithmetic. */
    public static int pixel(BigInteger offset, BigInteger length, int width) {
        if (length.signum() <= 0 || offset.signum() < 0 || width < 0)
            throw new IllegalArgumentException("Invalid range geometry");
        return offset.min(length).multiply(BigInteger.valueOf(width))
                .divide(length).intValueExact();
    }

    public static String hex(BigInteger value) {
        return "0x" + value.toString(16).toUpperCase(java.util.Locale.ROOT);
    }

    public static Diagram fromRows(List<MemoryProfilerJson.Row> rows) {
        Map<String, String> fields = new HashMap<>();
        for (var row : rows)
            if (fields.putIfAbsent(row.field(), row.rawValue()) != null)
                throw new IllegalArgumentException("Duplicate memory-report JSON field: " + row.field());
        if (!"1".equals(fields.get("schema_version")))
            throw new IllegalArgumentException("Unsupported memory report schema");
        String reportStatus = string(fields, "status");
        if (!List.of("memory-regions-observed", "memory-regions-unavailable").contains(reportStatus))
            throw new IllegalArgumentException("Unsupported memory report status");
        String digest = string(fields, "provenance.sha256");
        if (!DIGEST.matcher(digest).matches())
            throw new IllegalArgumentException("Missing map artifact SHA-256 provenance");

        int count = arrayCount(fields, "memory_regions", MAX_REGIONS);
        List<Region> regions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String p = "memory_regions[" + i + "].";
            String name = string(fields, p + "name");
            String kind = string(fields, p + "kind");
            if (!List.of("flash", "ram", "other").contains(kind))
                throw new IllegalArgumentException("Unsupported memory kind: " + kind);
            BigInteger start = number(fields, p + "origin");
            BigInteger end = number(fields, p + "end_exclusive");
            BigInteger size = number(fields, p + "size_bytes");
            Region region = new Region(name, kind, string(fields, p + "kind_basis"),
                    string(fields, p + "linker_attributes"), new Range(start, end), size);
            if (!region.address.length().equals(size))
                throw new IllegalArgumentException("Inconsistent linker-region length: " + name);
            regions.add(region);
        }
        int sc = arrayCount(fields, "output_sections", MAX_SECTIONS);
        List<Section> sections = new ArrayList<>();
        for (int i = 0; i < sc; i++) {
            String p = "output_sections[" + i + "].";
            String name = string(fields, p + "name");
            BigInteger size = number(fields, p + "size_bytes");
            Range vma = new Range(number(fields, p + "start"), number(fields, p + "end_exclusive"));
            if (!vma.length().equals(size))
                throw new IllegalArgumentException("Inconsistent output-section size: " + name);
            BigInteger load = optionalNumber(fields, p + "load_address");
            BigInteger loadEnd = optionalNumber(fields, p + "load_end_exclusive");
            if ((load == null) != (loadEnd == null))
                throw new IllegalArgumentException("Incomplete section LMA pair: " + name);
            Range lma = load == null ? null : new Range(load, loadEnd);
            if (lma != null && !lma.length().equals(size))
                throw new IllegalArgumentException("Inconsistent output-section LMA: " + name);
            sections.add(new Section(name, vma, lma, string(fields, p + "elf_allocation")));
        }

        String mpuStatus = string(fields, "mpu.status");
        int mc = arrayCount(fields, "mpu.regions", MAX_MPU);
        List<MpuRegion> mpu = new ArrayList<>();
        if (mpuStatus.equals("not-determinable-from-linker-map")) {
            if (mc != 0 || !"unknown".equals(string(fields, "mpu.confidence")))
                throw new IllegalArgumentException("A linker map alone cannot establish MPU regions");
        } else if (mpuStatus.equals("recorded-debugger-snapshot")) {
            // Gate imported MPU overlays on a separately identified halted target capture.
            // A hash proves bytes are identifiable; it does NOT authenticate hardware state.
            if (!"debugger-register-readback".equals(string(fields, "mpu.evidence.method"))
                    || !"halted-target".equals(string(fields, "mpu.evidence.context"))
                    || !digest.equalsIgnoreCase(string(fields, "mpu.evidence.map_sha256"))
                    || !DIGEST.matcher(string(fields, "mpu.evidence.capture_sha256")).matches()
                    || !DIGEST.matcher(string(fields, "mpu.evidence.elf_sha256")).matches())
                throw new IllegalArgumentException("MPU overlay lacks corresponding capture evidence");
            for (int i = 0; i < mc; i++) {
                String p = "mpu.regions[" + i + "].";
                mpu.add(new MpuRegion(string(fields, p + "name"),
                        new Range(number(fields, p + "start"), number(fields, p + "end_exclusive")),
                        string(fields, p + "permissions")));
            }
        } else {
            throw new IllegalArgumentException("Unsupported MPU evidence status: " + mpuStatus);
        }
        return new Diagram(regions, sections, mpu, mpuStatus, reportStatus, digest);
    }

    private static int arrayCount(Map<String, String> fields, String prefix, int limit) {
        if ("[]".equals(fields.get(prefix))) {
            if (fields.keySet().stream().anyMatch(k -> k.startsWith(prefix + "[")))
                throw new IllegalArgumentException("Nonempty array contradicts [] at " + prefix);
            return 0;
        }
        int high = -1;
        for (String key : fields.keySet()) {
            if (!key.startsWith(prefix + "[")) continue;
            int end = key.indexOf(']', prefix.length() + 1);
            if (end < 0 || end + 1 >= key.length() || key.charAt(end + 1) != '.')
                throw new IllegalArgumentException("Invalid array field " + key);
            String token = key.substring(prefix.length() + 1, end);
            if (!token.matches("0|[1-9][0-9]{0,5}"))
                throw new IllegalArgumentException("Invalid array index " + token);
            high = Math.max(high, Integer.parseInt(token));
            if (high >= limit) throw new IllegalArgumentException("Memory report exceeds array limit");
        }
        return high + 1;
    }

    private static String string(Map<String, String> fields, String key) {
        String raw = fields.get(key);
        if (raw == null || raw.length() < 2 || raw.charAt(0) != '"' || raw.charAt(raw.length()-1) != '"')
            throw new IllegalArgumentException("Missing JSON string: " + key);
        StringBuilder value = new StringBuilder();
        for (int i = 1; i < raw.length() - 1; i++) {
            char c = raw.charAt(i);
            if (c != '\\') { value.append(c); continue; }
            if (++i >= raw.length()-1) throw new IllegalArgumentException("Invalid escape in " + key);
            char escape = raw.charAt(i);
            switch (escape) {
                case '"' -> value.append('"');
                case '\\' -> value.append('\\');
                case '/' -> value.append('/');
                case 'n' -> value.append('\n');
                case 't' -> value.append('\t');
                case 'r' -> value.append('\r');
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'u' -> {
                    if (i + 4 >= raw.length()-1)
                        throw new IllegalArgumentException("Bad Unicode escape: " + key);
                    value.append((char) Integer.parseInt(raw.substring(i+1, i+5), 16));
                    i += 4;
                }
                default -> throw new IllegalArgumentException("Invalid JSON escape in " + key);
            }
        }
        return value.toString();
    }

    private static BigInteger optionalNumber(Map<String, String> fields, String key) {
        return "null".equals(fields.get(key)) ? null : number(fields, key);
    }

    private static BigInteger number(Map<String, String> fields, String key) {
        String raw = fields.get(key);
        if (raw == null || !NUMBER.matcher(raw).matches())
            throw new IllegalArgumentException("Missing or unsupported unsigned address: " + key);
        return new BigInteger(raw);
    }
}
