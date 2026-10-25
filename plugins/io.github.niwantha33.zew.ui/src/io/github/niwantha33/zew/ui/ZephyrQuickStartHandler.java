// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Path;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * One entry point for newcomers: initialize a managed environment if absent;
 * otherwise open the project wizard with board discovery automatically loaded.
 */
public final class ZephyrQuickStartHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) {
        boolean ready = false;
        for (var project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (!project.isAccessible()) continue;
            try {
                String root = project.getPersistentProperty(new QualifiedName(Activator.ID, "setupRoot"));
                if (root != null && !root.isBlank()) {
                    ProjectContext.load(Path.of(root));
                    ready = true;
                    break;
                }
            } catch (Exception ignored) { /* try another existing managed environment */ }
        }
        if (!ready) {
            String workspace = Activator.getDefault().getPreferenceStore()
                    .getString(WorkbenchPreferences.WORKSPACE);
            if (!workspace.isBlank()) {
                try {
                    ProjectContext.load(Path.of(workspace).toAbsolutePath().getParent());
                    ready = true;
                } catch (Exception ignored) { /* no managed setup yet */ }
            }
        }
        if (!ready) {
            new WizardDialog(HandlerUtil.getActiveShell(event), new SetupWizard()).open();
        } else {
            var wizard = new ZephyrProjectWizard();
            wizard.init(PlatformUI.getWorkbench(), StructuredSelection.EMPTY);
            new WizardDialog(HandlerUtil.getActiveShell(event), wizard).open();
        }
        return null;
    }
}
