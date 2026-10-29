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
import io.github.niwantha33.zew.core.BuildRecovery;
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
        if (kind != AUTO_BUILD || sourceChanged()) run(false, "true".equals(arguments.get("zew.pristine")), monitor);
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

    @Override protected void clean(IProgressMonitor monitor) throws CoreException {
        try { if (ZephyrProjectLifecycle.configure(getProject(), monitor)) run(true, false, monitor); }
        catch (CoreException error) { throw error; }
        catch (Exception error) { throw new CoreException(new Status(IStatus.ERROR, Activator.ID, error.getMessage(), error)); }
    }
    private void run(boolean clean, boolean pristine, IProgressMonitor monitor) throws CoreException {
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
            Path buildDirectory = ProjectContext.buildDirectory(getProject(), settings);
            var environment = ProjectContext.environment(settings);
            monitor.beginTask(clean ? "Clean Zephyr project" : "Prepare tools and build Zephyr project", IProgressMonitor.UNKNOWN);
            stage[0] = "Prepare dependencies";
            if (!clean) BoardTools.prepare(settings, environment, monitor, stream::println);
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
            if (!clean) command.addAll(configuration.cmakeArguments());
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
            stream.println("Exact board target: " + board + " | configuration: " + configuration.label() + " | independent output: " + buildDirectory);
            monitor.setTaskName(clean ? "Clean Zephyr project" : "Build Zephyr project");
            BuildDiagnostics.clear(getProject());
            var runner = new ProcessRunner();
            var kconfigFailure = new KconfigFailure();
            var workspace = Path.of(paths.workspace());
            java.util.function.Consumer<String> log = line -> {
                stream.println(line);
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
                requireConfigured(runner.run(configure, workspace, Duration.ofMinutes(15),
                        monitor::isCanceled, log, environment), kconfigFailure, stream::println);
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
            var compile = clean ? command : List.of(settings.getProperty("python"), "-m", "west", "build",
                "--build-dir", WestCommands.cmakePath(paths.build()));
            runner.run(compile, workspace, Duration.ofMinutes(30), monitor::isCanceled, log, environment).requireSuccess();
            stage[0] = "Verify configured firmware outputs";
            if (!clean) {
                BuildArtifacts.verify(buildDirectory);
                configuration.verify(buildDirectory);
            }
            if (!clean) {
                Files.writeString(buildDirectory.resolve(".zew-built-target"), board);
                EditorIntegration.schedule(getProject());
            }
            stream.println(clean ? "Clean completed." : "BUILD SUCCESS — configured firmware outputs verified.");
            getProject().refreshLocal(IResource.DEPTH_INFINITE, monitor);
            var output = getProject().getFolder(".zew-build"); if (output.exists()) output.setDerived(true, monitor);
        } catch (java.io.InterruptedIOException error) { throw new CoreException(Status.CANCEL_STATUS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new CoreException(Status.CANCEL_STATUS); }
        catch (Exception error) { throw new CoreException(new Status(IStatus.ERROR, Activator.ID, stage[0] + " failed: " + error.getMessage(), error)); }
        finally { monitor.done(); }
    }
}


