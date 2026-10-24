// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.*;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;

public final class EditorHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        IResource resource = null;
        var selection = HandlerUtil.getCurrentSelection(event);
        if (selection instanceof IStructuredSelection structured && structured.getFirstElement() instanceof IAdaptable adaptable)
            resource = adaptable.getAdapter(IResource.class);
        if (resource == null && HandlerUtil.getActiveEditor(event) != null)
            resource = HandlerUtil.getActiveEditor(event).getEditorInput().getAdapter(IResource.class);
        if (resource == null) MessageDialog.openInformation(HandlerUtil.getActiveShell(event), "Zephyr C/C++ editor", "Select your Zephyr project or open its source file first.");
        else EditorIntegration.schedule(resource.getProject());
        return null;
    }
}
