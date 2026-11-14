// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import org.eclipse.equinox.app.*;
import org.eclipse.core.runtime.Platform;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.application.WorkbenchAdvisor;
import org.eclipse.ui.plugin.AbstractUIPlugin;

/** Optional full-workbench harness for native importer/exporter APIs that require an active workbench. */
public final class WorkbenchSmoke implements IApplication {
    private volatile Throwable failure;
    @Override public Object start(IApplicationContext context) throws Exception {
        var ui = Platform.getBundle("io.github.niwantha33.zew.ui"); ui.start();
        var plugin = (AbstractUIPlugin) ui.loadClass("io.github.niwantha33.zew.ui.Activator")
                .getMethod("getDefault").invoke(null);
        plugin.getPreferenceStore().setValue("setup.welcomeShown", true);
        plugin.getPreferenceStore().setValue("updates.autoCheck", false);
        System.setProperty("zew.nativeFlow", "true");
        var display = PlatformUI.createDisplay();
        try {
            PlatformUI.createAndRunWorkbench(display, new WorkbenchAdvisor() {
                @Override public String getInitialWindowPerspectiveId() { return "org.eclipse.ui.resourcePerspective"; }
                @Override public void postStartup() {
                    new Thread(() -> {
                        try {
                            // SWT catches exceptions on the UI event loop. Preserve them
                            // on the worker thread so native smoke failures fail CI.
                            var uiFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                            display.syncExec(() -> {
                                try { ZephyrProfileToolbarSmoke.run(); TraceConsoleSmoke.run(); TraceCompassIntegrationSmoke.run(); SocRegistersSmoke.run(); YamlStructureSmoke.run(); DtsGraphSmoke.run(); UmlClassSmoke.run(); ThreadTimelineSmoke.run(); ZephyrCpuLoadSmoke.run(); TracepointPlotSmoke.run(); ZephyrContentionSmoke.run(); ZephyrMemoryProfileSmoke.run(); ZephyrBeginnerPerspectiveSmoke.run(); }
                                catch (Throwable ex) { uiFailure.set(ex); }
                            });
                            if (uiFailure.get() != null)
                                throw new IllegalStateException("Installed native SWT smoke failed", uiFailure.get());
                            new EditorSmoke().start(context);
                        }
                        catch (Throwable error) { failure = error; error.printStackTrace(); }
                        finally { display.asyncExec(() -> PlatformUI.getWorkbench().close()); }
                    }, "Standard Eclipse project flow checks").start();
                }
            });
        } finally { display.dispose(); }
        if (failure != null) throw new IllegalStateException("Native Eclipse workflow checks failed", failure);
        return IApplication.EXIT_OK;
    }
    @Override public void stop() { }
}
