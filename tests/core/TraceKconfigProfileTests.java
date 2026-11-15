// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.TraceKconfigProfile;
import io.github.niwantha33.zew.core.KconfigTraceEvidence;
import java.nio.file.Files;
import java.nio.file.Path;

/** Exact generated-config evidence; probe-free and conservative by design. */
public final class TraceKconfigProfileTests {
    private static int cases;
    private static void check(boolean valid, String reason) {
        if (!valid) throw new AssertionError(reason);
        cases++;
    }

    public static void main(String[] args) throws Exception {
        var ram = TraceKconfigProfile.select(
                TraceKconfigProfile.Transport.RAM_SNAPSHOT, true);
        var uart = TraceKconfigProfile.select(
                TraceKconfigProfile.Transport.UART_BINARY, false);
        var rtt = TraceKconfigProfile.select(
                TraceKconfigProfile.Transport.RTT_BINARY, true);
        check(ram.overlayAvailable() && !ram.liveReady()
                && ram.overlay().contains("CONFIG_TRACING_BACKEND_RAM=y")
                && ram.overlay().contains("CONFIG_RAM_TRACING_BUFFER_SIZE=8192"),
                "RAM remains an explicit non-live snapshot");
        check(ram.expected().get("DEBUG_THREAD_INFO").equals("y")
                && !uart.expected().containsKey("DEBUG_THREAD_INFO"),
                "OpenOCD thread metadata requires proven !SMP, not vendor inference");
        check(uart.overlayAvailable() && !uart.liveReady()
                && uart.overlay().contains("CONFIG_TRACING_ASYNC=y")
                && uart.limitation().contains("uart_poll_out"),
                "UART cannot be advertised nonblocking");
        check(!rtt.overlayAvailable() && !rtt.liveReady()
                && rtt.expected().isEmpty()
                && rtt.limitation().contains("TRACING_BACKEND_RTT"),
                "missing Zephyr 4.4 CTF RTT backend must fail closed");
        boolean refused = false;
        try { rtt.overlay(); } catch (IllegalStateException expected) { refused = true; }
        check(refused, "must refuse to invent RTT Kconfig overlay");

        Path build = Files.createTempDirectory("zew-ctf-gate");
        Path zephyr = Files.createDirectories(build.resolve("zephyr"));
        Path generated = zephyr.resolve(".config");
        check(KconfigTraceEvidence.inspectGenerated(build, ram.expected()).state()
                == KconfigTraceEvidence.State.MISSING, "missing generated config");
        Files.writeString(generated, ram.overlay());
        var observed = KconfigTraceEvidence.inspectGenerated(build, ram.expected());
        check(observed.matched() && observed.configSha256().matches("[0-9a-f]{64}"),
                "exact generated effective symbols match, without claiming live safety");
        Files.writeString(generated, ram.overlay()
                .replace("CONFIG_TRACING_CTF=y", "# CONFIG_TRACING_CTF is not set"));
        var disabled = KconfigTraceEvidence.inspectGenerated(build, ram.expected());
        check(!disabled.matched() && disabled.diagnostics().stream()
                .anyMatch(s -> s.contains("TRACING_CTF")), "effective n overrides requested y");
        Files.writeString(generated, ram.overlay()
                .replace("CONFIG_TRACING_CTF=y\n", ""));
        check(!KconfigTraceEvidence.inspectGenerated(build, ram.expected()).matched(),
                "missing symbol must not be assumed selected");
        Files.writeString(generated, ram.overlay() + "CONFIG_TRACING_CTF=y\n");
        check(!KconfigTraceEvidence.inspectGenerated(build, ram.expected()).matched(),
                "duplicate exact Kconfig symbol is ambiguous");
        Files.writeString(generated, "CONFIG_TRACING=y\n");
        check(!KconfigTraceEvidence.inspectGenerated(build, java.util.Map.of(
                "INVALID;INJECTION", "y")).matched(), "unsafe symbol names rejected");
        check(!KconfigTraceEvidence.inspectGenerated(build, java.util.Map.of(
                "TRACING", "arbitrary secret")).matched(), "unsafe values rejected");
        Files.writeString(generated, "x".repeat(4 * 1024 * 1024 + 1));
        check(!KconfigTraceEvidence.inspectGenerated(build, ram.expected()).matched(),
                "oversize generated config fails closed");
        check(TraceKconfigProfile.ZEPHYR_COMMIT.length() == 40,
                "pin exact immutable Zephyr commit");

        System.out.println("PASS Zephyr 4.4 trace Kconfig profile/evidence: " + cases);
    }
}
