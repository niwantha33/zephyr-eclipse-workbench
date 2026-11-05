// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.application.WorkbenchAdvisor;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.plugin.AbstractUIPlugin;

/** Installed native-view test, deliberately independent of debugger/probe services. */
public final class BuildFailureInvestigationSmoke implements IApplication {
    private volatile Throwable failure;
    private static int checks;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
    private static void runChecks() throws Exception {
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        var view = page.showView("io.github.niwantha33.zew.ui.buildFailureInvestigation");
        var rebuild = (Button) field(view, "rebuild");
        var open = (Button) field(view, "open");
        var report = (Text) field(view, "reportText");
        check(!rebuild.getEnabled(), "Initial rebuild must be disabled without project evidence");
        check(!open.getEnabled(), "Initial complete-log action must be disabled");
        check(report.getText().contains("failed managed build"), "Installed view explains its empty state");
        var project = ResourcesPlugin.getWorkspace().getRoot().getProject("failure-smoke");
        project.create(null);
        project.open(null);
        ResourcesPlugin.getWorkspace().save(true, null);
        Path application = project.getLocation().toFile().toPath();
        Path build = application.resolve(".zew-build/debug");
        Files.createDirectories(build);
        Path log = application.resolve("saved-failure.log");
        String diagnostic = "src/main.c:7:3: error: missing_symbol undeclared";
        Files.writeString(log, diagnostic + "\n");
        view.getClass().getMethod("displayFailure", IProject.class, Path.class, Path.class, String.class)
            .invoke(view, project, build, log, diagnostic);
        check(report.getText().contains(diagnostic), "Actual diagnostic is shown in installed report control");
        check(report.getText().contains(log.toString()), "Recorded log path is shown");
        check(rebuild.getEnabled(), "Accessible project enables explicit detailed rebuild");
        check(open.getEnabled(), "Existing saved log enables complete-log navigation");
        var commands = PlatformUI.getWorkbench().getService(ICommandService.class);
        check(commands.getCommand("io.github.niwantha33.zew.investigateBuildFailure").isDefined(), "Investigation command registered");
        boolean handlerRegistered = java.util.Arrays.stream(Platform.getExtensionRegistry()
            .getConfigurationElementsFor("org.eclipse.ui.handlers")).anyMatch(element ->
                "io.github.niwantha33.zew.investigateBuildFailure".equals(element.getAttribute("commandId"))
                && "io.github.niwantha33.zew.ui.BuildFailureInvestigationHandler".equals(element.getAttribute("class")));
        check(handlerRegistered, "Investigation command points to the installed failure-investigation handler");
        view.getClass().getMethod("displayProject", IProject.class).invoke(view, new Object[] { null });
        check(!rebuild.getEnabled(), "Clearing project disables rebuild");
        check(!open.getEnabled(), "Clearing project disables complete-log action");
        check(report.getText().isEmpty(), "Clearing project discards previous evidence");
        System.out.println("BuildFailureInvestigationSmoke PASS: " + checks + " checks");
    }
    @Override public Object start(IApplicationContext context) throws Exception {
        var ui = Platform.getBundle("io.github.niwantha33.zew.ui");
        ui.start();
        System.out.println("Installed Workbench UI version: " + ui.getVersion());
        var plugin = (AbstractUIPlugin) ui.loadClass("io.github.niwantha33.zew.ui.Activator").getMethod("getDefault").invoke(null);
        plugin.getPreferenceStore().setValue("setup.welcomeShown", true);
        plugin.getPreferenceStore().setValue("updates.autoCheck", false);
        var display = PlatformUI.createDisplay();
        try {
            PlatformUI.createAndRunWorkbench(display, new WorkbenchAdvisor() {
                @Override public String getInitialWindowPerspectiveId() { return "org.eclipse.ui.resourcePerspective"; }
                @Override public void postStartup() {
                    // Let the workbench finish its startup lifecycle before closing it.
                    display.timerExec(1000, () -> {
                        try { runChecks(); }
                        catch (Throwable error) { failure = error; error.printStackTrace(); }
                        finally {
                            try { ResourcesPlugin.getWorkspace().save(true, null); }
                            catch (Throwable error) { failure = error; error.printStackTrace(); }
                            PlatformUI.getWorkbench().close();
                        }
                    });
                }
            });
        } finally { display.dispose(); }
        if (failure != null) throw new IllegalStateException("Installed build-failure view smoke failed", failure);
        return IApplication.EXIT_OK;
    }
    @Override public void stop() { }
}
