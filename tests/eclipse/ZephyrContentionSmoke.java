// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;

/** Exercises a real installed ViewPart with synthetic, already analyzed data. */
public final class ZephyrContentionSmoke {
    private ZephyrContentionSmoke() {}

    private static Object field(Object view, String name) throws ReflectiveOperationException {
        Field f = view.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(view);
    }

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException("Contention installed UI: " + reason);
        System.out.println("PASS CONTENTION INSTALLED UI: " + reason);
    }

    public static void run() throws Exception {
        var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        check(window != null && window.getActivePage() != null, "real Eclipse page available");
        var view = window.getActivePage().showView("io.github.niwantha33.zew.ui.contentionReport");
        check(view != null && view.getClass().getName().endsWith(".ZephyrContentionView"),
                "Zephyr Contention Report native ViewPart is registered");

        Label disclaimer = (Label) field(view, "disclaimer");
        Label status = (Label) field(view, "status");
        Label priorities = (Label) field(view, "priorities");
        Label incomplete = (Label) field(view, "incomplete");
        Text threshold = (Text) field(view, "threshold");
        Table table = (Table) field(view, "table");
        check(disclaimer.getText().equals(
                "Findings are inferred from trace events. A reported priority inversion means "
                + "the pattern was observed in the event stream; it does not prove that a deadline was missed."),
                "non-accusatory evidence disclaimer appears above findings");
        check(threshold.getText().equals("1000")
                && status.getText().contains("No trace loaded")
                && table.getItemCount() == 0
                && field(view, "selectedTrace") == null
                && field(view, "loadJob") == null,
                "1000us default, no automatic CTF decoding or target job");
        check(table.getColumnCount() == 6 && List.of(
                "Kind", "Object", "Waiter", "Holder", "Duration (us)", "Explanation")
                .equals(Arrays.stream(table.getColumns()).map(c -> c.getText()).toList()),
                "requested six review columns are shown");

        var bundle = org.eclipse.core.runtime.Platform.getBundle("io.github.niwantha33.zew.ui");
        Class<?> reportType = bundle.loadClass("io.github.niwantha33.zew.ui.ContentionReport");
        Class<?> snapshot = bundle.loadClass("io.github.niwantha33.zew.ui.ContentionReport$Snapshot");
        var parse = reportType.getMethod("parse", String.class);
        var render = view.getClass().getDeclaredMethod("render", snapshot, String.class);
        render.setAccessible(true);
        String example = "ZEW_CONTENTION\t1\t1\t1\t0\t6\t\n"
                + "F\tInversion\t" + b64("lock_A") + "\t" + b64("high") + "\t"
                + b64("low") + "\t1500000\t" + b64("medium")
                + "\t1\t5\t3\t0\t"
                + b64("Observed high blocked while medium ran and low held lock_A.") + "\n"
                + "F\tLong Wait\t" + b64("sem_1") + "\t" + b64("high")
                + "\t\t2100000\t\t1\t\t\t1\t"
                + b64("Observed semaphore wait; holder unknown.") + "\n";
        render.invoke(view, parse.invoke(null, example), null);
        check(table.getItemCount() == 2
                && table.getItem(0).getText(0).equals("Inversion")
                && table.getItem(0).getText(2).equals("high")
                && table.getItem(0).getText(3).equals("low")
                && table.getItem(0).getText(4).equals("1500.000")
                && table.getItem(0).getText(5).contains("medium"),
                "inversion row names high/low/medium and exact wait duration");
        check(table.getItem(1).getText(3).isEmpty()
                && table.getItem(1).getText(5).contains("holder unknown"),
                "unknown holder is empty, explicitly explained, never guessed");
        check(incomplete.isVisible(), "partial trace receives visible evidence warning");

        render.invoke(view, parse.invoke(null, "ZEW_CONTENTION\t1\t0\t0\t0\t4\t\n"), null);
        check(table.getItemCount() == 0 && priorities.getText().contains("Pattern A disabled"),
                "missing priority information disables Pattern A visibly");

        threshold.setText("500");
        check(threshold.getText().equals("500") && field(view, "loadJob") == null,
                "changing threshold without a loaded trace launches no analysis");
        threshold.setText("");
        check(status.getText().contains("valid nonnegative threshold")
                && table.getItemCount() == 0,
                "invalid numeric threshold clears findings rather than keeping stale results");

        window.getActivePage().hideView(view);
        System.out.println("PASS CONTENTION INSTALLED UI: view disposed cleanly");
    }
}
