// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.cdt.core.build.CBuildConfiguration;
import org.eclipse.cdt.core.build.IToolChain;
import org.eclipse.cdt.core.resources.IConsole;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.launchbar.core.target.ILaunchTarget;

/** CDT launch metadata never selects the firmware compiler; West owns board, SDK and optimization settings. */
public final class ZephyrCoreBuildConfiguration extends CBuildConfiguration {
    ZephyrCoreBuildConfiguration(IBuildConfiguration config, String name) throws CoreException { super(config, name); }
    ZephyrCoreBuildConfiguration(IBuildConfiguration config, String name, IToolChain toolchain, String mode, ILaunchTarget target) {
        super(config, name, toolchain, mode, target);
    }
    @Override public IProject[] build(int kind, Map<String, String> arguments, IConsole console, IProgressMonitor monitor) throws CoreException {
        getProject().build(kind, ZephyrProjectImport.BUILDER, arguments == null ? Map.of() : arguments, monitor);
        return new IProject[0];
    }
    @Override public void clean(IConsole console, IProgressMonitor monitor) throws CoreException {
        getProject().build(IncrementalProjectBuilder.CLEAN_BUILD, ZephyrProjectImport.BUILDER, Map.of(), monitor);
    }
    @Override public Path getBuildDirectory() {
        try { return ProjectContext.buildDirectory(getProject(), ProjectContext.settings(getProject())); }
        catch (Exception pending) { return getProject().getLocation().toFile().toPath().resolve(".zew-build"); }
    }
    @Override public URI getBuildDirectoryURI() { return getBuildDirectory().toUri(); }
    @Override public IContainer getBuildContainer() {
        Path relative = getProject().getLocation().toFile().toPath().relativize(getBuildDirectory());
        return getProject().getFolder(new org.eclipse.core.runtime.Path(relative.toString()));
    }
    @Override public List<String> getBinaryParserIds() { return List.of("org.eclipse.cdt.core.ELF"); }
}
