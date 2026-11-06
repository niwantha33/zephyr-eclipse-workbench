// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.ui.MemoryProfilerReportGuard;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Pure Java report-identity and atomic-publication regressions. */
public final class MemoryProfilerReportGuardTests {
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        System.out.println("PASS MEMORY REPORT: " + label);
    }
    private static void rejects(RunnableWithIO action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Invalid report was accepted");
        } catch (java.io.IOException expected) { /* fail closed */ }
    }
    @FunctionalInterface private interface RunnableWithIO {
        void run() throws Exception;
    }
    private static String report(String sha) {
        return """
                {"schema_version":1,"status":"memory-regions-observed",
                "memory_regions":[{"name":"FLASH","kind":"flash","kind_basis":"region-name-heuristic",
                   "origin":0,"end_exclusive":65536,"size_bytes":65536,"linker_attributes":"xr",
                   "confidence":"derived"}],
                "output_sections":[],
                "mpu":{"status":"not-determinable-from-linker-map","confidence":"unknown","regions":[]},
                "provenance":{"artifact":"zephyr/zephyr.map","sha256":"%s"}}
                """.formatted(sha);
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("zew-memory-report-Ω ");
        try {
            Path zephyr = Files.createDirectories(root.resolve("zephyr"));
            Path map = zephyr.resolve("zephyr.map");
            Files.writeString(map, "Memory Configuration\n" +
                "Name Origin Length Attributes\nFLASH 0x0 0x10000 xr\n" +
                "Linker script and memory map\n.text 0x0 0x10\n", StandardCharsets.UTF_8);
            check(MemoryProfilerReportGuard.map(root).equals(map), "exact per-build map selected");
            String sha = MemoryProfilerReportGuard.hashMap(map);
            String good = report(sha);
            MemoryProfilerReportGuard.verifyReport(good, sha);
            check(true, "valid report binds to same map SHA-256");
            rejects(() -> MemoryProfilerReportGuard.verifyReport(report("f".repeat(64)), sha));
            check(true, "different map digest cannot load as fresh");
            Path output = MemoryProfilerReportGuard.output(root);
            Path staging = Files.createTempFile(root, "memory-", ".tmp");
            Files.writeString(staging, good);
            MemoryProfilerReportGuard.publish(staging, output, map, sha);
            check(Files.readString(output).equals(good) && !Files.exists(staging),
                    "validated JSON is atomically published");
            staging = Files.createTempFile(root, "memory-", ".tmp");
            Files.writeString(staging, good);
            Files.writeString(map, "Modified map with same expected filename");
            Path oldOutput = output, outdated = staging;
            rejects(() -> MemoryProfilerReportGuard.publish(outdated, oldOutput, map, sha));
            check(Files.readString(output).equals(good), "stale map cannot replace last snapshot");
            Files.deleteIfExists(staging);
            try {
                Path link = root.resolve("linked.map");
                link = zephyr.resolve("alias.map");
                Files.createSymbolicLink(link, map);
                Path linked = link;
                rejects(() -> MemoryProfilerReportGuard.hashMap(linked));
                check(true, "symbolic-link map is rejected");
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException ignored) {
                System.out.println("SKIP MEMORY REPORT: symlinks unavailable");
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }
    }
}
