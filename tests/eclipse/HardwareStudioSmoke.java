// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import java.nio.file.Path;
import java.util.List;
import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.PlatformUI;

/** Installed Eclipse smoke. Browser runtime may be absent; fallback is mandatory. */
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
    public static void run() throws Exception {
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        var bundle = Platform.getBundle("io.github.niwantha33.zew.ui");
        check(bundle.getEntry("hardware-studio/index.html") != null,
                "offline HTML must exist in installed bundle");
        String html;
        try (var input = bundle.getEntry("hardware-studio/index.html").openStream()) {
            html = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        check(html.contains("__HARDWARE_STUDIO_DATA__")
                && html.contains("Device explorer") && !html.contains("https://"),
                "installed UI includes only locally bundled browser content");
        var view = page.showView("io.github.niwantha33.zew.ui.hardwareStudio");
        check(view.getClass().getSimpleName().equals("HardwareStudioView"),
                "new integrated Eclipse ViewPart is registered");
        var browser = field(view, "browser");
        var fallback = field(view, "fallback");
        check((browser instanceof Browser web && !web.isDisposed())
                || (fallback instanceof Label label && !label.isDisposed()
                        && label.getText().contains("browser is unavailable")),
                "embedded browser or an accessible native browser-missing state");
        var model = bundle.loadClass("io.github.niwantha33.zew.ui.DtsGraphModel");
        var type = bundle.loadClass("io.github.niwantha33.zew.ui.DtsGraphModel$Snapshot");
        var nodeType = bundle.loadClass("io.github.niwantha33.zew.ui.DtsGraphModel$Node");
        var snapshot = model.getMethod("unavailable", String.class)
                .invoke(null, "synthetic empty build");
        view.getClass().getMethod("displayObservation", type, String.class)
                .invoke(view, snapshot, "");
        check(field(view, "snapshot") == snapshot,
                "synthetic evidence remains explicitly unavailable, no probe or parser started");
        page.hideView(view);
        if (browser instanceof Browser web)
            check(web.isDisposed(), "webview disposes with Eclipse part");
        System.out.println("HardwareStudioSmoke PASS");
    }
}
