// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.swt.widgets.Label;
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
    private static void waitUntil(java.util.function.BooleanSupplier ready, String message) throws Exception {
        var display = PlatformUI.getWorkbench().getDisplay();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) {
            if (!display.readAndDispatch()) Thread.sleep(10);
        }
        check(ready.getAsBoolean(), message);
    }
    private static void runChecks() throws Exception {
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        var view = page.showView("io.github.niwantha33.zew.ui.buildFailureInvestigation");
        var rebuild = (Button) field(view, "rebuild");
        var open = (Button) field(view, "open");
        var report = (Text) field(view, "reportText");
        var context = (Label) field(view, "context");
        var ui = Platform.getBundle("io.github.niwantha33.zew.ui");
        var plugin = (AbstractUIPlugin) ui.loadClass("io.github.niwantha33.zew.ui.Activator").getMethod("getDefault").invoke(null);
        var preferences = plugin.getPreferenceStore();
        preferences.setToDefault("buildFailure.lastProject");
        preferences.setToDefault("buildFailure.lastBuild");
        check(!rebuild.getEnabled(), "Initial rebuild must be disabled without project evidence");
        check(!open.getEnabled(), "Initial complete-log action must be disabled");
        check(report.getText().contains("failed managed build"), "Installed view explains its empty state");
        view.getClass().getMethod("displayLastFailure").invoke(view);
        check(context.getText().contains("No failed build has been recorded"), "Missing recorded preferences explain the empty state");
        check(!open.getEnabled() && !rebuild.getEnabled(), "No history does not guess a project or start a build");
        var project = ResourcesPlugin.getWorkspace().getRoot().getProject("failure-smoke");
        project.create(null);
        project.open(null);
        ResourcesPlugin.getWorkspace().save(true, null);
        Path application = project.getLocation().toFile().toPath();
        // Minimal ready-environment metadata selects a real managed profile path;
        // none of the placeholder executables is run by this read-only UI test.
        Path setup = application.resolve("test-setup");
        Files.createDirectories(setup);
        Files.writeString(setup.resolve(".zew-ready"), "test-only");
        var environment = new java.util.Properties();
        for (String key : new String[] { "python", "workspace", "tools.sdk", "tools.cmake", "tools.ninja", "tools.git" })
            environment.setProperty(key, setup.resolve(key).toString());
        environment.setProperty("application", application.toString());
        environment.setProperty("board", "qemu_x86/atom");
        try (var writer = Files.newBufferedWriter(setup.resolve("environment.properties"))) { environment.store(writer, "Smoke fixture"); }
        project.setPersistentProperty(new QualifiedName("io.github.niwantha33.zew.ui", "setupRoot"), setup.toString());
        var profileKey = new QualifiedName("io.github.niwantha33.zew.ui", "buildConfiguration");
        project.setPersistentProperty(profileKey, "debug");
        String boardKey = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest("qemu_x86/atom".getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 16);
        Path build = application.resolve(".zew-build/" + boardKey + "-debug");
        Files.createDirectories(build);
        var logs = ui.loadClass("io.github.niwantha33.zew.core.BuildFailureLogs");
        Path log = (Path) logs.getMethod("create", Path.class, Path.class).invoke(null, application, build);
        String diagnostic = "src/main.c:7:3: error: missing_symbol undeclared";
        Files.writeString(log, diagnostic + "\n");
        logs.getMethod("failed", Path.class, String.class).invoke(null, log, diagnostic);
        view.getClass().getMethod("displayFailure", IProject.class, Path.class, Path.class, String.class)
            .invoke(view, project, build, log, diagnostic);
        check(report.getText().contains(diagnostic), "Actual diagnostic is shown in installed report control");
        check(report.getText().contains(log.toString()), "Recorded log path is shown");
        check(rebuild.getEnabled(), "Accessible project enables explicit detailed rebuild");
        check(open.getEnabled(), "Existing saved log enables complete-log navigation");
        check(preferences.getString("buildFailure.lastProject").equals(project.getName()), "Failure display remembers exact workspace project");
        check(preferences.getString("buildFailure.lastBuild").equals(build.toAbsolutePath().normalize().toString()), "Failure display remembers exact build profile");
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
        view.getClass().getMethod("displayLastFailure").invoke(view);
        waitUntil(() -> report.getText().contains(diagnostic) && open.getEnabled(), "Recorded failure loads automatically without a file dialog");
        check(report.getText().contains(log.toString()), "Automatic loading selects exact failed log");
        check(log.equals(field(view, "log")), "Automatic loading binds complete-log action to recorded artifact");
        check(build.equals(field(view, "build")), "Automatic loading preserves recorded build directory");
        check(rebuild.getEnabled(), "Automatic loading enables explicit rebuild for accessible project");

        project.setPersistentProperty(profileKey, "release");
        view.getClass().getMethod("displayProject", IProject.class).invoke(view, project);
        waitUntil(() -> report.getText().contains("No recorded failed attempt"), "Explicit selected release profile stays scoped rather than reopening remembered debug failure");
        check(!open.getEnabled(), "A different profile does not expose another build's log");
        check(!report.getText().contains(diagnostic), "Explicit profile has no stale diagnostic from remembered failure");
        check(((Path) field(view, "build")).getFileName().toString().endsWith("-release"), "Explicit selected project uses current build profile");
        check(preferences.getString("buildFailure.lastBuild").equals(build.toString()), "Reading another profile does not rewrite failure history");

        var other = ResourcesPlugin.getWorkspace().getRoot().getProject("other-failure-smoke");
        other.create(null);
        other.open(null);
        Path otherApplication = other.getLocation().toFile().toPath();
        Path otherSetup = otherApplication.resolve("test-setup");
        Files.createDirectories(otherSetup);
        Files.writeString(otherSetup.resolve(".zew-ready"), "test-only");
        environment.setProperty("application", otherApplication.toString());
        try (var writer = Files.newBufferedWriter(otherSetup.resolve("environment.properties"))) { environment.store(writer, "Other smoke fixture"); }
        other.setPersistentProperty(new QualifiedName("io.github.niwantha33.zew.ui", "setupRoot"), otherSetup.toString());
        other.setPersistentProperty(profileKey, "debug");
        Path otherBuild = otherApplication.resolve(".zew-build/" + boardKey + "-debug");
        Path otherLog = (Path) logs.getMethod("create", Path.class, Path.class).invoke(null, otherApplication, otherBuild);
        String otherDiagnostic = "src/other.c:4:2: error: other_symbol undeclared";
        Files.writeString(otherLog, otherDiagnostic + "\n");
        logs.getMethod("failed", Path.class, String.class).invoke(null, otherLog, otherDiagnostic);
        view.getClass().getMethod("displayProject", IProject.class).invoke(view, other);
        waitUntil(() -> report.getText().contains(otherDiagnostic), "Explicit different project loads only its own failure");
        check(report.getText().contains(otherLog.toString()) && !report.getText().contains(diagnostic), "Another project's selected log cannot fall back to recorded history");
        check(preferences.getString("buildFailure.lastProject").equals(project.getName()), "Inspecting another project keeps original recorded failure history");

        project.close(null);
        view.getClass().getMethod("displayLastFailure").invoke(view);
        check(context.getText().contains("closed or unavailable"), "Unavailable recorded project explains why evidence cannot be selected");
        check(!open.getEnabled() && !rebuild.getEnabled(), "Unavailable project leaves actions disabled instead of guessing another project");
        check(report.getText().isEmpty(), "Unavailable project clears prior diagnostic report");
        project.open(null);
        page.hideView(view);
        var reopened = page.showView("io.github.niwantha33.zew.ui.buildFailureInvestigation");
        var reopenedReport = (Text) field(reopened, "reportText");
        var reopenedOpen = (Button) field(reopened, "open");
        waitUntil(() -> reopenedReport.getText().contains(diagnostic) && reopenedOpen.getEnabled(),
            "Reopening the view automatically restores recorded failure without any button click");
        check(reopenedReport.getText().contains(log.toString()), "Reopened view displays exact remembered debug log");
        check(log.equals(field(reopened, "log")) && build.equals(field(reopened, "build")),
            "Reopened view selects recorded build evidence even though current profile is release");
        preferences.setToDefault("buildFailure.lastProject");
        preferences.setToDefault("buildFailure.lastBuild");
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
