// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
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
    private Browser browser;
    private String guideTemplate;
    private String lastRendered;
    private int currentStep;

    @Override public void createPartControl(Composite parent) {
        parent.setLayout(new FillLayout());
        if (createVisualGuide(parent)) {
            installListeners(parent);
            refresh();
            return;
        }
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
        installListeners(parent);
        refresh();
    }

    /** The same project and resource listeners are used by both presentations. */
    private void installListeners(Composite parent) {
        selectionListener = (part, selection) -> refresh();
        getSite().getWorkbenchWindow().getSelectionService().addSelectionListener(selectionListener);
        var display = parent.getDisplay();
        resourceListener = event -> {
            if (event.getType() != IResourceChangeEvent.POST_CHANGE || display.isDisposed()) return;
            display.asyncExec(() -> {
                if ((browser != null && !browser.isDisposed())
                        || (scroll != null && !scroll.isDisposed())) refresh();
            });
        };
        ResourcesPlugin.getWorkspace().addResourceChangeListener(
                resourceListener, IResourceChangeEvent.POST_CHANGE);
    }

    /** Offline HTML guide; native SWT view below remains a functional fallback. */
    private boolean createVisualGuide(Composite parent) {
        try {
            var plugin = Activator.getDefault();
            var entry = plugin.getBundle().getEntry("getting-started/index.html");
            if (entry == null) throw new IOException("Packaged Getting Started interface is missing");
            try (var stream = entry.openStream()) {
                byte[] bytes = stream.readNBytes(40_001);
                if (bytes.length > 40_000) throw new IOException("Getting Started interface is too large");
                guideTemplate = new String(bytes, StandardCharsets.UTF_8);
            }
            browser = new Browser(parent, SWT.NONE);
            browser.addLocationListener(new org.eclipse.swt.browser.LocationAdapter() {
                @Override public void changing(org.eclipse.swt.browser.LocationEvent event) {
                    if (event.location != null && !event.location.isBlank()
                            && !"about:blank".equalsIgnoreCase(event.location)) event.doit = false;
                }
            });
            new BrowserFunction(browser, "zewGettingStarted") {
                @Override public Object function(Object[] arguments) {
                    if (arguments == null || arguments.length != 1
                            || !(arguments[0] instanceof String action)) return "DENIED";
                    if (action.matches("step:[0-6]")) {
                        currentStep = action.charAt(5) - '0';
                        return "SELECTED";
                    }
                    if (!java.util.Set.of("new-project", "project-explorer", "browse-boards",
                            "hardware", "properties", "configuration", "build", "flash",
                            "flash-debug", "debug-workspace", "trace", "timeline", "cpu",
                            "dts", "yaml", "registers", "developer").contains(action))
                        return "DENIED";
                    browser.getDisplay().asyncExec(() -> {
                        if (browser == null || browser.isDisposed()) return;
                        switch (action) {
                            case "new-project" -> command("io.github.niwantha33.zew.quickStart");
                            case "project-explorer" -> openProjectExplorer();
                            case "browse-boards" -> command("io.github.niwantha33.zew.embeddedHubBoards");
                            case "hardware" -> configuration("hardware");
                            case "properties" -> properties();
                            case "configuration" -> configuration("system");
                            case "build" -> build();
                            case "flash" -> command("io.github.niwantha33.zew.flash");
                            case "flash-debug" -> command("io.github.niwantha33.zew.flashDebug");
                            case "debug-workspace" -> debugPerspective();
                            case "trace" -> openView("Debug Trace Console", ZephyrTraceConsoleView.ID);
                            case "timeline" -> openView("Thread Timeline", ThreadTimelineView.ID);
                            case "cpu" -> openView("CPU Load", ZephyrCpuLoadView.ID);
                            case "dts" -> openView("Devicetree Graph Studio", Activator.ID + ".dtsGraph");
                            case "yaml" -> openView("YAML Connected Blocks", Activator.ID + ".yamlStructure");
                            case "registers" -> openView("Hardware Register Explorer", SocRegistersHandler.VIEW_ID);
                            case "developer" -> command("io.github.niwantha33.zew.developerPerspective");
                            default -> { /* All unrecognised requests are denied above. */ }
                        }
                    });
                    return "QUEUED";
                }
            };
            return true;
        } catch (IOException | SWTError | RuntimeException unavailable) {
            if (browser != null && !browser.isDisposed()) browser.dispose();
            browser = null;
            guideTemplate = null;
            var plugin = Activator.getDefault();
            if (plugin != null) plugin.getLog().log(new Status(IStatus.WARNING, Activator.ID,
                    "Visual Getting Started unavailable; using the native SWT guide", unavailable));
            return false;
        }
    }

    private static String escapeHtml(String value) {
        return (value == null ? "" : value).replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private void renderGuide(ProjectSummary summary) {
        if (browser == null || browser.isDisposed() || guideTemplate == null) return;
        String html = guideTemplate
                .replace("{{PROJECT}}", escapeHtml(summary.name()))
                .replace("{{BOARD}}", escapeHtml(summary.board()))
                .replace("{{PROFILE}}", escapeHtml(summary.profile()))
                .replace("{{OUTPUT}}", escapeHtml(summary.output()))
                .replace("{{OUTPUT_CLASS}}", summary.hasElf() ? "info" : "warn")
                .replace("{{STEP}}", Integer.toString(currentStep));
        // No reload on unrelated workspace changes: preserves focus and scroll position.
        if (!html.equals(lastRendered)) {
            lastRendered = html;
            browser.setText(html);
        }
    }

    private void openProjectExplorer() {
        try {
            var explorer = getSite().getPage().showView("org.eclipse.ui.navigator.ProjectExplorer");
            explorer.setFocus();
        } catch (PartInitException error) {
            MessageDialog.openError(getSite().getShell(), "Project Explorer", error.getMessage());
        }
    }

    /** Show the selected task in the second column without running anything. */
    private void showStep(int step) {
        if (step < 0 || step >= STEPS.length) return;
        currentStep = step;
        if (content == null || content.isDisposed()) return;
        for (Control child : content.getChildren()) child.dispose();
        switch (step) {
            case 0:
                heading(content, "1   Open or create a project");
                line(content, "• What: Create a Zephyr application or import an existing one.");
                line(content, "• How: Open the wizard, then select the project in Project Explorer.");
                button(content, "New / Import Project...", () -> command("io.github.niwantha33.zew.quickStart"));
                button(content, "Open Project Explorer", this::openProjectExplorer);
                break;
            case 1:
                heading(content, "2   Choose your hardware");
                line(content, "• What: Select the exact board and processor target for this project.");
                line(content, "• How: Browse supported boards before changing the saved target.");
                line(content, "• Changing a board requires Project Properties; browsing does not.");
                button(content, "Browse Available Boards...", () -> command("io.github.niwantha33.zew.embeddedHubBoards"));
                button(content, "Explore Current Board Hardware...", () -> configuration("hardware"));
                button(content, "Advanced Board Settings...", this::properties);
                break;
            case 2:
                heading(content, "3   Configure the firmware");
                line(content, "• What: Explore GPIO, ADC, UART, I2C, SPI and other detected hardware.");
                line(content, "• How: Start with guided hardware, then use advanced settings only if needed.");
                line(content, "• Select a named build profile if you need different configurations.");
                button(content, "Explore Pins and Peripherals...", () -> configuration("hardware"));
                button(content, "Advanced Build and RTOS Settings...", () -> configuration("system"));
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
                button(content, "Developer Workspace", () -> command("io.github.niwantha33.zew.developerPerspective"));
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

    private record ProjectSummary(String name, String board, String profile,
                                  String output, boolean hasElf) {}

    private ProjectSummary projectSummary() {
        IProject project = project();
        if (project == null) return new ProjectSummary(
                "Select a Zephyr project", "Not selected", "Not selected",
                "No project selected", false);
        Path root = project.getLocation() == null ? null : project.getLocation().toFile().toPath();
        String target = "";
        try {
            if (root != null && Files.isRegularFile(root.resolve(".zew/project.json")))
                target = ProjectProfile.read(root).board();
            else if (root != null && Files.isRegularFile(root.resolve(".zew/project.properties")))
                target = ZephyrProjectFiles.readBoard(root);
            else target = ProjectContext.settings(project).getProperty("board", "");
        } catch (Exception ignored) { /* Never guess a board. */ }
        if (target == null || target.isBlank()) target = "Not configured";

        String activeProfile = "Configure in Project Properties";
        if (root != null) {
            try {
                var choices = BuildProfileChoices.available(root);
                var active = BuildProfileChoices.selected(choices,
                        project.getPersistentProperty(ProjectContext.BUILD_CONFIGURATION),
                        project.getPersistentProperty(ProjectContext.NAMED_PROFILE));
                activeProfile = active.label();
            } catch (Exception ignored) { /* Missing profile remains explicit. */ }
        }

        boolean hasElf = false;
        String output = "Build setup incomplete";
        try {
            Path elf = ProjectContext.buildDirectory(project, ProjectContext.settings(project))
                    .resolve("zephyr/zephyr.elf");
            hasElf = Files.isRegularFile(elf);
            output = hasElf ? "ELF present (not target-verified)" : "No ELF for active profile";
        } catch (Exception ignored) { /* Never imply that a build succeeded. */ }
        return new ProjectSummary(project.getName(), target, activeProfile, output, hasElf);
    }

    private void refresh() {
        ProjectSummary summary = projectSummary();
        if (browser != null && !browser.isDisposed()) {
            renderGuide(summary);
            return;
        }
        if (selected == null || selected.isDisposed()) return;
        selected.setText(summary.name());
        board.setText("Board: " + summary.board());
        profile.setText("Profile: " + summary.profile());
        buildOutput.setText("Build output: " + summary.output());
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
            IProject project = project();
            var page = getSite().getPage();
            if ("hardware".equals(section)) {
                var topology = (HardwareStudioView) page.showView(HardwareStudioView.ID);
                topology.displayCurrentProject(project);
            } else {
                var home = (ConfigurationHomeView) page.showView(ConfigurationHomeView.ID);
                if (project == null) home.displayMissingProject();
                else home.displayProject(project);
                home.openSection(section);
            }
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
        if (browser != null && !browser.isDisposed()) browser.setFocus();
        else if (navigation != null && !navigation.isDisposed()) navigation.setFocus();
    }

    @Override public void dispose() {
        if (selectionListener != null)
            getSite().getWorkbenchWindow().getSelectionService().removeSelectionListener(selectionListener);
        if (resourceListener != null)
            ResourcesPlugin.getWorkspace().removeResourceChangeListener(resourceListener);
        super.dispose();
    }
}
