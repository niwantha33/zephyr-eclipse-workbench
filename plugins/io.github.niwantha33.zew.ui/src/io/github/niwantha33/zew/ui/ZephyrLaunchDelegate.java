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
            var plan = io.github.niwantha33.zew.core.TargetOperations.prepare(
                    output, settings.getProperty("board"), action, "");
            if ("debugserver".equals(action)) plan = SharedDebugTools.bindDebugTool(plan, settings);
            var builder = new ProcessBuilder(plan.command(settings.getProperty("python")));
            builder.directory(Path.of(settings.getProperty("workspace")).toFile());
            builder.environment().putAll(ProjectContext.environment(settings));
            builder.redirectErrorStream(true);
            if (monitor.isCanceled()) return;
            var lease = TargetOperationGuard.acquire(action);
            Process process = null;
            try {
                process = builder.start();
                Process owned = process;
                owned.onExit().thenRun(lease::close);
                DebugPlugin.newProcess(launch, owned, "Zephyr " + action + " — " + project.getName());
                // The Eclipse launch owns process termination and its console.
                // The lease persists until the West child exits (including user terminate).
            } catch (Exception error) {
                if (process != null) process.destroyForcibly();
                lease.close();
                throw error;
            }
        } catch (Exception error) {
            throw failure(error.getMessage(), error);
        }
    }

    private static CoreException failure(String message, Throwable cause) {
        return new CoreException(new Status(IStatus.ERROR, Activator.ID, message, cause));
    }
}
