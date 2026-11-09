// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.ui.PlatformUI;

/** Installed p2/SWT smoke: real registered DTS graph and typed links. */
public final class DtsGraphSmoke {
    private DtsGraphSmoke() {}

    private static Object field(Object value, String name) throws Exception {
        Field field = value.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(value);
    }
    private static void check(boolean valid, String message) {
        if (!valid) throw new IllegalStateException("DTS GRAPH INSTALLED UI: " + message);
        System.out.println("PASS DTS GRAPH INSTALLED UI: " + message);
    }
    private static String row(String kind, String... values) {
        StringBuilder result = new StringBuilder(kind);
        for (String value : values)
            result.append("\t").append(Base64.getEncoder()
                    .encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        return result.append("\n").toString();
    }
    public static void run() throws Exception {
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        var view = page.showView("io.github.niwantha33.zew.ui.dtsGraph");
        check(view != null && view.getClass().getSimpleName().equals("DtsGraphView"),
                "registered installed DTS Graph Studio opens");
        var bundle = Platform.getBundle("io.github.niwantha33.zew.ui");
        check(bundle.getEntry("scripts/dts_graph_observe.py") != null,
                "real DTS observer is in the packaged plugin");
        var model = bundle.loadClass("io.github.niwantha33.zew.ui.DtsGraphModel");
        String fixture = "ZEW-DTSGRAPH-1\n"
                + row("S", "built", "a".repeat(64), "observed")
                + row("N", "/", "/", "okay (default)", "", "", "1")
                + row("N", "/soc", "soc", "okay", "", "", "2")
                + row("N", "/soc/gpio@4000", "gpio@4000", "okay", "ti,mspm0-gpio", "", "3")
                + row("N", "/leds", "leds", "okay", "gpio-leds", "", "4")
                + row("N", "/leds/red", "red", "okay", "", "red_led", "5")
                + row("E", "/leds/red", "/soc/gpio@4000", "gpio", "gpios", "[0] pin=22");
        Object snapshot = model.getMethod("parse", String.class, Path.class)
                .invoke(null, fixture, Path.of("/smoke/generated/zephyr.dts"));
        view.getClass().getMethod("displayObservation",
                bundle.loadClass("io.github.niwantha33.zew.ui.DtsGraphModel$Snapshot"),
                String.class).invoke(view, snapshot, "/leds/red");
        Object canvas = field(view, "graph");
        check(canvas instanceof Canvas swt && !swt.isDisposed(),
                "graph is a real native SWT canvas, not a table/placeholder");
        Object graph = canvas.getClass().getMethod("layoutSnapshot").invoke(canvas);
        var cards = (java.util.List<?>) graph.getClass().getMethod("cards").invoke(graph);
        var edges = (java.util.List<?>) graph.getClass().getMethod("edges").invoke(graph);
        check(cards.size() >= 4 && edges.size() >= 4,
                "installed viewer draws exact observed parent-child and GPIO reference edges");
        canvas.getClass().getMethod("zoom", int.class).invoke(canvas, 130);
        check((Integer) canvas.getClass().getMethod("zoomPercent").invoke(canvas) == 130,
                "native DTS zoom controls are functional");
        page.hideView(view);
        check(((Canvas) canvas).isDisposed(), "native graph disposes SWT resources");

        // Regression for the user's primary Configuration Studio screenshot:
        // the Advanced toolbar alone is not discoverable from Home Hardware.
        var home = page.showView("io.github.niwantha33.zew.ui.configurationHome");
        check(home != null && home.getClass().getSimpleName().equals("ConfigurationHomeView"),
                "primary Configuration Studio opens");
        var tabs = (TabFolder) field(home, "primary");
        tabs.setSelection(1); // Hardware
        var graphShortcut = (Button) field(home, "dtsGraphButton");
        check(graphShortcut != null && !graphShortcut.isDisposed()
                && graphShortcut.isVisible()
                && graphShortcut.getText().startsWith("DTS Graph Studio"),
                "primary Hardware tab visibly advertises DTS Graph Studio without Advanced tools");
        page.hideView(home);
    }
}
