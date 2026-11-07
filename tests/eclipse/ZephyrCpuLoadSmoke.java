// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.ui.PlatformUI;

/** Passive Show View and fixed CPU table rendering in installed Eclipse. */
public final class ZephyrCpuLoadSmoke {
    private ZephyrCpuLoadSmoke() {}
    private static Object field(Object view, String name) throws Exception {
        Field f = view.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(view);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("CPU Load ViewPart: " + message);
    }
    public static void run() throws Exception {
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        var view = page.showView("io.github.niwantha33.zew.ui.cpuLoad");
        Label disclaimer = (Label) field(view, "disclaimer");
        Label banner = (Label) field(view, "banner");
        Label status = (Label) field(view, "status");
        Table table = (Table) field(view, "table");
        check(disclaimer.getText().equals("Derived from context-switch events. Not a hardware measurement."),
                "exact disclaimer is always visible");
        check(field(view, "service") == null && field(view, "selectedTrace") == null
                && field(view, "loadJob") == null && !banner.isVisible() && !table.isVisible()
                && status.getText().contains("No trace loaded"),
                "open without CTF performs no analysis and shows no error");
        var bundle = org.eclipse.core.runtime.Platform.getBundle("io.github.niwantha33.zew.ui");
        Class<?> result = bundle.loadClass("io.github.niwantha33.zew.ui.OfflineTraceAnalysisService$CpuLoadResult");
        Class<?> row = bundle.loadClass("io.github.niwantha33.zew.ui.OfflineTraceAnalysisService$CpuThread");
        Class<?> failure = bundle.loadClass("io.github.niwantha33.zew.ui.OfflineTraceAnalysisService$AnalysisFailure");
        Object thread = row.getConstructor(String.class, long.class, BigDecimal.class)
                .newInstance("worker", 2000L, new BigDecimal("20"));
        Object cpu = result.getConstructor(Path.class, Path.class, String.class, boolean.class,
                String.class, Long.class, Long.class, Long.class, Long.class, int.class,
                String.class, BigDecimal.class, Long.class, List.class, Long.class, BigDecimal.class)
                .newInstance(Path.of("ctf"), Path.of("build"), "incomplete", true, "truncated",
                        0L, 10000L, 0L, 10000L, 2, "partial", null, 8000L,
                        List.of(thread), 1000L, new BigDecimal("10"));
        var present = view.getClass().getDeclaredMethod("present", result, failure);
        present.setAccessible(true);
        present.invoke(view, cpu, null);
        check(banner.isVisible() && !table.isVisible() && table.getItemCount() == 0
                && status.getText().contains("CPU percentages are unavailable"),
                "incomplete saved trace suppresses derived CPU percentages");
        Object noScheduler = result.getConstructor(Path.class, Path.class, String.class, boolean.class,
                String.class, Long.class, Long.class, Long.class, Long.class, int.class,
                String.class, BigDecimal.class, Long.class, List.class, Long.class, BigDecimal.class)
                .newInstance(Path.of("ctf"), Path.of("build"), "no_scheduler", false, "",
                        0L, 10000L, 0L, 10000L, 0, "partial", null, null,
                        List.of(), null, null);
        present.invoke(view, noScheduler, null);
        check(!table.isVisible() && status.getText().contains("No scheduler events found"),
                "no scheduler events hide the table and show explicit reason");
        page.hideView(view);
        System.out.println("PASS ZephyrCpuLoadSmoke");
    }
}
