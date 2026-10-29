// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.ui.wizards.datatransfer.ProjectConfigurator;
import io.github.niwantha33.zew.core.ZephyrApplicationDiscovery;

/** Native File > Open Projects from File System or Archive contribution; deliberately stateless. */
public final class ZephyrProjectConfigurator implements ProjectConfigurator {
    @Override public Set<File> findConfigurableLocations(File root, IProgressMonitor monitor) {
        Set<File> result = new LinkedHashSet<>();
        try {
            var found = ZephyrApplicationDiscovery.scan(root.toPath(), true, monitor::isCanceled);
            for (var folder : found.applications()) result.add(folder.toFile());
        } catch (java.io.InterruptedIOException cancelled) { throw new OperationCanceledException(); }
        catch (Exception error) { ZephyrProjectLifecycle.log(error); }
        return result;
    }
    @Override public void removeDirtyDirectories(java.util.Map<File, java.util.List<ProjectConfigurator>> proposals) {
        var applications = proposals.keySet().stream().filter(folder -> {
            try { return ZephyrApplicationDiscovery.isApplication(folder.toPath()); }
            catch (Exception unavailable) { return false; }
        }).map(folder -> folder.toPath().toAbsolutePath().normalize()).toList();
        proposals.keySet().removeIf(folder -> {
            var path = folder.toPath().toAbsolutePath().normalize();
            for (var app : applications) if (!path.equals(app) && path.startsWith(app)) {
                String first = app.relativize(path).getName(0).toString();
                if (Set.of("build", ".zew", ".zew-build", ".zew-traces", ".git", ".venv").contains(first)
                        || first.startsWith("build-")) return true;
            }
            return false;
        });
    }
    @Override public boolean shouldBeAnEclipseProject(IContainer container, IProgressMonitor monitor) {
        if (container.getLocation() == null || monitor.isCanceled()) return false;
        try { return ZephyrApplicationDiscovery.isApplication(container.getLocation().toFile().toPath()); }
        catch (Exception unavailable) { return false; }
    }
    @Override public Set<IFolder> getFoldersToIgnore(IProject project, IProgressMonitor monitor) {
        Set<IFolder> folders = new LinkedHashSet<>();
        for (String name : new String[] {".zew", ".zew-build", ".zew-traces", "build", ".git", ".venv"})
            folders.add(project.getFolder(name));
        return folders;
    }
    @Override public boolean canConfigure(IProject project, Set<IPath> ignoredPaths, IProgressMonitor monitor) {
        return !monitor.isCanceled() && ZephyrProjectLifecycle.supports(project);
    }
    @Override public void configure(IProject project, Set<IPath> ignoredPaths, IProgressMonitor monitor) {
        if (monitor.isCanceled()) throw new OperationCanceledException();
        try { ZephyrProjectLifecycle.configure(project, monitor); }
        catch (Exception error) { ZephyrProjectLifecycle.log(error); }
    }
}
