// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.part.ViewPart;
import io.github.niwantha33.zew.core.BuildProfileChoices;
import io.github.niwantha33.zew.core.ProjectProfile;
import io.github.niwantha33.zew.core.ZephyrProjectFiles;

/**
 * Five small steps with true selected-project metadata. Displaying the guide
 * never launches a build, attaches OpenOCD or infers target hardware state.
 */
public final class ZephyrBeginnerView extends ViewPart {
    public static final String ID = Activator.ID + ".beginnerGuide";
    private ScrolledComposite scroll;
    private Label selected, board, profile, buildOutput;
    private ISelectionListener selectionListener;
    private IResourceChangeListener resourceListener;

    @Override public void createPartControl(Composite parent) {
        parent.setLayout(new FillLayout());
        scroll = new ScrolledComposite(parent, SWT.V_SCROLL | SWT.H_SCROLL);
        scroll.setExpandHorizontal(true);
        scroll.setExpandVertical(true);
        Composite body = new Composite(scroll, SWT.NONE);
        GridLayout grid = new GridLayout(1, false);
        grid.marginWidth = 14;
        grid.marginHeight = 14;
        grid.verticalSpacing = 8;
        body.setLayout(grid);

        heading(body, "GETTING STARTED");
        line(body, "Follow five steps. Nothing is flashed automatically.");
        heading(body, "Current project");
        selected = line(body, "Select a Zephyr project in Project Explorer.");
        board = line(body, "Board: not selected");
        profile = line(body, "Profile: not selected");
        buildOutput = line(body, "Build output: not checked");

        heading(body, "1  Select board");
        line(body, "Create or import a project and choose its exact board and CPU.");
        button(body, "Create or import project...", () -> command("io.github.niwantha33.zew.quickStart"));
        button(body, "Change board...", this::properties);

        heading(body, "2  Configure");
        line(body, "Inspect configuration or hardware when needed.");
        button(body, "Configuration Studio...", () -> configuration("overview"));
        button(body, "Review hardware...", () -> configuration("hardware"));

        heading(body, "3  Select profile");
        line(body, "The default profile is a good place to start.");
        button(body, "Manage build profiles...", this::properties);

        heading(body, "4  Build");
        line(body, "Check the Console and Problems views for the result.");
        button(body, "Build selected project", this::build);

        heading(body, "5  Debug");
        line(body, "Open debug tools first. Flash and Debug is a separate explicit action.");
        button(body, "Open Hardware Debug workspace", this::debugPerspective);

        heading(body, "More tools");
        Button advanced = new Button(body, SWT.PUSH);
        advanced.setText("Advanced views...");
        advanced.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        advanced.addListener(SWT.Selection, event -> advancedViews(advanced));
        line(body, "Return here using Zephyr > Beginner Workspace.");

        scroll.setContent(body);
        scroll.setMinSize(body.computeSize(280, SWT.DEFAULT));

        selectionListener = (part, selection) -> refresh();
        getSite().getWorkbenchWindow().getSelectionService().addSelectionListener(selectionListener);
        resourceListener = event -> {
            if (event.getType() != IResourceChangeEvent.POST_CHANGE) return;
            var display = scroll.getDisplay();
            if (!display.isDisposed()) display.asyncExec(() -> {
                if (!scroll.isDisposed()) refresh();
            });
        };
        ResourcesPlugin.getWorkspace().addResourceChangeListener(
                resourceListener, IResourceChangeEvent.POST_CHANGE);
        refresh();
    }

    private static void heading(Composite parent, String title) {
        Label label = new Label(parent, SWT.NONE);
        label.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DIALOG_FONT));
        label.setText(title);
        label.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
    }

    private static Label line(Composite parent, String value) {
        Label label = new Label(parent, SWT.WRAP);
        label.setText(value);
        label.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        return label;
    }

    private static void button(Composite parent, String title, Runnable run) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(title);
        button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        button.addListener(SWT.Selection, event -> run.run());
    }

    private IProject project() {
        var window = getSite().getWorkbenchWindow();
        return SelectedProject.forLaunch(window.getSelectionService().getSelection(),
                getSite().getPage().getActiveEditor());
    }

    private void refresh() {
        if (selected == null || selected.isDisposed()) return;
        IProject project = project();
        if (project == null) {
            selected.setText("Select a Zephyr project in Project Explorer.");
            board.setText("Board: not selected");
            profile.setText("Profile: not selected");
            buildOutput.setText("Build output: not checked");
            return;
        }
        selected.setText(project.getName());
        Path root = project.getLocation().toFile().toPath();
        String target = "";
        try {
            if (Files.isRegularFile(root.resolve(".zew/project.json")))
                target = ProjectProfile.read(root).board();
            else if (Files.isRegularFile(root.resolve(".zew/project.properties")))
                target = ZephyrProjectFiles.readBoard(root);
            else target = ProjectContext.settings(project).getProperty("board", "");
        } catch (Exception ignored) { /* Never guess a board. */ }
        board.setText("Board: " + (target.isBlank() ? "not configured" : target));
        try {
            var choices = BuildProfileChoices.available(root);
            var active = BuildProfileChoices.selected(choices,
                    project.getPersistentProperty(ProjectContext.BUILD_CONFIGURATION),
                    project.getPersistentProperty(ProjectContext.NAMED_PROFILE));
            profile.setText("Profile: " + active.label());
        } catch (Exception missing) {
            profile.setText("Profile: configure in Project Properties");
        }
        try {
            Path elf = ProjectContext.buildDirectory(project, ProjectContext.settings(project))
                    .resolve("zephyr/zephyr.elf");
            buildOutput.setText(Files.isRegularFile(elf)
                    ? "Build output: ELF present (not target-verified)"
                    : "Build output: no ELF for active profile");
        } catch (Exception missing) {
            buildOutput.setText("Build output: setup incomplete");
        }
    }

    private IProject requireProject() {
        IProject project = project();
        if (project == null) MessageDialog.openInformation(getSite().getShell(),
                "Select a Zephyr project", "Select one Zephyr project in Project Explorer or open its source file.");
        return project;
    }

    private void properties() {
        IProject project = requireProject();
        if (project == null) return;
        var dialog = PreferencesUtil.createPropertyDialogOn(getSite().getShell(),
                project, "io.github.niwantha33.zew.projectProperties", null, null);
        if (dialog != null) dialog.open();
        refresh();
    }

    private void configuration(String section) {
        try {
            var home = (ConfigurationHomeView) getSite().getPage().showView(ConfigurationHomeView.ID);
            IProject project = project();
            if (project == null) home.displayMissingProject();
            else home.displayProject(project);
            home.openSection(section);
        } catch (PartInitException error) {
            MessageDialog.openError(getSite().getShell(), "Configuration Studio", error.getMessage());
        }
    }

    private void build() {
        IProject project = requireProject();
        if (project == null) return;
        try { ProjectContext.settings(project); }
        catch (Exception incomplete) {
            var dialog = PreferencesUtil.createPropertyDialogOn(getSite().getShell(),
                    project, "io.github.niwantha33.zew.projectProperties", null, null);
            if (dialog == null || dialog.open() != Window.OK) return;
            try { ProjectContext.settings(project); }
            catch (Exception error) {
                MessageDialog.openError(getSite().getShell(), "Incomplete Zephyr setup", error.getMessage());
                return;
            }
        }
        ZephyrBuildHandler.schedule(project, false, false);
    }

    private void command(String id) {
        try {
            IHandlerService service = getSite().getService(IHandlerService.class);
            if (service == null) throw new IllegalStateException("Eclipse command service unavailable");
            service.executeCommand(id, null);
        } catch (Exception error) {
            MessageDialog.openError(getSite().getShell(), "Zephyr action failed", error.getMessage());
        }
    }

    private void debugPerspective() {
        var window = getSite().getWorkbenchWindow();
        var descriptor = window.getWorkbench().getPerspectiveRegistry()
                .findPerspectiveWithId(ZephyrHardwareDebugPerspective.ID);
        if (descriptor != null) getSite().getPage().setPerspective(descriptor);
    }

    private void advancedViews(Button anchor) {
        Menu menu = new Menu(anchor);
        advancedItem(menu, "Devicetree Graph Studio", Activator.ID + ".dtsGraph");
        advancedItem(menu, "YAML Connected Blocks", Activator.ID + ".yamlStructure");
        advancedItem(menu, "Hardware Register Explorer", SocRegistersHandler.VIEW_ID);
        advancedItem(menu, "Debug Trace Console", ZephyrTraceConsoleView.ID);
        advancedItem(menu, "Thread Timeline", ThreadTimelineView.ID);
        advancedItem(menu, "CPU Load", ZephyrCpuLoadView.ID);
        new MenuItem(menu, SWT.SEPARATOR);
        MenuItem debug = new MenuItem(menu, SWT.PUSH);
        debug.setText("Hardware Debug perspective");
        debug.addListener(SWT.Selection, event -> debugPerspective());
        menu.addListener(SWT.Hide, event -> anchor.getDisplay().asyncExec(() -> {
            if (!menu.isDisposed()) menu.dispose();
        }));
        menu.setLocation(anchor.toDisplay(0, anchor.getSize().y));
        menu.setVisible(true);
    }

    private void advancedItem(Menu menu, String title, String viewId) {
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        item.setText(title);
        item.addListener(SWT.Selection, event -> {
            try { getSite().getPage().showView(viewId); }
            catch (PartInitException error) {
                MessageDialog.openError(getSite().getShell(), title, error.getMessage());
            }
        });
    }

    @Override public void setFocus() {
        if (scroll != null && !scroll.isDisposed()) scroll.setFocus();
    }

    @Override public void dispose() {
        if (selectionListener != null)
            getSite().getWorkbenchWindow().getSelectionService().removeSelectionListener(selectionListener);
        if (resourceListener != null)
            ResourcesPlugin.getWorkspace().removeResourceChangeListener(resourceListener);
        super.dispose();
    }
}
