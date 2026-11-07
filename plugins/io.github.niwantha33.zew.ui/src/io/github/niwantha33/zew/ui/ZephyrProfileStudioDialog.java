// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Path;
import java.util.List;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import io.github.niwantha33.zew.core.BuildConfiguration;
import io.github.niwantha33.zew.core.BuildProfile;

/** Native, project-local editor for user-defined Zephyr firmware build profiles. */
final class ZephyrProfileStudioDialog extends Dialog {
    private final IProject project;
    private final Path application;
    private Table table;
    private String lastEdited;

    ZephyrProfileStudioDialog(Shell parent, IProject project) {
        super(parent);
        this.project = project;
        this.application = project.getLocation().toFile().toPath();
    }

    String lastEditedId() { return lastEdited; }

    @Override protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Zephyr Profile Studio");
    }

    @Override protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(new GridLayout(2, false));
        Label help = new Label(area, SWT.WRAP);
        help.setText("Create independent firmware profiles for this application. Each profile has its own "
                + "Kconfig fragment, compiler macros and build output. Select the active profile in Project Properties > Zephyr.");
        help.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        table = new Table(area, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridData tableGrid = new GridData(SWT.FILL, SWT.FILL, true, true);
        tableGrid.widthHint = 640;
        tableGrid.heightHint = 280;
        table.setLayoutData(tableGrid);
        for (String column : new String[] {"Name", "ID", "Optimization", "C macros"}) {
            TableColumn field = new TableColumn(table, SWT.LEFT);
            field.setText(column);
            field.setWidth(switch (column) {
                case "Name" -> 175;
                case "ID" -> 160;
                case "Optimization" -> 120;
                default -> 220;
            });
        }

        Composite buttons = new Composite(area, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, false, false));
        buttons.setLayout(new GridLayout(1, false));
        button(buttons, "New...", () -> create(null));
        button(buttons, "Duplicate...", () -> create(selected()));
        button(buttons, "Edit...", () -> edit(selected()));
        button(buttons, "Delete...", this::remove);
        table.addListener(SWT.DefaultSelection, e -> edit(selected()));
        refresh(null);
        return area;
    }

    private void button(Composite parent, String title, Runnable command) {
        Button control = new Button(parent, SWT.PUSH);
        control.setText(title);
        control.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        control.addListener(SWT.Selection, e -> command.run());
    }

    private BuildProfile selected() {
        if (table.getSelectionCount() != 1) return null;
        return (BuildProfile) table.getSelection()[0].getData();
    }

    private void refresh(String selectedId) {
        try {
            table.removeAll();
            for (BuildProfile profile : BuildProfile.list(application)) {
                TableItem row = new TableItem(table, SWT.NONE);
                row.setText(new String[]{profile.label(), profile.id(), profile.optimization().label(),
                        profile.definitions()});
                row.setData(profile);
                if (profile.id().equals(selectedId)) table.setSelection(row);
            }
        } catch (Exception error) {
            MessageDialog.openError(getShell(), "Profiles unavailable", error.getMessage());
        }
    }

    private void create(BuildProfile source) {
        InputDialog prompt = new InputDialog(getShell(), "New Zephyr build profile",
                "Enter a stable profile ID (lowercase letters, digits and hyphens).",
                source == null ? "debug-rtt" : source.id() + "-copy",
                value -> {
                    try {
                        new BuildProfile(value, value, BuildConfiguration.DEFAULT, "", "");
                        for (BuildProfile existing : BuildProfile.list(application))
                            if (existing.id().equals(value)) return "This profile ID already exists.";
                        return null;
                    } catch (Exception error) {
                        return error.getMessage();
                    }
                });
        if (prompt.open() != Window.OK) return;
        String id = prompt.getValue();
        try {
            BuildProfile blank = new BuildProfile(id, source == null ? id : source.label() + " Copy",
                    source == null ? BuildConfiguration.DEFAULT : source.optimization(),
                    source == null ? "" : source.definitions(),
                    source == null ? "" : source.kconfig());
            if (new Editor(getShell(), application, blank).open() == Window.OK) {
                lastEdited = id;
                refresh(id);
            }
        } catch (Exception error) {
            MessageDialog.openError(getShell(), "Profile creation failed", error.getMessage());
        }
    }

    private void edit(BuildProfile selected) {
        if (selected == null) return;
        if (new Editor(getShell(), application, selected).open() == Window.OK) {
            lastEdited = selected.id();
            refresh(selected.id());
        }
    }

    private void remove() {
        BuildProfile profile = selected();
        if (profile == null) return;
        try {
            if (profile.id().equals(project.getPersistentProperty(ProjectContext.NAMED_PROFILE))) {
                MessageDialog.openInformation(getShell(), "Active profile",
                        "Choose another profile and apply Project Properties before deleting this one.");
                return;
            }
            if (!MessageDialog.openConfirm(getShell(), "Delete profile",
                    "Delete " + profile.label() + " and its Kconfig fragment? Existing build outputs are not removed.")) return;
            BuildProfile.delete(application, profile.id());
            if (profile.id().equals(lastEdited)) lastEdited = null;
            refresh(null);
        } catch (Exception error) {
            MessageDialog.openError(getShell(), "Cannot delete profile", error.getMessage());
        }
    }

    /** Separate editor: all changes are validated before the profile is saved. */
    private static final class Editor extends Dialog {
        private final Path application;
        private final BuildProfile original;
        private Text name, macros, fragment;
        private Combo optimization;

        Editor(Shell shell, Path application, BuildProfile profile) {
            super(shell);
            this.application = application;
            this.original = profile;
        }

        @Override protected void configureShell(Shell shell) {
            super.configureShell(shell);
            shell.setText("Edit firmware profile — " + original.id());
        }

        @Override protected Control createDialogArea(Composite parent) {
            Composite area = (Composite) super.createDialogArea(parent);
            area.setLayout(new GridLayout(2, false));
            text(area, "Profile ID", original.id(), false);
            name = text(area, "Display name", original.label(), true);
            new Label(area, SWT.NONE).setText("Optimization");
            optimization = new Combo(area, SWT.READ_ONLY);
            optimization.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            BuildConfiguration[] choices = BuildConfiguration.values();
            optimization.setItems(java.util.Arrays.stream(choices).map(BuildConfiguration::label).toArray(String[]::new));
            optimization.select(original.optimization().ordinal());
            macros = text(area, "Compiler macros (-D)", original.definitions(), true);
            Label explanation = new Label(area, SWT.WRAP);
            explanation.setText("Use NAME or NAME=1, separated by commas or spaces. These are global C/C++ compiler "
                    + "definitions, not CONFIG_* symbols. Application-specific settings should use Kconfig.");
            explanation.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
            new Label(area, SWT.NONE).setText("Extra Kconfig fragment");
            fragment = new Text(area, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
            GridData editorGrid = new GridData(SWT.FILL, SWT.FILL, true, true);
            editorGrid.widthHint = 620;
            editorGrid.heightHint = 270;
            fragment.setLayoutData(editorGrid);
            fragment.setText(original.kconfig());
            Label note = new Label(area, SWT.WRAP);
            note.setText("Example: CONFIG_LOG=y, CONFIG_LOG_DEFAULT_LEVEL=4. Custom CONFIG_* symbols "
                    + "must exist in your application Kconfig. A build verifies the effective .config.");
            note.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
            return area;
        }

        private Text text(Composite parent, String label, String value, boolean editable) {
            new Label(parent, SWT.NONE).setText(label);
            Text field = new Text(parent, SWT.BORDER | (editable ? SWT.NONE : SWT.READ_ONLY));
            field.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            field.setText(value);
            return field;
        }

        @Override protected void okPressed() {
            try {
                var updated = new BuildProfile(original.id(), name.getText().strip(),
                        BuildConfiguration.values()[optimization.getSelectionIndex()],
                        macros.getText(), fragment.getText());
                updated.save(application);
                super.okPressed();
            } catch (Exception error) {
                MessageDialog.openError(getShell(), "Invalid build profile", error.getMessage());
            }
        }
    }
}
