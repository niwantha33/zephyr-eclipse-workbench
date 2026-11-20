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
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.List;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.part.ViewPart;
import io.github.niwantha33.zew.core.BuildProfileChoices;
import io.github.niwantha33.zew.core.ProjectProfile;
import io.github.niwantha33.zew.core.ZephyrProjectFiles;

/**
 * Two-pane, task-first guide using true selected-project metadata. Selecting a
 * step only updates the detail pane; it never launches commands or target IO.
 */
public final class ZephyrBeginnerView extends ViewPart {
    public static final String ID = Activator.ID + ".beginnerGuide";
    private static final String[] STEPS = {
        "1   Project", "2   Hardware", "3   Configuration",
        "4   Build", "5   Flash & Debug", "6   Inspect & Analyse",
        "7   More Tools"
    };
    private ScrolledComposite scroll;
    private Composite content;
    private List navigation;
    private Label selected, board, profile, buildOutput;
    private ISelectionListener selectionListener;
    private IResourceChangeListener resourceListener;

    @Override public void createPartControl(Composite parent) {
        parent.setLayout(new FillLayout());
        SashForm panes = new SashForm(parent, SWT.HORIZONTAL);

        // The left column is navigation and project context, never a list of
        // commands. Selection cannot build, flash or attach to physical hardware.
        Composite left = new Composite(panes, SWT.NONE);
        GridLayout leftLayout = new GridLayout(1, false);
        leftLayout.marginWidth = 12;
        leftLayout.marginHeight = 14;
        leftLayout.verticalSpacing = 9;
        left.setLayout(leftLayout);
        heading(left, "GETTING STARTED");
        line(left, "Choose a step. Its instructions appear on the right.");

        navigation = new List(left, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL);
        navigation.setItems(STEPS);
        navigation.setToolTipText("Select a step to view instructions and actions on the right.");
        GridData navigationData = new GridData(SWT.FILL, SWT.FILL, true, true);
        navigationData.minimumWidth = 170;
        navigationData.minimumHeight = 170;
        navigation.setLayoutData(navigationData);

        heading(left, "CURRENT PROJECT");
        selected = line(left, "Select a Zephyr project in Project Explorer.");
        board = line(left, "Board: not selected");
        profile = line(left, "Profile: not selected");
        buildOutput = line(left, "Build output: not checked");

        // Only one step's tools and short usage guidance are shown at a time.
        scroll = new ScrolledComposite(panes, SWT.V_SCROLL | SWT.H_SCROLL);
        scroll.setExpandHorizontal(true);
        scroll.setExpandVertical(true);
        content = new Composite(scroll, SWT.NONE);
        GridLayout detailLayout = new GridLayout(1, false);
        detailLayout.marginWidth = 20;
        detailLayout.marginHeight = 18;
        detailLayout.verticalSpacing = 10;
        content.setLayout(detailLayout);
        scroll.setContent(content);
        panes.setWeights(new int[] { 30, 70 });

        navigation.addListener(SWT.Selection, event -> showStep(navigation.getSelectionIndex()));
        navigation.select(0);
        showStep(0);

        selectionListener = (part, selection) -> refresh();
        getSite().getWorkbenchWindow().getSelectionService().addSelectionListener(selectionListener);
        var display = parent.getDisplay();
        resourceListener = event -> {
            if (event.getType() != IResourceChangeEvent.POST_CHANGE || display.isDisposed()) return;
            display.asyncExec(() -> {
                if (!scroll.isDisposed()) refresh();
            });
        };
        ResourcesPlugin.getWorkspace().addResourceChangeListener(
                resourceListener, IResourceChangeEvent.POST_CHANGE);
        refresh();
    }

    /** Show the selected task in the second column without running anything. */
    private void showStep(int step) {
        if (content == null || content.isDisposed()) return;
        for (Control child : content.getChildren()) child.dispose();
        switch (step) {
            case 0:
                heading(content, "1   Open or create a project");
                line(content, "• What: Create a Zephyr application or import an existing one.");
                line(content, "• How: Open the wizard, then select the project in Project Explorer.");
                button(content, "New / Import Project...", () -> command("io.github.niwantha33.zew.quickStart"));
                break;
            case 1:
                heading(content, "2   Choose your hardware");
                line(content, "• What: Select the exact board and processor target for this project.");
                line(content, "• How: Browse supported boards before changing the saved target.");
                line(content, "• Changing a board requires Project Properties; browsing does not.");
                button(content, "Browse Available Boards...", () -> command("io.github.niwantha33.zew.embeddedHubBoards"));
                button(content, "Change Project Board...", this::properties);
                break;
            case 2:
                heading(content, "3   Configure the firmware");
                line(content, "• What: Configure peripherals, Devicetree and build options.");
                line(content, "• How: Start in Configuration Studio; inspect generated evidence before editing.");
                line(content, "• Select a named build profile if you need different configurations.");
                button(content, "Open Configuration Studio...", () -> configuration("overview"));
                button(content, "Open Hardware & Devicetree...", () -> configuration("hardware"));
                button(content, "Change Build Profile...", this::properties);
                break;
            case 3:
                heading(content, "4   Build firmware");
                line(content, "• What: Compile the selected board and build profile.");
                line(content, "• How: Build, then inspect Console and Problems for diagnostics.");
                line(content, "• A successful local build does not prove the firmware ran on hardware.");
                button(content, "Build Firmware", this::build);
                break;
            case 4:
                heading(content, "5   Flash and debug");
                line(content, "• What: Program a connected board or start a debugging session.");
                line(content, "• How: Connect the correct probe and verify your target first.");
                line(content, "• These actions can change physical hardware. Nothing runs on selection.");
                button(content, "Flash Firmware...", () -> command("io.github.niwantha33.zew.flash"));
                button(content, "Flash and Debug...", () -> command("io.github.niwantha33.zew.flashDebug"));
                button(content, "Open Hardware Debug Workspace", this::debugPerspective);
                break;
            case 5:
                heading(content, "6   Inspect and analyse");
                line(content, "• What: Inspect trace, task scheduling and CPU activity where evidence exists.");
                line(content, "• How: Start with Debug Trace Console, then inspect timelines and CPU load.");
                line(content, "• Empty or offline data must not be presented as live target activity.");
                button(content, "Debug Trace Console...", () -> openView("Debug Trace Console", ZephyrTraceConsoleView.ID));
                button(content, "Thread Timeline...", () -> openView("Thread Timeline", ThreadTimelineView.ID));
                button(content, "CPU Load...", () -> openView("CPU Load", ZephyrCpuLoadView.ID));
                break;
            case 6:
                heading(content, "7   Additional tools");
                line(content, "• What: Explore generated hardware configuration and target registers.");
                line(content, "• How: Open a tool when the project or debug session needs it.");
                line(content, "• Register values require valid target evidence; unavailable data stays unavailable.");
                button(content, "Devicetree Graph Studio...", () -> openView("Devicetree Graph Studio", Activator.ID + ".dtsGraph"));
                button(content, "YAML Connected Blocks...", () -> openView("YAML Connected Blocks", Activator.ID + ".yamlStructure"));
                button(content, "Hardware Register Explorer...", () -> openView("Hardware Register Explorer", SocRegistersHandler.VIEW_ID));
                button(content, "Developer Workspace", () -> command("io.github.niwantha33.zew.developerWorkspace"));
                break;
            default:
                heading(content, "Choose a step on the left");
                line(content, "Select a workflow stage to view its actions and instructions.");
                break;
        }
        updateScrollSize();
        scroll.setOrigin(0, 0);
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
            updateScrollSize();
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
        updateScrollSize();
    }

    private void updateScrollSize() {
        if (content == null || content.isDisposed() || scroll.isDisposed()) return;
        content.layout(true, true);
        scroll.setMinSize(content.computeSize(300, SWT.DEFAULT));
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
        try { ZephyrBuildHandler.schedule(project, false, false); }
        catch (RuntimeException error) {
            MessageDialog.openError(getSite().getShell(), "Zephyr build", error.getMessage());
        }
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

    private void openView(String title, String viewId) {
        try {
            getSite().getPage().showView(viewId);
        } catch (PartInitException error) {
            MessageDialog.openError(getSite().getShell(), title, error.getMessage());
        }
    }

    @Override public void setFocus() {
        if (navigation != null && !navigation.isDisposed()) navigation.setFocus();
    }

    @Override public void dispose() {
        if (selectionListener != null)
            getSite().getWorkbenchWindow().getSelectionService().removeSelectionListener(selectionListener);
        if (resourceListener != null)
            ResourcesPlugin.getWorkspace().removeResourceChangeListener(resourceListener);
        super.dispose();
    }
}
