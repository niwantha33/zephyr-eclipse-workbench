// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.List;
import org.eclipse.core.commands.*;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.console.*;
import org.eclipse.ui.handlers.HandlerUtil;
import io.github.niwantha33.zew.core.ProcessRunner;

/** Project-scoped runner operations. Debug server is explicitly not a CDT debug session. */
public final class ProjectActionHandler extends AbstractHandler {
    public static final Object FAMILY = new Object();
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        IResource resource = null;
        if (HandlerUtil.getCurrentSelection(event) instanceof IStructuredSelection selection && selection.getFirstElement() instanceof IAdaptable adaptable)
            resource = adaptable.getAdapter(IResource.class);
        if (resource == null && HandlerUtil.getActiveEditor(event) != null) resource = HandlerUtil.getActiveEditor(event).getEditorInput().getAdapter(IResource.class);
        if (resource == null) { MessageDialog.openInformation(HandlerUtil.getActiveShell(event), "Zephyr", "Select a Zephyr project first."); return null; }
        IProject project = resource.getProject();
        String action = event.getCommand().getId().substring(event.getCommand().getId().lastIndexOf('.') + 1);
        if (!Set.of("run", "flash", "debugserver").contains(action)) return null;
        try {
            var settings = ProjectContext.settings(project); Path build = ProjectContext.buildDirectory(project, settings);
            var plan = io.github.niwantha33.zew.core.TargetOperations.prepare(
                    build, settings.getProperty("board"), action, "");
            if (action.equals("flash") && !MessageDialog.openConfirm(HandlerUtil.getActiveShell(event), "Flash " + project.getName(), "Program the connected board using the build's default runner for " + settings.getProperty("board") + "?")) return null;
            var job = new WorkspaceJob("Zephyr " + action + " — " + project.getName()) {
                @Override public boolean belongsTo(Object family) { return family == FAMILY; }
                @Override public IStatus runInWorkspace(IProgressMonitor monitor) {
                    var console = new MessageConsole("Zephyr " + action + " — " + project.getName(), null);
                    ConsolePlugin.getDefault().getConsoleManager().addConsoles(new IConsole[] {console}); ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
                    try (var stream = console.newMessageStream();
                         var lease = TargetOperationGuard.acquire(action)) {
                        monitor.beginTask("Zephyr " + action + " (cancel in Progress view)", IProgressMonitor.UNKNOWN);
                        // Revalidate after acquiring the physical guard: queued jobs must
                        // not flash a firmware image replaced while they were waiting.
                        var currentPlan = io.github.niwantha33.zew.core.TargetOperations.prepare(
                                plan.buildDirectory(), plan.board(), action, "");
                        stream.println("Exact board: " + plan.board());
                        stream.println("Verified build: " + currentPlan.buildDirectory());
                        stream.println("Runner: Zephyr build's default (resolved by West; probe not independently verified)");
                        if (action.equals("debugserver")) stream.println("External GDB server only. Eclipse source-level debugging is not yet available.");
                        new ProcessRunner().run(currentPlan.command(settings.getProperty("python")),
                            Path.of(settings.getProperty("workspace")), Duration.ofHours(8), monitor::isCanceled,
                            stream::println, ProjectContext.environment(settings)).requireSuccess();
                        stream.println(action.equals("flash") ? "West flash command completed successfully; independent read-back verification was not performed."
                                : "West " + action + " command completed.");
                        return Status.OK_STATUS;
                    } catch (java.io.InterruptedIOException e) { return Status.CANCEL_STATUS; }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return Status.CANCEL_STATUS; }
                    catch (Exception e) { return new Status(IStatus.ERROR, Activator.ID, e.getMessage(), e); }
                    finally { monitor.done(); }
                }
            };
            job.setRule(project); job.setUser(true); job.schedule();
        } catch (Exception error) { MessageDialog.openError(HandlerUtil.getActiveShell(event), "Zephyr " + action, error.getMessage()); }
        return null;
    }
}
