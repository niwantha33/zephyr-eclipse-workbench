// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;

/** Offer setup once for new Eclipse workspaces; existing configurations are left alone. */
public final class SetupStartup implements IStartup {
    @Override public void earlyStartup() {
        var display = PlatformUI.getWorkbench().getDisplay();
        display.asyncExec(() -> {
            var plugin = Activator.getDefault(); if (plugin == null) return;
            var store = plugin.getPreferenceStore();
            if (!store.getString(WorkbenchPreferences.WORKSPACE).isBlank() || store.getBoolean("setup.welcomeShown")) return;
            var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow(); if (window == null) return;
            store.setValue("setup.welcomeShown", true);
            int choice = new MessageDialog(window.getShell(), "Welcome to Zephyr Workbench", null,
                    "New to Zephyr? Set up an isolated development environment and build your first Hello World without opening a terminal.\n\nAlready have an environment? Choose Use Existing Tools.",
                    MessageDialog.INFORMATION, new String[] {"Set Up Zephyr", "Use Existing Tools", "Later"}, 0).open();
            if (choice == 0) new WizardDialog(window.getShell(), new SetupWizard()).open();
            else if (choice == 1) new ToolsDialog(window.getShell()).open();
        });
    }
}
