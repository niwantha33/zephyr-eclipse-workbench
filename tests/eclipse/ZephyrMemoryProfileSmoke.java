// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.ui.PlatformUI;

/** Installed Eclipse ViewPart smoke: synthetic in-memory reports, never a target. */
public final class ZephyrMemoryProfileSmoke {
    private ZephyrMemoryProfileSmoke() { }

    private static Object field(Object owner, String key) throws ReflectiveOperationException {
        Field f = owner.getClass().getDeclaredField(key);
        f.setAccessible(true);
        return f.get(owner);
    }
    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Memory profile installed UI: " + message);
        System.out.println("PASS MEMORY PROFILE INSTALLED UI: " + message);
    }
    private static String header(int n, boolean incomplete, boolean sized,
                                 String count, String current, String peak, String peakTime,
                                 String message, String imbalance) {
        return "ZEW_MEMORY\t1\t" + n + "\t" + (incomplete ? 1 : 0)
                + "\t0\t1\t" + (sized ? 1 : 0) + "\t" + count + "\t"
                + current + "\t" + peak + "\t" + peakTime + "\t"
                + b64(message) + "\t" + b64(imbalance) + "\t\t0\t0\n";
    }

    public static void run() throws Exception {
        var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        check(window != null && window.getActivePage() != null, "native workbench available");
        var view = window.getActivePage().showView("io.github.niwantha33.zew.ui.memoryProfile");
        check(view != null && view.getClass().getName().endsWith(".ZephyrMemoryProfileView"),
                "registered native Zephyr Memory Profile ViewPart opens");
        Label status = (Label) field(view, "status");
        Label summary = (Label) field(view, "summary");
        Label imbalance = (Label) field(view, "imbalance");
        Label incomplete = (Label) field(view, "incomplete");
        Label diagnostics = (Label) field(view, "diagnostics");
        Table table = (Table) field(view, "table");
        check(status.getText().contains("No trace loaded")
                && table.getItemCount() == 0 && field(view, "loadJob") == null
                && field(view, "selectedTrace") == null && field(view, "displayed") == null,
                "passive startup schedules no analysis, file read or target polling");
        check(table.getColumnCount() == 4 && List.of(
                "Pool / Slab", "Alloc count", "Free count", "Peak bytes").equals(
                Arrays.stream(table.getColumns()).map(c -> c.getText()).toList()),
                "pool table contains four requested data columns");

        var bundle = org.eclipse.core.runtime.Platform.getBundle("io.github.niwantha33.zew.ui");
        Class<?> model = bundle.loadClass("io.github.niwantha33.zew.ui.MemoryTraceReport");
        Class<?> snapshot = bundle.loadClass("io.github.niwantha33.zew.ui.MemoryTraceReport$Snapshot");
        var parse = model.getMethod("parse", String.class);
        var render = view.getClass().getDeclaredMethod("render", snapshot, String.class);
        render.setAccessible(true);
        String noEvents = "No memory allocation events found. Enable CONFIG_TRACING_HEAP or the "
                + "equivalent memory tracing option in the Zephyr build to use this view.";
        render.invoke(view, parse.invoke(null,
                header(0, false, true, "0", "", "", "", noEvents, "")), null);
        check(status.getText().equals(noEvents) && table.getItemCount() == 0
                && summary.getText().isEmpty() && !imbalance.isVisible()
                && !incomplete.isVisible(), "empty trace shows EXACT required no-events message without chart");

        String balance = "Trace ends with 2 outstanding allocations (200 bytes). "
                + "This may indicate a leak or may simply mean the allocations are long-lived. "
                + "The trace alone cannot distinguish these.";
        String samples = header(8, false, true, "2", "200", "500", "5000", balance, balance)
                + "P\t" + b64("pool_A") + "\t5\t3\t2\t200\t500\n"
                + "S\t1000\t1\t100\nS\t2000\t2\t200\n"
                + "S\t3000\t3\t300\nS\t4000\t4\t400\n"
                + "S\t5000\t5\t500\nS\t6000\t4\t400\n"
                + "S\t7000\t3\t300\nS\t8000\t2\t200\n";
        render.invoke(view, parse.invoke(null, samples), null);
        check(summary.getText().contains("Observed current: 2 allocations (200 bytes)")
                && summary.getText().contains("Observed peak: 500 bytes at 5000 ns"),
                "synthetic five-alloc three-free summary shows correct current peak and time");
        check(table.getItemCount() == 1 && table.getItem(0).getText(0).equals("pool_A")
                && table.getItem(0).getText(1).equals("5")
                && table.getItem(0).getText(2).equals("3")
                && table.getItem(0).getText(3).equals("500"),
                "pool table shows five allocs, three frees and peak 500 bytes");
        check(imbalance.isVisible() && imbalance.getText().equals(balance)
                && !incomplete.isVisible(), "complete imbalance disclaimer exact, not called a leak");

        render.invoke(view, parse.invoke(null, header(8, true, true, "2", "200", "500",
                "5000", "Trace is incomplete; outstanding allocation imbalance cannot be assessed.", "")
                + "P\t" + b64("pool_A") + "\t5\t3\t2\t200\t500\n"), null);
        check(!imbalance.isVisible() && incomplete.isVisible()
                && incomplete.getText().equals(
                    "Trace is incomplete; outstanding allocation imbalance cannot be assessed."),
                "truncated trace removes imbalance line and shows incomplete state");

        render.invoke(view, parse.invoke(null, header(3, false, false,
                "1", "", "", "", "Byte totals unavailable", "")
                + "P\t" + b64("heap ID 123") + "\t2\t1\t1\t\t\n"
                + "S\t1\t1\t\n"), null);
        check(table.getItem(0).getText(3).equals("unavailable")
                && summary.getText().contains("Observed peak: unavailable")
                && diagnostics.isVisible() && diagnostics.getText().contains("No size was estimated"),
                "native CTF without size never displays invented byte totals");
        window.getActivePage().hideView(view);
        System.out.println("PASS MEMORY PROFILE INSTALLED UI: view disposed cleanly");
    }
}
