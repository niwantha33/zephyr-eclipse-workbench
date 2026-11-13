// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Capture-to-analysis navigation only. Project discovery and directory enumeration
 * never connect to a target, start GDB, decode a trace or reset a board.
 */
final class TraceCaptureWorkflow {
    private TraceCaptureWorkflow() {}
    private static final int MAX_CAPTURE_DIRS = 512;

    static IProject selectedProject(IWorkbenchWindow window) {
        if (window == null) return null;
        var selection = window.getSelectionService().getSelection();
        if (!(selection instanceof IStructuredSelection rows) || rows.size() != 1) return null;
        return SelectedProject.importedProject(rows.getFirstElement());
    }

    static IProject activeDebugProject(IWorkbenchWindow window) {
        if (window == null) return null;
        var selection = DebugUITools.getDebugContextManager()
                .getContextService(window).getActiveContext();
        if (!(selection instanceof IStructuredSelection rows) || rows.size() != 1) return null;
        return SelectedProject.importedProject(rows.getFirstElement());
    }

    /** Unique-live-launch fallback, never first-project guessing. */
    static IProject uniqueDebugProject() {
        IProject found = null;
        for (var launch : DebugPlugin.getDefault().getLaunchManager().getLaunches()) {
            if (launch.isTerminated() || launch.getLaunchConfiguration() == null) continue;
            try {
                var config = launch.getLaunchConfiguration();
                if (!ZephyrLaunchDelegate.TYPE.equals(config.getType().getIdentifier())) continue;
                String name = config.getAttribute(ZephyrLaunchDelegate.PROJECT, "");
                if (name.isBlank()) continue;
                var project = SelectedProject.importedProject(
                        ResourcesPlugin.getWorkspace().getRoot().getProject(name));
                if (project == null) continue;
                if (found != null && !found.equals(project)) return null;
                found = project;
            } catch (org.eclipse.core.runtime.CoreException ignored) {
                // A launch without verifiable project metadata cannot be used.
            }
        }
        return found;
    }

    static IProject resolve(IWorkbenchWindow window) {
        var active = activeDebugProject(window);
        if (active != null) return active;
        var explicit = selectedProject(window);
        return explicit != null ? explicit : uniqueDebugProject();
    }

    static boolean isSavedCtf(Path folder) {
        if (folder == null || !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return false;
        return Files.isRegularFile(folder.resolve("metadata"), LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(folder.resolve("channel0_0"), LinkOption.NOFOLLOW_LINKS);
    }

    /** Only project-owned, saved, bounded evidence; never guess from another project. */
    static Optional<Path> latest(IProject project) throws IOException {
        if (project == null || !project.isAccessible() || project.getLocation() == null)
            return Optional.empty();
        return latest(project.getLocation().toFile().toPath());
    }

    /** Pure file-only overload for deterministic, probe-free capture regression tests. */
    static Optional<Path> latest(Path application) throws IOException {
        if (application == null) return Optional.empty();
        Path root = application.resolve(".zew-traces");
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        Path latest = null;
        FileTime newest = FileTime.fromMillis(0);
        int seen = 0;
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(root, "trace-*")) {
            for (Path dir : directories) {
                if (++seen > MAX_CAPTURE_DIRS)
                    throw new IOException("Too many saved trace directories (more than "
                            + MAX_CAPTURE_DIRS + "). Choose the exact capture manually.");
                if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue;
                Path ctf = dir.resolve("ctf");
                if (!isSavedCtf(ctf)) continue;
                FileTime modified = Files.getLastModifiedTime(dir, LinkOption.NOFOLLOW_LINKS);
                if (latest == null || modified.compareTo(newest) > 0) {
                    latest = ctf;
                    newest = modified;
                }
            }
        }
        return Optional.ofNullable(latest);
    }

    /** Called only after the user's explicit, successful RAM CTF capture. */
    static void captured(IProject project, Path capture) {
        if (project == null || capture == null || !isSavedCtf(capture.resolve("ctf"))) return;
        if (!PlatformUI.isWorkbenchRunning()) return;
        for (var window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
            if (window.getActivePage() == null) continue;
            var page = window.getActivePage();
            var timeline = page.findView(ThreadTimelineView.ID);
            if (timeline instanceof ThreadTimelineView view) {
                view.displayProject(project);
                view.loadSavedCapture(capture.resolve("ctf"));
            }
            var cpu = page.findView(ZephyrCpuLoadView.ID);
            if (cpu instanceof ZephyrCpuLoadView view) {
                view.displayProject(project);
                view.loadSavedCapture(capture.resolve("ctf"));
            }
        }
    }
}
