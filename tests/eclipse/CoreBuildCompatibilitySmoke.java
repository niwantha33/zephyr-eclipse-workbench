// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import org.eclipse.cdt.core.CCorePlugin;
import org.eclipse.cdt.core.build.*;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.*;
import org.eclipse.launchbar.core.*;
import org.eclipse.launchbar.core.target.*;
import java.util.*;

/** Exercises the CDT configuration-manager call that previously raised the missing-provider popup. */
public final class CoreBuildCompatibilitySmoke {
    private static final String UI = "io.github.niwantha33.zew.ui";
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        System.out.println("PASS Core Build: " + message);
    }
    public static void run(org.osgi.framework.Bundle ui) throws Exception {
        var workspace = ResourcesPlugin.getWorkspace();
        var project = workspace.getRoot().getProject("NativeNeedsTarget");
        var manager = CCorePlugin.getService(ICBuildConfigurationManager.class);
        var toolchains = CCorePlugin.getService(IToolChainManager.class).getAllToolChains();
        check(!toolchains.isEmpty(), "host toolchain available to reproduce CDT selection");
        var toolchain = toolchains.iterator().next();
        var target = CCorePlugin.getService(ILaunchTargetManager.class).getLocalLaunchTarget();
        check(manager.supports(project), "West project has a registered CDT configuration provider");
        var selected = new ICBuildConfiguration[1];
        workspace.run(monitor -> selected[0] = manager.getBuildConfiguration(project, toolchain, "run", target, monitor),
                workspace.getRoot(), IWorkspace.AVOID_UPDATE, new NullProgressMonitor());
        var config = selected[0];
        check(config != null && config.getClass().getName().equals(UI + ".ZephyrCoreBuildConfiguration"),
                "native run/toolchain selection resolves to West instead of missing provider");
        check(((ICBuildConfiguration2) config).getBuildDirectoryURI().getPath().contains("/.zew-build"),
                "Core Build uses managed Zephyr outputs rather than host CMake output");
        var pendingInfo = config.getScannerInformation(project.getFile("src/main.c"));
        check(pendingInfo != null && pendingInfo.getDefinedSymbols().isEmpty() && pendingInfo.getIncludePaths().length == 0,
                "unconfigured Core Build scanner does not probe the desktop compiler");
        config.build(IncrementalProjectBuilder.FULL_BUILD, Map.of(), null, new NullProgressMonitor());
        check(project.findMarkers(UI + ".configurationProblem", false, IResource.DEPTH_ZERO).length == 1,
                "native configuration waits for missing board without invoking host compiler");
        var launchbar = CCorePlugin.getService(ILaunchBarManager.class);
        var errors = Collections.synchronizedList(new ArrayList<IStatus>());
        var family = org.eclipse.cdt.debug.core.launch.CoreBuildLaunchBarTracker.JOB_FAMILY_CORE_BUILD_LAUNCH_BAR_TRACKER;
        var observer = new JobChangeAdapter() {
            @Override public void done(IJobChangeEvent event) {
                if (event.getJob().belongsTo(family) && event.getResult() != null && !event.getResult().isOK()) errors.add(event.getResult());
            }
        };
        Job.getJobManager().addJobChangeListener(observer);
        try {
            workspace.run(monitor -> {
                try {
                    var description = project.getDescription();
                    description.setNatureIds(new String[] {"org.eclipse.cdt.core.cnature", "org.eclipse.cdt.cmake.core.cmakeNature"});
                    var command = description.newCommand(); command.setBuilderName("org.eclipse.cdt.core.cBuilder");
                    description.setBuildSpec(new ICommand[] {command}); project.setDescription(description, monitor);
                    launchbar.launchObjectChanged(project);
                    var descriptor = Arrays.stream(launchbar.getLaunchDescriptors()).filter(d -> project.equals(d.getAdapter(IProject.class))).findFirst().orElseThrow();
                    launchbar.setActiveLaunchDescriptor(descriptor);
                    launchbar.setActiveLaunchTarget(target);
                    // A queued CDT configuration job cannot acquire the root rule until after conversion.
                    ui.loadClass(UI + ".ZephyrProjectImport").getMethod("attach", IProject.class, IProgressMonitor.class)
                            .invoke(null, project, monitor);
                } catch (Exception error) { throw new CoreException(new Status(IStatus.ERROR, "zew.editor.smoke", "CDT migration race", error)); }
            }, workspace.getRoot(), IWorkspace.AVOID_UPDATE, new NullProgressMonitor());
            Job.getJobManager().join(family, new NullProgressMonitor());
            check(errors.isEmpty(), "queued Change Build Configurations jobs survive CMake-to-West conversion: " + errors);
        } finally { Job.getJobManager().removeJobChangeListener(observer); }
        verifyRestored();
    }
    public static void verifyRestored() throws Exception {
        var project = ResourcesPlugin.getWorkspace().getRoot().getProject("NativeNeedsTarget");
        var manager = CCorePlugin.getService(ICBuildConfigurationManager.class);
        int count = 0;
        for (var config : project.getBuildConfigs()) if (config.getName().startsWith(UI + ".westProvider/")) {
            check(manager.getBuildConfiguration(config) != null, "saved West Core Build configuration reloads"); count++;
        }
        check(count > 0, "at least one persisted native configuration verified");
    }
}
