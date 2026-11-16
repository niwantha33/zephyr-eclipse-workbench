// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.ConfigurationEvidenceReceipt;
import io.github.niwantha33.zew.core.ElfSymbols;
import io.github.niwantha33.zew.core.KconfigTraceEvidence;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Run ONLY on a completed real pinned Zephyr build in the Windows CI matrix.
 * This is build-and-ELF evidence, NOT a physical-flash attestation.
 */
public final class TraceFirmwareRealBuildProbe {
    private TraceFirmwareRealBuildProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Require application, build, board.");
        }
        Path app = Path.of(args[0]).toRealPath();
        Path build = Path.of(args[1]).toRealPath();
        String board = args[2];
        if (!Files.isDirectory(app) || !Files.isDirectory(build)
                || board.isBlank() || board.length() > 160)
            throw new IllegalArgumentException("Invalid selected trace build.");
        var expected = new LinkedHashMap<String, String>();
        expected.put("TRACING", "y");
        expected.put("TRACING_CTF", "y");
        expected.put("TRACING_ASYNC", "y");
        expected.put("TRACING_BACKEND_ZEW_RAM", "y");
        var config = KconfigTraceEvidence.inspectGenerated(build, expected);
        if (!config.matched()) {
            throw new IllegalStateException("Generated .config does not match the requested "
                    + "vendor-neutral trace backend: " + config);
        }
        String generated = Files.readString(build.resolve("zephyr/.config"));
        if (generated.contains("CONFIG_SMP=y") ||
                generated.contains("CONFIG_TRACING_BACKEND_ZEW_RTT=y")) {
            throw new IllegalStateException("Unexpected multicore or conflicting RTT backend");
        }
        Path elf = build.resolve("zephyr/zephyr.elf");
        if (!Files.isRegularFile(elf, LinkOption.NOFOLLOW_LINKS)
                || Files.size(elf) < 1024)
            throw new IllegalStateException("No completed real Zephyr ELF");
        var elfReport = ElfSymbols.inspect(elf);
        if (!elfReport.hasCtfHooks()) {
            throw new IllegalStateException("Selected real ELF lacks allocated CTF hooks");
        }
        Path map = build.resolve("zephyr/zephyr.map");
        if (!Files.isRegularFile(map, LinkOption.NOFOLLOW_LINKS)
                || Files.size(map) > 32L * 1024 * 1024)
            throw new IllegalStateException("No bounded linked Zephyr map");
        String linkMap = Files.readString(map);
        for (String name : new String[] {
                "zew_trace_ram_bytes", "zew_trace_ram_receipt",
                "zew_trace_backend_close", "zew_trace_stop_request",
                "zew_trace_encoder_send",
        }) {
            if (!linkMap.contains(name))
                throw new IllegalStateException("Expected real ELF map symbol absent: " + name);
        }
        var capture = ConfigurationEvidenceReceipt.capture(app, build, board, "default");
        if (!capture.current())
            throw new IllegalStateException("Trace build source provenance unverified: " + capture);
        var check = ConfigurationEvidenceReceipt.inspect(app, build, board, "default");
        if (!check.current())
            throw new IllegalStateException("Trace build input receipt changed: " + check);
        System.out.println("ZEW_TRACE_FIRMWARE_BUILD_1");
        System.out.println("board=" + board);
        System.out.println("ELF_SHA256=" + elfReport.sha256());
        System.out.println("machine=" + elfReport.machine());
        System.out.println("ctf_hooks=" + elfReport.ctfHooks().size());
        System.out.println("CONFIG_TRACE_MATCH=YES");
        System.out.println("BUILD_RECEIPT=CURRENT");
        System.out.println("LIVE_TARGET_VERIFIED=NO");
    }
}
