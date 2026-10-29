// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.cdt.core.CCorePlugin;
import org.eclipse.cdt.core.build.*;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.launchbar.core.target.ILaunchTarget;

/** Native CDT configuration selection delegates to West, including stale queued CMake selections. */
public final class ZephyrCoreBuildProvider implements ICBuildConfigurationProvider {
    public static final String ID = Activator.ID + ".westProvider";
    @Override public String getId() { return ID; }
    @Override public ICBuildConfiguration getCBuildConfiguration(IBuildConfiguration config, String name) throws CoreException {
        return IBuildConfiguration.DEFAULT_CONFIG_NAME.equals(config.getName()) ? null : new ZephyrCoreBuildConfiguration(config, name);
    }
    @Override public synchronized ICBuildConfiguration createCBuildConfiguration(IProject project, IToolChain toolchain,
            String mode, ILaunchTarget target, IProgressMonitor monitor) throws CoreException {
        String name = getCBuildConfigName(project, "west", toolchain, mode, target);
        var manager = CCorePlugin.getService(ICBuildConfigurationManager.class);
        var config = CBuildConfigUtils.createBuildConfiguration(this, project, name, manager, monitor);
        var result = new ZephyrCoreBuildConfiguration(config, name, toolchain, mode, target);
        manager.addBuildConfiguration(config, result);
        return result;
    }
}
