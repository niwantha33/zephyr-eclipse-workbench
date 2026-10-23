// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/** Credentials belong to Eclipse secure storage, never the project or ordinary preferences. */
public final class UpdateConnection extends Dialog {
    static final String CONFIGURED = "updates.connectionConfigured";
    static final String AUTO = "updates.autoCheck";
    private static final String NODE = "io.github.niwantha33.zew/updates/github";
    private Text token;
    private Button publicAccess;
    private Button automatic;
    public UpdateConnection(Shell parent) { super(parent); }
    static String credential() throws StorageException {
        return SecurePreferencesFactory.getDefault().node(NODE).get("token", "");
    }
    @Override protected void configureShell(Shell shell) { super.configureShell(shell); shell.setText("Workbench update connection"); }
    @Override protected Control createDialogArea(Composite parent) {
        var area = (Composite) super.createDialogArea(parent);
        var body = new Composite(area, SWT.NONE);
        body.setLayout(new GridLayout(1, false));
        var text = new Label(body, SWT.WRAP);
        text.setText("Private updates: niwantha33/zephyr-eclipse-workbench\n"
                + "Use a fine-grained GitHub token with Contents: read access to this repository only.\n"
                + "The token is encrypted in Eclipse secure storage. Never paste it into chat or source files.\n"
                + "Leave the field empty to retain an existing saved token.");
        text.setLayoutData(new GridData(520, SWT.DEFAULT));
        token = new Text(body, SWT.BORDER | SWT.PASSWORD);
        token.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        token.setTextLimit(4096);
        publicAccess = new Button(body, SWT.CHECK);
        publicAccess.setText("Use public access without a token (only after the repository is public)");
        automatic = new Button(body, SWT.CHECK);
        automatic.setText("Check at startup and every six hours while Eclipse is open");
        automatic.setSelection(Activator.getDefault().getPreferenceStore().getBoolean(AUTO));
        return area;
    }
    @Override protected void okPressed() {
        try {
            var secure = SecurePreferencesFactory.getDefault().node(NODE);
            String value = token.getText().trim();
            if (value.chars().anyMatch(c -> c <= 32 || c >= 127)) {
                MessageDialog.openError(getShell(), "Update access", "The token must not contain spaces, line breaks or non-ASCII characters.");
                return;
            }
            if (publicAccess.getSelection()) secure.remove("token");
            else if (!value.isEmpty()) secure.put("token", value, true);
            else if (secure.get("token", "").isBlank()) {
                MessageDialog.openError(getShell(), "Update access", "Enter a repository read-only token, or select public access for a public repository.");
                return;
            }
            secure.flush();
            var preferences = Activator.getDefault().getPreferenceStore();
            preferences.setValue(CONFIGURED, true);
            preferences.setValue(AUTO, automatic.getSelection());
            token.setText("");
            super.okPressed();
        } catch (Exception error) {
            MessageDialog.openError(getShell(), "Secure storage", "The update connection could not be saved. Check Eclipse secure-storage configuration.");
        }
    }
}
