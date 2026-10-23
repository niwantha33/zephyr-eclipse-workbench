// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.ui.handlers.HandlerUtil;

/** Local version information: no network or secure-storage access. */
public final class VersionHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) {
        var plugin = Activator.getDefault();
        var version = plugin.getBundle().getVersion();
        var preferences = plugin.getPreferenceStore();
        var eclipse = Platform.getBundle("org.eclipse.platform");
        String latest = preferences.getString("updates.lastAvailableVersion");
        String checked = preferences.getString("updates.lastSuccessfulCheck");
        String notes = preferences.getString("updates.lastReleaseNotes");
        String details = "Zephyr Eclipse Workbench\n\n"
                + "Installed version: " + version.getMajor() + "." + version.getMinor() + "." + version.getMicro()
                + "\nBuild ID: " + (version.getQualifier().isBlank() ? "Unqualified development build" : version.getQualifier())
                + "\nFull version: " + version
                + "\nRelease type: Development preview"
                + "\n\nEclipse Platform: " + (eclipse == null ? "Unknown" : eclipse.getVersion())
                + "\nJava: " + System.getProperty("java.version", "Unknown")
                + "\n\nUpdate source: GitHub / niwantha33/zephyr-eclipse-workbench / updates"
                + "\nAutomatic checks: " + (!preferences.getBoolean(UpdateConnection.CONFIGURED) ? "Connection not configured"
                        : preferences.getBoolean(UpdateConnection.AUTO) ? "Enabled" : "Disabled")
                + "\nLatest version at last successful check: " + (latest.isBlank() ? "Not checked" : latest)
                + "\nLast successful check (UTC): " + (checked.isBlank() ? "Not checked" : checked)
                + (notes.isBlank() ? "" : "\nChannel release notes: " + notes.substring(0, Math.min(notes.length(), 1000)))
                + "\n\nOriginal workbench code: Apache-2.0"
                + "\nUse Zephyr > Check for Workbench Updates to refresh update information.";
        var shell = HandlerUtil.getActiveShell(event);
        var dialog = new MessageDialog(shell, "About Zephyr Workbench", null, details,
                MessageDialog.INFORMATION, new String[] {"Copy Details", "Close"}, 1);
        if (dialog.open() == 0) {
            var clipboard = new Clipboard(shell.getDisplay());
            try { clipboard.setContents(new Object[] {details}, new Transfer[] {TextTransfer.getInstance()}); }
            finally { clipboard.dispose(); }
        }
        return null;
    }
}
