// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.QualifiedName;
import io.github.niwantha33.zew.core.ProjectProfile;
import io.github.niwantha33.zew.core.ZephyrApplicationDiscovery;
import io.github.niwantha33.zew.core.ZephyrProjectFiles;

/** Native and dedicated imports share the same validation and West builder binding. */
public final class ZephyrProjectImport {
    static final String BUILDER = Activator.ID + ".zephyrBuilder";
    static final String CMAKE_NATURE = "org.eclipse.cdt.cmake.core.cmakeNature";
    static final String CMAKE_BUILDER = "org.eclipse.cdt.core.cBuilder";
    static final QualifiedName SETUP = new QualifiedName(Activator.ID, "setupRoot");

    private ZephyrProjectImport() { }

    static IProject existingProject(Path folder) throws IOException {
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (project.getLocation() != null && Files.exists(project.getLocation().toFile().toPath())
                    && Files.isSameFile(folder, project.getLocation().toFile().toPath())) return project;
        }
        return null;
    }

    static boolean isCMake(IProjectDescription description) {
        return Arrays.asList(description.getNatureIds()).contains(CMAKE_NATURE);
    }

    static void validate(IProjectDescription description) throws IOException {
        for (ICommand command : description.getBuildSpec()) {
            if (!BUILDER.equals(command.getBuilderName())
                    && !(isCMake(description) && CMAKE_BUILDER.equals(command.getBuilderName())))
                throw new IOException("Other Eclipse builder present; this project retains its native workflow.");
        }
    }

    static IProjectDescription description(Path folder) throws CoreException, IOException {
        IProject existing = existingProject(folder);
        if (existing != null && !existing.isOpen()) throw new IOException("Open this project in Eclipse before configuring it.");
        var description = existing != null ? existing.getDescription() : ResourcesPlugin.getWorkspace()
                .loadProjectDescription(new org.eclipse.core.runtime.Path(folder.resolve(".project").toString()));
        validate(description);
        return description;
    }

    /** Caller holds the workspace root scheduling rule, as in the import wizard. */
    public static IProject bind(Path folder, String name, String board, Path environment,
            IProgressMonitor monitor) throws Exception {
        if (!ZephyrApplicationDiscovery.isApplication(folder)) throw new IOException("Not a Zephyr application: " + folder);
        if (Files.isSymbolicLink(folder.resolve(".project")) || Files.isSymbolicLink(folder.resolve(".zew")))
            throw new IOException("Project metadata is a filesystem link; choose the real application folder.");
        ProjectContext.load(environment);
        // Validate the target before changing Eclipse metadata, including for legacy profiles.
        new ProjectProfile(board);
        IProject existing = existingProject(folder);
        if (existing != null && !existing.getName().equals(name)) throw new IOException("Keep the existing workspace project name: " + existing.getName());
        IProject project = existing != null ? existing : ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        if (existing == null && project.exists()) throw new IOException("Project name already exists: " + name);
        var description = existing != null || Files.isRegularFile(folder.resolve(".project"))
                ? description(folder) : ResourcesPlugin.getWorkspace().newProjectDescription(name);
        boolean cmake = isCMake(description);
        validate(description);
        String savedBoard = Files.isRegularFile(folder.resolve(".zew/project.json")) ? ProjectProfile.read(folder).board()
                : Files.isRegularFile(folder.resolve(".zew/project.properties")) ? ZephyrProjectFiles.readBoard(folder) : "";
        if (!savedBoard.isBlank() && !savedBoard.equals(board)) throw new IOException("Saved board changed; search again: " + folder);
        if (cmake) backup(folder);
        description.setName(name);
        if (existing == null) description.setLocationURI(folder.toUri());
        if (savedBoard.isBlank()) new ProjectProfile(board).write(folder);
        if (existing == null) { project.create(description, monitor); project.open(monitor); }
        attach(project, monitor);
        project.setPersistentProperty(SETUP, environment.toString());
        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
        return project;
    }

    /** Installs the native West workflow even when environment/target selection is pending. */
    public static void attach(IProject project, IProgressMonitor monitor) throws Exception {
        Path folder = project.getLocation().toFile().toPath();
        if (!ZephyrApplicationDiscovery.isApplication(folder)) throw new IOException("Not a Zephyr application: " + folder);
        if (Files.isSymbolicLink(folder.resolve(".project")) || Files.isSymbolicLink(folder.resolve(".zew")))
            throw new IOException("Project metadata is a filesystem link.");
        var description = project.getDescription();
        validate(description);
        boolean cmake = isCMake(description);
        boolean correct = !cmake && java.util.Arrays.asList(description.getNatureIds()).contains(ZephyrProjectNature.ID)
                && description.getBuildSpec().length == 1
                && BUILDER.equals(description.getBuildSpec()[0].getBuilderName());
        if (!correct) {
            if (cmake) backup(folder);
            var natures = new java.util.LinkedHashSet<>(Arrays.asList(description.getNatureIds()));
            natures.remove(CMAKE_NATURE); natures.add(ZephyrProjectNature.ID);
            description.setNatureIds(natures.toArray(String[]::new));
            ICommand command = description.newCommand(); command.setBuilderName(BUILDER);
            description.setBuildSpec(new ICommand[] {command});
            if (cmake) {
                // A queued CDT launch-bar request may already have created the
                // West configuration before this CMake-to-West conversion.
                // Deleting all configurations loses that real native selection
                // and triggers a persisted-configuration failure on reload.
                // Keep only configurations owned by our West provider, never
                // stale generic CMake configurations.
                var nativeConfigs = new java.util.LinkedHashSet<String>();
                nativeConfigs.add("default");
                for (String config : description.getBuildConfigs()) {
                    if (config.startsWith(ZephyrCoreBuildProvider.ID + "/")) nativeConfigs.add(config);
                }
                description.setBuildConfigs(nativeConfigs.toArray(String[]::new));
                description.setActiveBuildConfig(nativeConfigs.contains(description.getActiveBuildConfig())
                        ? description.getActiveBuildConfig() : "default");
            }
            // Apply after open: Eclipse may reload the original .project during open.
            project.setDescription(description, monitor);
        }
        CdtEditor.ensureProject(project, monitor);
        if (cmake) project.deleteMarkers("org.eclipse.cdt.cmake.core.cmakeproblem", true, IResource.DEPTH_INFINITE);
    }

    private static void backup(Path folder) throws IOException {
        Path backup = folder.resolve(".zew/import-backup");
        if (Files.isSymbolicLink(backup)) throw new IOException("Import backup folder is a filesystem link.");
        Files.createDirectories(backup);
        for (String name : new String[] {".project", ".cproject"}) {
            Path source = folder.resolve(name), destination = backup.resolve(name);
            if (Files.isSymbolicLink(source) || Files.isSymbolicLink(destination)) throw new IOException("Project backup metadata is a filesystem link.");
            if (Files.isRegularFile(source) && !Files.exists(destination)) Files.copy(source, destination);
        }
    }
}
