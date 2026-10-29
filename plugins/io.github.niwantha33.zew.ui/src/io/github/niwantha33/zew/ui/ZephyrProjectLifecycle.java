// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import io.github.niwantha33.zew.core.ZephyrApplicationDiscovery;

/** File-system import, normal project open and native Build share one automatic configuration path. */
public final class ZephyrProjectLifecycle implements IResourceChangeListener {
    static final String MARKER = Activator.ID + ".configurationProblem";
    static final String ENVIRONMENT = "project.defaultEnvironment";
    private boolean configuring;

    public static boolean supports(IProject project) {
        try {
            if (project == null || !project.isAccessible() || project.getLocation() == null) return false;
            if (!ZephyrApplicationDiscovery.isApplication(project.getLocation().toFile().toPath())) return false;
            ZephyrProjectImport.validate(project.getDescription());
            return true;
        } catch (Exception unavailable) { return false; }
    }

    /** Only a unique completed environment is selected automatically; boards are never inherited. */
    public static Path environment(IProject project) {
        try {
            String own = project.getPersistentProperty(ZephyrProjectImport.SETUP);
            if (own != null && ready(Path.of(own))) return Path.of(own);
            Set<Path> candidates = new LinkedHashSet<>();
            var plugin = Activator.getDefault();
            if (plugin != null) {
                String selected = plugin.getPreferenceStore().getString(ENVIRONMENT);
                if (!selected.isBlank() && ready(Path.of(selected))) return Path.of(selected);
                String workspace = plugin.getPreferenceStore().getString(WorkbenchPreferences.WORKSPACE);
                if (!workspace.isBlank()) add(candidates, Path.of(workspace).getParent());
            }
            for (IProject other : project.getWorkspace().getRoot().getProjects()) {
                if (!other.isAccessible()) continue;
                String value = other.getPersistentProperty(ZephyrProjectImport.SETUP);
                if (value != null && !value.isBlank()) add(candidates, Path.of(value));
            }
            return candidates.size() == 1 ? candidates.iterator().next() : null;
        } catch (Exception unavailable) { return null; }
    }
    private static void add(Set<Path> candidates, Path root) throws Exception {
        if (root != null && ready(root)) candidates.add(root.toRealPath());
    }
    private static boolean ready(Path root) {
        try { ProjectContext.load(root); return true; } catch (Exception unavailable) { return false; }
    }

    /** No external tools, downloads, source edits or board guesses during import. */
    public static boolean configure(IProject project, IProgressMonitor monitor) throws Exception {
        if (!supports(project)) return false;
        ZephyrProjectImport.attach(project, monitor);
        Path root = environment(project);
        if (root != null && !root.toString().equals(project.getPersistentProperty(ZephyrProjectImport.SETUP)))
            project.setPersistentProperty(ZephyrProjectImport.SETUP, root.toString());
        try {
            ProjectContext.settings(project);
            project.deleteMarkers(MARKER, false, IResource.DEPTH_ZERO);
            return true;
        } catch (Exception missing) {
            String message = "Select the Zephyr environment and exact board in Project Properties > Zephyr. " + missing.getMessage();
            IMarker[] markers = project.findMarkers(MARKER, false, IResource.DEPTH_ZERO);
            if (markers.length != 1 || !message.equals(markers[0].getAttribute(IMarker.MESSAGE, ""))) {
                project.deleteMarkers(MARKER, false, IResource.DEPTH_ZERO);
                var marker = project.createMarker(MARKER);
                marker.setAttributes(new String[] {IMarker.MESSAGE, IMarker.SEVERITY},
                        new Object[] {message, IMarker.SEVERITY_ERROR});
            }
            return false;
        }
    }

    @Override public void resourceChanged(IResourceChangeEvent event) {
        // PRE_BUILD explicitly permits resource changes on the notifying thread.
        // This also runs with auto-build disabled, after import has finished.
        if (configuring || event.getType() != IResourceChangeEvent.PRE_BUILD) return;
        configuring = true;
        try {
            IProject[] projects = event.getSource() instanceof IProject project ? new IProject[] {project}
                    : ResourcesPlugin.getWorkspace().getRoot().getProjects();
            for (IProject project : projects) {
                try { configure(project, new NullProgressMonitor()); }
                catch (Exception error) { log(error); }
            }
        } finally { configuring = false; }
    }

    static void log(Exception error) {
        var plugin = Activator.getDefault();
        if (plugin != null) plugin.getLog().log(new Status(IStatus.ERROR, Activator.ID,
                "Automatic Zephyr project configuration failed: " + error.getMessage(), error));
    }

    public static void scheduleOpenProjects() {
        var job = new WorkspaceJob("Configure open Zephyr projects") {
            @Override public IStatus runInWorkspace(IProgressMonitor monitor) {
                for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    try { configure(project, monitor); } catch (Exception error) { log(error); }
                }
                return Status.OK_STATUS;
            }
        };
        job.setRule(ResourcesPlugin.getWorkspace().getRoot());
        job.schedule();
    }
}
