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

/** Manual Project Build/Clean for the verified managed starter; no generic Make builder. */
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
                    && (path.startsWith("src/") || path.startsWith("include/") || path.equals("prj.conf") || path.equals("CMakeLists.txt"))) changed[0] = true;
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
            if (!Files.isRegularFile(root.resolve(".zew-ready"))) throw new java.io.IOException("Complete Set Up Zephyr before building this project.");
            var settings = new Properties(); try (var reader = Files.newBufferedReader(root.resolve("environment.properties"), StandardCharsets.UTF_8)) { settings.load(reader); }
            Path project = getProject().getLocation().toFile().toPath();
            if (!project.toRealPath().equals(Path.of(settings.getProperty("application")).toRealPath())) throw new java.io.IOException("Project location differs from its environment mapping.");
            var environment = new LinkedHashMap<String, String>(); var directories = new ArrayList<String>();
            for (String tool : new String[] {"cmake", "ninja", "git"}) directories.add(Path.of(settings.getProperty("tools." + tool)).getParent().toString());
            directories.add(Path.of(settings.getProperty("python")).getParent().toString());
            String pathKey = System.getenv().keySet().stream().filter(key -> key.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
            environment.put(pathKey, String.join(java.io.File.pathSeparator, directories) + java.io.File.pathSeparator + System.getenv().getOrDefault(pathKey, ""));
            environment.put("ZEPHYR_SDK_INSTALL_DIR", io.github.niwantha33.zew.core.WestCommands.cmakePath(settings.getProperty("tools.sdk")));
            environment.put("ZEPHYR_TOOLCHAIN_VARIANT", "zephyr");
            environment.put("ZEPHYR_BASE", io.github.niwantha33.zew.core.WestCommands.cmakePath(Path.of(settings.getProperty("workspace"), "zephyr").toString()));
            var command = new ArrayList<>(List.of(settings.getProperty("python"), "-m", "west", "build", "--build-dir", project.resolve(".zew-build/first").toString()));
            if (clean) command.addAll(List.of("--target", "clean"));
            else command.addAll(List.of("--board", settings.getProperty("board"), project.toString(), "--", "-DZEPHYR_MODULES=",
                    "-DCMAKE_MAKE_PROGRAM:FILEPATH=" + io.github.niwantha33.zew.core.WestCommands.cmakePath(settings.getProperty("tools.ninja")),
                    "-DPython3_EXECUTABLE:FILEPATH=" + io.github.niwantha33.zew.core.WestCommands.cmakePath(settings.getProperty("python"))));
            stream.println("Workbench " + Activator.getDefault().getBundle().getVersion() + " | " + getProject().getName());
            monitor.beginTask(clean ? "Clean Zephyr project" : "Build Zephyr project", IProgressMonitor.UNKNOWN);
            new ProcessRunner().run(command, Path.of(settings.getProperty("workspace")), Duration.ofMinutes(30), monitor::isCanceled, stream::println, environment).requireSuccess();
            if (!clean && !Files.isRegularFile(project.resolve(".zew-build/first/zephyr/zephyr.elf"))) throw new java.io.IOException("Expected firmware output is missing.");
            stream.println(clean ? "Clean completed." : "Firmware build verified.");
            getProject().refreshLocal(IResource.DEPTH_INFINITE, monitor);
            var output = getProject().getFolder(".zew-build"); if (output.exists()) output.setDerived(true, monitor);
        } catch (java.io.InterruptedIOException error) { throw new CoreException(Status.CANCEL_STATUS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new CoreException(Status.CANCEL_STATUS); }
        catch (Exception error) { throw new CoreException(new Status(IStatus.ERROR, Activator.ID, error.getMessage(), error)); }
        finally { monitor.done(); }
    }
}

