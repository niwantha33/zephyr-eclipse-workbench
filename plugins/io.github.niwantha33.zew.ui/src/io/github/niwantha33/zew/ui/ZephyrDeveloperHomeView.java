// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.part.ViewPart;

/**
 * Developer landing page, deliberately distinct from the step-by-step beginner
 * guide. Nothing builds, flashes, or changes the target until clicked.
 */
public final class ZephyrDeveloperHomeView extends ViewPart {
    public static final String ID = Activator.ID + ".developerHome";

    private ISelectionListener selectionListener;
    private IProject project;
    private ScrolledComposite scroll;
    private Composite body, columns;
    private GridLayout columnsLayout;
    private Label projectName, target, readiness;
    private Button source, hardware, build, workflow, debug, settings;

    @Override public void createPartControl(Composite parent) {
        parent.setLayout(new FillLayout());
        scroll = new ScrolledComposite(parent, SWT.V_SCROLL | SWT.H_SCROLL);
        scroll.setExpandHorizontal(true);
        scroll.setExpandVertical(true);
        body = new Composite(scroll, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 20;
        layout.marginHeight = 20;
        layout.verticalSpacing = 14;
        body.setLayout(layout);

        title(body, "DEVELOPMENT WORKSPACE");
        description(body, "Code, build and debug your current Zephyr application. "
                + "Use Project Explorer to open source files. Guided Setup is a separate workspace.");

        columns = new Composite(body, SWT.NONE);
        columns.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        columnsLayout = new GridLayout(2, true);
        columnsLayout.marginWidth = 0;
        columnsLayout.marginHeight = 0;
        columnsLayout.horizontalSpacing = 18;
        columnsLayout.verticalSpacing = 14;
        columns.setLayout(columnsLayout);

        Group context = group(columns, "Active project");
        projectName = title(context, "Select a project in Project Explorer");
        target = description(context, "Board: not selected");
        readiness = description(context, "Build: not checked");
        description(context, "Opening a project source file uses the normal Eclipse editor. "
                + "This dashboard is optional and can be closed.");

        Group actions = group(columns, "Development actions");
        source = action(actions, "Browse Source Files", () -> {
            try {
                var explorer = getSite().getPage().showView("org.eclipse.ui.navigator.ProjectExplorer");
                explorer.setFocus();
            } catch (PartInitException error) {
                failure("Project Explorer", error);
            }
        });
        hardware = action(actions, "Explore Pins and Peripherals", this::openHardware);
        hardware.setToolTipText("Guided hardware view for the saved board. "
                + "Actual pin/peripheral evidence needs a generated Zephyr build.");
        build = action(actions, "Build Firmware", this::build);
        build.setToolTipText("Compile the selected project. Does not flash the board.");
        workflow = action(actions, "Automate Build and Flash...", () -> {
            try {
                getSite().getPage().showView(WorkflowStudioView.ID);
            } catch (PartInitException error) {
                failure("Workflow Studio", error);
            }
        });
        workflow.setToolTipText("Open Workflow Studio to arrange and save Clean, Build, Flash and Debug blocks.");
        debug = action(actions, "Flash and Debug...", this::debug);
        debug.setToolTipText("Explicit hardware operation: may program and reset the target.");
        settings = action(actions, "Advanced Board and Build Settings...", this::settings);
        settings.setToolTipText("Open Eclipse Project Properties for advanced configuration.");

        action(body, "Open Guided Setup", () ->
                ZephyrBeginnerPerspectiveHandler.open(getSite().getWorkbenchWindow()));

        scroll.setContent(body);
        scroll.addListener(SWT.Resize, event -> resize());
        selectionListener = (part, selection) -> refresh();
        getSite().getWorkbenchWindow().getSelectionService().addSelectionListener(selectionListener);
        refresh();
    }

    private static Group group(Composite parent, String label) {
        Group result = new Group(parent, SWT.NONE);
        result.setText(label);
        result.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 14;
        layout.marginHeight = 14;
        layout.verticalSpacing = 10;
        result.setLayout(layout);
        return result;
    }

    private static Label title(Composite parent, String value) {
        Label label = new Label(parent, SWT.WRAP);
        label.setText(value);
        label.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DIALOG_FONT));
        label.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        return label;
    }

    private static Label description(Composite parent, String value) {
        Label label = new Label(parent, SWT.WRAP);
        label.setText(value);
        label.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        return label;
    }

    private static Button action(Composite parent, String value, Runnable runnable) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(value);
        button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        button.addListener(SWT.Selection, event -> runnable.run());
        return button;
    }

    private void resize() {
        if (scroll == null || scroll.isDisposed() || body == null || body.isDisposed()) return;
        int width = scroll.getClientArea().width;
        int nextColumns = width < 740 ? 1 : 2;
        if (columnsLayout.numColumns != nextColumns) {
            columnsLayout.numColumns = nextColumns;
            columns.layout(true, true);
        }
        body.layout(true, true);
        scroll.setMinSize(body.computeSize(Math.max(320, width - 25), SWT.DEFAULT));
    }

    private void refresh() {
        if (projectName == null || projectName.isDisposed()) return;
        var selection = getSite().getWorkbenchWindow().getSelectionService().getSelection();
        IProject selected = SelectedProject.forLaunch(selection, getSite().getPage().getActiveEditor());
        if (selected != null && selected.isAccessible()) project = selected;
        else if (project == null || !project.isAccessible()) project = null;
        projectName.setText(project == null ? "Select a project in Project Explorer" : project.getName());
        target.setText("Board: not selected");
        readiness.setText("Build: not checked");
        if (project != null) {
            try {
                var setup = ProjectContext.settings(project);
                target.setText("Board: " + setup.getProperty("board", "not configured"));
                var path = ProjectContext.buildDirectory(project, setup).resolve("zephyr/zephyr.elf");
                readiness.setText(java.nio.file.Files.isRegularFile(path)
                        ? "Build: ELF found (not verified for this target)"
                        : "Build: compile firmware to generate hardware evidence");
            } catch (Exception unavailable) {
                readiness.setText("Build: check project setup before compiling");
            }
        }
        hardware.setEnabled(project != null);
        build.setEnabled(project != null);
        debug.setEnabled(project != null);
        settings.setEnabled(project != null);
        resize();
    }

    private IProject requireProject() {
        if (project == null || !project.isAccessible()) {
            MessageDialog.openInformation(getSite().getShell(), "Select a project",
                    "Choose a Zephyr application in Project Explorer first.");
            return null;
        }
        return project;
    }

    private void openHardware() {
        IProject selected = requireProject();
        if (selected == null) return;
        try {
            var view = (ConfigurationHomeView) getSite().getPage().showView(ConfigurationHomeView.ID);
            view.displayProject(selected);
            view.openSection("hardware");
        } catch (Exception error) {
            failure("Hardware and peripherals", error);
        }
    }

    private void build() {
        IProject selected = requireProject();
        if (selected == null) return;
        try {
            ProjectContext.settings(selected);
            ZephyrBuildHandler.schedule(selected, false, false);
        } catch (Exception error) {
            failure("Build firmware", error);
        }
    }

    private void debug() {
        if (requireProject() == null) return;
        try {
            var service = getSite().getService(org.eclipse.ui.handlers.IHandlerService.class);
            if (service == null) throw new IllegalStateException("Eclipse command service unavailable");
            service.executeCommand("io.github.niwantha33.zew.flashDebug", null);
        } catch (Exception error) {
            failure("Flash and debug", error);
        }
    }

    private void settings() {
        IProject selected = requireProject();
        if (selected == null) return;
        var dialog = PreferencesUtil.createPropertyDialogOn(getSite().getShell(),
                selected, "io.github.niwantha33.zew.projectProperties", null, null);
        if (dialog != null) dialog.open();
        refresh();
    }

    private void failure(String operation, Exception error) {
        MessageDialog.openError(getSite().getShell(), operation, error.getMessage());
    }

    @Override public void setFocus() {
        if (source != null && !source.isDisposed()) source.setFocus();
    }

    @Override public void dispose() {
        if (selectionListener != null && getSite().getWorkbenchWindow() != null)
            getSite().getWorkbenchWindow().getSelectionService()
                    .removeSelectionListener(selectionListener);
        super.dispose();
    }
}
