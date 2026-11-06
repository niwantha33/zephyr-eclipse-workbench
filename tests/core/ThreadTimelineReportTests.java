// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.ui.ThreadTimelineReport;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Headless, pure JDK protocol tests (no Eclipse or target dependency). */
public final class ThreadTimelineReportTests {
    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }
    private static String text(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
    private static String row(long first, long last, String name, String state) {
        return "I\t" + first + "\t" + last + "\t" + text(name) + "\t" + text("3")
                + "\t" + state + "\n";
    }
    private static String header(boolean incomplete, int unknown, int scheduler) {
        return "ZEW_THREAD_TIMELINE\t1\t" + (incomplete ? "1" : "0") + "\t" + unknown
                + "\t" + scheduler + "\t" + text("truncated") + "\t0\n";
    }
    public static void main(String[] args) {
        var record = ThreadTimelineReport.parse(header(true, 2, 4)
                + row(100, 1100, "worker a", "Running")
                + row(1100, 3100, "", "Unknown"));
        check(record.intervals().size() == 2, "two rows decoded");
        check(record.intervals().get(0).durationUs().equals("1.000"), "exact ns-to-us duration");
        check(record.intervals().get(0).threadName().equals("worker a"), "thread name decoded");
        check(record.intervals().get(1).state().equals("Unknown"), "unknown state preserved");
        check(record.incomplete() && record.unknownEvents() == 2 && record.schedulerEvents() == 4,
                "warning and unknown count retained");

        var empty = ThreadTimelineReport.parse(header(false, 0, 0));
        check(empty.intervals().isEmpty() && empty.schedulerEvents() == 0, "empty report valid");

        boolean malformed = false;
        try { ThreadTimelineReport.parse(header(false, 0, 1) + row(200, 100, "x", "Running")); }
        catch (IllegalArgumentException expected) { malformed = true; }
        check(malformed, "reversed time boundaries rejected");

        malformed = false;
        try { ThreadTimelineReport.parse(header(false, 0, 1) + "I\t0\t10\t@@@\tMQ==\tRunning\n"); }
        catch (IllegalArgumentException expected) { malformed = true; }
        check(malformed, "malformed names rejected");

        malformed = false;
        try { ThreadTimelineReport.parse(header(false, 0, 1) + "I\t0\t10\t\t\tInvented\n"); }
        catch (IllegalArgumentException expected) { malformed = true; }
        check(malformed, "invented state rejected");

        System.out.println("PASS ThreadTimelineReportTests");
    }
}
