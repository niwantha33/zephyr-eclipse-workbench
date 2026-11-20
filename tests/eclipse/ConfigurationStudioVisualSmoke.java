// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import java.util.concurrent.TimeUnit;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

/** Installed Eclipse evidence: actual bundled UI, no fabricated device/target. */
public final class ConfigurationStudioVisualSmoke {
    private ConfigurationStudioVisualSmoke() {}

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("Configuration visual Studio: " + message);
    }

    private static Object field(Object object, String name) throws Exception {
        var f = object.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(object);
    }

    private static boolean loaded(Browser browser) {
        try {
            return Boolean.TRUE.equals(browser.evaluate(
                    "return !!document.getElementById('workspace')"
                    + " && !!document.getElementById('detail')"
                    + " && !!document.getElementById('theme')"
                    + " && typeof window.zewConfigurationAction === 'function';"));
        } catch (RuntimeException stillLoading) {
            return false;
        }
    }

    private static void settle(Browser browser) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        Display display = browser.getDisplay();
        while (!browser.isDisposed() && System.nanoTime() < until) {
            if (loaded(browser)) return;
            if (!display.readAndDispatch()) Thread.sleep(15);
        }
        throw new AssertionError("Offline Configuration Studio DOM never loaded");
    }

    public static void run() throws Exception {
        var bundle = org.eclipse.core.runtime.Platform.getBundle("io.github.niwantha33.zew.ui");
        check(bundle != null && bundle.getEntry("configuration-studio/index.html") != null,
                "packaged offline HTML resource");
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        var view = page.showView("io.github.niwantha33.zew.ui.configurationHome");
        view.getClass().getMethod("displayMissingProject").invoke(view);
        Browser browser = (Browser) field(view, "visualBrowser");
        if (browser == null || browser.isDisposed()) {
            check(field(view, "nativePage") != null, "SWT fallback present");
            System.out.println("ConfigurationVisualSmoke FALLBACK: browser runtime unavailable; screenshot not qualified");
            return;
        }
        settle(browser);
        check(Boolean.TRUE.equals(browser.evaluate(
                "return document.querySelectorAll('[data-section]').length === 4;")),
                "four focused navigation sections");
        check("DENIED".equals(browser.evaluate(
                "return window.zewConfigurationAction('execute','delete source');")),
                "unexpected command rejected");
        check("DENIED".equals(browser.evaluate(
                "return window.zewConfigurationAction('theme','javascript:bad');")),
                "invalid theme rejected");
        check("DENIED".equals(browser.evaluate(
                "return window.zewConfigurationAction('request','CONFIG_GPIO=y');")),
                "no-project source mutation always denied");
        check("DENIED".equals(browser.evaluate(
                "return window.zewConfigurationAction('focus-symbol','CONFIG_GPIO');")),
                "unknown project symbol deep-link always denied");
        check("DENIED".equals(browser.evaluate(
                "return window.zewConfigurationAction('inspect-kconfig','CONFIG_GPIO');")),
                "no-project dependency analysis cannot be invented");
        check("DENIED".equals(browser.evaluate(
                "return window.zewConfigurationAction('hardware-focus','/soc/adc@1234');")),
                "unknown Devicetree path cannot be selected");
        check("DENIED".equals(browser.evaluate(
                "return window.zewConfigurationAction('source-hardware','/soc/adc@1234');")),
                "unverified hardware source navigation denied");
        check(Boolean.TRUE.equals(browser.evaluate(
                "return document.getElementById('reviewDialog').hidden"
                    + " && !!document.getElementById('commitReview')"
                    + " && !!document.getElementById('cancelReview');")),
                "review dialog is present but no pending change may be applied");
        check(Boolean.TRUE.equals(browser.evaluate(
                "return typeof window.zewConfigurationInspector === 'function'"
                + " && document.getElementById('reviewDialog').hidden"
                + " && getComputedStyle(document.getElementById('reviewDialog')).display === 'none';")),
                "upstream evidence callback is wired and review dialog is hidden until requested");
        check(Boolean.TRUE.equals(browser.evaluate(
                "return document.getElementById('freshness').textContent === 'NO BUILD';")),
                "empty-state build evidence is never invented");
        check(browser.execute("document.querySelector('[data-section=\"software\"]').click();"),
                "software navigation dispatch");
        check(Boolean.TRUE.equals(browser.evaluate(
                "return document.getElementById('workspace').textContent.includes('Kconfig configuration');")),
                "graphical Kconfig section rendered");
        check(browser.execute("document.querySelector('[data-section=\"hardware\"]').click();"),
                "hardware navigation dispatch");
        check(Boolean.TRUE.equals(browser.evaluate(
                "return document.getElementById('workspace').textContent.includes('Peripheral setup');")),
                "graphical hardware section rendered");
        check(browser.execute("document.getElementById('theme').click();"),
                "theme command dispatch");
        check(Boolean.TRUE.equals(browser.evaluate(
                "return document.documentElement.classList.contains('light');")),
                "light theme applied without network resource");
        System.out.println("ConfigurationStudioVisualSmoke PASS: installed offline view and safe bridge");
    }
}
