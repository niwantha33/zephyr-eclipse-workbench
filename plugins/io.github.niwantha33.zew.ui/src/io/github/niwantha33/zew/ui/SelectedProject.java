// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;

/** One project-selection policy for every Zephyr toolbar/menu action. */
final class SelectedProject {
    private SelectedProject() {}

    static IProject from(ExecutionEvent event) {
        IResource resource = null;
        if (HandlerUtil.getCurrentSelection(event) instanceof IStructuredSelection selection) {
            Object selected = selection.getFirstElement();
            if (selected instanceof IResource direct) resource = direct;
            else if (selected instanceof IAdaptable adaptable)
                resource = adaptable.getAdapter(IResource.class);
        }
        if (resource == null && HandlerUtil.getActiveEditor(event) != null)
            resource = HandlerUtil.getActiveEditor(event).getEditorInput().getAdapter(IResource.class);
        return resource == null ? null : resource.getProject();
    }
}
