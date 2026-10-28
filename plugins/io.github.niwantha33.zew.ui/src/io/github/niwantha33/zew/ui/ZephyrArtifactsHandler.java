// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;
import io.github.niwantha33.zew.core.BuildArtifacts;

/** Build, validate and expose the real West firmware folder without knowing its target hash. */
public final class ZephyrArtifactsHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        IProject project = SelectedProject.from(event);
        Shell shell = HandlerUtil.getActiveShell(event);
        if (project == null || !project.isAccessible()) {
            MessageDialog.openInformation(shell, "Zephyr Firmware",
                "Select an imported Zephyr project in Project Explorer.");
            return null;
        }
        try {
            ProjectContext.settings(project);
        } catch (Exception error) {
            MessageDialog.openError(shell, "Zephyr Firmware", "Project setup is incomplete: " + error.getMessage());
            return null;
        }
        Job job = new Job("Build and show Zephyr firmware — " + project.getName()) {
            @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                try {
                    project.build(IncrementalProjectBuilder.FULL_BUILD, Activator.ID + ".zephyrBuilder", Map.of(), monitor);
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    var settings = ProjectContext.settings(project);
                    Path build = ProjectContext.buildDirectory(project, settings);
                    BuildArtifacts.verify(build);
                    var files = new ArrayList<>(BuildArtifacts.required(build));
                    files.sort(Comparator.comparing(Path::toString));
                    var details = new StringBuilder()
                        .append("Project: ").append(project.getName())
                        .append("\nExact board: ").append(settings.getProperty("board"))
                        .append("\nConfiguration: ").append(io.github.niwantha33.zew.core.BuildConfiguration
                                .fromId(settings.getProperty("zew.buildConfiguration")).label())
                        .append("\nBuild folder: ").append(build)
                        .append("\n\nVerified firmware output:\n");
                    for (Path file : files) {
                        details.append("  ").append(file.getFileName())
                            .append(" (").append(Files.size(file)).append(" bytes)\n");
                    }
                    Path yaml = build.resolve("zephyr/runners.yaml");
                    if (Files.isRegularFile(yaml) && Files.size(yaml) < 1024 * 1024) {
                        var rows = Files.readAllLines(yaml, StandardCharsets.UTF_8);
                        for (String kind : new String[] {"flash-runner", "debug-runner"}) {
                            rows.stream().map(String::trim).filter(s -> s.startsWith(kind + ":"))
                                .findFirst().ifPresent(s -> details.append(kind)
                                    .append(": ").append(s.substring(kind.length() + 1).trim()).append("\n"));
                        }
                    }
                    details.append("\nGenerated configuration: ").append(build.resolve("zephyr/.config"));
                    details.append("\nThe same build directory is used by West Flash and Debug.");
                    if (shell != null) shell.getDisplay().asyncExec(() -> {
                        if (shell.isDisposed()) return;
                        var dialog = new MessageDialog(shell, "Zephyr Firmware — " + project.getName(),
                            null, details.toString(), MessageDialog.INFORMATION,
                            new String[] {"Open Build Folder", "Close"}, 0);
                        if (dialog.open() == 0 && !Program.launch(build.toString()))
                            MessageDialog.openError(shell, "Open Build Folder",
                                "Could not open the build folder. Path:\n" + build);
                    });
                    return Status.OK_STATUS;
                } catch (org.eclipse.core.runtime.CoreException error) {
                    return error.getStatus();
                } catch (Exception error) {
                    return new Status(IStatus.ERROR, Activator.ID,
                        "Build or firmware inspection failed: " + error.getMessage(), error);
                }
            }
        };
        job.setUser(true);
        job.schedule();
        return null;
    }
}
