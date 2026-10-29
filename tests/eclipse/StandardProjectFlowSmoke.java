// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.Job;
import io.github.niwantha33.zew.core.ProjectProfile;

/** Uses Eclipse's own File System importer and Archive exporter, with CDT CMake also installed. */
public final class StandardProjectFlowSmoke {
    private static final String UI = "io.github.niwantha33.zew.ui";
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        System.out.println("PASS standard flow: " + message);
    }
    public static void run(org.osgi.framework.Bundle ui, Path temporary) throws Exception {
        var workspace = ResourcesPlugin.getWorkspace();
        var ide = Platform.getBundle("org.eclipse.ui.ide");
        var contribution = Arrays.stream(Platform.getExtensionRegistry().getConfigurationElementsFor(
                "org.eclipse.ui.ide.projectConfigurators"))
                .filter(element -> (UI + ".ZephyrProjectConfigurator").equals(element.getAttribute("class")))
                .findFirst().orElseThrow();
        check(contribution.createExecutableExtension("class") != null, "Zephyr detector registered with native File System importer");
        check(Platform.getBundle("org.eclipse.cdt.cmake.ui") != null, "competing generic CMake configurator installed during test");
        Path saved = application(temporary, "NativeSavedTarget");
        new ProjectProfile("qemu_x86/atom").write(saved);
        Path fresh = application(temporary, "NativeNeedsTarget");
        Path ordinary = Files.createDirectories(temporary.resolve("NativeOrdinaryCMake"));
        Files.writeString(ordinary.resolve("CMakeLists.txt"), "cmake_minimum_required(VERSION 3.20)\nproject(ordinary)\n");
        nativeImport(ide, temporary, Set.of(saved.toFile(), fresh.toFile(), ordinary.toFile()));
        settle(workspace);
        IProject imported = workspace.getRoot().getProject("NativeSavedTarget");
        IProject pending = workspace.getRoot().getProject("NativeNeedsTarget");
        assertWest(imported); assertWest(pending);
        check(imported.getPersistentProperty(new QualifiedName(UI, "setupRoot")) != null, "completed local environment rebound automatically");
        check(ProjectProfile.read(saved).board().equals("qemu_x86/atom"), "native import preserves saved board");
        check(!Files.exists(fresh.resolve(".zew/project.json")), "new application never inherits another project's board");
        check(pending.findMarkers(UI + ".configurationProblem", false, IResource.DEPTH_ZERO).length == 1,
                "missing target produces one normal project configuration marker");
        var generic = workspace.getRoot().getProject("NativeOrdinaryCMake");
        check(generic.hasNature("org.eclipse.cdt.cmake.core.cmakeNature"), "ordinary CMake project retains native CMake workflow");
        // Simulate a later generic configurator replacing our settings, then a normal build notification.
        workspace.run(monitor -> {
            var description = pending.getDescription();
            description.setNatureIds(new String[] {"org.eclipse.cdt.core.cnature", "org.eclipse.cdt.cmake.core.cmakeNature"});
            var command = description.newCommand(); command.setBuilderName("org.eclipse.cdt.core.cBuilder");
            description.setBuildSpec(new ICommand[] {command}); pending.setDescription(description, monitor);
        }, workspace.getRoot(), IWorkspace.AVOID_UPDATE, null);
        pending.build(IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor());
        settle(workspace);
        assertWest(pending);
        check(pending.findMarkers("org.eclipse.cdt.cmake.core.cmakeproblem", true, IResource.DEPTH_INFINITE).length == 0,
                "native Build waits for target selection without invoking generic CMake");
        check(true, "generic configurator ordering corrected automatically before builds");

        Path archive = temporary.resolve("native-project.zip");
        var exporterType = ide.loadClass("org.eclipse.ui.internal.wizards.datatransfer.ArchiveFileExportOperation");
        Object exporter = exporterType.getConstructor(IResource.class, String.class).newInstance(imported, archive.toString());
        exporterType.getMethod("setCreateLeadupStructure", boolean.class).invoke(exporter, true);
        exporterType.getMethod("run", IProgressMonitor.class).invoke(exporter, new NullProgressMonitor());
        check(((IStatus) exporterType.getMethod("getStatus").invoke(exporter)).isOK(), "standard Eclipse Archive export completes");
        Path extracted = Files.createDirectories(temporary.resolve("archive-round-trip"));
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            check(zip.getEntry("NativeSavedTarget/.project") != null
                    && zip.getEntry("NativeSavedTarget/.zew/project.json") != null
                    && zip.getEntry("NativeSavedTarget/src/main.c") != null, "Archive contains portable project, board and sources");
            for (var entries = zip.entries(); entries.hasMoreElements();) {
                var entry = entries.nextElement(); Path destination = extracted.resolve(entry.getName()).normalize();
                if (!destination.startsWith(extracted)) throw new AssertionError("invalid archive entry");
                if (entry.isDirectory()) Files.createDirectories(destination);
                else { Files.createDirectories(destination.getParent()); try (var input = zip.getInputStream(entry)) { Files.copy(input, destination); } }
            }
        }
        imported.delete(false, true, null);
        Path restored = extracted.resolve("NativeSavedTarget");
        nativeImport(ide, extracted, Set.of(restored.toFile()));
        settle(workspace);
        var reopened = workspace.getRoot().getProject("NativeSavedTarget");
        assertWest(reopened);
        check(ProjectProfile.read(restored).board().equals("qemu_x86/atom")
                && reopened.getPersistentProperty(new QualifiedName(UI, "setupRoot")) != null,
                "exported project reopens through native importer and rebinds local environment without repair");
    }
    private static void assertWest(IProject project) throws Exception {
        check(project.isAccessible() && !project.hasNature("org.eclipse.cdt.cmake.core.cmakeNature")
                && project.getDescription().getBuildSpec().length == 1
                && project.getDescription().getBuildSpec()[0].getBuilderName().equals(UI + ".zephyrBuilder"),
                "native import selects sole West builder for " + project.getName());
    }
    private static void nativeImport(org.osgi.framework.Bundle ide, Path root, Set<java.io.File> folders) throws Exception {
        var type = ide.loadClass("org.eclipse.ui.internal.wizards.datatransfer.SmartImportJob");
        Object job = type.getConstructor(java.io.File.class, Set.class, boolean.class, boolean.class)
                .newInstance(root.toFile(), null, true, true);
        type.getMethod("setDirectoriesToImport", Set.class).invoke(job, folders);
        ((Job) job).schedule(); ((Job) job).join();
        check(((Job) job).getResult().isOK(), "native File System import job completes");
        Map<?, ?> errors = (Map<?, ?>) type.getMethod("getErrors").invoke(job);
        for (Object error : errors.values()) if (error instanceof Throwable cause) cause.printStackTrace();
        check(errors.isEmpty(), "native import reports no project errors: " + errors);
    }
    private static void settle(IWorkspace workspace) throws Exception {
        // Empty build scope fires real PRE_BUILD notifications without executing any firmware tools.
        workspace.build(new IBuildConfiguration[0], IncrementalProjectBuilder.FULL_BUILD, false, new NullProgressMonitor());
        Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, new NullProgressMonitor());
    }
    private static Path application(Path root, String name) throws Exception {
        Path folder = Files.createDirectories(root.resolve(name).resolve("src")).getParent();
        Files.writeString(folder.resolve("CMakeLists.txt"), "cmake_minimum_required(VERSION 3.20)\nfind_package(Zephyr REQUIRED)\nproject(test)\n");
        Files.writeString(folder.resolve("src/main.c"), "int main(void) { return 0; }\n");
        Files.writeString(folder.resolve("prj.conf"), "# portable application\n");
        return folder;
    }
}
