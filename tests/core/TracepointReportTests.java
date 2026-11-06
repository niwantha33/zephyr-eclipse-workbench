// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.ui.TracepointReport;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class TracepointReportTests {
    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
    private static String header(String status, String error) {
        return "ZEW_TRACEPOINTS\t1\t" + status + "\t" + encoded(error) + "\t0\t1\t\n";
    }
    public static void main(String[] args) {
        var parsed = TracepointReport.parse(header("active", "")
                + "D\t" + encoded("motor_rpm") + "\t" + encoded("Motor RPM") + "\tinteger\t3\n"
                + "D\t" + encoded("motor_temp") + "\t" + encoded("Temperature") + "\tfloat\t0\n"
                + "S\t" + encoded("motor_rpm") + "\t10\t10\n"
                + "S\t" + encoded("motor_rpm") + "\t20\t20\n"
                + "S\t" + encoded("motor_rpm") + "\t30\t30\n");
        check(parsed.series().size() == 2, "two declarations retained");
        var rpm = parsed.series().getFirst();
        check(rpm.found() && rpm.samples().size() == 3, "three samples exist");
        check(rpm.samples().get(0).timestampNs() == 10
                && rpm.samples().get(2).timestampNs() == 30, "time ordered");
        check(rpm.min().intValueExact() == 10 && rpm.max().intValueExact() == 30
                && rpm.mean().intValueExact() == 20, "min/max/mean exactly computed");
        check(!parsed.series().get(1).found(), "declared but not found retained without throwing");
        check(parsed.unknownEvents() == 1, "unknown count remains visible");

        var missing = TracepointReport.parse(header("inactive", ""));
        check(missing.series().isEmpty() && missing.status().equals("inactive"),
                "missing project configuration is inactive");

        var malformed = TracepointReport.parse(header("invalid", "line 3: bad type"));
        check(malformed.status().equals("invalid")
                && malformed.configError().equals("line 3: bad type"),
                "line-numbered malformed config flows to view report");

        var floats = TracepointReport.parse(header("active", "")
                + "D\t" + encoded("temp") + "\t" + encoded("Temperature") + "\tfloat\t2\n"
                + "S\t" + encoded("temp") + "\t3\t1.25\n"
                + "S\t" + encoded("temp") + "\t4\t2.75\n");
        check(floats.series().getFirst().mean().toPlainString().equals("2.00"),
                "float sample mean is exact");

        boolean failed = false;
        try { TracepointReport.parse(header("active", "")
                + "D\t" + encoded("motor") + "\t" + encoded("Motor") + "\tinteger\t1\n"
                + "S\t" + encoded("motor") + "\t10\tNaN\n"); }
        catch (IllegalArgumentException expected) { failed = true; }
        check(failed, "non-numeric payload refused");

        failed = false;
        try { TracepointReport.parse(header("active", "")
                + "D\t" + encoded("motor") + "\t" + encoded("Motor") + "\tinteger\t2\n"
                + "S\t" + encoded("motor") + "\t20\t1\n"
                + "S\t" + encoded("motor") + "\t10\t1\n"); }
        catch (IllegalArgumentException expected) { failed = true; }
        check(failed, "out-of-order series timestamps refused");

        System.out.println("PASS TracepointReportTests");
    }
}
