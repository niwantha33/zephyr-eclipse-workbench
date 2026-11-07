// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.MessageConsole;

import io.github.niwantha33.zew.core.BuildHostRequirements;
import io.github.niwantha33.zew.core.BuildArtifacts;
import io.github.niwantha33.zew.core.ConfigurationEvidenceReceipt;
import io.github.niwantha33.zew.core.BuildRecovery;
import io.github.niwantha33.zew.core.BuildFailureInvestigator;
import io.github.niwantha33.zew.core.BuildFailureLogs;
import io.github.niwantha33.zew.core.DevicetreeOverlaySupport;
import io.github.niwantha33.zew.core.KconfigFailure;
import io.github.niwantha33.zew.core.BuildSession;
import io.github.niwantha33.zew.core.ProcessRunner;
import io.github.niwantha33.zew.core.WestCommands;
import io.github.niwantha33.zew.core.WorkbenchConfig;
import io.github.niwantha33.zew.core.ZephyrProjectFiles;

/** Native Eclipse Build/Clean for independently imported or generated managed Zephyr projects. */
public final class ZephyrProjectBuilder extends IncrementalProjectBuilder {
    @Override protected IProject[] build(int kind, Map<String, String> arguments, IProgressMonitor monitor) throws CoreException {
        try {
            if (!ZephyrProjectLifecycle.configure(getProject(), monitor)) return new IProject[0];
        } catch (Exception error) {
            throw new CoreException(new Status(IStatus.ERROR, Activator.ID, error.getMessage(), error));
        }
        if (kind != AUTO_BUILD || sourceChanged()) run(false, "true".equals(arguments.get("zew.pristine")), "true".equals(arguments.get("zew.investigate")), arguments.get("zew.expectedBuild"), monitor);
        return new IProject[0];
    }
    private boolean sourceChanged() throws CoreException {
        var delta = getDelta(getProject()); if (delta == null) return false;
        boolean[] changed = {false};
        delta.accept(change -> {
            String path = change.getProjectRelativePath().toPortableString();
            if (path.equals(".zew-build") || path.startsWith(".zew-build/")) return false;
            boolean contentChange = change.getKind() != org.eclipse.core.resources.IResourceDelta.CHANGED
                    || (change.getFlags() & (org.eclipse.core.resources.IResourceDelta.CONTENT | org.eclipse.core.resources.IResourceDelta.REPLACED | org.eclipse.core.resources.IResourceDelta.ENCODING)) != 0;
            if (contentChange && change.getResource().getType() == IResource.FILE
                    && (path.startsWith("src/") || path.startsWith("include/") || path.startsWith("boards/")
                        || path.startsWith("dts/") || path.startsWith("modules/")
                        || path.equals("prj.conf") || path.equals("CMakeLists.txt")
                        || path.equals("Kconfig") || (path.equals(".zew/project.properties") || path.equals(".zew/project.json"))
                        || path.endsWith(".overlay") || path.endsWith(".conf"))) changed[0] = true;
            return !changed[0];
        });
        return changed[0];
    }
    /**
     * `ninja -t commands` may emit more than ProcessRunner's 2 MB
     * diagnostic-capture cap. Inspect every line while retaining only the
     * detected missing-tool identifiers. Preserve nonzero exit failures.
     */
    private static java.util.Set<String> inspectRequiredHostTools(
            ProcessRunner runner, List<String> command, Path workspace,
            IProgressMonitor monitor, Map<String, String> environment)
            throws java.io.IOException, InterruptedException {
        var scanner = new BuildHostRequirements.CommandScanner();
        runner.run(command, workspace, Duration.ofMinutes(2),
                monitor::isCanceled, scanner, environment).requireSuccess();
        return scanner.missing();
    }

    /** Preserve Zephyr's fatal Kconfig validation and provide an actionable error. */
    private static void requireConfigured(ProcessRunner.Result result, KconfigFailure issues,
            java.util.function.Consumer<String> output) throws java.io.IOException {
        try {
            result.requireSuccess();
        } catch (java.io.IOException error) {
            if (!issues.hasIssues()) throw error;
            String explanation = issues.description();
            output.accept("Configuration diagnosis:\n" + explanation);
            throw new java.io.IOException(explanation, error);
        }
    }

    /** A completed managed build updates the open, matching Studio without
     *  changing its selected tab or treating a partial receipt as verified. */
    private static void notifyOpenConfigurationStudio(IProject builtProject) {
        if (!org.eclipse.ui.PlatformUI.isWorkbenchRunning()) return;
        var ui = org.eclipse.ui.PlatformUI.getWorkbench().getDisplay();
        if (ui == null || ui.isDisposed()) return;
        ui.asyncExec(() -> {
            if (ui.isDisposed() || !org.eclipse.ui.PlatformUI.isWorkbenchRunning()) return;
            for (var window : org.eclipse.ui.PlatformUI.getWorkbench().getWorkbenchWindows()) {
                var page = window.getActivePage();
                if (page == null) continue;
                var part = page.findView(ConfigurationHomeView.ID);
                if (part instanceof ConfigurationHomeView home)
                    home.refreshAfterBuild(builtProject);
            }
        });
    }

    @Override protected void clean(IProgressMonitor monitor) throws CoreException {
        try { if (ZephyrProjectLifecycle.configure(getProject(), monitor)) run(true, false, false, null, monitor); }
        catch (CoreException error) { throw error; }
        catch (Exception error) { throw new CoreException(new Status(IStatus.ERROR, Activator.ID, error.getMessage(), error)); }
    }
    private void run(boolean clean, boolean pristine, boolean investigate, String expectedBuild, IProgressMonitor monitor) throws CoreException {
        var console = new MessageConsole("Zephyr — " + getProject().getName(), null);
        ConsolePlugin.getDefault().getConsoleManager().addConsoles(new org.eclipse.ui.console.IConsole[] {console});
        ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
        String[] stage = {"Load project"};
        try (var stream = console.newMessageStream();
             var session = BuildSession.acquire(Path.of(ProjectContext.settings(getProject()).getProperty("zew.setupRoot")),
                 monitor::isCanceled, Duration.ofMinutes(45), stream::println)) {
            var settings = ProjectContext.settings(getProject());
            Path project = getProject().getLocation().toFile().toPath().toRealPath();
            String board = settings.getProperty("board");
            var configuration = io.github.niwantha33.zew.core.BuildConfiguration.fromId(settings.getProperty("zew.buildConfiguration"));
            String namedId = settings.getProperty("zew.profileId", "");
            var namedProfile = namedId.isBlank() ? null
                    : io.github.niwantha33.zew.core.BuildProfile.read(project, namedId);
            String profileLabel = namedProfile == null ? configuration.label() : namedProfile.label();
            String profileIdentity = namedProfile == null ? configuration.id() : "profile-" + namedProfile.id();
            Path buildDirectory = ProjectContext.buildDirectory(getProject(), settings);
            if (expectedBuild != null && !buildDirectory.toAbsolutePath().normalize().equals(Path.of(expectedBuild)))
                throw new java.io.IOException("The build profile changed while investigation was queued. Select the current build and retry.");
            var environment = ProjectContext.environment(settings);
            monitor.beginTask(clean ? "Clean Zephyr project" : "Prepare tools and build Zephyr project", IProgressMonitor.UNKNOWN);
            if (!clean) {
                stage[0] = "Preflight pinned project environment";
                ProjectVersionHandler.verifyBeforeBuild(getProject(), monitor, stream::println);
                stage[0] = "Prepare dependencies";
                BoardTools.prepare(settings, environment, monitor, stream::println);
                stage[0] = "Verify pinned environment after dependency preparation";
                ProjectVersionHandler.verifyBeforeBuild(getProject(), monitor, stream::println);
            }
            if (clean && !Files.isRegularFile(buildDirectory.resolve("CMakeCache.txt"))) {
                stream.println("No configured build exists for " + board + "; nothing to clean.");
                return;
            }
            var paths = BoardTools.buildPaths(settings, buildDirectory, environment, monitor);
            environment.put("ZEPHYR_BASE", paths.workspace() + "/zephyr");
            environment.put("ZEPHYR_SDK_INSTALL_DIR", paths.sdk());
            var command = new ArrayList<>(List.of(settings.getProperty("python"), "-m", "west", "build",
                    "--build-dir", WestCommands.cmakePath(paths.build())));
            if (clean) command.addAll(List.of("--target", "clean"));
            else command.addAll(List.of("--board", board, WestCommands.cmakePath(paths.application()), "--", "-DCMAKE_EXPORT_COMPILE_COMMANDS:BOOL=ON",
                    "-DCMAKE_MAKE_PROGRAM:FILEPATH=" + WestCommands.cmakePath(settings.getProperty("tools.ninja")),
                    "-DPython3_EXECUTABLE:FILEPATH=" + WestCommands.cmakePath(settings.getProperty("python"))));
            if (!clean) command.addAll(namedProfile == null ? configuration.cmakeArguments() : namedProfile.cmakeArguments(project));
            // Explicit extra overlays are opt-in. With no selection file,
            // preserve Zephyr's automatic board/SoC/app.overlay discovery and
            // all existing project CMake-cache overrides unchanged.
            final List<Path> selectedExtraOverlays = clean ? List.of()
                    : DevicetreeOverlaySupport.selectedExtras(project);
            if (!clean) command.addAll(DevicetreeOverlaySupport.extraCmakeArguments(project));
            if (!clean && "true".equals(settings.getProperty("zew.modules.none"))) command.add("-DZEPHYR_MODULES=");
            if (!clean) {
                // One shared OpenOCD tool registry serves every imported project.
                // Selection is target-aware: Espressif patched fork, TI fork,
                // or generic Zephyr/standard OpenOCD. No global PATH mutation.
                var selection = SharedDebugTools.select(board, settings);
                if (selection.isPresent()) {
                    var tool = selection.get();
                    command.add("-DOPENOCD:FILEPATH="
                        + WestCommands.cmakePath(tool.executable().toString()));
                    command.add("-DOPENOCD_DEFAULT_PATH:PATH="
                        + WestCommands.cmakePath(tool.scripts().toString()));
                    stream.println("Shared OpenOCD (" + tool.provider() + "): " + tool.executable());
                    stream.println("Runner scripts: " + tool.scripts());
                } else {
                    stream.println("Optional hardware debug tool not found for this board."
                        + " Open Zephyr > Manage Debug Tools to select a compatible shared OpenOCD installation.");
                    // Explicit empty settings also prevent CMake's
                    // find_program from silently choosing an incompatible
                    // OpenOCD from a user/system PATH. Building firmware is
                    // still allowed without a hardware debugger installed.
                    command.add("-DOPENOCD:FILEPATH=");
                    command.add("-DOPENOCD_DEFAULT_PATH:PATH=");
                }
            }
            stream.println("Workbench " + Activator.getDefault().getBundle().getVersion() + " | " + getProject().getName());
            stream.println("Exact board target: " + board + " | configuration: " + profileLabel + " | independent output: " + buildDirectory);
            monitor.setTaskName(clean ? "Clean Zephyr project" : "Build Zephyr project");
            BuildDiagnostics.clear(getProject());
            var runner = new ProcessRunner();
            var kconfigFailure = new KconfigFailure();
            var investigation = new BuildFailureInvestigator();
            // Separate exact board/profile attempts; pristine rebuilds retain the evidence.
            Path buildLog = BuildFailureLogs.create(project, buildDirectory);
            stream.println("Complete build log: " + buildLog);
            try (var savedLog = Files.newBufferedWriter(buildLog, StandardCharsets.UTF_8)) {
                String identity = "Board: " + board + " | profile: " + profileLabel
                    + " | build: " + buildDirectory;
                savedLog.write(identity);
                savedLog.newLine();
                investigation.accept(identity);
                var workspace = Path.of(paths.workspace());
                java.util.function.Consumer<String> log = line -> {
                    stream.println(line);
                    investigation.accept(line);
                    try { savedLog.write(line); savedLog.newLine(); }
                    catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
                    BuildDiagnostics.accept(getProject(), buildDirectory, line);
                    kconfigFailure.accept(line);
                };
                if (!clean) {
                    // Resolve real build requirements from Zephyr's configured
                    // Ninja commands. CMakeCache alone contains harmless optional
                    // tools marked NOTFOUND, so it is not authoritative.
                    var configure = new ArrayList<>(command);
                    configure.add(4, "--cmake-only");
                    if (paths.freshConfiguration() || pristine) {
                        stream.println(pristine ? "Explicit pristine rebuild; regenerating only this target build folder." : "Refreshing this managed target for a verified Windows short-path alias.");
                        configure.add(5, "--pristine=always");
                    }
                    stream.println("Preflight: Zephyr configure-only; checking actual host tool dependencies.");
                    stage[0] = "Configure Zephyr";
                    investigation.beginAttempt();
                var configured = runner.run(configure, workspace, Duration.ofMinutes(15),
                            monitor::isCanceled, log, environment);
                    if (configured.exitCode() != 0 && io.github.niwantha33.zew.core.DemoConfigurationCompatibility
                            .migrate(project, configured.output())) {
                        stream.println("Updated the original RTOS demo's obsolete CONFIG_DEBUG_INFO setting. Original saved in .zew/config-backup.");
                        BuildDiagnostics.clear(getProject());
                        investigation.beginAttempt();
                        configured = runner.run(configure, workspace, Duration.ofMinutes(15),
                                monitor::isCanceled, log, environment);
                    }
                    requireConfigured(configured, kconfigFailure, stream::println);
                    // Confirm that every explicitly selected file reached the
                    // actual Zephyr preprocessor, not just the CMake command.
                    // This is intentionally NOT required of unselected overlays.
                    if (!selectedExtraOverlays.isEmpty()) {
                        var context = DevicetreeOverlaySupport.inspect(project, buildDirectory,
                                Path.of(paths.workspace(), "zephyr"));
                        if (!context.verified())
                            throw new java.io.IOException("Cannot confirm selected overlay participation: "
                                    + context.message());
                        for (Path overlay : selectedExtraOverlays)
                            if (!context.active(overlay))
                                throw new java.io.IOException("Selected extra overlay did not reach the generated "
                                    + "Devicetree dependencies: " + project.relativize(overlay)
                                    + ". Check DTC_OVERLAY_FILE, EXTRA_DTC_OVERLAY_FILE and rebuild.");
                        stream.println("Verified " + selectedExtraOverlays.size()
                                + " explicitly selected overlay(s) in zephyr.dts.d.");
                    }
                    var inspectCommands = List.of(settings.getProperty("tools.ninja"), "-C", paths.build(), "-t", "commands");
                    var missing = inspectRequiredHostTools(runner, inspectCommands, workspace, monitor, environment);
                    var recovery = new BuildRecovery(Map.of("IMGTOOL", () ->
                        BoardTools.prepareHostTool(settings, environment, monitor, stream::println, "IMGTOOL"),
                        "ESPTOOL", () -> BoardTools.prepareHostTool(settings, environment, monitor, stream::println, "ESPTOOL")), 2);
                    while (!missing.isEmpty()) {
                        stage[0] = "Recover required build tools";
                        stream.println("Preparing mandatory host tools: " + missing);
                        recovery.repair(missing);
                        // Reconfigure after provisioning; no pristine deletion is needed.
                        // Force CMake to discard only stale host-tool cache entries.
                        configure.remove("-DZEPHYR_MODULES="); command.remove("-DZEPHYR_MODULES=");
                        configure.remove("--pristine=always");
                        var refresh = new ArrayList<>(configure);
                        refresh.add("-UZEPHYR_MODULES");
                        for (String tool : missing) refresh.add("-U" + tool);
                        stage[0] = "Refresh target configuration";
                        investigation.beginAttempt();
                    requireConfigured(runner.run(refresh, workspace, Duration.ofMinutes(15),
                                monitor::isCanceled, log, environment), kconfigFailure, stream::println);
                        missing = inspectRequiredHostTools(runner, inspectCommands, workspace, monitor, environment);
                    }
                    BuildArtifacts.verifyArchitecture(buildDirectory);
                    stream.println("Preflight: generated build commands have no unresolved host tool placeholders.");
                }
                stage[0] = clean ? "Clean" : "Compile and generate firmware";
                // Preflight already supplied all CMake arguments. Repeating them
                // here makes West configure twice on every build.
                var compile = new ArrayList<>(clean ? command : List.of(settings.getProperty("python"), "-m", "west", "build",
                    "--build-dir", WestCommands.cmakePath(paths.build())));
                if (investigate && !clean) {
                    compile.add(3, "-v");
                    compile.add("-o=-j1");
                    stream.println("Investigation build: verbose commands, one build job; same board, profile and build directory.");
                }
                investigation.beginAttempt();
                var compiled = runner.run(compile, workspace, Duration.ofMinutes(30), monitor::isCanceled, log, environment);
                if (compiled.exitCode() != 0) {
                    throw new java.io.IOException("Build command exited with status " + compiled.exitCode());
                }
                stage[0] = "Verify configured firmware outputs";
                if (!clean) {
                    BuildArtifacts.verify(buildDirectory);
                    if (namedProfile == null) configuration.verify(buildDirectory);
                    else namedProfile.verify(buildDirectory);
                }
                if (!clean) {
                    Files.writeString(buildDirectory.resolve(".zew-built-target"), board);
                    // Capture only AFTER the exact build and generated outputs
                    // have passed their existing integrity/target checks.
                    // A missing dependency file yields UNVERIFIED, never a
                    // forged "current" snapshot; receipt failure does not
                    // retroactively claim the firmware build failed.
                    try {
                        var receipt = ConfigurationEvidenceReceipt.capture(project,
                                buildDirectory, board, profileIdentity);
                        stream.println("Configuration provenance: " + receipt.state()
                                + " — " + receipt.detail());
                    } catch (java.io.IOException receiptError) {
                        stream.println("Configuration provenance UNVERIFIED: "
                                + "could not capture input fingerprints; rebuild after inspection.");
                    }
                    EditorIntegration.schedule(getProject());
                    ProjectVersionHandler.snapshotOnFirstBuild(getProject(), monitor, stream::println);
                    // PR1 Step4: CDT native builds and Zephyr menu builds both reach
                    // this builder. Only verified, non-clean successful builds
                    // publish a linker-map snapshot for already-open profiler views.
                    // Optional analysis never changes the firmware build result.
                    if (!monitor.isCanceled())
                        MemoryProfilerBuildIntegration.afterSuccessfulBuild(getProject(),
                                buildDirectory, settings.getProperty("python"),
                                monitor::isCanceled, stream::println);
                }
                stream.println(clean ? "Clean completed." : "BUILD SUCCESS — configured firmware outputs verified.");
            } catch (java.io.InterruptedIOException error) { throw error; }
            catch (InterruptedException error) { throw error; }
            catch (Exception error) {
                String diagnosis = "Failure stage: " + stage[0] + "\n" + error.getMessage() + "\n\n"
                    + investigation.summary() + "Complete build log: " + buildLog
                    + "\nUse Rebuild with detailed output in Zephyr Build Failure Investigation to retry in this environment.";
                stream.println(diagnosis);
                try { BuildFailureLogs.failed(buildLog, diagnosis); }
                catch (java.io.IOException receiptError) { error.addSuppressed(receiptError); }
                BuildFailureInvestigationHandler.showFailure(getProject(), buildDirectory, buildLog, diagnosis);
                throw new java.io.IOException(error.getMessage() + "\n" + diagnosis, error);
            }
            getProject().refreshLocal(IResource.DEPTH_INFINITE, monitor);
            var output = getProject().getFolder(".zew-build"); if (output.exists()) output.setDerived(true, monitor);
            if (!clean) notifyOpenConfigurationStudio(getProject());
        } catch (java.io.InterruptedIOException error) { throw new CoreException(Status.CANCEL_STATUS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new CoreException(Status.CANCEL_STATUS); }
        catch (Exception error) { throw new CoreException(new Status(IStatus.ERROR, Activator.ID, stage[0] + " failed: " + error.getMessage(), error)); }
        finally { monitor.done(); }
    }
}


