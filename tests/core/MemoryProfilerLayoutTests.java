// SPDX-License-Identifier: Apache-2.0
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import io.github.niwantha33.zew.ui.MemoryProfilerJson;
import io.github.niwantha33.zew.ui.MemoryProfilerLayout;
import io.github.niwantha33.zew.ui.MemoryProfilerLayout.Range;

/** Headless tests for linker-geometry and evidence gates; no Eclipse or hardware. */
public final class MemoryProfilerLayoutTests {
    private static final String HASH = "a".repeat(64), CAPTURE = "b".repeat(64), ELF = "c".repeat(64);

    private static void check(boolean ok, String description) {
        if (!ok) throw new AssertionError(description);
        System.out.println("PASS MEMORY DIAGRAM: " + description);
    }
    private static void add(List<MemoryProfilerJson.Row> rows, String field, String value) {
        rows.add(new MemoryProfilerJson.Row(field, value));
    }
    private static void str(List<MemoryProfilerJson.Row> rows, String field, String value) {
        add(rows, field, "\"" + value + "\"");
    }
    private static List<MemoryProfilerJson.Row> fixture() {
        List<MemoryProfilerJson.Row> rows = new ArrayList<>();
        add(rows, "schema_version", "1");
        str(rows, "status", "memory-regions-observed");
        str(rows, "provenance.sha256", HASH);
        str(rows, "memory_regions[0].name", "FLASH");
        str(rows, "memory_regions[0].kind", "flash");
        str(rows, "memory_regions[0].kind_basis", "region-name-heuristic");
        str(rows, "memory_regions[0].linker_attributes", "xr");
        add(rows, "memory_regions[0].origin", "0");
        add(rows, "memory_regions[0].end_exclusive", "65536");
        add(rows, "memory_regions[0].size_bytes", "65536");
        str(rows, "memory_regions[1].name", "SRAM");
        str(rows, "memory_regions[1].kind", "ram");
        str(rows, "memory_regions[1].kind_basis", "region-name-heuristic");
        str(rows, "memory_regions[1].linker_attributes", "xrw");
        add(rows, "memory_regions[1].origin", "536870912");
        add(rows, "memory_regions[1].end_exclusive", "536903680");
        add(rows, "memory_regions[1].size_bytes", "32768");
        str(rows, "output_sections[0].name", ".data");
        add(rows, "output_sections[0].start", "536870912");
        add(rows, "output_sections[0].end_exclusive", "536870928");
        add(rows, "output_sections[0].size_bytes", "16");
        add(rows, "output_sections[0].load_address", "96");
        add(rows, "output_sections[0].load_end_exclusive", "112");
        str(rows, "output_sections[0].elf_allocation", "unknown-without-elf");
        str(rows, "mpu.status", "not-determinable-from-linker-map");
        str(rows, "mpu.confidence", "unknown");
        add(rows, "mpu.regions", "[]");
        return rows;
    }
    private static void rejects(List<MemoryProfilerJson.Row> rows) {
        try {
            MemoryProfilerLayout.fromRows(rows);
            throw new AssertionError("Rejected data accepted");
        } catch (IllegalArgumentException expected) { /* pass */ }
    }
    private static void put(List<MemoryProfilerJson.Row> rows, String key, String value) {
        rows.removeIf(r -> r.field().equals(key));
        add(rows, key, value);
    }

    public static void main(String[] ignored) {
        var diagram = MemoryProfilerLayout.fromRows(fixture());
        check(diagram.regions().size() == 2 && diagram.sections().size() == 1,
                "flash and SRAM and one output section recognised");
        check(diagram.sections().get(0).vma().start().equals(BigInteger.valueOf(536870912L))
                && diagram.sections().get(0).lma().start().equals(BigInteger.valueOf(96)),
                "RAM VMA and separate Flash LMA retained without conflation");
        check(!diagram.hasMpuSnapshot() && diagram.conflicts(diagram.regions().get(1)).isEmpty(),
                "ordinary map cannot create MPU overlays or overlaps");
        check(MemoryProfilerLayout.pixel(BigInteger.valueOf(500), BigInteger.valueOf(1000), 600) == 300
                && MemoryProfilerLayout.pixel(BigInteger.valueOf(1000), BigInteger.valueOf(1000), 600) == 600,
                "pixel mapping uses region-relative exact integer geometry");
        Range first = new Range(BigInteger.ZERO, BigInteger.valueOf(16));
        Range touching = new Range(BigInteger.valueOf(16), BigInteger.valueOf(24));
        check(first.intersect(touching) == null, "end-exclusive boundary contact is not an overlap");
        check(first.intersect(new Range(BigInteger.valueOf(8), BigInteger.valueOf(24))).length()
                .equals(BigInteger.valueOf(8)), "crossing intervals are clipped precisely");
        check(MemoryProfilerLayout.pixel(new BigInteger("12345678901234567890123"),
                new BigInteger("24691357802469135780246"), 600) == 300,
                "large address arithmetic never loses precision through double");

        var forged = fixture();
        put(forged, "mpu.regions[0].name", "MPU0");
        rejects(forged);
        check(true, "MPU rows inserted into a map-only report fail closed");

        var captured = fixture();
        put(captured, "mpu.status", "\"recorded-debugger-snapshot\"");
        str(captured, "mpu.evidence.method", "debugger-register-readback");
        str(captured, "mpu.evidence.context", "halted-target");
        str(captured, "mpu.evidence.map_sha256", HASH);
        str(captured, "mpu.evidence.capture_sha256", CAPTURE);
        str(captured, "mpu.evidence.elf_sha256", ELF);
        captured.removeIf(r -> r.field().equals("mpu.regions"));
        str(captured, "mpu.regions[0].name", "region0");
        add(captured, "mpu.regions[0].start", "536870912");
        add(captured, "mpu.regions[0].end_exclusive", "536870944");
        str(captured, "mpu.regions[0].permissions", "rw");
        str(captured, "mpu.regions[1].name", "region1");
        add(captured, "mpu.regions[1].start", "536870928");
        add(captured, "mpu.regions[1].end_exclusive", "536870960");
        str(captured, "mpu.regions[1].permissions", "rw");
        var snapshot = MemoryProfilerLayout.fromRows(captured);
        check(snapshot.hasMpuSnapshot() && snapshot.mpu().size() == 2,
                "separately identified snapshot enables recorded MPU overlays");
        var overlaps = snapshot.conflicts(snapshot.regions().get(1));
        check(overlaps.size() == 1 && overlaps.get(0).start().equals(BigInteger.valueOf(536870928L))
                && overlaps.get(0).end().equals(BigInteger.valueOf(536870944L)),
                "two MPU snapshots yield the exact 16-byte overlap interval");
        check(snapshot.conflicts(snapshot.regions().get(0)).isEmpty(),
                "MPU overlap is never painted in unrelated Flash region");
        put(captured, "mpu.evidence.map_sha256", "\"" + "d".repeat(64) + "\"");
        rejects(captured);
        check(true, "a capture tied to a different map SHA is rejected");
        var corrupt = fixture();
        put(corrupt, "memory_regions[0].size_bytes", "42");
        rejects(corrupt);
        check(true, "inconsistent region geometry is rejected, not scaled");
    }
}
