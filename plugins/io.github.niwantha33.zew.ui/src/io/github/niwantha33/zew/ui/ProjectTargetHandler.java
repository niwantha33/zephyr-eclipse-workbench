// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Path;
import java.util.List;
import org.eclipse.core.commands.*;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.jface.dialogs.*;
import org.eclipse.jface.viewers.*;
import org.eclipse.ui.dialogs.ListDialog;
import org.eclipse.ui.handlers.HandlerUtil;
import io.github.niwantha33.zew.core.ProjectProfile;

/** Correct the CPU choice of an existing ESP32-S3 project without recreating source. */
public final class ProjectTargetHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        IResource resource = null;
        if (HandlerUtil.getCurrentSelection(event) instanceof IStructuredSelection selection && selection.getFirstElement() instanceof IAdaptable adaptable)
            resource = adaptable.getAdapter(IResource.class);
        if (resource == null && HandlerUtil.getActiveEditor(event) != null) resource = HandlerUtil.getActiveEditor(event).getEditorInput().getAdapter(IResource.class);
        try {
            if (resource == null) throw new java.io.IOException("Select your ESP32-S3 project or open its source first.");
            var project = resource.getProject(); Path application = project.getLocation().toFile().toPath();
            var profile = ProjectProfile.read(application); String[] parts = profile.board().split("/");
            if (parts.length != 3 || !parts[1].equals("esp32s3")) throw new java.io.IOException("This CPU selector applies to ESP32-S3 procpu/appcpu projects. Use the project wizard to select other hardware.");
            String base = parts[0] + "/esp32s3/";
            var dialog = new ListDialog(HandlerUtil.getActiveShell(event)); dialog.setTitle("ESP32-S3 CPU — " + project.getName());
            dialog.setMessage("procpu = main CPU for standalone applications. appcpu = secondary CPU requiring primary-core firmware.");
            dialog.setContentProvider(ArrayContentProvider.getInstance()); dialog.setLabelProvider(new LabelProvider());
            dialog.setInput(List.of(base + "procpu", base + "appcpu")); dialog.setInitialSelections(new Object[] {profile.board()});
            if (dialog.open() != IDialogConstants.OK_ID || dialog.getResult() == null || dialog.getResult().length != 1) return null;
            String selected = (String) dialog.getResult()[0]; if (selected.equals(profile.board())) return null;
            ResourcesPlugin.getWorkspace().run(monitor -> {
                try { new ProjectProfile(selected).replace(application, profile.board()); project.refreshLocal(IResource.DEPTH_INFINITE, monitor); }
                catch (Exception e) { throw new CoreException(new Status(IStatus.ERROR, Activator.ID, e.getMessage(), e)); }
            }, project, IWorkspace.AVOID_UPDATE, null);
            MessageDialog.openInformation(HandlerUtil.getActiveShell(event), "CPU target saved", selected + "\n\nBuild this project. Required ESP32-S3 tools will be prepared automatically; the prior build output and application source are preserved.");
        } catch (Exception error) { MessageDialog.openError(HandlerUtil.getActiveShell(event), "ESP32-S3 CPU target", error.getMessage()); }
        return null;
    }
}
