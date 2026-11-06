// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.ui.ContentionReport;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Headless protocol contract: the SWT view cannot invent a holder or priority. */
public final class ContentionReportTests {
    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    private static void check(boolean yes, String what) {
        if (!yes) throw new AssertionError(what);
    }
    private static String header(boolean priorities, boolean incomplete) {
        return "ZEW_CONTENTION\t1\t" + (priorities ? 1 : 0) + "\t"
                + (incomplete ? 1 : 0) + "\t0\t8\t\n";
    }
    private static String finding(String kind, String holder, boolean unknown) {
        return "F\t" + kind + "\t" + b64("lock_A") + "\t" + b64("high")
                + "\t" + b64(holder) + "\t1500000\t"
                + b64(kind.equals("Inversion") ? "medium" : "")
                + "\t1\t" + (unknown ? "" : "5") + "\t"
                + (kind.equals("Inversion") ? "3" : "") + "\t"
                + (unknown ? "1" : "0") + "\t"
                + b64(kind.equals("Inversion")
                       ? "Observed high blocked while medium ran and low held lock_A"
                       : "Observed long wait, holder unknown.") + "\n";
    }

    public static void main(String[] args) {
        var first = ContentionReport.parse(header(true, false)
                + finding("Inversion", "low", false)
                + finding("Long Wait", "", true));
        check(first.inversionEnabled(), "priority inversion enabled with priority data");
        check(first.findings().size() == 2, "two semantic findings");
        var inversion = first.findings().getFirst();
        check(inversion.kind().equals("Inversion")
                && inversion.waiter().equals("high") && inversion.holder().equals("low")
                && inversion.medium().equals("medium"), "three explicit threads survive wire format");
        check(inversion.durationNs() == 1_500_000
                && inversion.durationUs().equals("1500.000"), "nanosecond duration is exact");
        check(inversion.waiterPriority() == 1 && inversion.mediumPriority() == 3
                && inversion.holderPriority() == 5, "strict Zephyr priority order preserved");
        var wait = first.findings().get(1);
        check(wait.holderUnknown() && wait.holder().isEmpty(), "missing holder explicitly unknown");

        var noPriority = ContentionReport.parse(header(false, false));
        check(!noPriority.inversionEnabled() && noPriority.findings().isEmpty(),
                "no-priority mode available without guessing");

        boolean invalid = false;
        try {
            ContentionReport.parse(header(false, false) + finding("Inversion", "low", false));
        } catch (IllegalArgumentException expected) { invalid = true; }
        check(invalid, "cannot display inversion with disabled priorities");

        invalid = false;
        try { ContentionReport.parse(header(true, false) + finding("Long Wait", "low", true)); }
        catch (IllegalArgumentException expected) { invalid = true; }
        check(invalid, "cannot claim unknown holder alongside a named holder");

        invalid = false;
        try { ContentionReport.parse(header(true, false) + "F\tLong Wait\t"); }
        catch (IllegalArgumentException expected) { invalid = true; }
        check(invalid, "truncated finding rejected");
        System.out.println("PASS ContentionReportTests");
    }
}
