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
        var guided = java.util.Arrays.stream(status.getParent().getChildren())
                .filter(child -> child instanceof org.eclipse.swt.widgets.Composite)
                .map(child -> (org.eclipse.swt.widgets.Composite) child)
                .filter(group -> java.util.Arrays.stream(group.getChildren())
                        .anyMatch(child -> child instanceof org.eclipse.swt.widgets.Button b
                                && b.getText().equals("1. Capture new CTF...")))
                .findFirst().orElseThrow(() -> new AssertionError("Missing guided CTF workflow"));
        var actions = java.util.Arrays.stream(guided.getChildren())
                .filter(child -> child instanceof org.eclipse.swt.widgets.Button)
                .map(child -> ((org.eclipse.swt.widgets.Button) child).getText()).toList();
        check(actions.contains("2. Open latest capture")
                && field(view, "loadJob") == null,
                "CPU Load offers capture/latest actions without starting target operations");
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
        Table stateTable = (Table) field(view, "stateTable");
        check(!stateTable.isVisible(), "no scheduler data never creates fake state rows");

        Class<?> stateClass = bundle.loadClass(
                "io.github.niwantha33.zew.ui.OfflineTraceAnalysisService$CpuStateInterval");
        Class<?> stateType = bundle.loadClass(
                "io.github.niwantha33.zew.ui.OfflineTraceAnalysisService$ObservedState");
        @SuppressWarnings({"rawtypes", "unchecked"})
        Object scheduled = stateClass.getConstructor(String.class, stateType,
                long.class, long.class, boolean.class).newInstance("worker",
                Enum.valueOf((Class) stateType, "SCHEDULED"), 0L, 10000L, true);
        Object validThread = row.getConstructor(String.class, long.class, BigDecimal.class)
                .newInstance("worker", 9000L, new BigDecimal("90"));
        Object complete = result.getConstructor(Path.class, Path.class, String.class, boolean.class,
                String.class, Long.class, Long.class, Long.class, Long.class, int.class,
                String.class, BigDecimal.class, Long.class, List.class, Long.class, BigDecimal.class,
                List.class).newInstance(Path.of("ctf"), Path.of("build"), "ok", false, "",
                        0L, 10000L, 0L, 10000L, 2, "partial", null, 0L,
                        List.of(validThread), 1000L, new BigDecimal("10"), List.of(scheduled));
        present.invoke(view, complete, null);
        check(table.isVisible() && table.getItemCount() == 2
                && stateTable.isVisible() && stateTable.getItemCount() == 1
                && stateTable.getItem(0).getText(1).equals("Scheduled (ISR-inclusive)"),
                "complete CPU view distinguishes exclusive ISR and inclusive scheduler intervals");
        page.hideView(view);
        System.out.println("PASS ZephyrCpuLoadSmoke");
    }
}
