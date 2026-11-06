// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.ui.PlatformUI;

/** Real installed Eclipse UI smoke, no target, no Python process, no file I/O. */
public final class TracepointPlotSmoke {
    private TracepointPlotSmoke() { }

    private static Object field(Object view, String name) throws Exception {
        Field f = view.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(view);
    }

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void check(boolean truth, String message) {
        if (!truth) throw new IllegalStateException("Tracepoint installed UI: " + message);
        System.out.println("PASS TRACEPOINT INSTALLED UI: " + message);
    }

    public static void run() throws Exception {
        var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        check(window != null && window.getActivePage() != null,
                "real Eclipse workbench is available");
        var view = window.getActivePage().showView("io.github.niwantha33.zew.ui.tracepointPlot");
        check(view != null && view.getClass().getName().endsWith("TracepointPlotView"),
                "Zephyr Tracepoint Plot ViewPart is registered");

        Table table = (Table) field(view, "table");
        Combo selector = (Combo) field(view, "selector");
        Label status = (Label) field(view, "status");
        Label configStatus = (Label) field(view, "configStatus");
        Label statistics = (Label) field(view, "statistics");
        Label banner = (Label) field(view, "incompleteBanner");
        check(status.getText().contains("No trace loaded") && table.getItemCount() == 0
                && selector.getItemCount() == 0 && field(view, "loadJob") == null
                && field(view, "selectedTrace") == null,
                "opening view does not load config or trace or schedule analysis");
        check(table.getColumnCount() == 2 && List.of("Timestamp (ns)", "Value").equals(
                    Arrays.stream(table.getColumns()).map(col -> col.getText()).toList()),
                "table fallback shows timestamp and numeric value columns");

        var bundle = org.eclipse.core.runtime.Platform.getBundle("io.github.niwantha33.zew.ui");
        Class<?> model = bundle.loadClass("io.github.niwantha33.zew.ui.TracepointReport");
        Class<?> snapshot = bundle.loadClass("io.github.niwantha33.zew.ui.TracepointReport$Snapshot");
        Method parse = model.getMethod("parse", String.class);
        Method render = view.getClass().getDeclaredMethod("render", snapshot, String.class);
        render.setAccessible(true);

        render.invoke(view, parse.invoke(null, "ZEW_TRACEPOINTS\t1\tinactive\t\t0\t0\t\n"), null);
        check(status.getText().contains("inactive") && selector.getItemCount() == 0,
                "missing configuration is a quiet inactive view state");

        render.invoke(view, parse.invoke(null,
                "ZEW_TRACEPOINTS\t1\tinvalid\t" + b64("line 3: bad type") + "\t0\t0\t\n"), null);
        check(status.getText().contains("line 3: bad type") && !selector.isEnabled()
                && table.getItemCount() == 0,
                "malformed properties show one line-numbered error without exception");

        String sample = "ZEW_TRACEPOINTS\t1\tactive\t\t1\t0\t\n"
                + "D\t" + b64("motor_rpm") + "\t" + b64("Motor RPM") + "\tinteger\t3\n"
                + "D\t" + b64("motor_temp") + "\t" + b64("Motor temperature") + "\tfloat\t2\n"
                + "D\t" + b64("not_emitted") + "\t" + b64("Absent sample") + "\tinteger\t0\n"
                + "S\t" + b64("motor_rpm") + "\t10\t10\n"
                + "S\t" + b64("motor_rpm") + "\t20\t20\n"
                + "S\t" + b64("motor_rpm") + "\t30\t30\n"
                + "S\t" + b64("motor_temp") + "\t15\t1.25\n"
                + "S\t" + b64("motor_temp") + "\t25\t2.75\n";
        render.invoke(view, parse.invoke(null, sample), null);
        check(selector.getItemCount() == 2 && selector.getText().contains("motor_rpm")
                && table.getItemCount() == 3, "selector includes found declarations only");
        check(configStatus.getText().contains("declared but not found in trace: not_emitted"),
                "missing declared tracepoint appears in view");
        check(statistics.getText().contains("Min: 10")
                && statistics.getText().contains("Max: 30")
                && statistics.getText().contains("Mean: 20"),
                "three integer samples produce min/max/mean");
        check(banner.isVisible(), "partial capture has an incomplete-trace banner");

        selector.select(1);
        selector.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
        check(table.getItemCount() == 2
                && table.getItem(0).getText(0).equals("15")
                && table.getItem(1).getText(1).equals("2.75")
                && statistics.getText().contains("Mean: 2"),
                "switching tracepoint updates ordered numeric samples and statistics");
        check(field(view, "loadJob") == null, "in-memory sample switching never reads CTF");
        window.getActivePage().hideView(view);
        System.out.println("PASS TRACEPOINT INSTALLED UI: view disposed cleanly");
    }
}
