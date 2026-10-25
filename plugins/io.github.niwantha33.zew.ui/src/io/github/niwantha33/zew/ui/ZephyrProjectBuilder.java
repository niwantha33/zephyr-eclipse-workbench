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
import io.github.niwantha33.zew.core.ProcessRunner;
import io.github.niwantha33.zew.core.WestCommands;
import io.github.niwantha33.zew.core.WorkbenchConfig;
import io.github.niwantha33.zew.core.ZephyrProjectFiles;

/** Native Eclipse Build/Clean for independently imported or generated managed Zephyr projects. */
public final class ZephyrProjectBuilder extends IncrementalProjectBuilder {
    @Override protected IProject[] build(int kind, Map<String, String> arguments, IProgressMonitor monitor) throws CoreException {
        if (kind != AUTO_BUILD || sourceChanged()) run(false, monitor);
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
    @Override protected void clean(IProgressMonitor monitor) throws CoreException { run(true, monitor); }
    private void run(boolean clean, IProgressMonitor monitor) throws CoreException {
        var console = new MessageConsole("Zephyr — " + getProject().getName(), null);
        ConsolePlugin.getDefault().getConsoleManager().addConsoles(new org.eclipse.ui.console.IConsole[] {console});
        ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
        try (var stream = console.newMessageStream()) {
            var settings = ProjectContext.settings(getProject());
            Path project = getProject().getLocation().toFile().toPath().toRealPath();
            String board = settings.getProperty("board");
            Path buildDirectory = ProjectContext.buildDirectory(getProject(), settings);
            var environment = ProjectContext.environment(settings);
            monitor.beginTask(clean ? "Clean Zephyr project" : "Prepare tools and build Zephyr project", IProgressMonitor.UNKNOWN);
            if (!clean) BoardTools.prepare(settings, environment, monitor, stream::println);
            if (clean && !Files.isRegularFile(buildDirectory.resolve("CMakeCache.txt"))) {
                stream.println("No configured build exists for " + board + "; nothing to clean.");
                return;
            }
            var command = new ArrayList<>(List.of(settings.getProperty("python"), "-m", "west", "build",
                    "--build-dir", WestCommands.cmakePath(buildDirectory.toString())));
            if (clean) command.addAll(List.of("--target", "clean"));
            else command.addAll(List.of("--board", board, WestCommands.cmakePath(project.toString()), "--", "-DCMAKE_EXPORT_COMPILE_COMMANDS:BOOL=ON",
                    "-DCMAKE_MAKE_PROGRAM:FILEPATH=" + WestCommands.cmakePath(settings.getProperty("tools.ninja")),
                    "-DPython3_EXECUTABLE:FILEPATH=" + WestCommands.cmakePath(settings.getProperty("python"))));
            if (!clean && "true".equals(settings.getProperty("zew.modules.none"))) command.add("-DZEPHYR_MODULES=");
            stream.println("Workbench " + Activator.getDefault().getBundle().getVersion() + " | " + getProject().getName());
            stream.println("Exact board target: " + board + " | independent output: " + buildDirectory);
            monitor.setTaskName(clean ? "Clean Zephyr project" : "Build Zephyr project");
            BuildDiagnostics.clear(getProject());
            var runner = new ProcessRunner();
            var workspace = Path.of(settings.getProperty("workspace"));
            java.util.function.Consumer<String> log = line -> {
                stream.println(line);
                BuildDiagnostics.accept(getProject(), buildDirectory, line);
            };
            if (!clean) {
                // Resolve real build requirements from Zephyr's configured
                // Ninja commands. CMakeCache alone contains harmless optional
                // tools marked NOTFOUND, so it is not authoritative.
                var configure = new ArrayList<>(command);
                configure.add(4, "--cmake-only");
                stream.println("Preflight: Zephyr configure-only; checking actual host tool dependencies.");
                runner.run(configure, workspace, Duration.ofMinutes(15),
                        monitor::isCanceled, log, environment).requireSuccess();
                var missing = BuildHostRequirements.missingInGeneratedBuild(buildDirectory);
                boolean installedHostTool = false;
                for (String tool : new java.util.TreeSet<>(missing)) {
                    if (java.util.Set.of("IMGTOOL", "ESPTOOL").contains(tool)) {
                        stream.println("Build requires " + tool + "; preparing its pinned host-tool provider.");
                        BoardTools.prepareHostTool(settings, environment, monitor, stream::println, tool);
                        installedHostTool = true;
                    }
                }
                if (installedHostTool) {
                    // Installed modules must be visible even for an old minimal starter.
                    configure.remove("-DZEPHYR_MODULES="); command.remove("-DZEPHYR_MODULES=");
                    // Old CMakeCache can retain IMGTOOL-NOTFOUND, and module
                    // discovery happens at configure time. Regenerate only
                    // this project's independent .zew-build target directory.
                    var fresh = new ArrayList<>(configure);
                    fresh.add(5, "--pristine=always");
                    stream.println("Preflight: refreshing generated build rules for the newly activated module.");
                    runner.run(fresh, workspace, Duration.ofMinutes(15),
                            monitor::isCanceled, log, environment).requireSuccess();
                    missing = BuildHostRequirements.missingInGeneratedBuild(buildDirectory);
                }
                if (!missing.isEmpty()) {
                    throw new java.io.IOException("Zephyr generated build requires unresolved host tools: "
                        + String.join(", ", new java.util.TreeSet<>(missing))
                        + ". This managed profile has no reviewed pinned provider; "
                        + "the tool was not guessed or downloaded.");
                }
                stream.println("Preflight: generated build commands have no unresolved host tool placeholders.");
            }
            runner.run(command, workspace, Duration.ofMinutes(30), monitor::isCanceled, log, environment).requireSuccess();
            if (!clean && !Files.isRegularFile(buildDirectory.resolve("zephyr/zephyr.elf"))) throw new java.io.IOException("Expected firmware output is missing at " + buildDirectory);
            if (!clean) {
                Files.writeString(buildDirectory.resolve(".zew-built-target"), board);
                EditorIntegration.schedule(getProject());
            }
            stream.println(clean ? "Clean completed." : "Firmware build verified.");
            getProject().refreshLocal(IResource.DEPTH_INFINITE, monitor);
            var output = getProject().getFolder(".zew-build"); if (output.exists()) output.setDerived(true, monitor);
        } catch (java.io.InterruptedIOException error) { throw new CoreException(Status.CANCEL_STATUS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new CoreException(Status.CANCEL_STATUS); }
        catch (Exception error) { throw new CoreException(new Status(IStatus.ERROR, Activator.ID, error.getMessage(), error)); }
        finally { monitor.done(); }
    }
}



