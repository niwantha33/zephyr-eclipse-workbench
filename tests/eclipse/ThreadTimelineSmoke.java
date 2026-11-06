// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import java.lang.reflect.Field;
import java.util.List;
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
        window.getActivePage().hideView(view);
        System.out.println("PASS THREAD TIMELINE INSTALLED UI: no-trace view disposed cleanly");
    }
}
