// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectNature;

/** Identifies West projects to CDT without retaining the generic CMake provider. */
public final class ZephyrProjectNature implements IProjectNature {
    public static final String ID = Activator.ID + ".zephyrNature";
    private IProject project;
    @Override public IProject getProject() { return project; }
    @Override public void setProject(IProject value) { project = value; }
    @Override public void configure() { }
    @Override public void deconfigure() { }
}
