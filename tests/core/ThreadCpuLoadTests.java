// SPDX-License-Identifier: Apache-2.0
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import io.github.niwantha33.zew.ui.ThreadCpuLoad;
import io.github.niwantha33.zew.ui.ThreadTimelineReport;

/** Pure Java contract tests: derived shares are not hardware measurements. */
public final class ThreadCpuLoadTests {
    private static ThreadTimelineReport.Interval row(long from, long to, String thread, String state) {
        return new ThreadTimelineReport.Interval(from, to, "", thread, "", state);
    }
    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
    private static void percent(BigDecimal actual, String expected, String detail) {
        check(actual.compareTo(new BigDecimal(expected)) == 0, detail + ": " + actual);
    }

    public static void main(String[] args) {
        long ms = 1_000_000;
        var complete = List.of(
                row(0, 300 * ms, "A", "Running"),
                row(300 * ms, 1000 * ms, "idle", "Running"));
        var full = ThreadCpuLoad.calculate(complete, 0, 1000 * ms);
        check(full.threads().size() == 2, "one row per thread name");
        check(full.threads().get(0).threadName().equals("idle"),
              "descending thread share places idle first");
        percent(full.threads().get(0).percentage(), "70", "idle 70%");
        percent(full.threads().get(1).percentage(), "30", "thread A 30%");
        check(full.threads().get(0).runningNs().equals(BigInteger.valueOf(700 * ms)),
              "idle running nanoseconds exactly 700ms");
        check(full.threads().get(1).runningNs().equals(BigInteger.valueOf(300 * ms)),
              "thread A running nanoseconds exactly 300ms");
        check(full.threads().get(1).runningUs().equals("300000.000"), "us conversion exact");
        percent(full.idlePercentage(), "70", "idle percentage separately reported");
        percent(full.unknownPercentage(), "0", "no unknown time");
        check(!full.warnUnknown(), "no warning for complete scheduling window");

        var incomplete = List.of(
                row(0, 300 * ms, "A", "Running"),
                row(300 * ms, 400 * ms, "", "Unknown"),
                row(400 * ms, 1000 * ms, "IDLE_THREAD", "Running"));
        var partial = ThreadCpuLoad.calculate(incomplete, 0, 1000 * ms);
        percent(partial.threads().get(0).percentage(), "60", "idle_thread 60%");
        percent(partial.threads().get(1).percentage(), "30", "A 30%");
        percent(partial.idlePercentage(), "60", "case-insensitive idle_thread");
        percent(partial.unknownPercentage(), "10", "Unknown time 10%");
        check(partial.unknownNs().equals(BigInteger.valueOf(100 * ms)), "Unknown nanoseconds 100ms");
        check(partial.warnUnknown(), "warning triggered above 5% unknown");
        check(partial.threads().stream().map(ThreadCpuLoad.ThreadShare::percentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .compareTo(new BigDecimal("100")) < 0, "unknown not assigned to threads");

        var clipped = ThreadCpuLoad.calculate(incomplete, 250 * ms, 450 * ms);
        percent(clipped.unknownPercentage(), "50", "selected window clips unknown span");
        check(clipped.threads().stream().anyMatch(t -> t.threadName().equals("A")
                && t.runningNs().equals(BigInteger.valueOf(50 * ms))),
                "selected window clips A to 50ms");
        percent(clipped.idlePercentage(), "25", "selected window clips idle to 50ms");

        var overlaps = List.of(
                row(0, 100 * ms, "A", "Running"),
                row(20 * ms, 50 * ms, "", "ISR"),
                row(100 * ms, 200 * ms, "", "Unknown"),
                row(120 * ms, 180 * ms, "", "Unknown"),
                row(200 * ms, 1000 * ms, "idle", "Running"));
        var withIsr = ThreadCpuLoad.calculate(overlaps, 0, 1000 * ms);
        check(withIsr.isrNs().equals(BigInteger.valueOf(30 * ms)), "ISR duration separate");
        percent(withIsr.isrPercentage(), "3", "ISR percent separate");
        percent(withIsr.unknownPercentage(), "10", "overlapping Unknown intervals counted once");
        check(withIsr.warnUnknown(), "unknown union warning");

        var threshold = ThreadCpuLoad.calculate(List.of(
                row(0, 50 * ms, "", "Unknown"),
                row(50 * ms, 1000 * ms, "idle", "Running")), 0, 1000 * ms);
        percent(threshold.unknownPercentage(), "5", "5% exact threshold");
        check(!threshold.warnUnknown(), "warning strictly greater than 5%");

        boolean rejected = false;
        try { ThreadCpuLoad.calculate(complete, 100 * ms, 100 * ms); }
        catch (IllegalArgumentException expected) {
            rejected = expected.getMessage().contains("greater than Start");
        }
        check(rejected, "zero-length window rejected clearly before division");

        rejected = false;
        try { ThreadCpuLoad.calculate(complete, 0, 1001 * ms); }
        catch (IllegalArgumentException expected) {
            rejected = expected.getMessage().contains("observed trace");
        }
        check(rejected, "window beyond observed trace bounds refused");

        rejected = false;
        try { ThreadCpuLoad.observedBounds(List.of()); }
        catch (IllegalArgumentException expected) {
            rejected = expected.getMessage().contains("No observed");
        }
        check(rejected, "no trace window is not invented");

        check(ThreadCpuLoad.observedBounds(complete).startNs() == 0
                && ThreadCpuLoad.observedBounds(complete).endNs() == 1000 * ms,
                "default window uses observed interval bounds");
        var boundedTrace = List.of(row(200 * ms, 900 * ms, "A", "Running"));
        var withTraceBounds = ThreadCpuLoad.calculate(boundedTrace, 0, 1000 * ms,
                new ThreadCpuLoad.Bounds(0, 1000 * ms));
        percent(withTraceBounds.threads().getFirst().percentage(), "70",
                "unpaired trace edges are retained in the denominator");
        check(!withTraceBounds.warnUnknown(),
                "missing interval edges are not falsely labelled explicit Unknown");
        rejected = false;
        try { ThreadCpuLoad.calculate(boundedTrace, 0, 1001 * ms,
                new ThreadCpuLoad.Bounds(0, 1000 * ms)); }
        catch (IllegalArgumentException expected) {
            rejected = expected.getMessage().contains("observed trace");
        }
        check(rejected, "explicit trace bounds prevent extrapolation");
        rejected = false;
        try { ThreadCpuLoad.calculate(boundedTrace, 0, 1000 * ms,
                new ThreadCpuLoad.Bounds(300 * ms, 1000 * ms)); }
        catch (IllegalArgumentException expected) {
            rejected = expected.getMessage().contains("conflict");
        }
        check(rejected, "inconsistent header bounds rejected rather than trusted");

        System.out.println("PASS ThreadCpuLoadTests");
    }
}
