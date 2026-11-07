// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;

/** Installed UI smoke: explicit saved-CTF load only and typed contention rows. */
public final class ZephyrContentionSmoke {
    private ZephyrContentionSmoke() {}
    private static Object field(Object view, String name) throws Exception {
        Field f = view.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(view);
    }
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError("Contention view: " + name);
    }
    public static void run() throws Exception {
        var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        var view = window.getActivePage().showView("io.github.niwantha33.zew.ui.contentionReport");
        Label disclaimer = (Label) field(view, "disclaimer");
        Label status = (Label) field(view, "status");
        Label priority = (Label) field(view, "priorities");
        Label incomplete = (Label) field(view, "incomplete");
        Text threshold = (Text) field(view, "threshold");
        Table table = (Table) field(view, "table");
        check(disclaimer.getText().equals(
                "Findings are inferred from trace events. A reported priority inversion means "
                + "the pattern was observed; it does not prove a deadline was missed."),
                "permanent exact disclaimer");
        check(threshold.getText().equals("1000") && status.getText().contains("No trace loaded")
                && field(view, "selectedTrace") == null && field(view, "loadJob") == null
                && table.getItemCount() == 0, "no work or target access on Show View");
        var bundle = org.eclipse.core.runtime.Platform.getBundle("io.github.niwantha33.zew.ui");
        Class<?> result = bundle.loadClass("io.github.niwantha33.zew.ui.OfflineTraceAnalysisService$ContentionResult");
        Class<?> finding = bundle.loadClass("io.github.niwantha33.zew.ui.OfflineTraceAnalysisService$ContentionFinding");
        Class<?> failure = bundle.loadClass("io.github.niwantha33.zew.ui.OfflineTraceAnalysisService$AnalysisFailure");
        var makeFinding = finding.getConstructor(String.class, String.class, String.class,
                String.class, boolean.class, long.class, String.class);
        Object longWait = makeFinding.newInstance("Long Wait", "sem", "high", null, true,
                1500000L, "Unknown holder");
        var makeReport = result.getConstructor(Path.class, Path.class, boolean.class,
                String.class, long.class, boolean.class, int.class, List.class);
        Object partial = makeReport.newInstance(Path.of("saved"), Path.of("build"), true,
                "truncated", 1000L, false, 2, List.of(longWait));
        var render = view.getClass().getDeclaredMethod("render", result, failure);
        render.setAccessible(true);
        render.invoke(view, partial, null);
        check(incomplete.isVisible() && table.isVisible()
                && table.getItemCount() == 1
                && table.getItem(0).getText(0).equals("Long wait")
                && table.getItem(0).getText(3).equals("unknown")
                && priority.getText().contains("disabled"),
                "unknown owner and long wait remain visible without priorities");
        Object empty = makeReport.newInstance(Path.of("saved"), Path.of("build"), false,
                "", 1000L, true, 0, List.of());
        render.invoke(view, empty, null);
        check(!table.isVisible() && status.getText().contains("No contention patterns detected"),
                "empty findings hide table with explicit message");
        threshold.setText("500");
        check(field(view, "loadJob") == null, "editing a threshold without loaded trace is passive");
        window.getActivePage().hideView(view);
        System.out.println("PASS ZephyrContentionSmoke");
    }
}
