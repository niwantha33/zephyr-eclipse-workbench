// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;

/**
 * A CTF stream is not a .ctf file: Zephyr saves extensionless
 * "metadata" and "channel0_0" files within one ctf/ directory.
 * A native DirectoryDialog hides both files, which made successful
 * captures appear empty on Windows. Let users see and select either file.
 *
 * This is strictly local, read-only navigation; each consuming view
 * independently verifies the complete CTF directory before decoding.
 */
final class SavedCtfFilePicker {
    private SavedCtfFilePicker() {}

    static Path choose(Shell shell) {
        FileDialog dialog = new FileDialog(shell, SWT.OPEN);
        dialog.setText("Open Zephyr CTF: select metadata or channel0_0");
        dialog.setFilterNames(new String[] { "CTF metadata / channel0_0", "All files" });
        dialog.setFilterExtensions(new String[] { "metadata;channel0_0", "*" });
        String selection = dialog.open();
        if (selection == null) return null;
        Path file = Path.of(selection).toAbsolutePath().normalize();
        if (file.getFileName() == null
                || !("metadata".equals(file.getFileName().toString())
                        || "channel0_0".equals(file.getFileName().toString()))
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            MessageDialog.openError(shell, "Not a CTF capture file",
                    "Choose metadata or channel0_0 inside a saved trace-*/ctf folder. "
                    + "Both extensionless files must exist. "
                    + "You can also use Open latest capture without browsing.");
            return null;
        }
        return file.getParent();
    }
}
