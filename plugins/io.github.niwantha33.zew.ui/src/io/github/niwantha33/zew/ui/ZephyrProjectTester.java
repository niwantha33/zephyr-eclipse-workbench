// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.expressions.PropertyTester;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Adapters;

/** Scope Zephyr actions to relevant project resources, never unrelated files. */
public final class ZephyrProjectTester extends PropertyTester {
    @Override public boolean test(Object receiver, String property, Object[] args, Object expectedValue) {
        boolean result;
        if ("isConfigurationStudioTarget".equals(property)) {
            IResource resource = receiver instanceof IResource direct ? direct : Adapters.adapt(receiver, IResource.class);
            IProject project = resource == null ? null : resource.getProject();
            if (project == null || !ZephyrProjectLifecycle.supports(project)) result = false;
            else if (resource instanceof IProject) result = true;
            else if (resource instanceof IFile file)
                result = io.github.niwantha33.zew.core.ConfigurationStudioResources.contextTarget(
                        file.getProjectRelativePath().toPortableString(), false);
            else result = false;
        } else {
            result = "isZephyrProject".equals(property)
                    ? ZephyrDebugShortcut.project(receiver) != null
                        || ZephyrProjectLifecycle.supports(SelectedProject.fromObject(receiver))
                    : "isImportedProject".equals(property) && ZephyrDebugShortcut.project(receiver) != null;
        }
        return expectedValue == null ? result : result == Boolean.parseBoolean(expectedValue.toString());
    }
}
