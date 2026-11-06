// SPDX-License-Identifier: Apache-2.0
import java.util.List;
import io.github.niwantha33.zew.ui.MemoryProfilerJson;

/** Fast, headless parser contract checks, without Eclipse or actual hardware. */
public final class MemoryProfilerJsonTests {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        System.out.println("PASS MEMORY PROFILER: " + message);
    }

    private static void rejects(String json) {
        try {
            MemoryProfilerJson.parse(json);
            throw new AssertionError("Invalid JSON accepted: " + json);
        } catch (IllegalArgumentException expected) {
            // Fail-closed, without showing data from malformed reports.
        }
    }

    public static void main(String[] args) {
        String fixture = """
                {
                  "schema_version": 1,
                  "status": "memory-regions-observed",
                  "memory_regions": [{"name": "FLASH", "origin": 0, "end_exclusive": 65536}],
                  "output_sections": [{"name": ".data", "load_address": 96, "vma_region": "SRAM"}],
                  "mpu": {"status": "not-determinable-from-linker-map", "regions": []},
                  "provenance": {"artifact": "zephyr/zephyr.map", "sha256": null}
                }
                """;
        List<MemoryProfilerJson.Row> rows = MemoryProfilerJson.parse(fixture);
        check(rows.stream().anyMatch(r -> r.field().equals("memory_regions[0].name")
                && r.rawValue().equals("\"FLASH\"")), "region names retain raw JSON strings");
        check(rows.stream().anyMatch(r -> r.field().equals("output_sections[0].load_address")
                && r.rawValue().equals("96")), "LMA fields retain numeric JSON values");
        check(rows.stream().anyMatch(r -> r.field().equals("mpu.regions")
                && r.rawValue().equals("[]")), "unknown MPU data stays empty");
        check(rows.equals(MemoryProfilerJson.parse(fixture)), "table row order is deterministic");
        check(MemoryProfilerJson.parse(fixture.replace("FLASH", "FL\\\\ASH")).stream()
                .anyMatch(r -> r.rawValue().contains("FL\\\\ASH")), "backslash escape is preserved");
        rejects("{\"schema_version\":1}");
        rejects(fixture + "trailing");
        rejects(fixture.replace("\"schema_version\": 1", "\"schema_version\": 2"));
        rejects(fixture.replace("\"origin\": 0", "\"origin\": 09"));
        rejects(fixture.replace("\"origin\": 0", "\"origin\": null, INVALID"));
        rejects(fixture.replace("[]", "[[[")); 
        check(true, "malformed and unsupported reports fail closed");
    }
}
