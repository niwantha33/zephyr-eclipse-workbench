// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.ILaunchConfigurationDelegate;
import io.github.niwantha33.zew.core.WestCommands;

/** Native Run Configurations. Eclipse owns console output and process termination. */
public final class ZephyrLaunchDelegate implements ILaunchConfigurationDelegate {
    public static final String TYPE = Activator.ID + ".launchType";
    public static final String PROJECT = Activator.ID + ".launch.project";
    public static final String ACTION = Activator.ID + ".launch.action";

    @Override public void launch(ILaunchConfiguration configuration, String mode, ILaunch launch,
                                  IProgressMonitor monitor) throws CoreException {
        if (!ILaunchManager.RUN_MODE.equals(mode)) {
            throw failure("Use Run mode. CDT source-level GDB debugging is not implemented.", null);
        }
        String name = configuration.getAttribute(PROJECT, "").trim();
        String action = configuration.getAttribute(ACTION, "run");
        if (!List.of("run", "flash", "debugserver").contains(action)) throw failure("Unsupported Zephyr runner action.", null);
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        if (!project.isAccessible()) throw failure("Select an open imported Zephyr project.", null);
        try {
            var settings = ProjectContext.settings(project);
            Path output = ProjectContext.buildDirectory(project, settings);
            String target = settings.getProperty("board");
            Path receipt = output.resolve(".zew-built-target");
            if (!Files.isRegularFile(output.resolve("CMakeCache.txt")) || !Files.isRegularFile(receipt) ||
                    !Files.readString(receipt).equals(target))
                throw new java.io.IOException("Build this project's current exact target before running or flashing.");
            if (action.equals("run") && !target.startsWith("qemu_"))
                throw new java.io.IOException("Run in Emulator requires a QEMU board. Choose Flash for physical hardware.");
            if (!action.equals("run") && !Files.isRegularFile(output.resolve("zephyr/runners.yaml")))
                throw new java.io.IOException("This build has no Zephyr runner definition for flashing/debug server.");
            var command = new ArrayList<>(List.of(settings.getProperty("python"), "-m", "west"));
            if (action.equals("run")) command.addAll(List.of("build", "--build-dir", WestCommands.cmakePath(output.toString()), "--target", "run"));
            else command.addAll(List.of(action, "--build-dir", WestCommands.cmakePath(output.toString())));
            var builder = new ProcessBuilder(command);
            builder.directory(Path.of(settings.getProperty("workspace")).toFile());
            builder.environment().putAll(ProjectContext.environment(settings));
            builder.redirectErrorStream(true);
            if (monitor.isCanceled()) return;
            Process process = builder.start();
            DebugPlugin.newProcess(launch, process, "Zephyr " + action + " — " + project.getName());
            // The Eclipse launch owns termination; do not spawn a detached console
            // process or a vendor-dependent script.
        } catch (Exception error) {
            throw failure(error.getMessage(), error);
        }
    }

    private static CoreException failure(String message, Throwable cause) {
        return new CoreException(new Status(IStatus.ERROR, Activator.ID, message, cause));
    }
}
