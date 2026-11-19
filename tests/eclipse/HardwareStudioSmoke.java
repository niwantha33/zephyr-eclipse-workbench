// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;

/** Installed Eclipse UI smoke; screenshots show TEST FIXTURES, never physical hardware. */
public final class HardwareStudioSmoke {
    private HardwareStudioSmoke() {}

    private static Object field(Object value, String name) throws Exception {
        var f = value.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(value);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("Hardware Studio: " + message);
    }

    private static boolean loaded(Browser browser) {
        try {
            return Boolean.TRUE.equals(browser.evaluate(
                    "return !!document.getElementById('navigation')"
                            + " && !!document.getElementById('detail')"
                            + " && !!document.getElementById('theme');"));
        } catch (RuntimeException pending) {
            return false;
        }
    }

    private static void settle(Browser browser) throws Exception {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(7);
        Display display = browser.getDisplay();
        while (System.nanoTime() < until && !browser.isDisposed()) {
            if (loaded(browser)) return;
            if (!display.readAndDispatch()) Thread.sleep(15);
        }
        throw new AssertionError("Installed Hardware Studio browser did not render its offline DOM");
    }

    private static Object eval(Browser browser, String expression) {
        return browser.evaluate("return " + expression + ";");
    }

    private static void capture(Shell shell, Path destination) throws Exception {
        shell.layout(true, true);
        shell.redraw();
        shell.update();
        for (int i = 0; i < 100 && shell.getDisplay().readAndDispatch(); i++) {
            shell.update();
        }
        var bounds = shell.getBounds().intersection(
                shell.getDisplay().getPrimaryMonitor().getBounds());
        check(bounds.width >= 900 && bounds.height >= 550, "screenshot was clipped");
        Files.createDirectories(destination.getParent());
        Image shot = new Image(shell.getDisplay(), bounds.width, bounds.height);
        try {
            GC gc = new GC(shell.getDisplay());
            try { gc.copyArea(shot, bounds.x, bounds.y); }
            finally { gc.dispose(); }
            ImageLoader writer = new ImageLoader();
            writer.data = new ImageData[] {shot.getImageData()};
            writer.save(destination.toString(), SWT.IMAGE_PNG);
        } finally { shot.dispose(); }
        check(Files.size(destination) > 4096, "screenshot is unexpectedly empty");
    }

    private static void screenshotMatrix(Browser browser) throws Exception {
        String target = System.getenv("ZEW_SCREENSHOT_DIR");
        if (target == null || target.isBlank()) return;
        Shell shell = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
        var screen = shell.getDisplay().getPrimaryMonitor().getBounds();
        if (screen.width < 1920 || screen.height < 1080) {
            System.out.println("HardwareStudioScreenshot NOT QUALIFIED: display below 1920x1080");
            return;
        }
        var previous = shell.getBounds();
        try {
            for (int[] resolution : new int[][] {{1366, 768}, {1920, 1080}}) {
                shell.setBounds(screen.x, screen.y, resolution[0], resolution[1]);
                shell.setActive();
                shell.layout(true, true);
                for (String theme : new String[] {"dark", "light"}) {
                    String js = "document.documentElement.classList.toggle('light', '"
                            + theme + "' === 'light');";
                    check(browser.execute(js), "failed to render selected screenshot theme");
                    String name = "hardware-studio-fixture-" + resolution[0] + "x"
                            + resolution[1] + "-" + theme + ".png";
                    capture(shell, Path.of(target).resolve(name));
                }
            }
        } finally {
            shell.setBounds(previous);
            shell.layout(true, true);
        }
    }

    private static Object fixtureSnapshot(org.osgi.framework.Bundle bundle) throws Exception {
        Class<?> node = bundle.loadClass("io.github.niwantha33.zew.ui.DtsGraphModel$Node");
        Class<?> edge = bundle.loadClass("io.github.niwantha33.zew.ui.DtsGraphModel$Edge");
        Class<?> snapshot = bundle.loadClass("io.github.niwantha33.zew.ui.DtsGraphModel$Snapshot");
        var nodeCtor = node.getConstructor(String.class, String.class, String.class,
                String.class, String.class, int.class);
        var edgeCtor = edge.getConstructor(String.class, String.class,
                String.class, String.class, String.class);
        var nodes = List.of(
                nodeCtor.newInstance("/", "/", "okay", "", "", 1),
                nodeCtor.newInstance("/soc", "soc", "okay", "", "", 2),
                nodeCtor.newInstance("/soc/gpio@4000", "gpio@4000", "okay",
                        "fixture,gpio", "gpio0", 3),
                nodeCtor.newInstance("/soc/uart@5000", "uart@5000", "disabled",
                        "fixture,uart", "", 4),
                nodeCtor.newInstance("/leds", "leds", "okay", "gpio-leds", "", 5),
                nodeCtor.newInstance("/leds/led0", "led0", "okay", "", "led0", 6));
        var edges = List.of(edgeCtor.newInstance("/leds/led0", "/soc/gpio@4000",
                "gpio", "gpios", "[0] pin=22"));
        return snapshot.getConstructor(String.class, String.class, String.class,
                Path.class, List.class, List.class)
                .newInstance("built", "CI FIXTURE ONLY — NO REAL BOARD OR PROBE",
                        "f".repeat(64), Path.of("/unavailable/ci-fixture/zephyr.dts"),
                        nodes, edges);
    }

    public static void run() throws Exception {
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        var bundle = Platform.getBundle("io.github.niwantha33.zew.ui");
        check(bundle != null && bundle.getEntry("hardware-studio/index.html") != null,
                "offline HTML exists in installed plugin");
        String html;
        try (var input = bundle.getEntry("hardware-studio/index.html").openStream()) {
            html = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        check(html.contains("__HARDWARE_STUDIO_DATA__")
                        && html.contains("Device explorer")
                        && !html.contains("https://"),
                "offline UI and only locally bundled content");
        var view = page.showView("io.github.niwantha33.zew.ui.hardwareStudio");
        check("HardwareStudioView".equals(view.getClass().getSimpleName()),
                "new Eclipse ViewPart registered");
        var browser = field(view, "browser");
        var fallback = field(view, "fallback");
        check((browser instanceof Browser web && !web.isDisposed())
                        || (fallback instanceof Label label && !label.isDisposed()
                                && label.getText().contains("browser is unavailable")),
                "native browser or accessible browser-missing fallback");
        var model = bundle.loadClass("io.github.niwantha33.zew.ui.DtsGraphModel");
        var snapshotType = bundle.loadClass("io.github.niwantha33.zew.ui.DtsGraphModel$Snapshot");
        var empty = model.getMethod("unavailable", String.class)
                .invoke(null, "CI fixture — no built hardware");
        view.getClass().getMethod("displayObservation", snapshotType, String.class)
                .invoke(view, empty, "");
        check(field(view, "snapshot") == empty, "unknown evidence is never invented");

        if (browser instanceof Browser web) {
            settle(web);
            var fixture = fixtureSnapshot(bundle);
            view.getClass().getMethod("displayObservation", snapshotType, String.class)
                    .invoke(view, fixture, "/soc");
            settle(web);
            check(String.valueOf(eval(web, "document.getElementById('graphCount').textContent"))
                            .contains("observed"),
                    "real installed renderer displays the explicit test-only node count");
            check(Boolean.TRUE.equals(eval(web, "document.getElementById('openSource').disabled")),
                    "no source action is offered for an unverified CI fixture");
            check(Boolean.TRUE.equals(eval(web, "document.getElementById('openGraph').disabled")) == false,
                    "classic graph fallback is accessible for a selected test node");
            check(web.execute("document.getElementById('search').value='uart';"
                            + "document.getElementById('search').dispatchEvent(new Event('input'));"),
                    "search is executable");
            check(String.valueOf(eval(web, "document.getElementById('navCount').textContent"))
                            .startsWith("1 matching"),
                    "search filters exact observed nodes");
            check(web.execute("document.getElementById('search').value='';"
                            + "document.getElementById('search').dispatchEvent(new Event('input'));"),
                    "restore navigator");
            screenshotMatrix(web);
        } else {
            System.out.println("HardwareStudioBrowser NOT QUALIFIED: SWT Browser runtime missing");
        }
        page.hideView(view);
        if (browser instanceof Browser web)
            check(web.isDisposed(), "browser releases native resources on close");
        System.out.println("HardwareStudioSmoke PASS");
    }
}
