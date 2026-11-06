// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * CPU load for thread X = sum of X's Running intervals within the window, divided by
 * window length, multiplied by 100. Intervals marked Unknown are excluded from the
 * numerator but included in the window length, so percentages will not sum to 100
 * when the trace is incomplete.
 *
 * All values are derived from saved scheduler intervals, not a hardware measurement.
 * ISR durations are reported separately; a Running interval can include ISR time,
 * so ISR time must NOT be added to thread percentages to infer a total. Rows with
 * the same thread name are aggregated because the timeline records names, not IDs.
 * No time outside the observed interval bounds is extrapolated.
 */
public final class ThreadCpuLoad {
    private static final BigInteger THOUSAND = BigInteger.valueOf(1000);
    private static final BigInteger HUNDRED = BigInteger.valueOf(100);

    public record Bounds(long startNs, long endNs) {}

    public record ThreadShare(String threadName, BigInteger runningNs, BigDecimal percentage) {
        public String runningUs() { return microseconds(runningNs); }
    }

    public record Result(long startNs, long endNs, List<ThreadShare> threads,
                         BigInteger isrNs, BigDecimal isrPercentage,
                         BigDecimal idlePercentage, BigInteger unknownNs,
                         BigDecimal unknownPercentage, boolean warnUnknown) {
        public Result { threads = List.copyOf(threads); }
        public String isrUs() { return microseconds(isrNs); }
        public String unknownUs() { return microseconds(unknownNs); }
    }

    private record Slice(long start, long end) {}

    private ThreadCpuLoad() {}

    public static Bounds observedBounds(List<ThreadTimelineReport.Interval> intervals) {
        if (intervals == null || intervals.isEmpty())
            throw new IllegalArgumentException("No observed timeline intervals are available.");
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (var interval : intervals) {
            if (interval.startNs() < 0 || interval.endNs() <= interval.startNs())
                throw new IllegalArgumentException("Timeline contains invalid interval boundaries.");
            first = Math.min(first, interval.startNs());
            last = Math.max(last, interval.endNs());
        }
        return new Bounds(first, last);
    }

    public static Result calculate(List<ThreadTimelineReport.Interval> intervals, long startNs, long endNs) {
        if (startNs < 0 || endNs <= startNs)
            throw new IllegalArgumentException("End (ns) must be greater than Start (ns).");
        Bounds bounds = observedBounds(intervals);
        if (startNs < bounds.startNs() || endNs > bounds.endNs())
            throw new IllegalArgumentException(
                    "Selected window must stay within the observed trace interval range ("
                    + bounds.startNs() + " to " + bounds.endNs() + " ns).");
        BigInteger window = BigInteger.valueOf(endNs).subtract(BigInteger.valueOf(startNs));
        Map<String, BigInteger> running = new TreeMap<>();
        BigInteger isr = BigInteger.ZERO;
        List<Slice> unknown = new ArrayList<>();

        for (var interval : intervals) {
            long from = Math.max(startNs, interval.startNs());
            long to = Math.min(endNs, interval.endNs());
            if (to <= from) continue;
            BigInteger span = BigInteger.valueOf(to).subtract(BigInteger.valueOf(from));
            switch (interval.state()) {
                case "Running" -> running.merge(interval.threadName(), span, BigInteger::add);
                case "ISR" -> isr = isr.add(span);
                case "Unknown" -> unknown.add(new Slice(from, to));
                default -> { /* Ready/Blocked/Sleeping do not establish CPU execution. */ }
            }
        }

        // Count the union of observed Unknown spans once, even if rows overlap.
        unknown.sort(Comparator.comparingLong(Slice::start).thenComparingLong(Slice::end));
        BigInteger unknownTotal = BigInteger.ZERO;
        long openStart = -1;
        long openEnd = -1;
        for (var slice : unknown) {
            if (openStart < 0) {
                openStart = slice.start();
                openEnd = slice.end();
            } else if (slice.start() <= openEnd) {
                openEnd = Math.max(openEnd, slice.end());
            } else {
                unknownTotal = unknownTotal.add(BigInteger.valueOf(openEnd - openStart));
                openStart = slice.start();
                openEnd = slice.end();
            }
        }
        if (openStart >= 0)
            unknownTotal = unknownTotal.add(BigInteger.valueOf(openEnd - openStart));

        BigInteger idle = BigInteger.ZERO;
        List<ThreadShare> shares = new ArrayList<>();
        for (var entry : running.entrySet()) {
            String name = entry.getKey();
            BigInteger duration = entry.getValue();
            shares.add(new ThreadShare(name, duration, percent(duration, window)));
            if ("idle".equalsIgnoreCase(name) || "idle_thread".equalsIgnoreCase(name))
                idle = idle.add(duration);
        }
        shares.sort(Comparator.comparing(ThreadShare::runningNs).reversed()
                .thenComparing(ThreadShare::threadName));
        return new Result(startNs, endNs, shares, isr, percent(isr, window),
                percent(idle, window), unknownTotal, percent(unknownTotal, window),
                unknownTotal.multiply(HUNDRED).compareTo(window.multiply(BigInteger.valueOf(5))) > 0);
    }

    private static BigDecimal percent(BigInteger numerator, BigInteger denominator) {
        return new BigDecimal(numerator.multiply(HUNDRED))
                .divide(new BigDecimal(denominator), 4, RoundingMode.HALF_UP);
    }

    private static String microseconds(BigInteger nanoseconds) {
        BigInteger[] parts = nanoseconds.divideAndRemainder(THOUSAND);
        return parts[0] + "." + String.format(java.util.Locale.ROOT, "%03d", parts[1].intValue());
    }
}
