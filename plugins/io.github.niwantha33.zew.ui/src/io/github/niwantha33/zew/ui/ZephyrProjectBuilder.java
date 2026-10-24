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
            if (change.getResource().getType() == IResource.FILE
                    && (path.startsWith("src/") || path.startsWith("include/") || path.startsWith("boards/")
                        || path.startsWith("dts/") || path.startsWith("modules/")
                        || path.equals("prj.conf") || path.equals("CMakeLists.txt")
                        || path.equals("Kconfig") || path.equals(".zew/project.properties")
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
            String selected = getProject().getPersistentProperty(new QualifiedName(Activator.ID, "setupRoot"));
            if (selected == null) throw new java.io.IOException("This project has no managed Zephyr environment mapping.");
            Path root = Path.of(selected);
            ZephyrProjectFiles.requireManagedEnvironment(root);
            var settings = new Properties(); try (var reader = Files.newBufferedReader(root.resolve("environment.properties"), StandardCharsets.UTF_8)) { settings.load(reader); }
            Path project = getProject().getLocation().toFile().toPath().toRealPath();
            if (!Files.isRegularFile(project.resolve("CMakeLists.txt"))) throw new java.io.IOException("Missing application CMakeLists.txt.");
            String board = ZephyrProjectFiles.readBoard(project);
            var config = new WorkbenchConfig(Path.of(settings.getProperty("python")),
                    Path.of(settings.getProperty("workspace")), project, board);
            Path buildDirectory = WestCommands.buildDirectory(config);
            // Preserve the previously built first-profile directory rather than
            // invalidating a successful first run by changing its CMake cache path.
            if (project.equals(Path.of(settings.getProperty("application")).toRealPath())
                    && Files.isRegularFile(project.resolve(".zew-build/first/CMakeCache.txt")))
                buildDirectory = project.resolve(".zew-build/first");
            var environment = new LinkedHashMap<String, String>(); var directories = new ArrayList<String>();
            for (String tool : new String[] {"cmake", "ninja", "git"}) directories.add(Path.of(settings.getProperty("tools." + tool)).getParent().toString());
            directories.add(Path.of(settings.getProperty("python")).getParent().toString());
            String pathKey = System.getenv().keySet().stream().filter(key -> key.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
            environment.put(pathKey, String.join(java.io.File.pathSeparator, directories) + java.io.File.pathSeparator + System.getenv().getOrDefault(pathKey, ""));
            environment.put("ZEPHYR_SDK_INSTALL_DIR", io.github.niwantha33.zew.core.WestCommands.cmakePath(settings.getProperty("tools.sdk")));
            environment.put("ZEPHYR_TOOLCHAIN_VARIANT", "zephyr");
            environment.put("ZEPHYR_BASE", io.github.niwantha33.zew.core.WestCommands.cmakePath(Path.of(settings.getProperty("workspace"), "zephyr").toString()));
            if (clean && !Files.isRegularFile(buildDirectory.resolve("CMakeCache.txt"))) {
                stream.println("No configured build exists for " + board + "; nothing to clean.");
                return;
            }
            var command = new ArrayList<>(List.of(settings.getProperty("python"), "-m", "west", "build",
                    "--build-dir", WestCommands.cmakePath(buildDirectory.toString())));
            if (clean) command.addAll(List.of("--target", "clean"));
            else command.addAll(List.of("--board", board, WestCommands.cmakePath(project.toString()), "--", "-DZEPHYR_MODULES=",
                    "-DCMAKE_MAKE_PROGRAM:FILEPATH=" + WestCommands.cmakePath(settings.getProperty("tools.ninja")),
                    "-DPython3_EXECUTABLE:FILEPATH=" + WestCommands.cmakePath(settings.getProperty("python"))));
            stream.println("Workbench " + Activator.getDefault().getBundle().getVersion() + " | " + getProject().getName());
            stream.println("Exact board target: " + board + " | independent output: " + buildDirectory);
            monitor.beginTask(clean ? "Clean Zephyr project" : "Build Zephyr project", IProgressMonitor.UNKNOWN);
            new ProcessRunner().run(command, Path.of(settings.getProperty("workspace")), Duration.ofMinutes(30), monitor::isCanceled, stream::println, environment).requireSuccess();
            if (!clean && !Files.isRegularFile(buildDirectory.resolve("zephyr/zephyr.elf"))) throw new java.io.IOException("Expected firmware output is missing at " + buildDirectory);
            stream.println(clean ? "Clean completed." : "Firmware build verified.");
            getProject().refreshLocal(IResource.DEPTH_INFINITE, monitor);
            var output = getProject().getFolder(".zew-build"); if (output.exists()) output.setDerived(true, monitor);
        } catch (java.io.InterruptedIOException error) { throw new CoreException(Status.CANCEL_STATUS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new CoreException(Status.CANCEL_STATUS); }
        catch (Exception error) { throw new CoreException(new Status(IStatus.ERROR, Activator.ID, error.getMessage(), error)); }
        finally { monitor.done(); }
    }
}

