// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import io.github.niwantha33.zew.core.ProjectProfile;

/** Exercises real Eclipse descriptions and workspace resources, without running firmware tools. */
public final class ProjectImportSmoke {
    private static final String UI = "io.github.niwantha33.zew.ui";
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        System.out.println("PASS import: " + message);
    }
    public static void run(org.osgi.framework.Bundle ui, Path temporary) throws Exception {
        var workspace = ResourcesPlugin.getWorkspace();
        Path environment = Files.createDirectories(temporary.resolve("import-environment"));
        Files.writeString(environment.resolve(".zew-ready"), "ready\n");
        Files.writeString(environment.resolve("environment.properties"),
                "python=/unused/python\nworkspace=/unused/workspace\ntools.sdk=/unused/sdk\n"
                + "tools.cmake=/unused/cmake\ntools.ninja=/unused/ninja\ntools.git=/unused/git\n");
        var bind = ui.loadClass(UI + ".ZephyrProjectImport").getMethod("bind", Path.class,
                String.class, String.class, Path.class, IProgressMonitor.class);
        Path folder = application(temporary, "GenericCMakeRepair");
        String generic = "<?xml version=\"1.0\"?><projectDescription><name>GenericCMakeRepair</name>"
                + "<comment>preserve my project comment</comment><projects></projects>"
                + "<buildSpec><buildCommand><name>org.eclipse.cdt.core.cBuilder</name><arguments/></buildCommand></buildSpec>"
                + "<natures><nature>org.eclipse.cdt.core.cnature</nature><nature>org.eclipse.cdt.cmake.core.cmakeNature</nature></natures>"
                + "<buildConfigs><buildConfig name=\"default\"/><buildConfig name=\"cmake.run.win32.x86_64.Local\"/></buildConfigs></projectDescription>";
        Files.writeString(folder.resolve(".project"), generic);
        Files.writeString(folder.resolve(".cproject"), "<?xml version=\"1.0\"?><cproject/>");
        Files.createDirectories(folder.resolve("build/cmake.run.win32.x86_64.Local"));
        Files.writeString(folder.resolve("build/cmake.run.win32.x86_64.Local/CMakeCache.txt"), "old generic cache");
        String source = Files.readString(folder.resolve("CMakeLists.txt"));
        workspace.run(monitor -> {
            try {
                IProject repaired = (IProject) bind.invoke(null, folder, "GenericCMakeRepair", "qemu_x86/atom", environment, monitor);
                check(repaired.getDescription().getBuildSpec().length == 1
                        && repaired.getDescription().getBuildSpec()[0].getBuilderName().equals(UI + ".zephyrBuilder"), "generic CMake replaced by sole West builder: " + Arrays.toString(Arrays.stream(repaired.getDescription().getBuildSpec()).map(ICommand::getBuilderName).toArray()));
                check(!Arrays.asList(repaired.getDescription().getNatureIds()).contains("org.eclipse.cdt.cmake.core.cmakeNature"), "CMake build provider nature removed");
                check(repaired.getBuildConfigs().length == 1 && repaired.getActiveBuildConfig().getName().equals("default"), "native build no longer selects cmake.run.win32 configuration");
                check(repaired.getPersistentProperty(new QualifiedName(UI, "setupRoot")).equals(environment.toString()), "ready environment bound to project");
                check(repaired.getDescription().getComment().equals("preserve my project comment"), "project comment preserved");
                check(Files.readString(folder.resolve(".zew/import-backup/.project")).equals(generic), "original CMake Eclipse metadata backed up");
                check(Files.readString(folder.resolve("CMakeLists.txt")).equals(source)
                        && Files.readString(folder.resolve("prj.conf")).equals("# keep my config\n"), "CMake and application configuration unchanged");
                check(Files.readString(folder.resolve("build/cmake.run.win32.x86_64.Local/CMakeCache.txt")).equals("old generic cache"), "old generic build files retained");
                Path liveFolder = application(temporary, "LiveGenericCMakeRepair");
                Files.writeString(liveFolder.resolve(".project"), generic.replace("GenericCMakeRepair", "LiveGenericCMakeRepair"));
                var live = workspace.getRoot().getProject("LiveGenericCMakeRepair");
                var liveDescription = workspace.loadProjectDescription(new org.eclipse.core.runtime.Path(liveFolder.resolve(".project").toString()));
                liveDescription.setBuildConfigs(new String[] {"default", "cmake.run.win32.x86_64.Local"});
                liveDescription.setActiveBuildConfig("cmake.run.win32.x86_64.Local");
                live.create(liveDescription, monitor);
                live.open(monitor);
                live.setDescription(liveDescription, monitor);
                check(live.getActiveBuildConfig().getName().equals("cmake.run.win32.x86_64.Local"), "fixture reproduces active native CMake build configuration");
                IProject liveRepair = (IProject) bind.invoke(null, liveFolder, live.getName(), "qemu_x86/atom", environment, monitor);
                check(live.equals(liveRepair) && !Arrays.asList(live.getDescription().getNatureIds()).contains("org.eclipse.cdt.cmake.core.cmakeNature")
                        && live.getDescription().getBuildSpec()[0].getBuilderName().equals(UI + ".zephyrBuilder")
                        && live.getActiveBuildConfig().getName().equals("default"), "already open generic CMake project converted in place");
                // A project already present through generic File System import must bind in place.
                Path existingFolder = application(temporary, "AlreadyImportedZephyr");
                var existing = workspace.getRoot().getProject("AlreadyImportedZephyr");
                var description = workspace.newProjectDescription(existing.getName());
                description.setLocationURI(existingFolder.toUri());
                existing.create(description, monitor); existing.open(monitor);
                new ProjectProfile("qemu_x86/atom").write(existingFolder);
                IProject rebound = (IProject) bind.invoke(null, existingFolder, existing.getName(), "qemu_x86/atom", environment, monitor);
                check(existing.equals(rebound) && existing.isOpen(), "existing workspace project repaired without deletion or reimport");
                String saved = Files.readString(existingFolder.resolve(".zew/project.json"));
                bind.invoke(null, existingFolder, existing.getName(), "qemu_x86/atom", environment, monitor);
                check(Files.readString(existingFolder.resolve(".zew/project.json")).equals(saved), "repeat binding preserves saved target");
                try {
                    bind.invoke(null, existingFolder, existing.getName(), "different_board", environment, monitor);
                    throw new AssertionError("saved target overwritten");
                } catch (java.lang.reflect.InvocationTargetException expected) {
                    check(Files.readString(existingFolder.resolve(".zew/project.json")).equals(saved), "changed saved target rejected before mutation");
                }
                // Do not take ownership of an unrelated vendor builder, even on a Zephyr source tree.
                Path vendor = application(temporary, "VendorBuildPreserved");
                Files.writeString(vendor.resolve(".project"), generic.replace("GenericCMakeRepair", "VendorBuildPreserved")
                        .replace("org.eclipse.cdt.core.cBuilder", "vendor.custom.builder"));
                String before = Files.readString(vendor.resolve(".project"));
                try {
                    bind.invoke(null, vendor, "VendorBuildPreserved", "qemu_x86/atom", environment, monitor);
                    throw new AssertionError("vendor builder replaced");
                } catch (java.lang.reflect.InvocationTargetException expected) {
                    check(Files.readString(vendor.resolve(".project")).equals(before)
                            && !Files.exists(vendor.resolve(".zew")), "unrelated vendor builder rejected without metadata changes");
                }
            } catch (Exception error) { throw new CoreException(new Status(IStatus.ERROR, "zew.editor.smoke", "Import repair checks failed", error)); }
        }, workspace.getRoot(), IWorkspace.AVOID_UPDATE, new NullProgressMonitor());
    }
    private static Path application(Path parent, String name) throws Exception {
        Path folder = Files.createDirectories(parent.resolve(name));
        Files.writeString(folder.resolve("CMakeLists.txt"), "cmake_minimum_required(VERSION 3.20)\nfind_package(Zephyr REQUIRED)\nproject(test)\n");
        Files.writeString(folder.resolve("prj.conf"), "# keep my config\n");
        return folder;
    }
}
