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
            if (!Files.isRegularFile(build.resolve("CMakeCache.txt"))) throw new java.io.IOException("Build the selected project before running it.");
            Path receipt = build.resolve(".zew-built-target");
            if (!Files.isRegularFile(receipt) || !Files.readString(receipt).equals(settings.getProperty("board"))) throw new java.io.IOException("Build this project's current target once before using Run, Flash or Debug Server.");
            if (action.equals("run") && !settings.getProperty("board").startsWith("qemu_")) throw new java.io.IOException("This build has no detected QEMU emulator. Use Flash for a physical board.");
            if (!action.equals("run") && !Files.isRegularFile(build.resolve("zephyr/runners.yaml"))) throw new java.io.IOException("This build has no runner configuration. Build a physical board target first.");
            if (action.equals("flash") && !MessageDialog.openConfirm(HandlerUtil.getActiveShell(event), "Flash " + project.getName(), "Program the connected board using the build's default runner for " + settings.getProperty("board") + "?")) return null;
            var job = new WorkspaceJob("Zephyr " + action + " — " + project.getName()) {
                @Override public boolean belongsTo(Object family) { return family == FAMILY; }
                @Override public IStatus runInWorkspace(IProgressMonitor monitor) {
                    var console = new MessageConsole("Zephyr " + action + " — " + project.getName(), null);
                    ConsolePlugin.getDefault().getConsoleManager().addConsoles(new IConsole[] {console}); ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
                    try (var stream = console.newMessageStream()) {
                        var command = new ArrayList<>(List.of(settings.getProperty("python"), "-m", "west"));
                        if (action.equals("run")) command.addAll(List.of("build", "--build-dir", build.toString(), "--target", "run"));
                        else command.addAll(List.of(action, "--build-dir", build.toString()));
                        monitor.beginTask("Zephyr " + action + " (cancel in Progress view)", IProgressMonitor.UNKNOWN);
                        if (action.equals("debugserver")) stream.println("Starting the default board debug server. Connect a compatible GDB client separately; Eclipse stepping/variables integration is pending.");
                        new ProcessRunner().run(command, Path.of(settings.getProperty("workspace")), Duration.ofHours(8), monitor::isCanceled, stream::println, ProjectContext.environment(settings)).requireSuccess();
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
