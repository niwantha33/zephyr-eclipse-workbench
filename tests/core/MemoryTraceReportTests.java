// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.ui.MemoryTraceReport;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class MemoryTraceReportTests {
    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
    private static void check(boolean test, String description) {
        if (!test) throw new AssertionError(description);
    }
    private static String header(int events, boolean incomplete, boolean bytes,
                                 String count, String current, String peak,
                                 String peakTime, String message, String imbalance) {
        return "ZEW_MEMORY\t1\t" + events + "\t" + (incomplete ? 1 : 0)
                + "\t0\t1\t" + (bytes ? 1 : 0) + "\t" + count + "\t"
                + current + "\t" + peak + "\t" + peakTime + "\t"
                + b64(message) + "\t" + b64(imbalance) + "\t\t0\t0\n";
    }

    public static void main(String[] args) {
        String imbalance = "Trace ends with 2 outstanding allocations (200 bytes). "
                + "This may indicate a leak or may simply mean the allocations are long-lived. "
                + "The trace alone cannot distinguish these.";
        var profile = MemoryTraceReport.parse(header(8, false, true,
                "2", "200", "500", "5000", imbalance, imbalance)
                + "P\t" + b64("pool_A") + "\t5\t3\t2\t200\t500\n"
                + "S\t1000\t1\t100\n"
                + "S\t2000\t2\t200\n"
                + "S\t3000\t3\t300\n"
                + "S\t4000\t4\t400\n"
                + "S\t5000\t5\t500\n"
                + "S\t6000\t4\t400\n"
                + "S\t7000\t3\t300\n"
                + "S\t8000\t2\t200\n");
        check(profile.currentCount() == 2 && profile.currentBytes().intValueExact() == 200
                && profile.peakBytes().intValueExact() == 500
                && profile.peakTimestampNs() == 5000L,
                "exact observed current / peak / fifth allocation timestamp");
        check(profile.steps().size() == 8 && profile.steps().get(4).outstandingBytes().intValueExact() == 500
                && profile.pools().getFirst().allocCount() == 5
                && profile.pools().getFirst().freeCount() == 3,
                "step and pool evidence retained");
        check(profile.imbalance().equals(imbalance), "exact non-accusatory imbalance text");

        var none = MemoryTraceReport.parse(header(0, false, true, "0", "", "", "",
                MemoryTraceReport.NO_EVENTS, ""));
        check(none.noEvents() && none.message().equals(
                "No memory allocation events found. Enable CONFIG_TRACING_HEAP or the equivalent "
                + "memory tracing option in the Zephyr build to use this view."),
                "no events exact message");

        var truncated = MemoryTraceReport.parse(header(8, true, true, "2", "200", "500", "5000",
                MemoryTraceReport.INCOMPLETE, "")
                + "P\t" + b64("pool_A") + "\t5\t3\t2\t200\t500\n");
        check(truncated.incomplete() && truncated.imbalance().isEmpty()
                && truncated.message().equals(MemoryTraceReport.INCOMPLETE),
                "truncation suppresses end-of-trace imbalance");

        var missing = MemoryTraceReport.parse(header(3, false, false, "1", "", "", "",
                "Byte totals unavailable", "")
                + "P\t" + b64("heap ID 10") + "\t2\t1\t1\t\t\n"
                + "S\t1\t1\t\n");
        check(missing.currentCount() == 1 && missing.currentBytes() == null
                && missing.peakBytes() == null && missing.pools().getFirst().peakBytes() == null,
                "unsized native CTF never gets invented byte totals");

        boolean failed = false;
        try { MemoryTraceReport.parse(header(8, true, true, "2", "200", "500", "5000",
                    "Incomplete", imbalance)); }
        catch (IllegalArgumentException ex) { failed = true; }
        check(failed, "incomplete report must reject leak-style line");

        failed = false;
        try { MemoryTraceReport.parse(header(4, false, false, "1", "100", "100", "1",
                    "Byte evidence absent", "")); }
        catch (IllegalArgumentException ex) { failed = true; }
        check(failed, "unsized data cannot claim exact bytes");

        System.out.println("PASS MemoryTraceReportTests");
    }
}
