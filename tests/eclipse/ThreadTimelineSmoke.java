// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import java.lang.reflect.Field;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;

/**
 * Installed SWT manual-equivalent smoke: open an unpopulated native view.
 * Must not load files, spawn Python, access the probe, or start background jobs.
 */
public final class ThreadTimelineSmoke {
    private ThreadTimelineSmoke() {}

    private static Object field(Object view, String name) throws ReflectiveOperationException {
        Field f = view.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(view);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new IllegalStateException("Thread timeline installed UI: " + description);
        System.out.println("PASS THREAD TIMELINE INSTALLED UI: " + description);
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public static void run() throws Exception {
        var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        check(window != null && window.getActivePage() != null,
                "native Eclipse page is available");
        var view = window.getActivePage().showView(
                "io.github.niwantha33.zew.ui.threadTimeline");
        check(view != null && view.getClass().getName().endsWith(".ThreadTimelineView"),
                "registered native Zephyr Thread Timeline opens");

        Label status = (Label) field(view, "status");
        Label banner = (Label) field(view, "banner");
        Table table = (Table) field(view, "table");
        Text filter = (Text) field(view, "filter");
        check(status.getText().contains("No trace loaded")
                && !table.isDisposed() && table.getItemCount() == 0,
                "empty-state message is visible without any trace or exception");
        check(table.getColumnCount() == 6 && List.of(
                "Start (ns)", "End (ns)", "Duration (us)",
                "Thread Name", "Priority", "State").equals(
                        java.util.Arrays.stream(table.getColumns()).map(column -> column.getText()).toList()),
                "six requested interval columns are registered");
        check(!banner.isVisible() && field(view, "displayed") == null
                && field(view, "selectedTrace") == null && field(view, "loadJob") == null,
                "no incomplete banner, file read, decoder or analysis job at startup");
        filter.setText("worker");
        check(table.getItemCount() == 0 && status.getText().contains("No trace loaded"),
                "filtering an unloaded view is safe and does not trigger analysis");
        TabFolder tabs = (TabFolder) field(view, "tabs");
        Table cpuTable = (Table) field(view, "cpuTable");
        Label cpuDisclaimer = (Label) field(view, "cpuDisclaimer");
        Label cpuWarning = (Label) field(view, "cpuWarning");
        Label cpuStatus = (Label) field(view, "cpuStatus");
        Label cpuMetrics = (Label) field(view, "cpuMetrics");
        Text windowStart = (Text) field(view, "windowStart");
        Text windowEnd = (Text) field(view, "windowEnd");
        Button apply = (Button) field(view, "cpuApply");
        check(tabs.getItemCount() == 2 && tabs.getItem(1).getText().equals("Zephyr CPU Load"),
                "a second CPU Load tab exists alongside Thread Timeline");
        check(cpuTable.getColumnCount() == 3 && List.of(
                "Thread Name", "Running (us)", "Percentage").equals(
                java.util.Arrays.stream(cpuTable.getColumns()).map(column -> column.getText()).toList()),
                "CPU tab exposes three requested columns");
        check(cpuDisclaimer.getText().equals(
                    "Derived from context-switch events. Not a hardware measurement.")
                && cpuTable.getItemCount() == 0 && !apply.isEnabled()
                && !windowStart.isEnabled() && !windowEnd.isEnabled()
                && field(view, "loadJob") == null,
                "CPU tab displays disclaimer and runs no calculation or decoding before load");

        tabs.setSelection(1);
        check(tabs.getSelectionIndex() == 1 && field(view, "loadJob") == null,
                "selecting CPU tab remains passive and starts no decoder job");

        // Inject a synthetic *already normalized interval report* into the
        // installed SWT view. Never open a file or start the Python decoder.
        String encoded = "ZEW_THREAD_TIMELINE\t1\t0\t0\t4\t\t0\n"
                + "I\t0\t300000000\t" + b64("A") + "\t\tRunning\n"
                + "I\t300000000\t400000000\t\t\tUnknown\n"
                + "I\t400000000\t1000000000\t" + b64("idle") + "\t\tRunning\n";
        var pluginBundle = org.eclipse.core.runtime.Platform.getBundle("io.github.niwantha33.zew.ui");
        Class<?> model = pluginBundle.loadClass("io.github.niwantha33.zew.ui.ThreadTimelineReport");
        Object snapshot = model.getMethod("parse", String.class).invoke(null, encoded);
        Field displayed = view.getClass().getDeclaredField("displayed");
        displayed.setAccessible(true);
        displayed.set(view, snapshot);
        var prepare = view.getClass().getDeclaredMethod("prepareCpuLoad");
        prepare.setAccessible(true);
        prepare.invoke(view);
        check(windowStart.getText().equals("0") && windowEnd.getText().equals("1000000000")
                && apply.isEnabled(), "full observed interval range is selected by default");
        check(cpuTable.getItemCount() == 2 && cpuTable.getItem(0).getText(0).equals("idle")
                && cpuTable.getItem(0).getText(2).equals("60.00%")
                && cpuTable.getItem(1).getText(2).equals("30.00%"),
                "CPU tab shows descending scheduler-derived shares");
        check(cpuWarning.isVisible() && cpuWarning.getText().contains("10.00%")
                && cpuMetrics.getText().contains("Idle time: 60.00%"),
                "10% Unknown warning and separate idle share are visible");
        windowStart.setText("250000000");
        windowEnd.setText("450000000");
        apply.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
        check(cpuTable.getItemCount() == 2
                && cpuTable.getItem(0).getText(2).equals("25.00%")
                && cpuTable.getItem(1).getText(2).equals("25.00%")
                && cpuWarning.getText().contains("50.00%"),
                "manual time-window selection clips intervals and updates unknown warning");
        windowEnd.setText(windowStart.getText());
        apply.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
        check(cpuTable.getItemCount() == 0
                && cpuStatus.getText().contains("greater than Start"),
                "zero-length CPU window displays a clear validation error");
        // Version 2 carries actual event timestamps, which may extend beyond
        // paired interval edges. Default to those observed bounds, not guesses.
        String v2 = "ZEW_THREAD_TIMELINE\t2\t0\t0\t2\t\t0\t0\t1000000000\n"
                + "I\t200000000\t900000000\t" + b64("A") + "\t\tRunning\n";
        displayed.set(view, model.getMethod("parse", String.class).invoke(null, v2));
        prepare.invoke(view);
        check(windowStart.getText().equals("0") && windowEnd.getText().equals("1000000000")
                && cpuTable.getItemCount() == 1
                && cpuTable.getItem(0).getText(2).equals("70.00%"),
                "CPU default uses full observed trace range including unpaired edges");
        window.getActivePage().hideView(view);
        System.out.println("PASS THREAD TIMELINE INSTALLED UI: no-trace view disposed cleanly");
    }
}
