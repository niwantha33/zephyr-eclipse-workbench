// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.jface.preference.DirectoryFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.FileFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

public final class WorkbenchPreferences extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {
    public static final String PYTHON = "python";
    public static final String WORKSPACE = "workspace";
    public static final String APPLICATION = "application";
    public static final String BOARD = "board";
    public WorkbenchPreferences() {
        super(GRID);
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
        setDescription("Select your existing tools. Paths stay local to this Eclipse workspace.\n"
                + "Use the Python executable in the West workspace's .venv; no shell activation is needed.");
    }
    @Override public void init(IWorkbench workbench) {}
    @Override protected void createFieldEditors() {
        addField(new FileFieldEditor(PYTHON, "Virtual-environment Python:", getFieldEditorParent()));
        addField(new DirectoryFieldEditor(WORKSPACE, "West workspace root:", getFieldEditorParent()));
        var application = new DirectoryFieldEditor(APPLICATION, "Application folder:", getFieldEditorParent());
        application.setEmptyStringAllowed(true);
        addField(application);
        addField(new StringFieldEditor(BOARD, "Exact board target:", getFieldEditorParent()));
    }
}
