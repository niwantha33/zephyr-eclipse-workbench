// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.handlers.HandlerUtil;
import io.github.niwantha33.zew.core.OpenOcdRegistry;

/**
 * One workspace-level OpenOCD tool manager, independent of any Eclipse
 * project or vendor SDK configuration. Does not download or touch drivers.
 */
public final class DebugToolsHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        new Dialog(HandlerUtil.getActiveShell(event)).open();
        return null;
    }

    private static final class Dialog extends TitleAreaDialog {
        private final Map<OpenOcdRegistry.Provider, Text> fields =
                new EnumMap<>(OpenOcdRegistry.Provider.class);
        private final Map<OpenOcdRegistry.Provider, Label> statuses =
                new EnumMap<>(OpenOcdRegistry.Provider.class);

        Dialog(Shell shell) { super(shell); setShellStyle(getShellStyle() | SWT.RESIZE); }

        @Override protected Control createDialogArea(Composite parent) {
            var content = (Composite) super.createDialogArea(parent);
            setTitle("Manage Shared OpenOCD Debug Tools");
            setMessage("Configure compatible OpenOCD distributions once for all Zephyr projects."
                    + " Each board automatically chooses its required provider."
                    + " No global PATH changes, flashing or USB driver changes.");
            var panel = new Composite(content, SWT.NONE);
            panel.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            panel.setLayout(new GridLayout(3, false));
            add(panel, OpenOcdRegistry.Provider.GENERAL, SharedDebugTools.GENERAL,
                    "Zephyr / general OpenOCD (STM32, CMSIS-DAP, compatible probes)");
            add(panel, OpenOcdRegistry.Provider.ESPRESSIF, SharedDebugTools.ESPRESSIF,
                    "Espressif patched OpenOCD (ESP32, ESP32-S3)");
            add(panel, OpenOcdRegistry.Provider.TI, SharedDebugTools.TI,
                    "TI OpenOCD (TI-specific targets and probes)");
            var info = new Label(panel, SWT.WRAP);
            info.setText("Leave a provider blank for automatic discovery in its standard install location."
                    + " Tools must include their own matching interface and target scripts."
                    + " A valid executable alone does not prove that a USB/JTAG driver or board is supported."
                    + " Changing providers requires rebuilding the affected target.");
            info.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 3, 1));
            validate();
            return content;
        }

        private void add(Composite panel, OpenOcdRegistry.Provider provider,
                         String preference, String labelText) {
            var title = new Label(panel, SWT.NONE);
            title.setText(labelText);
            title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false, 3, 1));
            var text = new Text(panel, SWT.BORDER);
            text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            text.setText(Activator.getDefault().getPreferenceStore().getString(preference));
            fields.put(provider, text);
            text.addModifyListener(event -> validate());
            var browse = new Button(panel, SWT.PUSH);
            browse.setText("Browse...");
            browse.addListener(SWT.Selection, event -> {
                var picker = new FileDialog(getShell(), SWT.OPEN);
                picker.setText("Select the " + provider + " openocd.exe");
                picker.setFilterExtensions(new String[] {"openocd.exe", "*.exe", "*.*"});
                String selected = picker.open();
                if (selected != null) text.setText(selected);
            });
            var auto = new Button(panel, SWT.PUSH);
            auto.setText("Auto");
            auto.addListener(SWT.Selection, event -> text.setText(""));
            var status = new Label(panel, SWT.WRAP);
            status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
            statuses.put(provider, status);
        }

        @Override protected void createButtonsForButtonBar(Composite parent) {
            createButton(parent, IDialogConstants.OK_ID, "Save Shared Debug Tools", true);
            createButton(parent, IDialogConstants.CANCEL_ID, "Cancel", false);
            validate();
        }

        private void validate() {
            if (fields.size() != 3 || statuses.size() != 3) return;
            boolean valid = true;
            for (var entry : fields.entrySet()) {
                String value = entry.getValue().getText().trim();
                Label status = statuses.get(entry.getKey());
                if (status == null || status.isDisposed()) continue;
                if (value.isBlank()) {
                    status.setText("Automatic discovery at build time; no explicit override.");
                    continue;
                }
                OpenOcdRegistry.Provider provider = entry.getKey();
                var found = OpenOcdRegistry.fromExecutable(provider, safePath(value), "User selected");
                if (found.isPresent()) {
                    status.setText("Verified executable and scripts: " + found.get().scripts());
                } else {
                    status.setText("Invalid or incomplete OpenOCD distribution: missing binary or matching scripts.");
                    valid = false;
                }
            }
            setErrorMessage(valid ? null : "Fix invalid OpenOCD paths, or choose Auto to leave them unset.");
            if (getButton(IDialogConstants.OK_ID) != null)
                getButton(IDialogConstants.OK_ID).setEnabled(valid);
        }

        private static Path safePath(String value) {
            try { return Path.of(value); } catch (RuntimeException ignored) { return null; }
        }

        @Override protected void okPressed() {
            var prefs = Activator.getDefault().getPreferenceStore();
            prefs.setValue(SharedDebugTools.GENERAL, fields.get(OpenOcdRegistry.Provider.GENERAL).getText().trim());
            prefs.setValue(SharedDebugTools.ESPRESSIF, fields.get(OpenOcdRegistry.Provider.ESPRESSIF).getText().trim());
            prefs.setValue(SharedDebugTools.TI, fields.get(OpenOcdRegistry.Provider.TI).getText().trim());
            super.okPressed();
        }
    }
}
