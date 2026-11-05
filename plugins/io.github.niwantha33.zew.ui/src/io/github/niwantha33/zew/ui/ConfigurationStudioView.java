// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.TableEditor;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.ViewPart;
import io.github.niwantha33.zew.core.ConfigurationDiagnosis;
import io.github.niwantha33.zew.core.ConfigurationStudioFiles;
import io.github.niwantha33.zew.core.DevicetreeHardware;
import io.github.niwantha33.zew.core.DevicetreeBindingInspector;
import io.github.niwantha33.zew.core.DevicetreeRealConfigureInspector;
import io.github.niwantha33.zew.core.PeripheralEvidenceInspector;
import io.github.niwantha33.zew.core.PeripheralGuideInspector;
import io.github.niwantha33.zew.core.DevicetreeDraftConfigureInspector;
import io.github.niwantha33.zew.core.GuidedOverlaySave;
import io.github.niwantha33.zew.core.GpioBankEvidence;
import io.github.niwantha33.zew.core.GpioSourceEvidence;
import io.github.niwantha33.zew.core.HardwarePeripheralIndex;
import io.github.niwantha33.zew.core.PeripheralCoverage;
import io.github.niwantha33.zew.core.VendorFeatureCatalogue;
import io.github.niwantha33.zew.core.PeripheralStartGuide;
import io.github.niwantha33.zew.core.DevicetreeParameters;
import io.github.niwantha33.zew.core.DevicetreeDependencyEvidence;
import io.github.niwantha33.zew.core.DevicetreeSourceExplorer;
import io.github.niwantha33.zew.core.DevicetreeSourceTrace;
import io.github.niwantha33.zew.core.BeginnerHardwareGuide;
import io.github.niwantha33.zew.core.DevicetreeOverlayChanges;
import io.github.niwantha33.zew.core.ConfigurationStudioPresentation;
import io.github.niwantha33.zew.core.ConfigurationStudioDomains;
import io.github.niwantha33.zew.core.ConfigurationStudioDomains.Domain;
import io.github.niwantha33.zew.core.KconfigSourceHelp;
import io.github.niwantha33.zew.core.KconfigInspector;
import io.github.niwantha33.zew.core.ConfigurationStudioFiles.Option;
import io.github.niwantha33.zew.core.ConfigurationStudioFiles.Snapshot;

/**
 * Project-scoped native Eclipse configuration explorer.
 *
 * Effective Kconfig and compiled Devicetree are read-only. All application
 * configuration edits require a review, a backup, and a subsequent Zephyr build.
 * This view deliberately does not fabricate vendor pinmux or clock solutions.
 */
public final class ConfigurationStudioView extends ViewPart {
    public static final String ID = Activator.ID + ".configurationStudio";
    private static final int START_TAB = 0, SOFTWARE_TAB = 1, HARDWARE_TAB = 2, EVIDENCE_TAB = 3;
    private IProject project;
    private Snapshot snapshot;
    private Text search, help, tree, analysis, codeExample;
    private org.eclipse.swt.widgets.List peripheralCategories;
    private Text peripheralDescription;
    private Button peripheralParameters;
    private Domain activeDomain = ConfigurationStudioDomains.OVERVIEW;
    private TableEditor inlineToggleEditor;
    private Composite inlineToggle;
    private Table options;
    private org.eclipse.swt.widgets.Tree hardwareDevices, hardwarePeripheralTree;
    private Text hardwareSearch;
    private Combo hardwareState, hardwareKind;
    private Label hardwareSummary, navigationLocation, vendorFeatureStatus;
    private Table vendorFeatures;
    private Text vendorFeatureDescription;
    private Button backButton, forwardButton;
    private final java.util.List<String> navigationHistory = new java.util.ArrayList<>();
    private int navigationPosition = -1;
    private Text hardwareDescription, hardwareInspector, hardwarePreview, hardwareValidation, gpioExample;
    private Combo startGuideCategory;
    private Text startGuideSteps, startGuideC, startGuideOverlay, startGuideConfig;
    private Label startGuideStatus;
    private Button copyStartCode;
    private PeripheralStartGuide.Guide currentStartGuide;
    private String proposedOverlayDraft = "";
    private record ReviewedGuidedDraft(IProject project, Path build, String overlayName,
            String node, String property, String intent, String value,
            String originalDtsSha, String originalOverlaySha, String additionSha,
            String proposedOverlaySha, String sourceAddition) {}
    private ReviewedGuidedDraft reviewedGuidedDraft;
    private DevicetreeDraftConfigureInspector.Result verifiedGuidedPreflight;
    private Job unsavedDraftJob;
    private final java.util.concurrent.atomic.AtomicInteger unsavedDraftGeneration =
            new java.util.concurrent.atomic.AtomicInteger();
    private Composite expertToolbar, expertMetrics;
    private Label beginnerHeadline, beginnerNext;
    private Text beginnerSteps, beginnerEvidence, beginnerGlossary;
    private BeginnerHardwareGuide.Goal beginnerGoal = BeginnerHardwareGuide.GOALS.get(0);
    private TabFolder hardwareDetails;
    private Table sourceFileList;
    private Text sourceFileContent;
    private Label sourceFileNotice;
    private DevicetreeSourceExplorer.Inventory sourceFileInventory;
    private Table sourceTraceMatches;
    private Text sourceTraceContent, sourceTraceComparison;
    private Label sourceTraceNotice;
    private TabFolder sourceTraceViews;
    private Button sourceTraceSave;
    private record StudioOverlayDraft(IProject project, String generatedDts, String overlayName,
            String node, String property, String requested,
            DevicetreeOverlayChanges.Preview preview) {}
    private StudioOverlayDraft sourceTraceDraft;
    private Table hardwareParameters, gpioUsageTable;
    private Label gpioUsageSummary;
    private Button addGpioButton;
    private Button showDtsGpioDeclarations, showUnverifiedSourcePatterns;
    private GpioBankEvidence.Bank selectedGpioBank;
    private int selectedGpioBit = -1;
    private List<GpioSourceEvidence.Call> selectedGpioSourceCalls = List.of();
    private record GpioUsageSelection(String controllerPath, int bit, String detail) {}
    private Label parameterTitle;
    private ChipPinCanvas chipPins;
    private Snapshot hardwarePropertiesSnapshot;
    private List<DevicetreeParameters.Property> builtHardwareProperties = List.of();
    private Button enableButton, disableButton, editButton, removeButton;
    private Combo category;
    private TabFolder tabs;
    private Label context, status, overview, detailTitle, detailMeta, detailNotice;
    private Label totalMetric, changesMetric, issuesMetric, targetMetric;
    private Font headingFont, metricFont;
    private boolean hasGeneratedConfiguration;
    private boolean buildOutdated;
    private String filter = "";
    private String selectedOverlay = "app.overlay";
    private Job helpJob;
    private Job diagnosisJob;
    private Job bindingJob;
    private Job peripheralEvidenceJob;
    private final java.util.concurrent.atomic.AtomicInteger peripheralEvidenceGeneration =
            new java.util.concurrent.atomic.AtomicInteger();
    private Job realConfigureJob;
    private Job guidedOverlayJob;
    private final java.util.concurrent.atomic.AtomicInteger guidedOverlayGeneration =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger realConfigureGeneration =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger bindingGeneration = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger diagnosisGeneration = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger helpGeneration = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.ConcurrentMap<String, String> definitionCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<String, KconfigInspector.Result> kconfigCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private Path inspectionBuild;
    private java.util.Properties inspectionSettings;

    @Override public void createPartControl(Composite parent) {
        var outer = new Composite(parent, SWT.NONE);
        outer.setLayout(new GridLayout(1, false));
        var fontData = parent.getFont().getFontData()[0];
        FontData bold = new FontData(fontData.getName(), fontData.getHeight(), SWT.BOLD);
        FontData prominent = new FontData(fontData.getName(), fontData.getHeight() + 2, SWT.BOLD);
        metricFont = new Font(parent.getDisplay(), bold);
        headingFont = new Font(parent.getDisplay(), prominent);

        var heading = new Composite(outer, SWT.NONE);
        heading.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        heading.setLayout(new GridLayout(2, false));

        var brand = new Composite(heading, SWT.NONE);
        brand.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        brand.setLayout(new GridLayout(1, false));
        Label name = new Label(brand, SWT.NONE);
        name.setText("ZEPHYR   /   CONFIGURATION STUDIO");
        name.setFont(headingFont);
        context = new Label(brand, SWT.WRAP);
        context.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        context.setText("Select a Zephyr application to explore its configuration.");

        var primary = new Composite(heading, SWT.NONE);
        primary.setLayout(new GridLayout(2, false));
        action(primary, "Project settings", this::openProjectSettings)
                .setToolTipText("Environment, exact target and build configuration");
        Button buildButton = action(primary, "Build & validate", this::build);
        buildButton.setToolTipText("Rebuild selected target to verify requested configuration values");

        var secondary = new Composite(outer, SWT.NONE);
        expertToolbar = secondary;
        secondary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        secondary.setLayout(new GridLayout(5, false));
        action(secondary, "Open prj.conf", () -> openResource("prj.conf"))
                .setToolTipText("Open the exact application configuration source");
        action(secondary, "Edit hardware overlay", this::openOverlay)
                .setToolTipText("Open the selected overlay; no pins or clocks are generated automatically");
        action(secondary, "Refresh", this::reload)
                .setToolTipText("Reload project and generated configuration after editing or building");
        action(secondary, "Diagnose", this::diagnoseConfiguration)
                .setToolTipText("Read-only checks of the selected build and, in Hardware, the selected peripheral");
        action(secondary, "Copy selected symbol", this::copySymbol)
                .setToolTipText("Copy CONFIG_<symbol> to the clipboard");

        Composite metrics = new Composite(outer, SWT.BORDER);
        expertMetrics = metrics;
        metrics.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        metrics.setLayout(new GridLayout(4, true));
        totalMetric = metric(metrics, "AVAILABLE", "—");
        changesMetric = metric(metrics, "PROJECT OVERRIDES", "—");
        issuesMetric = metric(metrics, "NEEDS REVIEW", "—");
        targetMetric = metric(metrics, "BUILD SNAPSHOT", "Not built");

        tabs = new TabFolder(outer, SWT.NONE);
        tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        Composite beginner = tab(tabs, "Start Here");
        Composite software = tab(tabs, "Software · Kconfig");
        software.setLayout(new GridLayout(1, false));

        var softwareSplit = new SashForm(software, SWT.HORIZONTAL);
        softwareSplit.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        var domainPanel = new Composite(softwareSplit, SWT.NONE);
        domainPanel.setLayout(new GridLayout(1, false));
        var domainHeading = new Label(domainPanel, SWT.NONE);
        domainHeading.setText("CATEGORIES");
        domainHeading.setFont(metricFont);
        peripheralCategories = new org.eclipse.swt.widgets.List(domainPanel,
                SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL);
        peripheralCategories.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        for (Domain domain : ConfigurationStudioDomains.TABS)
            peripheralCategories.add(domain.title());
        peripheralCategories.select(0);
        peripheralCategories.setToolTipText("Select a software area from the generated Kconfig snapshot.");
        peripheralDescription = new Text(domainPanel, SWT.BORDER | SWT.MULTI | SWT.WRAP
                | SWT.READ_ONLY | SWT.V_SCROLL);
        GridData descriptionSize = new GridData(SWT.FILL, SWT.CENTER, true, false);
        descriptionSize.heightHint = 105;
        peripheralDescription.setLayoutData(descriptionSize);
        peripheralParameters = action(domainPanel, "Parameters & pins →",
                () -> openPeripheralParameters(activeDomain));
        peripheralParameters.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        peripheralParameters.setToolTipText("Inspect actual controller, channel and pin route evidence");
        peripheralCategories.addListener(SWT.Selection, event -> {
            int index = peripheralCategories.getSelectionIndex();
            activeDomain = index < 0 ? ConfigurationStudioDomains.OVERVIEW
                    : ConfigurationStudioDomains.TABS.get(index);
            if (category != null && !category.isDisposed()) category.select(0);
            refreshOptions();
            refreshPeripheralInfo();
        });
        var softwareMain = new Composite(softwareSplit, SWT.NONE);
        softwareMain.setLayout(new GridLayout(1, false));
        softwareSplit.setWeights(new int[] {22, 78});

        var shortcuts = new Composite(softwareMain, SWT.NONE);
        shortcuts.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        shortcuts.setLayout(new GridLayout(4, false));
        shortcut(shortcuts, "All features", ConfigurationStudioPresentation.ALL);
        shortcut(shortcuts, "My changes", ConfigurationStudioPresentation.MINE);
        shortcut(shortcuts, "Needs review", ConfigurationStudioPresentation.REVIEW);
        shortcut(shortcuts, "RTOS & tracing", ConfigurationStudioPresentation.DEBUG);

        var navigation = new Composite(softwareMain, SWT.NONE);
        navigation.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        navigation.setLayout(new GridLayout(2, false));
        category = new Combo(navigation, SWT.DROP_DOWN | SWT.READ_ONLY);
        category.setItems(ConfigurationStudioPresentation.FILTERS);
        category.select(0);
        category.setToolTipText("Show a subsystem or project-owned settings; categories are approximate");
        category.addListener(SWT.Selection, event -> refreshOptions());
        search = new Text(navigation, SWT.SEARCH | SWT.ICON_SEARCH | SWT.CANCEL | SWT.BORDER);
        search.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        search.setMessage("Find any feature · paste CONFIG_ name or search by value…");
        search.addModifyListener(event -> {
            filter = search.getText();
            if (!filter.isBlank()) {
                selectDomain(ConfigurationStudioDomains.OVERVIEW);
                category.select(0);
            }
            refreshOptions();
        });
        search.addListener(SWT.DefaultSelection, event -> options.setFocus());
        search.addListener(SWT.Selection, event -> {
            if (event.detail == SWT.ICON_CANCEL) search.setText("");
        });
        search.addListener(SWT.KeyDown, event -> {
            if (event.keyCode == SWT.ESC) search.setText("");
        });
        getViewSite().getActionBars().setGlobalActionHandler(org.eclipse.ui.actions.ActionFactory.FIND.getId(),
                new org.eclipse.jface.action.Action() {
                    @Override public void run() { focusSearch(); }
                });

        overview = new Label(softwareMain, SWT.WRAP);
        overview.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        overview.setText("Select a feature to inspect its definition and change its project request.");

        var trail = new Composite(softwareMain, SWT.NONE);
        trail.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        trail.setLayout(new GridLayout(4, false));
        backButton = action(trail, "← Back", () -> navigateHistory(-1));
        backButton.setToolTipText("Previous inspected setting (Alt+Left)");
        forwardButton = action(trail, "Forward →", () -> navigateHistory(1));
        forwardButton.setToolTipText("Next inspected setting (Alt+Right)");
        backButton.setEnabled(false);
        forwardButton.setEnabled(false);
        navigationLocation = new Label(trail, SWT.WRAP);
        navigationLocation.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        action(trail, "Clear filters", this::clearFeatureFilters);

        var sash = new SashForm(softwareMain, SWT.VERTICAL);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        options = new Table(sash, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
        options.setHeaderVisible(true);
        options.setLinesVisible(true);
        for (String header : new String[] {"Kconfig symbol", "Feature", "Enable / Disable · Value", "Project request", "Review"}) {
            var column = new TableColumn(options, SWT.LEFT);
            column.setText(header);
        }
        // One native, keyboard-accessible Enable/Disable editor is reused for
        // the selected boolean row. Hundreds of native controls are NOT created
        // for large .config files.
        inlineToggleEditor = new TableEditor(options);
        inlineToggleEditor.grabHorizontal = true;
        inlineToggleEditor.minimumWidth = 154;
        inlineToggleEditor.minimumHeight = options.getItemHeight();
        inlineToggle = new Composite(options, SWT.NONE);
        GridLayout toggleLayout = new GridLayout(2, true);
        toggleLayout.marginWidth = 0;
        toggleLayout.marginHeight = 0;
        toggleLayout.horizontalSpacing = 1;
        inlineToggle.setLayout(toggleLayout);
        enableButton = new Button(inlineToggle, SWT.TOGGLE);
        enableButton.setText("Enable");
        enableButton.setToolTipText("Request CONFIG_<symbol>=y; review and backup before saving");
        enableButton.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        enableButton.addListener(SWT.Selection, event -> setToggle("y"));
        disableButton = new Button(inlineToggle, SWT.TOGGLE);
        disableButton.setText("Disable");
        disableButton.setToolTipText("Request CONFIG_<symbol>=n; review and backup before saving");
        disableButton.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        disableButton.addListener(SWT.Selection, event -> setToggle("n"));
        inlineToggle.setVisible(false);
        options.addListener(SWT.Selection, event -> {
            rememberSelection();
            describeSelected();
        });
        options.addListener(SWT.KeyDown, event -> {
            if ((event.stateMask & SWT.ALT) != 0 && event.keyCode == SWT.ARROW_LEFT) {
                navigateHistory(-1); event.doit = false;
            } else if ((event.stateMask & SWT.ALT) != 0 && event.keyCode == SWT.ARROW_RIGHT) {
                navigateHistory(1); event.doit = false;
            }
        });
        options.addControlListener(new ControlAdapter() {
            @Override public void controlResized(ControlEvent event) { sizeColumns(); }
        });
        options.addListener(SWT.DefaultSelection, event -> {
            Option o = selected();
            if (o != null && o.overridden()) openResource("prj.conf");
            else describeSelected();
        });
        installContextMenu();

        Composite detail = new Composite(sash, SWT.NONE);
        detail.setLayout(new GridLayout(1, false));

        detailTitle = new Label(detail, SWT.WRAP);
        detailTitle.setFont(headingFont);
        detailTitle.setText("Select a setting");
        detailTitle.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        detailMeta = new Label(detail, SWT.WRAP);
        detailMeta.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        detailMeta.setText("Effective value   •   Requested override   •   Source");

        detailNotice = new Label(detail, SWT.WRAP);
        detailNotice.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        detailNotice.setText("Changes are reviewed and backed up before saving.");

        var explanationTabs = new TabFolder(detail, SWT.NONE);
        explanationTabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        Composite infoPage = tab(explanationTabs, "Explanation & dependencies");
        infoPage.setLayout(new GridLayout(1, false));
        help = new Text(infoPage, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        help.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        Composite examplePage = tab(explanationTabs, "Example C");
        examplePage.setLayout(new GridLayout(1, false));
        codeExample = new Text(examplePage, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY
                | SWT.H_SCROLL | SWT.V_SCROLL);
        codeExample.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        Button copyExample = action(examplePage, "Copy example code", () -> {
            if (codeExample != null && !codeExample.isDisposed()) {
                Clipboard clipboard = new Clipboard(codeExample.getDisplay());
                try {
                    clipboard.setContents(new Object[] {codeExample.getText()},
                            new Transfer[] {TextTransfer.getInstance()});
                } finally { clipboard.dispose(); }
            }
        });
        copyExample.setToolTipText("Copy a template. Review the selected board's bindings before use.");

        Composite actions = new Composite(detail, SWT.NONE);
        actions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        actions.setLayout(new GridLayout(2, true));
        editButton = action(actions, "Edit value…", () -> editSelected(false));
        editButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        editButton.setToolTipText("Edit numeric, string and non-switch Kconfig requests");
        removeButton = action(actions, "Remove override…", () -> editSelected(true));
        removeButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        removeButton.setToolTipText("Remove from prj.conf; other fragments or defaults may still control the value");

        sash.setWeights(new int[] {62, 38});

        // Native Visual Hardware Studio 2.0: three interactive panes, with a
        // separate evidence/overlay dock. No generated or user files are edited
        // by navigating this view.
        Composite hardware = tab(tabs, "Hardware · Devicetree");
        hardware.setLayout(new GridLayout(1, false));
        Label warning = new Label(hardware, SWT.WRAP);
        warning.setText("VISUAL HARDWARE STUDIO 2.0  /  Last generated build  ·  "
                + "Logical routes are not physical package pins; verified package mapping is unavailable.");
        warning.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        var studioVertical = new SashForm(hardware, SWT.VERTICAL);
        studioVertical.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        var studioColumns = new SashForm(studioVertical, SWT.HORIZONTAL);

        // Left pane: peripheral-first navigation and lossless raw Devicetree.
        var devicePanel = new Composite(studioColumns, SWT.NONE);
        devicePanel.setLayout(new GridLayout(1, false));
        var deviceTitle = new Label(devicePanel, SWT.NONE);
        deviceTitle.setText("PERIPHERAL EXPLORER");
        deviceTitle.setFont(metricFont);
        var hardwareNavigation = new Composite(devicePanel, SWT.NONE);
        hardwareNavigation.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        hardwareNavigation.setLayout(new GridLayout(2, false));
        hardwareSearch = new Text(hardwareNavigation, SWT.SEARCH | SWT.ICON_SEARCH | SWT.CANCEL | SWT.BORDER);
        hardwareSearch.setMessage("Search peripherals, channels, addresses…");
        GridData searchLayout = new GridData(SWT.FILL, SWT.CENTER, true, false);
        searchLayout.horizontalSpan = 2;
        hardwareSearch.setLayoutData(searchLayout);
        hardwareKind = new Combo(hardwareNavigation, SWT.DROP_DOWN | SWT.READ_ONLY);
        hardwareKind.setItems(HardwarePeripheralIndex.FILTERS.toArray(String[]::new));
        hardwareKind.select(0);
        hardwareKind.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        hardwareKind.setToolTipText("Categories are derived from built node names and compatibles, not physical capabilities.");
        hardwareState = new Combo(hardwareNavigation, SWT.DROP_DOWN | SWT.READ_ONLY);
        hardwareState.setItems("All nodes", "Okay/default", "Disabled");
        hardwareState.select(0);
        hardwareState.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        var catalogueTools = new Composite(devicePanel, SWT.NONE);
        catalogueTools.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        catalogueTools.setLayout(new GridLayout(2, true));
        Button allPeripheralCategories = action(catalogueTools, "Show all DTS controllers", () -> {
            hardwareSearch.setText("");
            hardwareState.select(0);
            hardwareKind.select(0);
            refreshHardware();
        });
        allPeripheralCategories.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        allPeripheralCategories.setToolTipText("Clear category/search/status filters; only real generated Zephyr DTS nodes appear.");
        Button exactSilicon = action(catalogueTools, "Exact silicon catalogue (SVD)…",
                this::openExactSiliconCatalogue);
        exactSilicon.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        exactSilicon.setToolTipText("View exact vendor SVD peripheral/register descriptions, separate from Zephyr DTS.");
        var navTabs = new TabFolder(devicePanel, SWT.NONE);
        navTabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        var peripheralNav = tab(navTabs, "Peripherals");
        peripheralNav.setLayout(new GridLayout(1, false));
        hardwarePeripheralTree = new org.eclipse.swt.widgets.Tree(peripheralNav,
                SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
        hardwarePeripheralTree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        hardwarePeripheralTree.setToolTipText("Only controllers/channels detected from the built Devicetree. No unverified package pins.");
        hardwarePeripheralTree.addListener(SWT.Selection, event -> {
            if (hardwarePeripheralTree.getSelectionCount() == 0) return;
            invalidateGuidedDraftSelection();
            var device = hardwarePeripheralTree.getSelection()[0].getData();
            if (!(device instanceof DevicetreeHardware.Device selected)) return;
            for (var node : hardwareItems()) {
                var candidate = (DevicetreeHardware.Device) node.getData();
                if (candidate.path().equals(selected.path())) {
                    hardwareDevices.setSelection(node);
                    hardwareDevices.showSelection();
                    break;
                }
            }
            hardwareDescription.setText(DevicetreeHardware.explanation(selected));
            showHardwareParameters(selected);
        });
        hardwarePeripheralTree.addListener(SWT.DefaultSelection, event -> revealHardwareSource());

        var siliconPage = tab(navTabs, "Silicon / vendor");
        siliconPage.setLayout(new GridLayout(1, false));
        vendorFeatureStatus = new Label(siliconPage, SWT.WRAP);
        vendorFeatureStatus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        vendorFeatureStatus.setText("Silicon data requires an exact SoC and a verified vendor source.");
        vendorFeatures = new Table(siliconPage, SWT.BORDER | SWT.SINGLE
                | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        vendorFeatures.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        vendorFeatures.setHeaderVisible(true);
        vendorFeatures.setLinesVisible(true);
        for (String columnTitle : new String[] {"Peripheral", "Vendor silicon spec", "Build DTS", "Evidence"}) {
            var column = new TableColumn(vendorFeatures, SWT.LEFT);
            column.setText(columnTitle);
        }
        vendorFeatures.getColumn(0).setWidth(95);
        vendorFeatures.getColumn(1).setWidth(155);
        vendorFeatures.getColumn(2).setWidth(95);
        vendorFeatures.getColumn(3).setWidth(150);
        vendorFeatureDescription = new Text(siliconPage, SWT.MULTI | SWT.WRAP
                | SWT.READ_ONLY | SWT.V_SCROLL | SWT.BORDER);
        GridData siliconDetails = new GridData(SWT.FILL, SWT.CENTER, true, false);
        siliconDetails.heightHint = 85;
        vendorFeatureDescription.setLayoutData(siliconDetails);
        vendorFeatureDescription.setText("Silicon capability is not Zephyr driver/DTS integration, "
                + "electrical availability, or runtime use.");
        vendorFeatures.addListener(SWT.Selection, event -> {
            if (vendorFeatures.getSelectionCount() == 0) return;
            var data = vendorFeatures.getSelection()[0].getData();
            if (!(data instanceof VendorFeatureCatalogue.Feature feature)) return;
            var described = snapshot == null ? List.<DevicetreeHardware.Device>of()
                    : DevicetreeHardware.inspect(snapshot.devicetree());
            var covered = PeripheralCoverage.count(described, feature.category());
            vendorFeatureDescription.setText(feature.category() + " | TI silicon specification: "
                    + feature.silicon() + " " + feature.unit()
                    + "\n" + feature.details()
                    + "\nSelected Zephyr DTS nodes: " + covered.present()
                    + " (" + covered.enabled() + " okay/default, " + covered.disabled() + " disabled)."
                    + (covered.present() == 0 ? "\nNo corresponding node in this build; do not invent an overlay." : "")
                    + "\nVerified vendor source: " + feature.source());
        });
        var siliconActions = new Composite(siliconPage, SWT.NONE);
        siliconActions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        siliconActions.setLayout(new GridLayout(2, true));
        action(siliconActions, "Find exact register SVD…", this::openExactSiliconCatalogue);
        action(siliconActions, "Explore selected DTS category", () -> {
            if (vendorFeatures.getSelectionCount() == 0) return;
            var selected = vendorFeatures.getSelection()[0].getData();
            if (!(selected instanceof VendorFeatureCatalogue.Feature feature)) return;
            hardwareSearch.setText("");
            hardwareState.select(0);
            int categoryIndex = hardwareKind.indexOf(feature.category());
            hardwareKind.select(categoryIndex < 0 ? 0 : categoryIndex);
            refreshHardware();
            navTabs.setSelection(0);
        });

        var devicetreeNav = tab(navTabs, "Devicetree");
        devicetreeNav.setLayout(new GridLayout(1, false));
        var treeTools = new Composite(devicetreeNav, SWT.NONE);
        treeTools.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        treeTools.setLayout(new GridLayout(2, false));
        action(treeTools, "Expand all", () -> expandHardware(true));
        action(treeTools, "Collapse all", () -> expandHardware(false));
        hardwareDevices = new org.eclipse.swt.widgets.Tree(devicetreeNav,
                SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
        hardwareDevices.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        hardwareDevices.setHeaderVisible(true);
        hardwareDevices.setLinesVisible(true);
        for (String columnName : new String[] {"Device / hierarchy", "Compatible", "Node status"}) {
            var column = new org.eclipse.swt.widgets.TreeColumn(hardwareDevices, SWT.LEFT);
            column.setText(columnName);
        }
        hardwareDevices.addListener(SWT.Selection, event -> {
            invalidateGuidedDraftSelection();
            describeHardware();
        });
        hardwareDevices.addListener(SWT.DefaultSelection, event -> revealHardwareSource());

        hardwareSummary = new Label(devicePanel, SWT.WRAP);
        hardwareSummary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        hardwareDescription = new Text(devicePanel, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        GridData descriptionLayout = new GridData(SWT.FILL, SWT.CENTER, true, false);
        descriptionLayout.heightHint = 48;
        hardwareDescription.setLayoutData(descriptionLayout);
        hardwareDescription.setText("Build the exact board to discover peripheral controllers and channels.");
        hardwareSearch.addModifyListener(event -> refreshHardware());
        hardwareSearch.addListener(SWT.DefaultSelection, event -> hardwarePeripheralTree.setFocus());
        hardwareSearch.addListener(SWT.Selection, event -> {
            if (event.detail == SWT.ICON_CANCEL) hardwareSearch.setText("");
        });
        hardwareSearch.addListener(SWT.KeyDown, event -> {
            if (event.keyCode == SWT.ESC) hardwareSearch.setText("");
        });
        hardwareState.addListener(SWT.Selection, event -> refreshHardware());
        hardwareKind.addListener(SWT.Selection, event -> refreshHardware());

        // Centre pane: package metadata state + selected logical route planner.
        var plannerPanel = new Composite(studioColumns, SWT.NONE);
        plannerPanel.setLayout(new GridLayout(1, false));
        Label plannerTitle = new Label(plannerPanel, SWT.NONE);
        plannerTitle.setText("GPIO / PIN PLANNER");
        plannerTitle.setFont(metricFont);
        var modes = new Composite(plannerPanel, SWT.NONE);
        modes.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        modes.setLayout(new GridLayout(3, false));
        Button packageMode = new Button(modes, SWT.RADIO);
        packageMode.setText("Package");
        Button logicalMode = new Button(modes, SWT.RADIO);
        logicalMode.setText("Logical signals");
        Button connectionMode = new Button(modes, SWT.RADIO);
        connectionMode.setText("Connections");
        logicalMode.setSelection(true);
        var evidenceLayers = new Composite(plannerPanel, SWT.NONE);
        evidenceLayers.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        evidenceLayers.setLayout(new GridLayout(2, false));
        showDtsGpioDeclarations = new Button(evidenceLayers, SWT.CHECK);
        showDtsGpioDeclarations.setText("Board DTS declarations");
        showDtsGpioDeclarations.setToolTipText("Optional reference layer; board-provided nodes do not establish application use.");
        showUnverifiedSourcePatterns = new Button(evidenceLayers, SWT.CHECK);
        showUnverifiedSourcePatterns.setText("Unverified C patterns");
        showUnverifiedSourcePatterns.setToolTipText("Saved C source patterns may be excluded by Kconfig or the compiler.");
        parameterTitle = new Label(plannerPanel, SWT.WRAP);
        parameterTitle.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        parameterTitle.setText("Select a peripheral to explore the exact built controller and logical routes.");
        var chipScroll = new org.eclipse.swt.custom.ScrolledComposite(
                plannerPanel, SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL);
        chipScroll.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        chipPins = new ChipPinCanvas(chipScroll);
        showDtsGpioDeclarations.addListener(SWT.Selection,
                event -> chipPins.showGpioDeclarations(showDtsGpioDeclarations.getSelection()));
        showUnverifiedSourcePatterns.addListener(SWT.Selection,
                event -> chipPins.showSourcePatterns(showUnverifiedSourcePatterns.getSelection()));
        chipScroll.setContent(chipPins);
        chipScroll.setExpandHorizontal(true);
        chipScroll.setExpandVertical(true);
        chipScroll.setMinSize(540, 295);
        packageMode.addListener(SWT.Selection, event -> {
            if (packageMode.getSelection()) chipPins.setMode(ChipPinCanvas.Mode.PACKAGE);
        });
        logicalMode.addListener(SWT.Selection, event -> {
            if (logicalMode.getSelection()) chipPins.setMode(ChipPinCanvas.Mode.SIGNALS);
        });
        connectionMode.addListener(SWT.Selection, event -> {
            if (connectionMode.getSelection()) chipPins.setMode(ChipPinCanvas.Mode.CONNECTIONS);
        });
        Label legend = new Label(plannerPanel, SWT.WRAP);
        legend.setText("GPIO usage UNKNOWN until separately verified. All cells neutral by default."
                + " DTS and saved C patterns are optional reference layers, not live readings.");
        legend.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Right pane: exact built parameters and explicit, reviewed source edits.
        var parameterPanel = new Composite(studioColumns, SWT.NONE);
        parameterPanel.setLayout(new GridLayout(1, false));
        Label inspectorTitle = new Label(parameterPanel, SWT.NONE);
        inspectorTitle.setFont(metricFont);
        inspectorTitle.setText("PERIPHERAL PARAMETERS");
        hardwareInspector = new Text(parameterPanel, SWT.READ_ONLY | SWT.WRAP | SWT.MULTI | SWT.V_SCROLL | SWT.BORDER);
        GridData inspectorSize = new GridData(SWT.FILL, SWT.CENTER, true, false);
        inspectorSize.heightHint = 110;
        hardwareInspector.setLayoutData(inspectorSize);
        hardwareInspector.setText("Select a controller or channel. Current values come only from the last build.");
        hardwareParameters = new Table(parameterPanel,
                SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.H_SCROLL | SWT.V_SCROLL);
        hardwareParameters.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        hardwareParameters.setHeaderVisible(true);
        hardwareParameters.setLinesVisible(true);
        for (String caption : new String[] {"Property", "Built value", "Node / channel"}) {
            var column = new TableColumn(hardwareParameters, SWT.LEFT);
            column.setText(caption);
        }
        hardwareParameters.addListener(SWT.DefaultSelection, event -> prepareHardwareOverride());
        hardwareParameters.addListener(SWT.Selection, event -> {
            invalidateGuidedDraftSelection();
            if (hardwareParameters.getSelectionCount() == 0) return;
            var selected = (DevicetreeParameters.Property) hardwareParameters.getSelection()[0].getData();
            chipPins.selectRoute(selected.path(), selected.name());
            updateHardwareInspector(selected);
        });
        var parameterActions = new Composite(parameterPanel, SWT.NONE);
        parameterActions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        parameterActions.setLayout(new GridLayout(2, true));
        Button configure = action(parameterActions, "Configure property…", this::prepareHardwareOverride);
        action(parameterActions, "Guided binding draft…", this::prepareGuidedOverlay)
                .setToolTipText("Upstream edtlib checks the selected binding; preview a source diff without saving");
        configure.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button source = action(parameterActions, "Show declaration", this::revealParameterSource);
        source.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        action(parameterActions, "Trace DTS / overlay →", this::showSourceTrace)
                .setToolTipText("Connect selected generated property with possible original DTS/DTSI and overlay declarations.");
        studioColumns.setWeights(new int[] {22, 45, 33});

        // Bottom evidence dock: propose changes in-memory; generated files
        // remain read-only; existing overlay commit remains explicitly reviewed.
        hardwareDetails = new TabFolder(studioVertical, SWT.NONE);
        var changesPage = tab(hardwareDetails, "Changes / app.overlay");
        changesPage.setLayout(new GridLayout(1, false));
        var changeToolbar = new Composite(changesPage, SWT.NONE);
        changeToolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        changeToolbar.setLayout(new GridLayout(4, false));
        action(changeToolbar, "Edit application overlay…", this::openOverlay);
        action(changeToolbar, "Copy proposed change", this::copyHardwarePreview);
        action(changeToolbar, "Test unsaved draft with CMake…", this::testUnsavedGuidedDraft)
                .setToolTipText("Opt-in: actual West/CMake on disposable source, exact generated value verified");
        action(changeToolbar, "Save verified draft with backup…", this::saveVerifiedGuidedDraft)
                .setToolTipText("Explicitly save only the exact, successfully preflighted guided addition with an atomic backup");
        hardwarePreview = new Text(changesPage,
                SWT.BORDER | SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.READ_ONLY);
        hardwarePreview.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        hardwarePreview.setText("No proposed changes. Select a built property and choose Configure property…"
                + "\nA reviewed draft appears here. No file is written automatically.");
        var validationPage = tab(hardwareDetails, "Validation / evidence");
        validationPage.setLayout(new GridLayout(1, false));
        hardwareValidation = new Text(validationPage,
                SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.READ_ONLY | SWT.V_SCROLL);
        hardwareValidation.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        hardwareValidation.setText("No selected build. Build the exact target before checking configuration.");
        var sourcePage = tab(hardwareDetails, "Generated DTS (read-only)");
        sourcePage.setLayout(new GridLayout(1, false));
        tree = new Text(sourcePage,
                SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        tree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));


        // Evidence across ALL GPIO controllers, not only the selected bank.
        // Unresolved C calls remain separate: no inferred physical/logical bank.
        var gpioUsagePage = tab(hardwareDetails, "GPIO Evidence");
        gpioUsagePage.setLayout(new GridLayout(1, false));
        gpioUsageSummary = new Label(gpioUsagePage, SWT.WRAP);
        gpioUsageSummary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        gpioUsageSummary.setText("GPIO usage UNKNOWN. Inspect board declarations and unverified source patterns.");
        gpioUsageTable = new Table(gpioUsagePage,
                SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.H_SCROLL | SWT.V_SCROLL);
        gpioUsageTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        gpioUsageTable.setHeaderVisible(true);
        gpioUsageTable.setLinesVisible(true);
        for (String columnTitle : new String[] {"GPIO / bit", "Evidence", "Reference", "Location"}) {
            var usageColumn = new TableColumn(gpioUsageTable, SWT.LEFT);
            usageColumn.setText(columnTitle);
        }
        gpioUsageTable.getColumn(0).setWidth(125);
        gpioUsageTable.getColumn(1).setWidth(145);
        gpioUsageTable.getColumn(2).setWidth(340);
        gpioUsageTable.getColumn(3).setWidth(350);
        gpioUsageTable.addListener(SWT.Selection, event -> inspectSelectedGpioUsage());

        var gpioExamplePage = tab(hardwareDetails, "GPIO Example C");
        gpioExamplePage.setLayout(new GridLayout(1, false));
        gpioExample = new Text(gpioExamplePage, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.H_SCROLL | SWT.V_SCROLL);
        gpioExample.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        gpioExample.setText(io.github.niwantha33.zew.core.GpioExampleCode.selectedIndex(null, -1, java.util.Map.of()));
        action(gpioExamplePage, "Copy GPIO example", () -> {
            var clipboard = new Clipboard(gpioExample.getDisplay());
            try { clipboard.setContents(new Object[] {gpioExample.getText()}, new Transfer[] {TextTransfer.getInstance()}); }
            finally { clipboard.dispose(); }
        });

        // Built-DTS-first getting-started workflow. This is source guidance,
        // never an automatic board pin assignment or generated DTS edit.
        var startPage = tab(hardwareDetails, "Start peripheral");
        startPage.setLayout(new GridLayout(1, false));
        var startBar = new Composite(startPage, SWT.NONE);
        startBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        startBar.setLayout(new GridLayout(5, false));
        new Label(startBar, SWT.NONE).setText("Peripheral");
        startGuideCategory = new Combo(startBar, SWT.DROP_DOWN | SWT.READ_ONLY);
        startGuideCategory.setItems(HardwarePeripheralIndex.FILTERS.stream()
                .filter(kind -> !HardwarePeripheralIndex.ALL.equals(kind)
                        && !"Other".equals(kind)).toArray(String[]::new));
        startGuideCategory.select(startGuideCategory.indexOf("ADC"));
        startGuideCategory.addListener(SWT.Selection, event -> {
            if (hardwareKind.indexOf(startGuideCategory.getText()) >= 0) {
                hardwareKind.select(hardwareKind.indexOf(startGuideCategory.getText()));
                hardwareSearch.setText("");
                hardwareState.select(0);
                refreshHardware();
            }
            refreshStartGuide();
        });
        action(startBar, "Find controller in DTS", () -> {
            String category = startGuideCategory.getText();
            hardwareKind.select(hardwareKind.indexOf(category));
            hardwareSearch.setText("");
            hardwareState.select(0);
            refreshHardware();
            tabs.setSelection(HARDWARE_TAB);
            var nav = (org.eclipse.swt.widgets.TabFolder) hardwarePeripheralTree.getParent().getParent();
            nav.setSelection(0);
        }).setToolTipText("Navigate only real controller nodes from this exact generated DTS.");
        action(startBar, "Inspect generated DTS", () -> {
            selectHardwareDetails(2);
            if (hardwareDevices.getSelectionCount() > 0) revealHardwareSource();
        });
        action(startBar, "Official Zephyr guide / sample", this::openStartGuideSample);
        startGuideStatus = new Label(startPage, SWT.WRAP);
        startGuideStatus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        startGuideStatus.setText("Choose the peripheral and build the exact target.");
        var guideSplit = new SashForm(startPage, SWT.HORIZONTAL);
        guideSplit.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        var stepsPanel = new Composite(guideSplit, SWT.NONE);
        stepsPanel.setLayout(new GridLayout(1, false));
        new Label(stepsPanel, SWT.NONE).setText("UNDERSTAND THE SOC DTS → CONNECT THE APPLICATION");
        startGuideSteps = new Text(stepsPanel, SWT.BORDER | SWT.READ_ONLY | SWT.MULTI
                | SWT.WRAP | SWT.V_SCROLL);
        startGuideSteps.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        startGuideSteps.setText("Select ADC to learn how the controller, channel and io-channels connect.");
        var codePanel = new Composite(guideSplit, SWT.NONE);
        codePanel.setLayout(new GridLayout(1, false));
        new Label(codePanel, SWT.NONE).setText("REFERENCE CODE — NEVER AUTO-WRITTEN");
        startGuideC = new Text(codePanel, SWT.BORDER | SWT.READ_ONLY | SWT.MULTI
                | SWT.H_SCROLL | SWT.V_SCROLL);
        startGuideC.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        startGuideC.setText("A C scaffold appears only when the generated ADC io-channels wiring is verified.");
        guideSplit.setWeights(new int[] {52, 48});
        var snippetBar = new Composite(startPage, SWT.NONE);
        snippetBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        snippetBar.setLayout(new GridLayout(8, false));
        copyStartCode = action(snippetBar, "Copy C scaffold", () -> copyStartGuideText(
                currentStartGuide == null ? "" : currentStartGuide.cExample()));
        action(snippetBar, "Open src/main.c", () -> openExistingStartFile("src/main.c"));
        action(snippetBar, "Open prj.conf", () -> openExistingStartFile("prj.conf"));
        action(snippetBar, "Open app.overlay", this::openOverlay);
        action(snippetBar, "Check selected binding", () -> inspectSelectedBinding(false))
                .setToolTipText("Inspect the upstream Zephyr binding for the selected generated controller.");
        action(snippetBar, "Build & validate", this::build)
                .setToolTipText("Build the exact project/profile and regenerate effective DTS and Kconfig.");
        action(snippetBar, "Copy Kconfig review", () -> copyStartGuideText(
                currentStartGuide == null ? "" : currentStartGuide.configExample()));
        action(snippetBar, "Copy overlay guidance", () -> copyStartGuideText(
                currentStartGuide == null ? "" : currentStartGuide.overlayExample()));
        startGuideConfig = new Text(startPage, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.WRAP);
        GridData cfgLayout = new GridData(SWT.FILL, SWT.CENTER, true, false);
        cfgLayout.heightHint = 40;
        startGuideConfig.setLayoutData(cfgLayout);
        startGuideOverlay = new Text(startPage, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.WRAP);
        GridData overlayLayout = new GridData(SWT.FILL, SWT.CENTER, true, false);
        overlayLayout.heightHint = 45;
        startGuideOverlay.setLayoutData(overlayLayout);
        copyStartCode.setEnabled(false);

        // A native read-only browser for exact Zephyr DTS/DTSI build inputs.
        var sourceBrowserPage = tab(hardwareDetails, "DTS / DTSI Sources");
        sourceBrowserPage.setLayout(new GridLayout(1, false));
        sourceFileNotice = new Label(sourceBrowserPage, SWT.WRAP);
        sourceFileNotice.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        sourceFileNotice.setText("Build the selected target to discover its source dependency files.");
        var sourceButtons = new Composite(sourceBrowserPage, SWT.NONE);
        sourceButtons.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        sourceButtons.setLayout(new GridLayout(3, false));
        action(sourceButtons, "Refresh sources", this::refreshSourceBrowser);
        action(sourceButtons, "Edit selected application overlay…", this::editSelectedSourceOverlay)
                .setToolTipText("Only a project-owned overlay can be edited; vendor DTS/DTSI remain read-only.");
        action(sourceButtons, "Create / open app.overlay…", this::openOverlay);
        var sourceSash = new SashForm(sourceBrowserPage, SWT.HORIZONTAL);
        // Keep the browser's source area usable on compact Eclipse workbenches.
        GridData sourceSashSize = new GridData(SWT.FILL, SWT.FILL, true, true);
        sourceSashSize.minimumHeight = 160;
        sourceSash.setLayoutData(sourceSashSize);
        sourceFileList = new Table(sourceSash, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION
                | SWT.V_SCROLL | SWT.H_SCROLL);
        sourceFileList.setHeaderVisible(true);
        sourceFileList.setLinesVisible(true);
        for (String caption : new String[] {"File", "Role", "Evidence"}) {
            var column = new TableColumn(sourceFileList, SWT.LEFT);
            column.setText(caption);
        }
        sourceFileList.getColumn(0).setWidth(225);
        sourceFileList.getColumn(1).setWidth(180);
        sourceFileList.getColumn(2).setWidth(220);
        sourceFileContent = new Text(sourceSash, SWT.BORDER | SWT.MULTI
                | SWT.READ_ONLY | SWT.H_SCROLL | SWT.V_SCROLL);
        sourceFileContent.setText("Select a source file to inspect it without modifying upstream Zephyr files.");
        sourceFileList.addListener(SWT.Selection, event -> displaySelectedSource());
        sourceSash.setWeights(new int[] {38, 62});



        // Integrated, build-qualified node → source → overlay workflow. Exact
        // original files are inspected read-only; only application-owned
        // overlays can be saved after an explicit reviewed preview.
        var tracePage = tab(hardwareDetails, "Node → Source → Overlay");
        tracePage.setLayout(new GridLayout(1, false));
        var traceActions = new Composite(tracePage, SWT.NONE);
        traceActions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        traceActions.setLayout(new GridLayout(4, false));
        action(traceActions, "Trace selected property", this::showSourceTrace);
        action(traceActions, "Preview overlay change…", this::draftSourceTraceOverlay);
        sourceTraceSave = action(traceActions, "Save reviewed overlay…", this::saveSourceTraceOverlay);
        sourceTraceSave.setEnabled(false);
        action(traceActions, "Open app.overlay", this::openOverlay);
        action(traceActions, "Inspect selected binding", () -> inspectSelectedBinding(false));
        action(traceActions, "Real configure + overlay", () -> validateRealDevicetree(true));
        action(traceActions, "Start selected peripheral", () -> openStartGuide(null));
        action(traceActions, "Refresh source inventory", this::refreshSourceBrowser);
        sourceTraceNotice = new Label(tracePage, SWT.WRAP);
        sourceTraceNotice.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        sourceTraceNotice.setText("Select a built peripheral and one of its properties, then Trace selected property.");
        sourceTraceViews = new TabFolder(tracePage, SWT.NONE);
        sourceTraceViews.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        var traceCandidatesPage = tab(sourceTraceViews, "Source declarations (read-only)");
        traceCandidatesPage.setLayout(new GridLayout(1, false));
        var traceSplit = new SashForm(traceCandidatesPage, SWT.HORIZONTAL);
        traceSplit.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        sourceTraceMatches = new Table(traceSplit, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION
                | SWT.V_SCROLL | SWT.H_SCROLL);
        sourceTraceMatches.setHeaderVisible(true);
        sourceTraceMatches.setLinesVisible(true);
        for (String caption : new String[] {"Source / line", "Evidence", "Declared value"}) {
            var column = new TableColumn(sourceTraceMatches, SWT.LEFT);
            column.setText(caption);
        }
        sourceTraceMatches.getColumn(0).setWidth(230);
        sourceTraceMatches.getColumn(1).setWidth(245);
        sourceTraceMatches.getColumn(2).setWidth(160);
        sourceTraceContent = new Text(traceSplit, SWT.BORDER | SWT.READ_ONLY
                | SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
        sourceTraceContent.setText("No source declaration selected. Original vendor files remain read-only.");
        sourceTraceMatches.addListener(SWT.Selection, event -> displaySourceTraceMatch());
        traceSplit.setWeights(new int[] {45, 55});
        var traceDifferencePage = tab(sourceTraceViews, "Built → proposed overlay");
        traceDifferencePage.setLayout(new GridLayout(1, false));
        sourceTraceComparison = new Text(traceDifferencePage, SWT.BORDER | SWT.READ_ONLY
                | SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
        sourceTraceComparison.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        sourceTraceComparison.setText("No proposed change. Built values are not changed until an actual Zephyr configure.");

        studioVertical.setWeights(new int[] {58, 42});
        // Browsing original DTS/DTSI requires usable space for the file tree and
        // its read-only source. Maximise the evidence pane while this tab is open;
        // returning to another dock tab restores the interactive chip planner.
        hardwareDetails.addListener(SWT.Selection, event -> updateHardwareDetailsLayout());

        // A fourteen-button four-row footer previously squeezed the evidence
        // dock to a tab strip at 1024x768. Keep every action but use one
        // horizontally scrollable, keyboard-reachable toolbar.
        var actionsScroll = new org.eclipse.swt.custom.ScrolledComposite(
                hardware, SWT.H_SCROLL | SWT.BORDER);
        GridData actionsSize = new GridData(SWT.FILL, SWT.CENTER, true, false);
        actionsSize.heightHint = 56;
        actionsScroll.setLayoutData(actionsSize);
        var hardwareActions = new Composite(actionsScroll, SWT.NONE);
        GridLayout actionsRow = new GridLayout(14, false);
        actionsRow.marginWidth = 2;
        actionsRow.marginHeight = 2;
        hardwareActions.setLayout(actionsRow);
        actionsScroll.setContent(hardwareActions);
        action(hardwareActions, "Draw SoC memory map", this::openSocMemoryMap)
                .setToolTipText("Draw RAM, flash, partitions and peripheral reg ranges from the exact generated DTS.");
        action(hardwareActions, "Reveal in source", this::revealHardwareSource);
        action(hardwareActions, "Copy node path", this::copyHardwarePath);
        action(hardwareActions, "Diagnose selected", this::diagnoseConfiguration);
        action(hardwareActions, "Validate selected binding", () -> inspectSelectedBinding(false));
        action(hardwareActions, "Guided overlay draft…", this::prepareGuidedOverlay)
                .setToolTipText("Review a binding-checked status, integer or enum source request; no writes");
        action(hardwareActions, "Peripheral evidence…", this::inspectPeripheralEvidence)
                .setToolTipText("Exact generated DTS and optional independent CONFIG_ symbol; no pin or driver assumptions");
        action(hardwareActions, "Preview saved overlay binding", () -> inspectSelectedBinding(true));
        action(hardwareActions, "Real configure (scratch)", () -> validateRealDevicetree(false))
                .setToolTipText("Run actual Zephyr CMake with copied source and a disposable build; explicit confirmation required");
        action(hardwareActions, "Real configure + saved overlay", () -> validateRealDevicetree(true))
                .setToolTipText("Apply saved overlay additionally in disposable Zephyr configure; no firmware is flashed");
        action(hardwareActions, "Edit application overlay…", this::openOverlay);
        action(hardwareActions, "GPIO example C", () -> selectHardwareDetails(4));
        action(hardwareActions, "Start ADC reading →", () -> openStartGuide("ADC"));
        action(hardwareActions, "Start selected peripheral →", () -> openStartGuide(null));
        addGpioButton = action(hardwareActions, "Add DTS GPIO…", this::prepareNewGpio);
        addGpioButton.setEnabled(false);
        addGpioButton.setToolTipText("Select an unreferenced logical index. Creates a reviewed source request, not an availability or live-state claim.");
        hardwareActions.setSize(hardwareActions.computeSize(SWT.DEFAULT, SWT.DEFAULT));

        Composite reports = tab(tabs, "Evidence && Diagnostics");
        reports.setLayout(new GridLayout(1, false));
        var evidenceActions = new Composite(reports, SWT.NONE);
        evidenceActions.setLayout(new GridLayout(3, false));
        action(evidenceActions, "Export evidence…", this::exportEvidence);
        action(evidenceActions, "Open evidence…", () -> openEvidence(false));
        action(evidenceActions, "Compare evidence…", () -> openEvidence(true));
        action(evidenceActions, "Validate selected binding", () -> inspectSelectedBinding(false));
        action(evidenceActions, "Peripheral evidence…", this::inspectPeripheralEvidence);
        action(evidenceActions, "Guided overlay draft…", this::prepareGuidedOverlay);
        action(evidenceActions, "Preview saved overlay binding", () -> inspectSelectedBinding(true));
        action(evidenceActions, "Real configure (scratch)", () -> validateRealDevicetree(false));
        action(evidenceActions, "Real configure + saved overlay", () -> validateRealDevicetree(true));
        analysis = new Text(reports, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        analysis.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));


        // A task-first landing page; the underlying evidence, driver, DTS,
        // Kconfig and overlay checks still live in the existing advanced views.
        // The first click is an intent ("read a voltage"), not a CONFIG_ symbol.
        beginner.setLayout(new GridLayout(1, false));
        Label beginnerTitle = new Label(beginner, SWT.NONE);
        beginnerTitle.setFont(headingFont);
        beginnerTitle.setText("WHAT DO YOU WANT YOUR BOARD TO DO?");
        Label beginnerIntro = new Label(beginner, SWT.WRAP);
        beginnerIntro.setText("Pick one small job. We will show the real hardware described by "
                + "your board, explain what is missing, and lead you to the right setup."
                + " You do not need to understand DTS or Kconfig first.");
        beginnerIntro.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        var simpleGoals = new Composite(beginner, SWT.NONE);
        simpleGoals.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        simpleGoals.setLayout(new GridLayout(3, true));
        for (var goal : BeginnerHardwareGuide.GOALS) {
            Button choose = action(simpleGoals, goal.title(), () -> chooseBeginnerGoal(goal));
            choose.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            choose.setToolTipText(goal.plainEnglish()
                    + "\nNo pins, sensors or supported drivers are assumed.");
        }

        beginnerHeadline = new Label(beginner, SWT.WRAP);
        beginnerHeadline.setFont(metricFont);
        beginnerHeadline.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        beginnerNext = new Label(beginner, SWT.WRAP);
        beginnerNext.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        var simpleActions = new Composite(beginner, SWT.NONE);
        simpleActions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        simpleActions.setLayout(new GridLayout(4, true));
        action(simpleActions, "1. Find my hardware", this::showBeginnerHardware)
                .setToolTipText("Only nodes evidenced by the selected generated Zephyr DTS.");
        action(simpleActions, "2. Help me set it up", this::setupBeginnerHardware)
                .setToolTipText("Guidance checks actual peripheral and binding evidence, not guessed pins.");
        action(simpleActions, "3. Build & check", this::build)
                .setToolTipText("Run the real Zephyr build to check configuration requests.");
        action(simpleActions, "Advanced settings", () -> {
            tabs.setSelection(SOFTWARE_TAB);
            updateBeginnerChrome();
        });
        for (Control item : simpleActions.getChildren())
            item.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        beginnerSteps = new Text(beginner, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.READ_ONLY
                | SWT.V_SCROLL);
        beginnerSteps.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        beginnerSteps.setText("Pick a job to begin.");

        var more = new Composite(beginner, SWT.NONE);
        more.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        more.setLayout(new GridLayout(2, false));
        action(more, "Why DTS, overlays and Kconfig?", this::toggleBeginnerGlossary)
                .setToolTipText("A plain-English explanation of the Zephyr configuration files.");
        action(more, "Draw SoC memory map", this::openSocMemoryMap);
        action(more, "Show build evidence", () -> {
            if (beginnerEvidence != null && !beginnerEvidence.isDisposed())
                MessageDialog.openInformation(getSite().getShell(), "Evidence from this board",
                        beginnerEvidence.getText());
        });
        beginnerGlossary = new Text(beginner, SWT.BORDER | SWT.MULTI | SWT.WRAP
                | SWT.READ_ONLY | SWT.V_SCROLL);
        GridData glossarySize = new GridData(SWT.FILL, SWT.CENTER, true, false);
        glossarySize.heightHint = 160;
        glossarySize.exclude = true;
        beginnerGlossary.setLayoutData(glossarySize);
        beginnerGlossary.setVisible(false);
        beginnerGlossary.setText(
                "Think of building something with blocks:\n\n"
                + "DTSI — describes parts shared by a chip family. It is usually supplied by Zephyr.\n"
                + "DTS — describes how your selected board uses those parts.\n"
                + "OVERLAY — your project's request to change the board description "
                + "without editing vendor files. It must be included in the build.\n"
                + "KCONFIG — chooses which Zephyr software/driver features get compiled.\n"
                + "C CODE — tells an enabled, correctly configured device what to do.\n\n"
                + "BUILD — checks and combines the configuration. The generated zephyr.dts "
                + "is what Devicetree finally says; it does not prove physical wiring.");
        beginnerEvidence = new Text(beginner, SWT.READ_ONLY | SWT.MULTI);
        beginnerEvidence.setVisible(false);
        GridData evidenceSize = new GridData(SWT.FILL, SWT.CENTER, false, false);
        evidenceSize.exclude = true;
        beginnerEvidence.setLayoutData(evidenceSize);
        beginnerEvidence.setText("No generated build evidence.");
        tabs.addListener(SWT.Selection, event -> updateBeginnerChrome());

        status = new Label(outer, SWT.WRAP);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        status.setText("Select a Zephyr project to inspect it safely.");
        reload();
        // For an ordinary Configuration Studio launch, start with the user's
        // desired task. Explicit Hardware / Features navigation still wins.
        tabs.setSelection(START_TAB);
        updateBeginnerChrome();
    }

    private Label metric(Composite parent, String caption, String initial) {
        var cell = new Composite(parent, SWT.NONE);
        cell.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        cell.setLayout(new GridLayout(1, false));
        Label heading = new Label(cell, SWT.NONE);
        heading.setText(caption);
        Label value = new Label(cell, SWT.NONE);
        value.setFont(metricFont);
        value.setText(initial);
        value.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return value;
    }

    private void shortcut(Composite parent, String caption, String value) {
        action(parent, caption, () -> {
            if (category == null || category.isDisposed()) return;
            selectDomain(ConfigurationStudioDomains.OVERVIEW);
            int i = java.util.Arrays.asList(ConfigurationStudioPresentation.FILTERS).indexOf(value);
            if (i >= 0) category.select(i);
            refreshOptions();
        });
    }

    private void copySymbol() {
        Option selection = selected();
        if (selection == null) return;
        Clipboard clipboard = new Clipboard(options.getDisplay());
        try {
            clipboard.setContents(new Object[] {"CONFIG_" + selection.symbol()},
                    new Transfer[] {TextTransfer.getInstance()});
            status.setText("Copied CONFIG_" + selection.symbol() + " to the clipboard.");
        } finally { clipboard.dispose(); }
    }

    private void installContextMenu() {
        // SWT does not select a table row merely because the user right-clicks it.
        // Bind menu actions to the row under the pointer, never a stale selection.
        options.addListener(SWT.MouseDown, event -> {
            if (event.button != 3) return;
            TableItem clicked = options.getItem(new Point(event.x, event.y));
            if (clicked != null) {
                options.setSelection(clicked);
                describeSelected();
            } else {
                options.deselectAll();
                describeSelected();
            }
        });
        Menu menu = new Menu(options);
        options.setMenu(menu);
        MenuItem copy = new MenuItem(menu, SWT.PUSH);
        copy.setText("Copy CONFIG_ symbol");
        copy.addListener(SWT.Selection, event -> copySymbol());
        MenuItem source = new MenuItem(menu, SWT.PUSH);
        source.setText("Open prj.conf");
        source.addListener(SWT.Selection, event -> openResource("prj.conf"));
        new MenuItem(menu, SWT.SEPARATOR);
        MenuItem change = new MenuItem(menu, SWT.PUSH);
        change.setText("Enable selected setting");
        change.addListener(SWT.Selection, event -> setToggle("y"));
        MenuItem turnOff = new MenuItem(menu, SWT.PUSH);
        turnOff.setText("Disable selected setting");
        turnOff.addListener(SWT.Selection, event -> setToggle("n"));
        menu.addListener(SWT.Show, event -> {
            Option o = selected();
            copy.setEnabled(o != null);
            change.setEnabled(ConfigurationStudioPresentation.isToggle(o) && !"y".equals(o.requested()));
            turnOff.setEnabled(ConfigurationStudioPresentation.isToggle(o) && !"n".equals(o.requested()));
        });
    }

    private static Composite tab(TabFolder folder, String name) {
        var item = new TabItem(folder, SWT.NONE);
        item.setText(name);
        var body = new Composite(folder, SWT.NONE);
        item.setControl(body);
        return body;
    }
    private static Button action(Composite parent, String label, Runnable run) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(label);
        button.addListener(SWT.Selection, event -> run.run());
        return button;
    }

    public void displayProject(IProject selected) { displayProject(selected, false, null); }

    public void displayProject(IProject selected, boolean hardware) {
        displayProject(selected, hardware, null);
    }

    public void displayProject(IProject selected, boolean hardware, String overlayPath) {
        if (selected == null || !selected.isAccessible()) return;
        boolean changedProject = project != null && !project.equals(selected);
        project = selected;
        selectedOverlay = overlayPath == null ? "app.overlay" : overlayPath;
        if (changedProject && category != null && !category.isDisposed()) {
            navigationHistory.clear();
            navigationPosition = -1;
            backButton.setEnabled(false);
            forwardButton.setEnabled(false);
            hardwareSearch.setText("");
            hardwareState.select(0);
            selectDomain(ConfigurationStudioDomains.OVERVIEW);
            category.select(0);
            if (search != null && !search.isDisposed()) search.setText("");
            filter = "";
        }
        setPartName("Configuration — " + selected.getName());
        if (options != null && !options.isDisposed()) {
            reload();
            tabs.setSelection(hardware ? HARDWARE_TAB : START_TAB);
            updateBeginnerChrome();
        }
    }

    /** Context action on prj.conf highlights only the project-owned choices. */
    public void focusProjectChanges() {
        if (category == null || category.isDisposed()) return;
        selectDomain(ConfigurationStudioDomains.OVERVIEW);
        category.select(1);
        tabs.setSelection(SOFTWARE_TAB);
        refreshOptions();
    }


    /** Diagnose using generated evidence without mutating source or generated files. */
    private void diagnoseConfiguration() {
        if (project == null || !project.isAccessible()) {
            MessageDialog.openInformation(getSite().getShell(), "Diagnose",
                    "Select a Zephyr application in Project Explorer first.");
            return;
        }
        int generation = diagnosisGeneration.incrementAndGet();
        if (diagnosisJob != null) diagnosisJob.cancel();
        IProject selectedProject = project;
        Path build = inspectionBuild;
        Path app = selectedProject.getLocation().toFile().toPath();
        String selectedNode = null;
        if (tabs.getSelectionIndex() == HARDWARE_TAB && hardwareDevices.getSelectionCount() > 0) {
            var selected = (DevicetreeHardware.Device) hardwareDevices.getSelection()[0].getData();
            selectedNode = selected.path();
        }
        String node = selectedNode;
        var display = analysis.getDisplay();
        status.setText("Checking available generated configuration evidence…");
        diagnosisJob = new Job("Diagnose Zephyr configuration") {
            @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                String message;
                try {
                    message = ConfigurationDiagnosis.inspect(app, build, node).display();
                } catch (Exception error) {
                    message = "Configuration diagnosis could not finish: " + error.getMessage()
                            + "\nNo files were changed. Check project settings and regenerate the selected build.";
                }
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                String report = message;
                display.asyncExec(() -> {
                    if (analysis.isDisposed() || generation != diagnosisGeneration.get() || project != selectedProject
                            || !java.util.Objects.equals(inspectionBuild, build)) return;
                    analysis.setText(report);
                    tabs.setSelection(EVIDENCE_TAB);
                    status.setText("Read-only diagnosis complete. Findings are scoped to the selected build.");
                });
                return Status.OK_STATUS;
            }
        };
        diagnosisJob.schedule();
    }

    /**
     * Render exactly the headless zew peripheral evidence contract through
     * the existing Hardware evidence dock. The optional CONFIG_ choice is
     * explicit, not deduced from compatible strings or a guessed driver.
     */
    private void inspectPeripheralEvidence() {
        if (project == null || !project.isAccessible() || project.getLocation() == null
                || inspectionBuild == null || inspectionSettings == null
                || hardwareDevices.getSelectionCount() != 1) {
            MessageDialog.openInformation(getSite().getShell(), "Selected peripheral evidence",
                    "Select one generated hardware node and configure/build the exact target first.");
            return;
        }
        var device = (DevicetreeHardware.Device) hardwareDevices.getSelection()[0].getData();
        if (device == null) return;
        String node = device.path();
        String property = null;
        if (hardwareParameters.getSelectionCount() == 1) {
            var row = (DevicetreeParameters.Property) hardwareParameters.getSelection()[0].getData();
            if (row != null && node.equals(row.path())) property = row.name();
        }
        InputDialog ask = new InputDialog(getSite().getShell(),
                "Optional independent Kconfig observation",
                "Enter an exact CONFIG_ symbol to compare with this generated node (e.g. CONFIG_GPIO). "
                + "Leave blank for node/binding evidence only. This does NOT infer driver dependencies.",
                "", value -> {
                    String requested = value.trim();
                    return requested.isEmpty() || PeripheralEvidenceInspector.allowedSymbol(requested)
                            ? null : "Enter a non-sensitive exact CONFIG_ symbol (uppercase and underscores).";
                });
        if (ask.open() != Window.OK) return;
        final String symbol = ask.getValue().trim().isEmpty() ? null : ask.getValue().trim();
        final IProject chosenProject = project;
        final Path build = inspectionBuild;
        final java.util.Properties settings = (java.util.Properties) inspectionSettings.clone();
        final String propertyName = property;
        final int generation = peripheralEvidenceGeneration.incrementAndGet();
        if (peripheralEvidenceJob != null) peripheralEvidenceJob.cancel();
        final var display = analysis.getDisplay();
        final boolean stale = buildOutdated;
        status.setText("Reading generated peripheral and independent Kconfig evidence…");
        peripheralEvidenceJob = new Job("Inspect selected build peripheral evidence") {
            @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                PeripheralEvidenceInspector.Result result;
                try {
                    var resources = new java.util.LinkedHashMap<String, java.io.InputStream>();
                    for (String name : java.util.List.of("peripheral_inspect.py",
                            "zew_evidence/__init__.py", "zew_evidence/collector.py",
                            "zew_evidence/dt_binding_preview.py", "zew_evidence/peripheral_evidence.py")) {
                        resources.put(name, ConfigurationStudioView.class.getResourceAsStream(
                                "/scripts/" + name));
                    }
                    result = PeripheralEvidenceInspector.inspect(
                            Path.of(settings.getProperty("python")),
                            Path.of(settings.getProperty("workspace"), "zephyr"),
                            build, node, propertyName, symbol, resources, monitor::isCanceled);
                } catch (Exception unavailable) {
                    result = PeripheralEvidenceInspector.Result.unavailable();
                }
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                final String report = result.describe(node, propertyName, symbol, stale);
                display.asyncExec(() -> {
                    if (analysis.isDisposed() || hardwareValidation.isDisposed()
                            || generation != peripheralEvidenceGeneration.get()
                            || project != chosenProject
                            || !java.util.Objects.equals(build, inspectionBuild)
                            || hardwareDevices.getSelectionCount() != 1) return;
                    var current = (DevicetreeHardware.Device) hardwareDevices.getSelection()[0].getData();
                    if (current == null || !node.equals(current.path())) return;
                    String selectedProperty = null;
                    if (hardwareParameters.getSelectionCount() == 1) {
                        var row = (DevicetreeParameters.Property)
                                hardwareParameters.getSelection()[0].getData();
                        if (row != null && node.equals(row.path())) selectedProperty = row.name();
                    }
                    if (!java.util.Objects.equals(propertyName, selectedProperty)) return;
                    hardwareValidation.setText(report);
                    analysis.setText(report);
                    if (tabs.getSelectionIndex() == HARDWARE_TAB) selectHardwareDetails(1);
                    status.setText("Selected-build peripheral observations shown in Hardware and Insights. "
                            + "No GPIO pin or driver causality was inferred.");
                });
                return Status.OK_STATUS;
            }
        };
        peripheralEvidenceJob.setSystem(true);
        peripheralEvidenceJob.schedule();
    }

    /** A copied override belongs to one exact selected node/property/build. */
    private void invalidateGuidedDraftSelection() {
        guidedOverlayGeneration.incrementAndGet();
        if (guidedOverlayJob != null) guidedOverlayJob.cancel();
        proposedOverlayDraft = "";
        reviewedGuidedDraft = null;
        verifiedGuidedPreflight = null;
        unsavedDraftGeneration.incrementAndGet();
        if (unsavedDraftJob != null) unsavedDraftJob.cancel();
        if (hardwarePreview != null && !hardwarePreview.isDisposed())
            hardwarePreview.setText("No proposed changes. The hardware selection changed; the previous draft is invalid. "
                    + "Run Guided overlay draft… again for the newly selected node/property.");
    }

    /**
     * Guided source intent is derived only from an explicitly selected node
     * and an explicitly selected property. The headless EDT guide decides
     * what is permitted; this dialog never claims a pin or driver is ready.
     */
    private void prepareGuidedOverlay() {
        if (project == null || !project.isAccessible() || project.getLocation() == null
                || inspectionBuild == null || inspectionSettings == null
                || hardwareDevices.getSelectionCount() != 1) {
            MessageDialog.openInformation(getSite().getShell(), "Guided overlay review",
                    "Select one generated hardware node and build the exact board first.");
            return;
        }
        var selectedDevice = (DevicetreeHardware.Device) hardwareDevices.getSelection()[0].getData();
        if (selectedDevice == null) return;
        final String node = selectedDevice.path();
        String chosenProperty = null;
        if (hardwareParameters.getSelectionCount() == 1) {
            var selected = (DevicetreeParameters.Property)
                    hardwareParameters.getSelection()[0].getData();
            if (selected != null && node.equals(selected.path())) chosenProperty = selected.name();
        }
        int choice = new MessageDialog(getSite().getShell(), "Guided Zephyr source draft", null,
                "Choose a source request for the selected generated Devicetree node:\n" + node
                + "\n\nThe exact selected Zephyr binding must permit the request."
                + "\nNo GPIO pin, interrupt, pinmux or driver relationship will be guessed."
                + "\nOnly a reviewable source diff will be produced. Nothing will be saved.",
                MessageDialog.QUESTION,
                new String[] {"Enable node", "Disable node", "Existing integer property…",
                        "Binding string enum…", "Cancel"}, 4).open();
        if (choice < 0 || choice > 3) return;
        final String intent = choice <= 1 ? "status" : choice == 2 ? "int" : "enum";
        final String property = choice <= 1 ? "status" : chosenProperty;
        if (property == null) {
            MessageDialog.openInformation(getSite().getShell(), "Choose a built property",
                    "Select a property that belongs to this exact generated node first.\n"
                    + "The guide only accepts existing upstream binding-interpreted "
                    + "integer and string-enum properties.");
            return;
        }
        String value = choice == 0 ? "okay" : choice == 1 ? "disabled" : null;
        if (value == null) {
            InputDialog request = new InputDialog(getSite().getShell(), "Binding-guided value",
                    "Selected property: " + property + "\nGenerated node: " + node
                    + "\n\nEnter " + ("int".equals(intent)
                            ? "an unsigned 32-bit decimal or hexadecimal integer"
                            : "an exact string choice declared by the Zephyr binding")
                    + ". The guide will reject incompatible types, deprecated values, "
                    + "unknown choices and pinmux/phandle fields.",
                    "", candidate -> candidate.length() == 0 || candidate.length() > 80
                    || candidate.contains("\n") || candidate.contains("\r")
                            ? "Enter a nonempty, bounded value without line breaks." : null);
            if (request.open() != Window.OK) return;
            value = request.getValue();
        }
        final String requestedValue = value;
        final IProject chosenProject = project;
        final Path app = chosenProject.getLocation().toFile().toPath();
        final Path build = inspectionBuild;
        final var settings = (java.util.Properties) inspectionSettings.clone();
        final String selectedOverlayName = selectedOverlay;
        final Path overlay = app.resolve(selectedOverlayName).normalize();
        if (!overlay.startsWith(app.normalize()) || !overlay.toString().endsWith(".overlay")
                || !Files.isDirectory(overlay.getParent())) {
            MessageDialog.openInformation(getSite().getShell(), "Choose an application overlay",
                    "The source-draft destination must be an .overlay file inside "
                    + "the selected project. Select a valid overlay first.");
            return;
        }
        if (!MessageDialog.openQuestion(getSite().getShell(), "Show local source diff?",
                "Inspect the saved overlay and show its proposed diff locally?\n"
                + overlay.getFileName() + "\n\n"
                + "The diff may include existing project source comments or values. "
                + "It is not sent to a remote service."
                + "\nNo file is written, configured, built or flashed.")) return;
        final int generation = guidedOverlayGeneration.incrementAndGet();
        if (guidedOverlayJob != null) guidedOverlayJob.cancel();
        final var display = analysis.getDisplay();
        final boolean stale = buildOutdated;
        proposedOverlayDraft = "";
        reviewedGuidedDraft = null;
        verifiedGuidedPreflight = null;
        unsavedDraftGeneration.incrementAndGet();
        if (unsavedDraftJob != null) unsavedDraftJob.cancel();
        hardwarePreview.setText("Inspecting selected generated Devicetree and upstream binding…"
                + "\nAny former draft has been invalidated. No file is changed.");
        status.setText("Inspecting the actual selected Zephyr binding; preparing source-only draft…");
        guidedOverlayJob = new Job("Guided selected Zephyr overlay source review") {
            @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                PeripheralGuideInspector.Result result;
                try {
                    var packaged = new java.util.LinkedHashMap<String, java.io.InputStream>();
                    for (String name : java.util.List.of("peripheral_guide_inspect.py",
                            "zew_evidence/__init__.py", "zew_evidence/collector.py",
                            "zew_evidence/dt_binding_preview.py", "zew_evidence/peripheral_guide.py"))
                        packaged.put(name, ConfigurationStudioView.class.getResourceAsStream("/scripts/" + name));
                    result = PeripheralGuideInspector.inspect(
                            Path.of(settings.getProperty("python")),
                            Path.of(settings.getProperty("workspace"), "zephyr"), build,
                            app, overlay, node, property, intent, requestedValue,
                            packaged, monitor::isCanceled);
                } catch (Exception unavailable) {
                    result = PeripheralGuideInspector.Result.unavailable();
                }
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                final String report = result.describe(node, property, intent, stale);
                final PeripheralGuideInspector.Result observed = result;
                display.asyncExec(() -> {
                    if (analysis.isDisposed() || hardwareValidation.isDisposed()
                            || hardwarePreview.isDisposed()
                            || generation != guidedOverlayGeneration.get()
                            || project != chosenProject
                            || !java.util.Objects.equals(build, inspectionBuild)
                            || !java.util.Objects.equals(selectedOverlayName, selectedOverlay)
                            || hardwareDevices.getSelectionCount() != 1) return;
                    var current = (DevicetreeHardware.Device) hardwareDevices.getSelection()[0].getData();
                    if (current == null || !node.equals(current.path())) return;
                    if (!"status".equals(intent)) {
                        if (hardwareParameters.getSelectionCount() != 1) return;
                        var row = (DevicetreeParameters.Property)
                                hardwareParameters.getSelection()[0].getData();
                        if (row == null || !node.equals(row.path())
                                || !property.equals(row.name())) return;
                    }
                    hardwareValidation.setText(report);
                    analysis.setText(report);
                    if (observed.hasDraft()) {
                        proposedOverlayDraft = observed.addition();
                        try {
                            reviewedGuidedDraft = new ReviewedGuidedDraft(
                                    chosenProject, build, selectedOverlayName, node, property,
                                    intent, requestedValue, observed.dtsHash(),
                                    observed.previousOverlayHash(),
                                    DevicetreeDraftConfigureInspector.digest(
                                            observed.addition().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                                    observed.newOverlayHash(), observed.addition());
                        } catch (java.io.IOException invalid) {
                            reviewedGuidedDraft = null;
                            verifiedGuidedPreflight = null;
                            proposedOverlayDraft = "";
                        }
                        hardwarePreview.setText("BINDING-GROUNDED PROPOSED DIFF — NOT APPLIED\n"
                                + "The change has NOT been built, saved, or electrically verified.\n"
                                + "Use 'Copy proposed change' to copy only the new addition.\n"
                                + "Edit and save the selected application overlay deliberately, "
                                + "then run 'Real configure + saved overlay'.\n\n"
                                + observed.diff());
                        selectHardwareDetails(0);
                        status.setText("Reviewed source draft available in Changes / app.overlay. "
                                + "Nothing saved. Copy only the proposed addition.");
                    } else {
                        proposedOverlayDraft = "";
                        reviewedGuidedDraft = null;
                        verifiedGuidedPreflight = null;
                        hardwarePreview.setText("No verified source draft is available. "
                                + "The selected Zephyr binding or property does not support the request.\n"
                                + "No file was changed. Use the source editor and the real configure tool.");
                        selectHardwareDetails(1);
                        status.setText("Binding guidance unavailable; manual source review required.");
                    }
                });
                return Status.OK_STATUS;
            }
        };
        guidedOverlayJob.setUser(true);
        guidedOverlayJob.schedule();
    }

    /**
     * Opt-in West/CMake preflight of EXACTLY the current reviewed guided draft.
     * Actual project CMake hooks execute with host permissions in scratch.
     * No original overlay/build is written; no firmware is compiled/flashed.
     */
    private void testUnsavedGuidedDraft() {
        final ReviewedGuidedDraft draft = reviewedGuidedDraft;
        if (draft == null || proposedOverlayDraft.isEmpty()
                || !proposedOverlayDraft.equals(draft.sourceAddition())
                || project != draft.project() || !project.isAccessible()
                || !java.util.Objects.equals(inspectionBuild, draft.build())
                || !java.util.Objects.equals(selectedOverlay, draft.overlayName())
                || inspectionSettings == null || hardwareDevices.getSelectionCount() != 1) {
            MessageDialog.openInformation(getSite().getShell(), "Reviewed guided draft required",
                    "Create a new verified binding-guided draft for the selected node first. "
                    + "Older drafts or arbitrary source edits cannot be preflighted.");
            return;
        }
        var current = (DevicetreeHardware.Device) hardwareDevices.getSelection()[0].getData();
        if (current == null || !draft.node().equals(current.path())) return;
        if (!"status".equals(draft.intent())) {
            if (hardwareParameters.getSelectionCount() != 1) return;
            var row = (DevicetreeParameters.Property)
                    hardwareParameters.getSelection()[0].getData();
            if (row == null || !draft.node().equals(row.path())
                    || !draft.property().equals(row.name())) return;
        }
        final java.util.Properties settings = (java.util.Properties) inspectionSettings.clone();
        final String board = settings.getProperty("board", "");
        if (board.isBlank()) {
            MessageDialog.openInformation(getSite().getShell(), "Select an exact board",
                    "Choose the full board / SoC / CPU-core identity in Project settings first.");
            return;
        }
        final Path app = project.getLocation().toFile().toPath();
        final Path overlay = app.resolve(draft.overlayName()).normalize();
        if (!overlay.startsWith(app.normalize()) || !overlay.toString().endsWith(".overlay")
                || !Files.isDirectory(overlay.getParent())) {
            MessageDialog.openInformation(getSite().getShell(), "Invalid overlay destination",
                    "The guided draft must target a .overlay in the current application.");
            return;
        }
        if (!MessageDialog.openQuestion(getSite().getShell(),
                "Execute real CMake for UNSAVED guided source?",
                "Run trusted Zephyr West/CMake on a disposable copy of this unsaved draft?\n\n"
                + "Board/SoC/core: " + board
                + "\nSelected node: " + draft.node()
                + "\nSelected property: " + draft.property()
                + "\nExact requested value: " + draft.value()
                + "\nApplication overlay: " + draft.overlayName()
                + "\n\nThe exact value must appear in scratch generated DTS."
                + "\nNo original source/build is intentionally changed; no firmware is compiled or flashed."
                + "\n\nIMPORTANT: CMake and project/module hooks execute with host permissions. "
                + "Scratch is NOT a security sandbox. Only continue with trusted source.")) return;
        verifiedGuidedPreflight = null;
        final int generation = unsavedDraftGeneration.incrementAndGet();
        if (unsavedDraftJob != null) unsavedDraftJob.cancel();
        final var display = analysis.getDisplay();
        final boolean stale = buildOutdated;
        status.setText("Running actual West/CMake with reviewed unsaved source in disposable scratch…");
        unsavedDraftJob = new Job("Guided unsaved source: actual Zephyr CMake preflight") {
            @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                DevicetreeDraftConfigureInspector.Result evidence;
                try {
                    var resources = new java.util.LinkedHashMap<String, java.io.InputStream>();
                    for (String name : java.util.List.of("dt_draft_configure_inspect.py",
                            "zew_evidence/__init__.py", "zew_evidence/collector.py",
                            "zew_evidence/dt_binding_preview.py", "zew_evidence/peripheral_guide.py",
                            "zew_evidence/dt_build_validation.py", "zew_evidence/dt_draft_validation.py"))
                        resources.put(name, ConfigurationStudioView.class.getResourceAsStream(
                                "/scripts/" + name));
                    final Path ninja = settings.getProperty("tools.ninja", "").isBlank()
                            ? null : Path.of(settings.getProperty("tools.ninja"));
                    evidence = DevicetreeDraftConfigureInspector.inspect(
                            Path.of(settings.getProperty("python")),
                            Path.of(settings.getProperty("workspace"), "zephyr"),
                            app, draft.build(), overlay, ninja, board,
                            draft.node(), draft.property(), draft.intent(), draft.value(),
                            draft.originalDtsSha(), draft.originalOverlaySha(),
                            draft.additionSha(), draft.proposedOverlaySha(),
                            ProjectContext.environment(settings), resources, monitor::isCanceled);
                } catch (Exception unavailable) {
                    evidence = DevicetreeDraftConfigureInspector.Result.unavailable();
                }
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                final String report = evidence.describe(
                        draft.node(), draft.property(), draft.value(), stale);
                final boolean accepted = evidence.configured();
                final DevicetreeDraftConfigureInspector.Result exactEvidence = evidence;
                display.asyncExec(() -> {
                    if (analysis.isDisposed() || hardwareValidation.isDisposed()
                            || generation != unsavedDraftGeneration.get()
                            || reviewedGuidedDraft != draft
                            || !java.util.Objects.equals(proposedOverlayDraft, draft.sourceAddition())
                            || project != draft.project()
                            || !java.util.Objects.equals(inspectionBuild, draft.build())
                            || !java.util.Objects.equals(selectedOverlay, draft.overlayName())
                            || hardwareDevices.getSelectionCount() != 1) return;
                    var selected = (DevicetreeHardware.Device) hardwareDevices.getSelection()[0].getData();
                    if (selected == null || !draft.node().equals(selected.path())) return;
                    if (!"status".equals(draft.intent())) {
                        if (hardwareParameters.getSelectionCount() != 1) return;
                        var row = (DevicetreeParameters.Property)
                                hardwareParameters.getSelection()[0].getData();
                        if (row == null || !draft.node().equals(row.path())
                                || !draft.property().equals(row.name())) return;
                    }
                    verifiedGuidedPreflight = accepted ? exactEvidence : null;
                    hardwareValidation.setText(report);
                    analysis.setText(report);
                    selectHardwareDetails(1);
                    status.setText(accepted
                            ? "Exact unsaved value observed after real scratch CMake. "
                              + "Original source unchanged; review and save separately."
                            : "Unsaved draft NOT validated. See Validation/evidence; "
                              + "original source unchanged.");
                });
                return Status.OK_STATUS;
            }
        };
        unsavedDraftJob.setUser(true);
        unsavedDraftJob.schedule();
    }

    /**
     * This is the ONLY guided source write. It requires a matching actual
     * scratch-CMake result and an independent confirmation showing the
     * selected file, exact source addition and original/source hashes.
     * Existing backup + atomic writer are reused without replacing studios.
     */
    private void saveVerifiedGuidedDraft() {
        ReviewedGuidedDraft draft = reviewedGuidedDraft;
        DevicetreeDraftConfigureInspector.Result evidence = verifiedGuidedPreflight;
        if (draft == null || evidence == null || !evidence.configured()
                || !draft.sourceAddition().equals(proposedOverlayDraft)
                || project != draft.project() || !project.isAccessible()
                || !java.util.Objects.equals(inspectionBuild, draft.build())
                || !java.util.Objects.equals(selectedOverlay, draft.overlayName())
                || hardwareDevices.getSelectionCount() != 1) {
            MessageDialog.openInformation(getSite().getShell(), "Preflight and review required",
                    "Run Guided overlay draft… then Test unsaved draft with CMake… "
                    + "for this exact project, node, property and overlay before saving."
                    + "\nAn old, unverified or manually edited source request will not be applied.");
            return;
        }
        var current = (DevicetreeHardware.Device) hardwareDevices.getSelection()[0].getData();
        if (current == null || !draft.node().equals(current.path())) return;
        if (!"status".equals(draft.intent())) {
            if (hardwareParameters.getSelectionCount() != 1) return;
            var currentProperty = (DevicetreeParameters.Property)
                    hardwareParameters.getSelection()[0].getData();
            if (currentProperty == null || !draft.node().equals(currentProperty.path())
                    || !draft.property().equals(currentProperty.name())) return;
        }
        final Path app = project.getLocation().toFile().toPath();
        final Path source = app.resolve(draft.overlayName()).normalize();
        try {
            var request = new GuidedOverlaySave.Request(
                    draft.node(), draft.property(), draft.originalDtsSha(),
                    draft.originalOverlaySha(), draft.additionSha(),
                    draft.proposedOverlaySha(), draft.sourceAddition());
            // Complete optimistic-lock and exact source hash checking takes
            // place before the explicit write confirmation.
            GuidedOverlaySave.Review review = GuidedOverlaySave.review(
                    app, draft.build(), source, request, evidence);
            String confirmation = "SAVE EXACT GUIDED DEVICETREE SOURCE ADDITION?\n\n"
                    + "Project: " + project.getName()
                    + "\nSelected overlay: " + draft.overlayName()
                    + "\nNode: " + draft.node()
                    + "\nProperty: " + draft.property()
                    + "\nRequested value: " + draft.value()
                    + "\n\nThe following source will be appended exactly:\n\n"
                    + draft.sourceAddition()
                    + "\nOriginal source SHA-256: " + draft.originalOverlaySha()
                    + "\nResulting source SHA-256: " + draft.proposedOverlaySha()
                    + "\n\nThe selected Zephyr preflight confirmed the requested generated value "
                    + "ONLY inside the disposable source copy."
                    + "\nA backup of an existing overlay will be saved before the atomic replacement."
                    + "\nThis will change application source only. It will NOT rebuild, "
                    + "compile firmware, flash or prove physical pin safety."
                    + "\n\nConfirm saving this exact source addition?";
            if (!MessageDialog.openConfirm(getSite().getShell(),
                    "Confirm backed-up guided overlay save", confirmation)) return;
            // The existing writer rejects stale edits, symlinks and unsafe
            // paths again immediately before commit.
            var saved = GuidedOverlaySave.commit(app, draft.build(), review);
            project.refreshLocal(IResource.DEPTH_INFINITE, null);
            verifiedGuidedPreflight = null;
            reviewedGuidedDraft = null;
            proposedOverlayDraft = "";
            buildOutdated = true;
            reload();
            status.setText("Saved reviewed " + draft.property() + " to " + draft.overlayName()
                    + (saved.backup() == null ? " (new overlay; no previous file to back up)"
                            : " (previous overlay backed up under .zew/overlay-backups)")
                    + ". Next: click Build & validate; verify resulting generated DTS and application "
                    + "overlay selection before hardware use.");
        } catch (Exception rejected) {
            MessageDialog.openError(getSite().getShell(), "Guided source not saved",
                    rejected.getMessage() == null ? "Review or source changed; no new edit accepted."
                            : rejected.getMessage());
        }
    }

    /** Evaluate the selected built node with upstream edtlib in a background job. */
    private void inspectSelectedBinding(boolean preview) {
        if (project == null || !project.isAccessible() || project.getLocation() == null
                || inspectionBuild == null || inspectionSettings == null
                || hardwareDevices.getSelectionCount() != 1) {
            MessageDialog.openInformation(getSite().getShell(), "Devicetree binding evidence",
                    "Select a hardware node and build the exact board before inspecting.");
            return;
        }
        var device = (DevicetreeHardware.Device) hardwareDevices.getSelection()[0].getData();
        if (device == null) return;
        IProject chosenProject = project;
        Path build = inspectionBuild;
        Path app = chosenProject.getLocation().toFile().toPath();
        Path overlay = preview ? app.resolve(selectedOverlay).normalize() : null;
        if (preview && (overlay == null || !overlay.startsWith(app.normalize())
                || !Files.isRegularFile(overlay))) {
            MessageDialog.openInformation(getSite().getShell(), "Saved overlay unavailable",
                    "Save an overlay inside the selected project first. The unsaved draft is not inspected.");
            return;
        }
        String prop = null;
        if (hardwareParameters.getSelectionCount() == 1) {
            var row = (DevicetreeParameters.Property) hardwareParameters.getSelection()[0].getData();
            if (row != null && row.path().equals(device.path())) prop = row.name();
        }
        final String node = device.path();
        final String propertyName = prop;
        final int generation = bindingGeneration.incrementAndGet();
        if (bindingJob != null) bindingJob.cancel();
        final var display = analysis.getDisplay();
        final boolean stale = buildOutdated;
        final var settings = inspectionSettings;
        status.setText("Inspecting selected generated DTS and Zephyr bindings…");
        bindingJob = new Job("Inspect selected Devicetree binding") {
            @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                DevicetreeBindingInspector.Result result;
                try {
                    result = DevicetreeBindingInspector.inspect(
                            Path.of(settings.getProperty("python")),
                            Path.of(settings.getProperty("workspace"), "zephyr"),
                            app, build, node, propertyName, overlay,
                            ConfigurationStudioView.class.getResourceAsStream(
                                    "/scripts/dt_binding_inspect.py"));
                } catch (Exception error) {
                    result = DevicetreeBindingInspector.Result.unknown("edtlib-unavailable");
                }
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                final String report = result.describe(node, propertyName, preview, stale);
                display.asyncExec(() -> {
                    if (analysis.isDisposed() || hardwareValidation.isDisposed()
                            || generation != bindingGeneration.get() || project != chosenProject
                            || !java.util.Objects.equals(inspectionBuild, build)
                            || hardwareDevices.getSelectionCount() != 1) return;
                    var current = (DevicetreeHardware.Device) hardwareDevices.getSelection()[0].getData();
                    if (current == null || !node.equals(current.path())) return;
                    String currentProperty = null;
                    if (hardwareParameters.getSelectionCount() == 1) {
                        var selected = (DevicetreeParameters.Property)
                                hardwareParameters.getSelection()[0].getData();
                        if (selected != null && node.equals(selected.path()))
                            currentProperty = selected.name();
                    }
                    if (!java.util.Objects.equals(propertyName, currentProperty)) return;
                    hardwareValidation.setText(report);
                    analysis.setText(report);
                    if (tabs.getSelectionIndex() == HARDWARE_TAB) selectHardwareDetails(1);
                    status.setText("Binding evidence shown in Hardware / Validation and Insights; "
                            + "physical signals and runtime remain unverified.");
                });
                return Status.OK_STATUS;
            }
        };
        bindingJob.setSystem(true);
        bindingJob.schedule();
    }

    /**
     * Runs real trusted Zephyr/CMake only after an explicit warning and opt-in.
     * Unlike the metadata lookup this process executes project CMake code.
     */
    private void validateRealDevicetree(boolean addSavedOverlay) {
        if (project == null || !project.isAccessible() || project.getLocation() == null
                || inspectionBuild == null || inspectionSettings == null
                || hardwareDevices.getSelectionCount() != 1) {
            MessageDialog.openInformation(getSite().getShell(), "Real Devicetree configuration",
                    "Select a hardware node and complete the exact board build first.");
            return;
        }
        var device = (DevicetreeHardware.Device) hardwareDevices.getSelection()[0].getData();
        if (device == null) return;
        final IProject chosenProject = project;
        final Path app = chosenProject.getLocation().toFile().toPath();
        final Path build = inspectionBuild;
        final java.util.Properties settings = (java.util.Properties) inspectionSettings.clone();
        final String overlaySelection = selectedOverlay;
        final String node = device.path();
        String property = null;
        if (hardwareParameters.getSelectionCount() == 1) {
            var selectedProperty = (DevicetreeParameters.Property)
                    hardwareParameters.getSelection()[0].getData();
            if (selectedProperty != null && selectedProperty.path().equals(node))
                property = selectedProperty.name();
        }
        final String expectedProperty = property;
        final Path overlay = addSavedOverlay ? app.resolve(overlaySelection).normalize() : null;
        if (addSavedOverlay && (overlay == null || !overlay.startsWith(app.normalize())
                || !Files.isRegularFile(overlay))) {
            MessageDialog.openInformation(getSite().getShell(), "Saved overlay unavailable",
                    "The selected overlay must be saved inside this application first. "
                    + "Unsaved proposals cannot be tested as real build inputs.");
            return;
        }
        String board = settings.getProperty("board", "");
        if (board.isBlank()) {
            MessageDialog.openInformation(getSite().getShell(), "Select board",
                    "Choose the exact board, SoC and core in Project settings first.");
            return;
        }
        String question = "This runs the selected project's real Zephyr west/CMake "
                + "configuration on a copied application and disposable build.\\n\\n"
                + "CMake and module hooks can execute code outside that directory. "
                + "Only continue if you trust the selected project and workspace.\\n\\n"
                + "Board: " + board + "\\n"
                + "Selected Devicetree node: " + node + "\\n"
                + (expectedProperty == null ? "" : "Selected property: " + expectedProperty + "\\n")
                + (addSavedOverlay ? "The saved overlay is passed as an ADDITIONAL "
                        + "EXTRA_DTC_OVERLAY_FILE after normal overlays, not a replacement.\\n"
                        + "Overlay: " + overlaySelection + "\\n" : "Normal overlay discovery only.\\n")
                + "\\nNo original source or build output is intentionally overwritten; "
                + "nothing is flashed. A configured result is not proof of compiled firmware "
                + "or physical pin safety. Run this validation?";
        if (!MessageDialog.openQuestion(getSite().getShell(),
                "Run real Zephyr configuration in scratch?", question.replace("\\n", "\n"))) return;
        final int generation = realConfigureGeneration.incrementAndGet();
        if (realConfigureJob != null) realConfigureJob.cancel();
        final var display = analysis.getDisplay();
        final boolean stale = buildOutdated;
        status.setText("Running real Zephyr CMake in disposable scratch; results will be verified…");
        realConfigureJob = new Job("Real Zephyr Devicetree configuration — " + chosenProject.getName()) {
            @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                DevicetreeRealConfigureInspector.Result result;
                try {
                    final Path python = Path.of(settings.getProperty("python"));
                    final Path zephyr = Path.of(settings.getProperty("workspace"), "zephyr");
                    final Path ninja = settings.getProperty("tools.ninja", "").isBlank()
                            ? null : Path.of(settings.getProperty("tools.ninja"));
                    result = DevicetreeRealConfigureInspector.inspect(
                            python, zephyr, app, build, ninja, settings.getProperty("board"),
                            node, expectedProperty, overlay, ProjectContext.environment(settings),
                            ConfigurationStudioView.class.getResourceAsStream(
                                    "/scripts/dt_real_configure_inspect.py"), monitor::isCanceled);
                } catch (Exception error) {
                    result = DevicetreeRealConfigureInspector.Result.unavailable("invalid-or-missing-input");
                }
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                final String report = result.describe(node, expectedProperty, stale);
                display.asyncExec(() -> {
                    if (analysis.isDisposed() || hardwareValidation.isDisposed()
                            || generation != realConfigureGeneration.get()
                            || project != chosenProject || !java.util.Objects.equals(build, inspectionBuild)
                            || !java.util.Objects.equals(overlaySelection, selectedOverlay)
                            || hardwareDevices.getSelectionCount() != 1) return;
                    var current = (DevicetreeHardware.Device) hardwareDevices.getSelection()[0].getData();
                    if (current == null || !node.equals(current.path())) return;
                    String currentProperty = null;
                    if (hardwareParameters.getSelectionCount() == 1) {
                        var chosen = (DevicetreeParameters.Property)
                                hardwareParameters.getSelection()[0].getData();
                        if (chosen != null && node.equals(chosen.path()))
                            currentProperty = chosen.name();
                    }
                    if (!java.util.Objects.equals(expectedProperty, currentProperty)) return;
                    hardwareValidation.setText(report);
                    analysis.setText(report);
                    if (tabs.getSelectionIndex() == HARDWARE_TAB) selectHardwareDetails(1);
                    status.setText("Real CMake configuration evidence available in Hardware / Validation "
                            + "and Insights; firmware and physical pins remain unverified.");
                });
                return Status.OK_STATUS;
            }
        };
        realConfigureJob.setUser(true);
        realConfigureJob.schedule();
    }

    private void showEvidence(String text) {
        analysis.setText(text);
        tabs.setSelection(EVIDENCE_TAB);
        status.setText("Offline configuration evidence; no target operation was performed.");
    }

    private Path chooseEvidence(String title, int mode) {
        var dialog = new org.eclipse.swt.widgets.FileDialog(getSite().getShell(), mode);
        dialog.setText(title);
        dialog.setFilterExtensions(new String[] {"*.zew-evidence.zip", "*.zip"});
        String selected = dialog.open();
        return selected == null ? null : Path.of(selected);
    }

    private void exportEvidence() {
        if (project == null || !project.isAccessible() || project.getLocation() == null) {
            MessageDialog.openInformation(getSite().getShell(), "Export evidence", "Select an accessible local project first.");
            return;
        }
        Path app = project.getLocation().toFile().toPath();
        Path build = inspectionBuild;
        var display = analysis.getDisplay();
        new Job("Prepare configuration evidence") {
            @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                try {
                    var preview = io.github.niwantha33.zew.core.InvestigationBundle.collect(app, build);
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    display.asyncExec(() -> {
                        if (analysis.isDisposed()) return;
                        showEvidence(io.github.niwantha33.zew.core.InvestigationBundle.inspect(preview));
                        if (!MessageDialog.openConfirm(getSite().getShell(), "Review evidence export",
                                preview.display() + "\nThe captured contents are now visible in Insights. Export this snapshot?")) return;
                        Path output = chooseEvidence("Save evidence to a new file", SWT.SAVE);
                        if (output == null) return;
                        evidenceJob("Export configuration evidence", () -> {
                            io.github.niwantha33.zew.core.InvestigationBundle.write(output, preview);
                            return "Evidence saved to " + output + "\n" + preview.display();
                        });
                    });
                    return Status.OK_STATUS;
                } catch (Exception error) {
                    display.asyncExec(() -> { if (!analysis.isDisposed()) showEvidence("Export failed: " + error.getMessage()); });
                    return Status.OK_STATUS;
                }
            }
        }.schedule();
    }

    @FunctionalInterface private interface EvidenceWork { String run() throws Exception; }

    private void evidenceJob(String title, EvidenceWork work) {
        var display = analysis.getDisplay();
        new Job(title) {
            @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                String result;
                try { result = work.run(); }
                catch (Exception error) { result = title + " failed: " + error.getMessage(); }
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                String report = result;
                display.asyncExec(() -> { if (!analysis.isDisposed()) showEvidence(report); });
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    private void openEvidence(boolean compare) {
        Path first = chooseEvidence(compare ? "Select first evidence package" : "Open evidence package", SWT.OPEN);
        if (first == null) return;
        Path second = compare ? chooseEvidence("Select second evidence package", SWT.OPEN) : null;
        if (compare && second == null) return;
        evidenceJob(compare ? "Compare configuration evidence" : "Open configuration evidence", () -> {
            var before = io.github.niwantha33.zew.core.InvestigationBundle.read(first);
            return compare ? io.github.niwantha33.zew.core.InvestigationBundle.compare(before,
                    io.github.niwantha33.zew.core.InvestigationBundle.read(second))
                    : io.github.niwantha33.zew.core.InvestigationBundle.inspect(before);
        });
    }

    private void reload() {
        diagnosisGeneration.incrementAndGet();
        if (diagnosisJob != null) diagnosisJob.cancel();
        bindingGeneration.incrementAndGet();
        if (bindingJob != null) bindingJob.cancel();
        peripheralEvidenceGeneration.incrementAndGet();
        if (peripheralEvidenceJob != null) peripheralEvidenceJob.cancel();
        realConfigureGeneration.incrementAndGet();
        if (realConfigureJob != null) realConfigureJob.cancel();
        guidedOverlayGeneration.incrementAndGet();
        if (guidedOverlayJob != null) guidedOverlayJob.cancel();
        unsavedDraftGeneration.incrementAndGet();
        if (unsavedDraftJob != null) unsavedDraftJob.cancel();
        helpGeneration.incrementAndGet();
        if (helpJob != null) helpJob.cancel();
        proposedOverlayDraft = "";
        reviewedGuidedDraft = null;
        verifiedGuidedPreflight = null;
        unsavedDraftGeneration.incrementAndGet();
        if (unsavedDraftJob != null) unsavedDraftJob.cancel();
        if (hardwarePreview != null && !hardwarePreview.isDisposed())
            hardwarePreview.setText("No proposed changes. Build or project context changed; all previous source drafts are invalid. "
                    + "Select a node and request a new reviewed draft.");
        definitionCache.clear();
        kconfigCache.clear();
        inspectionBuild = null;
        inspectionSettings = null;
        if (options == null || options.isDisposed()) return;
        if (project == null) {
            snapshot = null;
            hasGeneratedConfiguration = false;
            buildOutdated = false;
            updateMetrics();
            options.removeAll();
            describeSelected();
            help.setText("Open Zephyr > Configuration Studio with an application selected in Project Explorer.");
            tree.setText("");
            refreshSourceBrowser();
            refreshHardware();
            refreshBeginnerGuide();
            analysis.setText("");
            return;
        }
        try {
            Path app = project.getLocation().toFile().toPath();
            Path build = null;
            String profile = "Source configuration";
            String board = "No target selected";
            String setupProblem = null;
            try {
                var settings = ProjectContext.settings(project);
                board = settings.getProperty("board", "No target selected");
                profile = settings.getProperty("zew.buildConfiguration", "default");
                build = ProjectContext.buildDirectory(project, settings);
                inspectionBuild = build;
                inspectionSettings = settings;
            } catch (Exception notConfigured) {
                // Project configuration is still useful if tool installation or
                // board selection fails. Source repair must not require a build.
                setupProblem = notConfigured.getMessage();
            }
            snapshot = ConfigurationStudioFiles.inspect(app, build, profile);
            Path effectiveFile = build == null ? null : build.resolve("zephyr/.config");
            hasGeneratedConfiguration = effectiveFile != null && Files.isRegularFile(effectiveFile);
            Path effectiveDts = build == null ? null : build.resolve("zephyr/zephyr.dts");
            Path overlay = app.resolve("app.overlay");
            boolean staleKconfig = hasGeneratedConfiguration && Files.isRegularFile(app.resolve("prj.conf"))
                    && Files.getLastModifiedTime(app.resolve("prj.conf"))
                            .compareTo(Files.getLastModifiedTime(effectiveFile)) > 0;
            // A newer application overlay invalidates the last generated GPIO/DTS
            // evidence even if prj.conf has not been touched.
            boolean staleDts = Files.isRegularFile(overlay)
                    && (effectiveDts == null || !Files.isRegularFile(effectiveDts)
                        || Files.getLastModifiedTime(overlay)
                                .compareTo(Files.getLastModifiedTime(effectiveDts)) > 0);
            buildOutdated = staleKconfig || staleDts;
            context.setText(project.getName() + "   •   " + board + "   •   " + snapshot.profile());
            updateMetrics();
            refreshPeripheralInfo();
            refreshOptions();
            tree.setText(snapshot.devicetree().isEmpty()
                    ? "No resolved Devicetree is available. Select the board in Project settings "
                            + "and build to generate zephyr.dts. Existing overlays remain editable."
                    : snapshot.devicetree());
            refreshSourceBrowser();
            refreshHardware();
            refreshBeginnerGuide();
            if (build == null) {
                long requested = snapshot.options().stream().filter(Option::overridden).count();
                analysis.setText("Project: " + project.getName()
                        + "\nStatus: Environment or board not configured"
                        + "\nRequested settings in prj.conf: " + requested
                        + "\nNo effective Kconfig or hardware data is available yet."
                        + "\n\nProject settings → choose the Zephyr installation and exact board."
                        + "\nBuild & validate → generate the effective Kconfig and Devicetree."
                        + "\n\nYou can inspect prj.conf now and remove obsolete or invalid "
                        + "requests with the normal backup and change review.");
                status.setText("Source-only mode: " + setupProblem
                        + "  •  Configure the project to enable validated setting changes.");
            } else {
                refreshAnalysis(build);
                status.setText(buildOutdated
                        ? "Build required: prj.conf or app.overlay changed since the generated build."
                        : "Values reflect the last generated output; build to verify any changes.");
            }
        } catch (Exception error) {
            snapshot = null;
            hasGeneratedConfiguration = false;
            buildOutdated = false;
            updateMetrics();
            options.removeAll();
            describeSelected();
            help.setText("Configuration cannot be loaded: " + error.getMessage());
            tree.setText("");
            refreshHardware();
            refreshBeginnerGuide();
            analysis.setText("Open Project settings or inspect the source file for configuration errors.");
            status.setText("Configuration unavailable. No source files were changed.");
        }
    }



    private void chooseBeginnerGoal(BeginnerHardwareGuide.Goal goal) {
        beginnerGoal = goal;
        refreshBeginnerGuide();
    }

    private void refreshBeginnerGuide() {
        if (beginnerHeadline == null || beginnerHeadline.isDisposed()) return;
        var guide = BeginnerHardwareGuide.inspect(
                snapshot == null ? "" : snapshot.devicetree(), buildOutdated, beginnerGoal);
        beginnerHeadline.setText(beginnerGoal.title() + " — " + guide.headline());
        beginnerNext.setText("NEXT: " + guide.nextAction());
        beginnerSteps.setText("YOUR SMALL STEPS\n\n" + guide.steps()
                + "\n\nYou only need the exact connections for YOUR board. "
                + "The IDE will not invent GPIO pads, ADC channels, sensor addresses or pinmux.");
        beginnerEvidence.setText(guide.evidence());
        beginnerHeadline.getParent().layout(true, true);
    }

    private void showBeginnerHardware() {
        if (hardwareKind == null || hardwareKind.isDisposed()) return;
        hardwareSearch.setText("");
        hardwareState.select(0);
        int index = hardwareKind.indexOf(beginnerGoal.category());
        if (index >= 0) hardwareKind.select(index);
        refreshHardware();
        tabs.setSelection(HARDWARE_TAB);
        updateBeginnerChrome();
        status.setText("Showing actual " + beginnerGoal.category()
                + " node descriptions from the last selected build. "
                + "No physical connection or driver is assumed.");
    }

    private void setupBeginnerHardware() {
        showBeginnerHardware();
        openStartGuide(beginnerGoal.category());
        status.setText("Guided " + beginnerGoal.category()
                + " setup. Start with actual DTS evidence; inspect binding, channels and driver "
                + "requirements before writing any code.");
    }

    private void toggleBeginnerGlossary() {
        if (beginnerGlossary == null || beginnerGlossary.isDisposed()) return;
        boolean visible = !beginnerGlossary.getVisible();
        beginnerGlossary.setVisible(visible);
        ((GridData) beginnerGlossary.getLayoutData()).exclude = !visible;
        beginnerGlossary.getParent().layout(true, true);
    }

    private void updateBeginnerChrome() {
        if (tabs == null || tabs.isDisposed()) return;
        boolean advanced = tabs.getSelectionIndex() != START_TAB;
        setExpertVisibility(expertToolbar, advanced);
        setExpertVisibility(expertMetrics, advanced);
        tabs.getParent().layout(true, true);
    }

    private static void setExpertVisibility(Control control, boolean visible) {
        if (control == null || control.isDisposed()) return;
        if (control.getLayoutData() instanceof GridData data)
            data.exclude = !visible;
        control.setVisible(visible);
    }

    private void focusSearch() {
        if (tabs.getSelectionIndex() == START_TAB || tabs.getSelectionIndex() == EVIDENCE_TAB) {
            tabs.setSelection(SOFTWARE_TAB);
            updateBeginnerChrome();
        }
        Text target = tabs.getSelectionIndex() == HARDWARE_TAB ? hardwareSearch : search;
        target.setFocus();
        target.selectAll();
    }

    private void clearFeatureFilters() {
        selectDomain(ConfigurationStudioDomains.OVERVIEW);
        category.select(0);
        search.setText("");
        refreshOptions();
    }

    private void rememberSelection() {
        Option option = selected();
        if (option == null || navigationPosition >= 0
                && navigationHistory.get(navigationPosition).equals(option.symbol())) return;
        while (navigationHistory.size() > navigationPosition + 1)
            navigationHistory.remove(navigationHistory.size() - 1);
        navigationHistory.add(option.symbol());
        if (navigationHistory.size() > 40) navigationHistory.remove(0);
        navigationPosition = navigationHistory.size() - 1;
        backButton.setEnabled(navigationPosition > 0);
        forwardButton.setEnabled(false);
    }

    private void navigateHistory(int direction) {
        int next = navigationPosition + direction;
        if (next < 0 || next >= navigationHistory.size()) return;
        String symbol = navigationHistory.get(next);
        clearFeatureFilters();
        // Exact search also finds symbols beyond the bounded default table.
        search.setText("CONFIG_" + symbol);
        for (TableItem item : options.getItems()) {
            if (((Option) item.getData()).symbol().equals(symbol)) {
                options.setSelection(item);
                options.showSelection();
                navigationPosition = next;
                describeSelected();
                break;
            }
        }
        backButton.setEnabled(navigationPosition > 0);
        forwardButton.setEnabled(navigationPosition + 1 < navigationHistory.size());
    }

    private java.util.List<org.eclipse.swt.widgets.TreeItem> hardwareItems() {
        var result = new java.util.ArrayList<org.eclipse.swt.widgets.TreeItem>();
        var queue = new java.util.ArrayDeque<org.eclipse.swt.widgets.TreeItem>();
        queue.addAll(java.util.List.of(hardwareDevices.getItems()));
        while (!queue.isEmpty()) {
            var item = queue.removeFirst();
            result.add(item);
            queue.addAll(java.util.List.of(item.getItems()));
        }
        return result;
    }

    private void expandHardware(boolean expanded) {
        for (var item : hardwareItems()) item.setExpanded(expanded);
    }

    private void describeHardware() {
        if (hardwareDevices.getSelectionCount() == 0) return;
        var device = (DevicetreeHardware.Device) hardwareDevices.getSelection()[0].getData();
        hardwareDescription.setText(DevicetreeHardware.explanation(device));
        showHardwareParameters(device);
        selectPeripheralNavigation(device.path());
    }

    private void openPeripheralParameters(Domain domain) {
        tabs.setSelection(HARDWARE_TAB);
        selectHardwareDetails(0);
        hardwareState.select(0);
        String name = domain.id().toUpperCase(java.util.Locale.ROOT);
        int kind = hardwareKind.indexOf(name);
        hardwareKind.select(kind >= 0 ? kind : 0);
        hardwareSearch.setText(kind >= 0 ? "" : domain.id());
        refreshHardware();
        if (hardwareDevices.getSelectionCount() == 0)
            status.setText("No " + domain.title() + " controller in the last generated Devicetree. Build the exact board first.");
    }

    /** Cross-controller inventory: compiled DTS and saved C-source usages are distinct. */
    private void populateGpioUsageOverview(List<DevicetreeParameters.Property> properties,
            List<GpioSourceEvidence.Call> calls, String scanNote) {
        if (gpioUsageTable == null || gpioUsageTable.isDisposed()) return;
        gpioUsageTable.setRedraw(false);
        int dtsCount = 0, codeCount = 0, unresolvedCount = 0;
        try {
            gpioUsageTable.removeAll();
            var banks = new java.util.LinkedHashMap<String, GpioBankEvidence.Bank>();
            var nodeLabels = DevicetreeHardware.nodeLabels(snapshot.devicetree());
            for (var device : DevicetreeHardware.inspect(snapshot.devicetree())) {
                var bank = GpioBankEvidence.inspect(device, properties, nodeLabels);
                if (bank == null) continue;
                banks.put(device.path(), bank);
                for (var usage : bank.uses()) {
                    var item = new TableItem(gpioUsageTable, SWT.NONE);
                    String detail = "Board DTS declaration only (NOT proof of application use): "
                            + usage.consumerPath() + " / " + usage.property()
                            + " (flags " + usage.flags() + ")";
                    item.setText(new String[] {bank.bitLabel(usage.bit()),
                            buildOutdated ? "STALE board DTS" : "Board DTS only",
                            usage.consumerPath() + " / " + usage.property(),
                            "generated zephyr.dts:" + usage.line()});
                    item.setData(new GpioUsageSelection(bank.controllerPath(), usage.bit(), detail));
                    dtsCount++;
                }
            }
            for (var call : calls) {
                boolean resolved = call.resolved() && banks.containsKey(call.controllerPath());
                var bank = banks.get(call.controllerPath());
                String bitLabel = resolved ? bank.bitLabel(call.bit()) : (call.bit() < 0 ? "Unresolved GPIO" : "Unresolved bit " + call.bit());
                String detail = "Saved C-source pattern (not verified as compiled, reachable or executed): "
                        + call.description();
                var item = new TableItem(gpioUsageTable, SWT.NONE);
                item.setText(new String[] {bitLabel,
                        resolved ? "Saved C pattern" : "Unresolved source",
                        call.expression(),
                        call.sourceFile() + ":" + call.line() + "  [" + call.bindingName() + "]"});
                item.setData(new GpioUsageSelection(resolved ? call.controllerPath() : "", call.bit(), detail));
                if (resolved) codeCount++;
                else unresolvedCount++;
            }
            if (dtsCount + codeCount + unresolvedCount == 0) {
                var item = new TableItem(gpioUsageTable, SWT.NONE);
                item.setText(new String[] {"No static references found", "Not measured",
                        "No GPIO runtime evidence. Rebuild and inspect the application.",
                        "Supported: generated DTS numeric/symbolic GPIOs; direct and _dt configure calls."});
            }
            gpioUsageSummary.setText("GPIO RUNTIME USE: UNKNOWN (not measured). Board DTS declarations: " + dtsCount
                    + "    Saved C patterns (not compile-verified): " + codeCount
                    + "    Unresolved source bindings: " + unresolvedCount
                    + (buildOutdated ? "    BUILD REQUIRED: generated DTS may be stale." : "")
                    + " None of these prove application use, live state or package bonding."
                    + (scanNote.isBlank() ? "" : "  " + scanNote.strip()));
        } finally {
            gpioUsageTable.setRedraw(true);
        }
    }

    /** Selecting an unresolved call never marks a guessed GPIO controller. */
    private void inspectSelectedGpioUsage() {
        if (gpioUsageTable == null || gpioUsageTable.isDisposed()
                || gpioUsageTable.getSelectionCount() == 0) return;
        var usage = (GpioUsageSelection) gpioUsageTable.getSelection()[0].getData();
        if (usage == null) return;
        if (!usage.controllerPath().isBlank()) {
            for (var node : hardwareItems()) {
                var device = (DevicetreeHardware.Device) node.getData();
                if (!device.path().equals(usage.controllerPath())) continue;
                hardwareDevices.setSelection(node);
                hardwareDevices.showSelection();
                describeHardware();
                chipPins.selectGpioBit(usage.bit());
                break;
            }
        }
        hardwareInspector.setText(usage.detail() + "\n"
                + (usage.controllerPath().isBlank()
                    ? "Not allocated to any bank: the device-name lookup is unresolved."
                    : "Compiled GPIO controller: " + usage.controllerPath())
                + "\nSource and Devicetree references are not live readings.");
        selectHardwareDetails(3);
    }

    private void showHardwareParameters(DevicetreeHardware.Device device) {
        if (hardwareParameters == null || hardwareParameters.isDisposed()) return;
        hardwareParameters.removeAll();
        selectedGpioBank = null; selectedGpioBit = -1; selectedGpioSourceCalls = List.of();
        if (addGpioButton != null && !addGpioButton.isDisposed()) addGpioButton.setEnabled(false);
        if (device == null || snapshot == null) {
            if (gpioUsageTable != null && !gpioUsageTable.isDisposed()) {
                gpioUsageTable.removeAll();
                gpioUsageSummary.setText("GPIO runtime usage unknown. Select a controller to inspect DTS declarations and saved C-source patterns.");
            }
            boolean hasDts = snapshot != null && !snapshot.devicetree().isBlank();
            String guidance = hasDts
                    ? "No peripheral matches this filter. Change category/state or clear the search."
                    : "No generated Devicetree. Build the exact target first.";
            parameterTitle.setText(guidance);
            hardwareInspector.setText(hasDts
                    ? "A build is available, but no controller is selected."
                    : "No built peripheral selected. No parameters are inferred.");
            hardwareValidation.setText(hasDts
                    ? "FILTER EMPTY  |  The generated Devicetree exists. Select a different peripheral category."
                    : "MISSING EVIDENCE  |  Build the exact target, then select a controller.");
            chipPins.showRoutes("Select a peripheral", List.of(), route -> {});
            return;
        }
        if (hardwarePropertiesSnapshot != snapshot) {
            // Parse only when a new build snapshot is loaded, not on every click.
            builtHardwareProperties = DevicetreeParameters.inspect(snapshot.devicetree());
            hardwarePropertiesSnapshot = snapshot;
        }
        var properties = builtHardwareProperties;
        String owner = device.path();
        if (device.name().startsWith("channel@")) owner = owner.substring(0, owner.lastIndexOf('/'));
        var routes = DevicetreeParameters.pinRoutes(properties, owner);
        var gpio = GpioBankEvidence.inspect(device, properties, DevicetreeHardware.nodeLabels(snapshot.devicetree()));
        var shown = new java.util.LinkedHashSet<>(DevicetreeParameters.forDevice(properties, device.path()));
        if (gpio != null) {
            for (var usage : gpio.uses())
                properties.stream().filter(p -> p.path().equals(usage.consumerPath())
                        && p.name().equals(usage.property())).forEach(shown::add);
        }
        for (var route : routes)
            properties.stream().filter(p -> p.path().equals(route.path()) && p.name().equals(route.property()))
                    .forEach(shown::add);
        for (var property : shown) {
            var item = new TableItem(hardwareParameters, SWT.NONE);
            item.setText(new String[] {property.title() + " · " + property.name(), property.displayValue(), property.path()});
            item.setData(property);
        }
        for (int i = 0; i < hardwareParameters.getColumnCount(); i++) {
            hardwareParameters.getColumn(i).pack();
            hardwareParameters.getColumn(i).setWidth(Math.max(130, Math.min(i == 0 ? 250 : 320,
                    hardwareParameters.getColumn(i).getWidth())));
        }
        if (hardwareParameters.getItemCount() > 0) {
            hardwareParameters.select(0);
            updateHardwareInspector((DevicetreeParameters.Property) hardwareParameters.getItem(0).getData());
        } else {
            hardwareInspector.setText("Node: " + device.path() + "\nNo built properties for this selection.");
        }
        hardwareValidation.setText("DISCOVERED FROM BUILD  |  " + device.path()
                + "\nNode status property: " + (device.status().isBlank() ? "not explicit; ancestor status not evaluated" : device.status())
                + "\nBuilt parameter rows: " + shown.size()
                + "\nResolved logical route entries: " + routes.size()
                + "\nPHYSICAL PINOUT: NOT VERIFIED - exact part/package data not supplied."
                + "\nNo pin conflict or electrical-capability claim is possible."
                + (buildOutdated ? "\nBUILD REQUIRED - generated configuration may be stale." : "")
                + "\nAfter any overlay change, Build & validate with Zephyr."
                + DevicetreeDependencyEvidence.describe(
                        DevicetreeDependencyEvidence.inspect(properties, device.path())));
        parameterTitle.setText(device.path() + " · " + shown.size() + " built parameters · "
                + (gpio == null ? routes.size() + " logical routes"
                    : gpio.bitPositions() + " GPIO register bit indexes · "
                            + gpio.uses().size() + " DTS-only declarations")
                + " · physical package pads unverified");
        chipPins.showRoutes(owner, routes, route -> {
            for (var row : hardwareParameters.getItems()) {
                var property = (DevicetreeParameters.Property) row.getData();
                if (property.path().equals(route.path()) && property.name().equals(route.property())) {
                    hardwareParameters.setSelection(row); hardwareParameters.showSelection(); break;
                }
            }
        });
        if (gpio != null) {
            chipPins.setGeneratedDtsStale(buildOutdated);
            List<GpioSourceEvidence.Call> sourceCalls = List.of();
            String sourceScanNote = "Saved source candidates (up to 80 files / 1 MiB), not compiler/AST or execution evidence.";
            if (project != null && project.isAccessible() && project.getLocation() != null) {
                try {
                    sourceCalls = GpioSourceEvidence.scanProject(
                            project.getLocation().toFile().toPath(), snapshot.devicetree());
                } catch (java.io.IOException | IllegalArgumentException error) {
                    sourceScanNote = "\nC SOURCE INSPECTION UNAVAILABLE: " + error.getMessage();
                }
            } else {
                sourceScanNote = "\nC source scan unavailable: no accessible application.";
            }
            final List<GpioSourceEvidence.Call> appCalls = sourceCalls;
            populateGpioUsageOverview(properties, appCalls, sourceScanNote);
            selectHardwareDetails(3);
            var resolvedForBank = appCalls.stream()
                    .filter(call -> device.path().equals(call.controllerPath())).toList();
            var unresolvedCalls = appCalls.stream().filter(call -> !call.resolved()).toList();
            parameterTitle.setText(device.name() + " · "
                    + gpio.bitPositions() + " logical GPIO indexes"
                    + " · board DTS only " + gpio.uses().size()
                    + " · saved C patterns " + resolvedForBank.size()
                    + " · unresolved C " + unresolvedCalls.size()
                    + " (see GPIO Evidence)");
            StringBuilder sourceNotes = new StringBuilder("\nSAVED C PATTERNS (may not compile or execute; NOT proof of GPIO use):")
                    .append("\nExact device-name matches for this bank: ").append(resolvedForBank.size())
                    .append("\nUnresolved bindings in app: ").append(unresolvedCalls.size());
            for (var call : appCalls) {
                if (sourceNotes.length() > 2000) break;
                if (device.path().equals(call.controllerPath()) || !call.resolved())
                    sourceNotes.append("\n").append(call.description());
            }
            sourceNotes.append(sourceScanNote);
            hardwareValidation.setText("GPIO CONTROLLER FROM COMPILED DTS  |  " + device.path()
                    + "\nNode status property: " + (device.status().isBlank() ? "not explicit; ancestor status not evaluated" : device.status())
                    + "\nBoard DTS-only declarations (NOT used pins): " + gpio.uses().size()
                    + "\n" + gpio.provenance()
                    + "\nAPPLICATION/RUNTIME GPIO USAGE: UNKNOWN; no compiled-code or target proof."
                    + "\nLogical register indexes are NOT package pads, electrical capability,"
                    + " runtime direction, or proof that the pin is bonded."
                    + (buildOutdated ? "\nBUILD REQUIRED: compiled values may be stale." : "")
                    + "\nSelect an index to inspect observed consumers; edit through a source overlay."
                    + "\nRebuild the exact target to validate changes."
                    + sourceNotes);
            selectedGpioBank = gpio;
            selectedGpioSourceCalls = appCalls;
            gpioExample.setText(io.github.niwantha33.zew.core.GpioExampleCode.selectedIndex(null, -1, java.util.Map.of()));
            chipPins.showGpioBank(gpio, bit -> {
                gpioExample.setText(io.github.niwantha33.zew.core.GpioExampleCode.selectedIndex(
                        gpio, bit, DevicetreeHardware.nodeLabels(snapshot.devicetree())));
                selectedGpioBit = bit;
                var matches = gpio.uses().stream().filter(usage -> usage.bit() == bit).toList();
                boolean knownUse = !matches.isEmpty() || resolvedForBank.stream().anyMatch(call -> call.bit() == bit);
                boolean knownLabel = DevicetreeHardware.nodeLabels(snapshot.devicetree()).entrySet().stream()
                        .anyMatch(entry -> entry.getValue().equals(gpio.controllerPath()) && entry.getKey().matches("[A-Za-z_][A-Za-z_0-9]*"));
                addGpioButton.setEnabled(!knownUse && knownLabel && !buildOutdated);
                String names = GpioBankEvidence.consumerNames(gpio, properties,
                        DevicetreeHardware.nodeLabels(snapshot.devicetree())).getOrDefault(bit, "");
                StringBuilder info = new StringBuilder("GPIO LOGICAL INDEX  " + gpio.bitLabel(bit)
                        + (names.isBlank() ? "" : " / " + names))
                        .append("\nController: ").append(gpio.controllerPath())
                        .append("\nBoard DTS declarations (not application use): ").append(matches.size())
                        .append("\nThis is not a package pad, runtime register reading or available-pin claim.");
                for (var call : resolvedForBank) if (call.bit() == bit)
                    info.append("\nSAVED C PATTERN (compile/execution unverified): ").append(call.description());
                for (var call : unresolvedCalls) if (call.bit() == bit)
                    info.append("\nUNRESOLVED C REFERENCE: ").append(call.description())
                            .append("\nNot assigned to this bank without a matching DTS device name.");
                for (var usage : matches) {
                    info.append("\n").append(usage.consumerPath()).append(" / ")
                            .append(usage.property()).append(" at zephyr.dts:")
                            .append(usage.line()).append(" (flags ").append(usage.flags()).append(")");
                    for (var row : hardwareParameters.getItems()) {
                        var p = (DevicetreeParameters.Property) row.getData();
                        if (p.path().equals(usage.consumerPath()) && p.name().equals(usage.property())) {
                            hardwareParameters.setSelection(row);
                            hardwareParameters.showSelection();
                            break;
                        }
                    }
                }
                if (!knownUse) info.append("\nNo board DTS declaration or exact saved-source candidate for this index.\n")
                        .append(buildOutdated ? "Rebuild before requesting a GPIO addition."
                                : !knownLabel ? "No verified controller node label: additions are unavailable."
                                : "Add DTS GPIO… offers an input/output and polarity request with a source diff preview.");
                hardwareInspector.setText(info.toString());
            });
            chipPins.showGpioConsumerNames(GpioBankEvidence.consumerNames(gpio, properties,
                    DevicetreeHardware.nodeLabels(snapshot.devicetree())));
            chipPins.showSourceCandidates(resolvedForBank);
        } else if (gpioUsageTable != null && !gpioUsageTable.isDisposed()) {
            gpioExample.setText(io.github.niwantha33.zew.core.GpioExampleCode.selectedIndex(null, -1, java.util.Map.of()));
            gpioUsageTable.removeAll();
            gpioUsageSummary.setText("GPIO runtime usage unknown. Select a controller to inspect board DTS declarations and saved C-source patterns.");
        }
        chipPins.setMode(chipPins.mode());
        parameterTitle.getParent().layout(true, true);
    }

    /** Read-only parameter provenance; no inferred binding defaults. */
    private void updateHardwareInspector(DevicetreeParameters.Property property) {
        if (hardwareInspector == null || hardwareInspector.isDisposed()) return;
        hardwareInspector.setText("PROPERTY  " + property.title()
                + "\nNode / channel: " + property.path()
                + "\nDTS name: " + property.name()
                + "\nBuilt value: " + property.displayValue()
                + "\nSource: generated zephyr.dts, line " + property.line()
                + "\nShape: " + property.kind() + " (not binding validation)");
    }

    private void copyHardwarePreview() {
        if (hardwarePreview == null || hardwarePreview.isDisposed()) return;
        String value = proposedOverlayDraft;
        if (value.isBlank()) {
            status.setText("No proposed change to copy. Choose a built property and Configure property… first.");
            return;
        }
        var clipboard = new Clipboard(hardwarePreview.getDisplay());
        try {
            clipboard.setContents(new Object[] {value}, new Transfer[] {TextTransfer.getInstance()});
        } finally {
            clipboard.dispose();
        }
        status.setText("Unapplied draft copied. Verify active overlay selection and build before relying on it.");
    }

    private void selectPeripheralNavigation(String path) {
        if (hardwarePeripheralTree == null || hardwarePeripheralTree.isDisposed()) return;
        var pending = new java.util.ArrayDeque<org.eclipse.swt.widgets.TreeItem>();
        pending.addAll(java.util.List.of(hardwarePeripheralTree.getItems()));
        while (!pending.isEmpty()) {
            var item = pending.removeFirst();
            if (item.getData() instanceof DevicetreeHardware.Device device && device.path().equals(path)) {
                hardwarePeripheralTree.setSelection(item);
                hardwarePeripheralTree.showSelection();
                return;
            }
            pending.addAll(java.util.List.of(item.getItems()));
        }
    }

    /**
     * Launch the existing on-demand exact-part vendor SVD discovery. Never
     * mistake SVD-only register blocks for Zephyr-driver enabled devices.
     */
    private void openExactSiliconCatalogue() {
        if (project == null || !project.isAccessible()) {
            MessageDialog.openInformation(getSite().getShell(), "Exact silicon catalogue",
                    "Select an imported project with an exact board/SoC first.");
            return;
        }
        try {
            var view = getSite().getWorkbenchWindow().getActivePage()
                    .showView(SocRegistersHandler.VIEW_ID);
            if (view instanceof SocRegistersView registers) registers.useLaunchProject(project);
            status.setText("Searching installed exact vendor SVD for " + project.getName()
                    + ". This catalogue does not establish Zephyr DTS or driver support.");
        } catch (org.eclipse.ui.PartInitException error) {
            MessageDialog.openError(getSite().getShell(), "Cannot open SVD catalogue", error.getMessage());
        }
    }

    /** Never put vendor silicon features into the generated-DTS tree. */
    private void refreshVendorFeatures(List<DevicetreeHardware.Device> devices) {
        if (vendorFeatures == null || vendorFeatures.isDisposed()) return;
        vendorFeatures.removeAll();
        String board = inspectionSettings == null ? "" : inspectionSettings.getProperty("board", "");
        var catalogue = VendorFeatureCatalogue.exactPart(board);
        if (catalogue == null) {
            vendorFeatureStatus.setText("No reviewed exact-part silicon summary bundled for this target. "
                    + "Use Find exact register SVD to inspect installed verified vendor data. "
                    + "No hardware capability or Zephyr support is guessed.");
            vendorFeatureDescription.setText("The generated DTS remains visible under Peripherals. "
                    + "No silicon-level peripheral inventory is inferred from a family or Cortex-M CPU.");
            return;
        }
        vendorFeatureStatus.setText("Official TI specification for exact " + catalogue.part()
                + " (silicon only). Compare with Zephyr build DTS; package pins and driver support unverified.");
        for (var feature : catalogue.features()) {
            var count = PeripheralCoverage.count(devices, feature.category());
            var item = new TableItem(vendorFeatures, SWT.NONE);
            item.setText(new String[] {feature.category(), feature.silicon() + " " + feature.unit(),
                    count.present() + " DTS nodes", "TI datasheet, not driver"});
            item.setData(feature);
        }
        vendorFeatureDescription.setText("Selected build: " + devices.size()
                + " total DTS nodes" + (buildOutdated ? " (BUILD REQUIRED / potentially stale)" : "")
                + ". TI product features are a separate source. For each peripheral select a row "
                + "to compare; missing DTS nodes are not safe to configure using invented overlays."
                + "\nVendor source: " + catalogue.source());
    }

    /** Group actual generated nodes, preserving children of matched controllers. */
    private void refreshPeripheralNavigation(List<DevicetreeHardware.Device> devices) {
        if (hardwarePeripheralTree == null || hardwarePeripheralTree.isDisposed()) return;
        String categoryFilter = hardwareKind.getText();
        String query = hardwareSearch.getText();
        String state = hardwareState.getText();
        String currentPath = hardwareDevices.getSelectionCount() == 0 ? null
                : ((DevicetreeHardware.Device) hardwareDevices.getSelection()[0].getData()).path();
        hardwarePeripheralTree.setRedraw(false);
        try {
            hardwarePeripheralTree.removeAll();
            var groups = new java.util.LinkedHashMap<String, org.eclipse.swt.widgets.TreeItem>();
            for (String group : HardwarePeripheralIndex.FILTERS) {
                if (HardwarePeripheralIndex.ALL.equals(group)) continue;
                if (!HardwarePeripheralIndex.ALL.equals(categoryFilter) && !group.equals(categoryFilter))
                    continue;
                var root = new org.eclipse.swt.widgets.TreeItem(hardwarePeripheralTree, SWT.NONE);
                root.setText(group);
                root.setExpanded(true);
                groups.put(group, root);
            }
            var accepted = new java.util.LinkedHashMap<String, org.eclipse.swt.widgets.TreeItem>();
            for (var device : devices) {
                String name = device.name();
                // Generic DTS containers are not user-selectable peripherals.
                if (name.equals("/") || name.equals("soc") || name.equals("aliases")
                        || name.equals("chosen") || name.equals("cpus")) continue;
                int slash = device.path().lastIndexOf('/');
                String parent = slash <= 0 ? "/" : device.path().substring(0, slash);
                var parentItem = accepted.get(parent);
                String category = HardwarePeripheralIndex.category(device);
                // Never bury a real GPIO controller inside the Pinctrl group:
                // the raw DTS parent may be a pin-controller, but navigation
                // must respect this node's own compatible/category.
                if (parentItem != null) {
                    var parentDevice = (DevicetreeHardware.Device) parentItem.getData();
                    if (!category.equals(HardwarePeripheralIndex.category(parentDevice))
                            && !name.startsWith("channel@")) parentItem = null;
                }
                // Child ADC channels remain under their actual controller.
                boolean child = parentItem != null;
                if (!child && (!HardwarePeripheralIndex.matches(device, categoryFilter)
                        || !PeripheralCoverage.matches(device, devices, categoryFilter, state, query))) continue;
                if (!child && name.startsWith("channel@")) continue;
                var group = groups.get(category);
                if (parentItem == null && group == null) continue;
                var item = parentItem != null
                        ? new org.eclipse.swt.widgets.TreeItem(parentItem, SWT.NONE)
                        : new org.eclipse.swt.widgets.TreeItem(group, SWT.NONE);
                item.setText(name + (PeripheralCoverage.effectiveEnabled(device, devices)
                        ? "  [DTS okay]" : "  [DTS disabled]"));
                item.setData(device);
                item.setData("path", device.path());
                item.setExpanded(true);
                accepted.put(device.path(), item);
            }
            for (var entry : groups.entrySet()) {
                var group = entry.getValue();
                group.setText(entry.getKey() + " (" + group.getItemCount() + ")");
            }
            if (currentPath != null) selectPeripheralNavigation(currentPath);
        } finally {
            hardwarePeripheralTree.setRedraw(true);
        }
    }

    /** An unused-in-evidence index is not a free physical pin; all edits remain reviewed source requests. */
    private void prepareNewGpio() {
        if (selectedGpioBank == null || selectedGpioBit < 0 || snapshot == null || buildOutdated
                || project == null || project.getLocation() == null) return;
        final var bank = selectedGpioBank;
        final int bit = selectedGpioBit;
        final String builtDts = snapshot.devicetree();
        final var calls = selectedGpioSourceCalls;
        var name = new InputDialog(getSite().getShell(), "Add DTS GPIO — " + bank.bitLabel(bit),
                "Choose a DTS node label/name. This index has no known reference; verify the schematic and pin ownership."
                        + "\nLowercase letters, digits and underscores; no source is changed until the final diff is confirmed.",
                "user_gpio", io.github.niwantha33.zew.core.GpioOverlayDraft::validateName);
        if (name.open() != Window.OK) return;
        int direction = new MessageDialog(getSite().getShell(), "GPIO direction", null,
                "Choose the consumer request. Output uses Zephyr gpio-leds; input uses gpio-keys."
                        + "\nThis creates a semantic LED/key consumer, not a live direction setting. Configure any input event code and driver ownership separately.",
                MessageDialog.QUESTION, new String[] {"Input", "Output", "Cancel"}, 2).open();
        if (direction < 0 || direction > 1) return;
        int polarity = new MessageDialog(getSite().getShell(), "GPIO polarity", null,
                "Choose logical active-high or active-low from the actual circuit. No pull, drive strength or package pad is inferred.",
                MessageDialog.QUESTION, new String[] {"Active-high", "Active-low", "Cancel"}, 2).open();
        if (polarity < 0 || polarity > 1) return;
        try {
            Path app = project.getLocation().toFile().toPath();
            var select = new org.eclipse.swt.widgets.FileDialog(getSite().getShell(), SWT.SAVE);
            select.setText("Choose app.overlay or another active source overlay");
            select.setFilterPath(app.toString()); select.setFileName(selectedOverlay);
            select.setFilterExtensions(new String[] {"*.overlay"});
            String chosen = select.open();
            if (chosen == null) return;
            var preview = io.github.niwantha33.zew.core.DevicetreeOverlayChanges.previewGenerated(app, Path.of(chosen), original ->
                    io.github.niwantha33.zew.core.GpioOverlayDraft.generate(builtDts, bank.controllerPath(), bit, name.getValue(),
                            direction == 0 ? io.github.niwantha33.zew.core.GpioOverlayDraft.Direction.INPUT
                                    : io.github.niwantha33.zew.core.GpioOverlayDraft.Direction.OUTPUT,
                            polarity == 0 ? io.github.niwantha33.zew.core.GpioOverlayDraft.Polarity.ACTIVE_HIGH
                                    : io.github.niwantha33.zew.core.GpioOverlayDraft.Polarity.ACTIVE_LOW,
                            original, calls));
            proposedOverlayDraft = preview.addition();
            String diff = "--- " + preview.file() + " (existing)\n+++ " + preview.file() + " (proposed)\n"
                    + "@@ append after " + preview.original().lines().count() + " existing lines @@\n"
                    + preview.updated().substring(preview.original().length()).lines().map(line -> "+" + line).collect(java.util.stream.Collectors.joining("\n")) + "\n";
            hardwarePreview.setText("PROPOSED SOURCE DIFF — NOT APPLIED\n\n" + diff);
            selectHardwareDetails(0);
            hardwareValidation.setText("NEW GPIO REQUEST — NOT BUILT\n" + bank.bitLabel(bit) + " / " + name.getValue()
                    + "\nBinding, controller, package and runtime behaviour require a Zephyr build and hardware verification.");
            if (!MessageDialog.openConfirm(getSite().getShell(), "Save reviewed overlay change",
                    diff + "\nExisting content is preserved and backed up. This does not activate a new overlay."
                            + "\nConfirm the exact source addition, verify overlay selection, then Build & validate.")) return;
            Path backup = io.github.niwantha33.zew.core.DevicetreeOverlayChanges.commit(app, preview);
            project.refreshLocal(IResource.DEPTH_INFINITE, null);
            selectedOverlay = app.toRealPath().relativize(preview.file()).toString().replace('\\', '/');
            openResource(selectedOverlay);
            buildOutdated = true;
            addGpioButton.setEnabled(false);
            status.setText("Saved GPIO source request to " + selectedOverlay
                    + (backup == null ? "" : "; backup: " + backup.getFileName()) + ". Rebuild before relying on it.");
        } catch (Exception error) {
            MessageDialog.openError(getSite().getShell(), "GPIO addition not saved", error.getMessage());
        }
    }

    private void prepareHardwareOverride() {
        if (hardwareParameters.getSelectionCount() == 0) return;
        var property = (DevicetreeParameters.Property) hardwareParameters.getSelection()[0].getData();
        var dialog = new InputDialog(getSite().getShell(), property.title(),
                property.path() + "\n" + property.name()
                + "\nValue shape: " + property.kind()
                + ". Syntax checks only; rebuild validates the device binding. Use the source editor for other syntax.",
                property.value(), value -> {
                    try { DevicetreeParameters.overlayRequest(property, value); return null; }
                    catch (IllegalArgumentException invalid) { return invalid.getMessage(); }
                });
        if (dialog.open() != Window.OK) return;
        String draft = DevicetreeParameters.overlayRequest(property, dialog.getValue());
        proposedOverlayDraft = draft;
        hardwarePreview.setText("PROPOSED SOURCE CHANGE (not applied)\n\n" + draft
                + "\n\nNo package-pin mapping or binding semantics have been validated."
                + "\nReview the active overlay and rebuild the exact target.");
        selectHardwareDetails(0);
        hardwareValidation.setText("PROPOSED CHANGE - NOT BUILT\n" + property.path() + " / " + property.name()
                + "\nSyntax shape inspected. A Zephyr build, binding check and physical pin"
                + " verification are still required before the result can be trusted.");
        var choice = new MessageDialog(getSite().getShell(), "Review overlay request", null,
                draft + "\nBinding semantics and hardware behaviour are validated separately."
                + "\nSave appends an override to an explicitly selected source overlay; earlier values may be superseded.",
                MessageDialog.QUESTION, new String[] {"Choose overlay and save…", "Copy draft", "Cancel"}, 2);
        int result = choice.open();
        if (result == 1) {
            var clipboard = new Clipboard(hardwareParameters.getDisplay());
            try { clipboard.setContents(new Object[] {draft}, new Transfer[] {TextTransfer.getInstance()}); }
            finally { clipboard.dispose(); }
            status.setText("Draft copied. Add it to the selected overlay and Build & validate.");
            return;
        }
        if (result != 0 || project == null || project.getLocation() == null) return;
        try {
            Path app = project.getLocation().toFile().toPath();
            var select = new org.eclipse.swt.widgets.FileDialog(getSite().getShell(), SWT.SAVE);
            select.setText("Choose a source overlay inside this application");
            select.setFilterPath(app.toString());
            select.setFileName(selectedOverlay);
            select.setFilterExtensions(new String[] {"*.overlay"});
            String chosen = select.open();
            if (chosen == null) return;
            var preview = io.github.niwantha33.zew.core.DevicetreeOverlayChanges.preview(
                    app, Path.of(chosen), property, dialog.getValue());
            if (!MessageDialog.openConfirm(getSite().getShell(), "Save reviewed overlay change",
                    "Destination: " + preview.file() + "\n\nAppend:\n" + preview.addition()
                    + "\nExisting content is preserved and backed up. This does not activate a new overlay."
                    + "\nVerify the file is selected in your build configuration, then Build & validate.")) return;
            Path backup = io.github.niwantha33.zew.core.DevicetreeOverlayChanges.commit(app, preview);
            project.refreshLocal(IResource.DEPTH_INFINITE, null);
            selectedOverlay = app.toRealPath().relativize(preview.file()).toString();
            openResource(selectedOverlay);
            status.setText("Saved " + selectedOverlay + (backup == null ? "" : "; backup: " + backup.getFileName())
                    + ". Check overlay selection and rebuild; generated values are unchanged until then.");
        } catch (Exception error) {
            MessageDialog.openError(getSite().getShell(), "Overlay change not saved", error.getMessage());
        }
    }

    private void revealParameterSource() {
        if (hardwareParameters.getSelectionCount() == 0) return;
        var property = (DevicetreeParameters.Property) hardwareParameters.getSelection()[0].getData();
        revealHardwareLine(property.line(), property.path() + " · " + property.name());
    }

    private void openSocMemoryMap() {
        if (project == null || !project.isAccessible()) {
            MessageDialog.openInformation(getSite().getShell(), "SoC Memory Map",
                    "Select a Zephyr application project first. Build the board to obtain real reg ranges.");
            return;
        }
        try {
            var part = getSite().getPage().showView(SocMemoryMapView.ID);
            if (part instanceof SocMemoryMapView map)
                map.displayProject(project,
                        snapshot == null ? "" : snapshot.devicetree(),
                        inspectionBuild, buildOutdated);
        } catch (org.eclipse.ui.PartInitException error) {
            MessageDialog.openError(getSite().getShell(), "SoC memory map unavailable",
                    error.getMessage());
        }
    }

    private void revealHardwareSource() {
        if (hardwareDevices.getSelectionCount() == 0 || snapshot == null) return;
        var device = (DevicetreeHardware.Device) hardwareDevices.getSelection()[0].getData();
        revealHardwareLine(device.line(), device.path());
    }

    private void revealHardwareLine(int number, String path) {
        selectHardwareDetails(2);
        String source = tree.getText();
        int start = 0;
        for (int line = 1; line < number; line++) {
            int end = source.indexOf('\n', start);
            if (end < 0) return;
            start = end + 1;
        }
        int end = source.indexOf('\n', start);
        tree.setSelection(start, end < 0 ? source.length() : end);
        tree.showSelection();
        tree.setFocus();
        status.setText(path + " · generated zephyr.dts:" + number);
    }

    private void copyHardwarePath() {
        if (hardwareDevices.getSelectionCount() == 0) return;
        var device = (DevicetreeHardware.Device) hardwareDevices.getSelection()[0].getData();
        var clipboard = new Clipboard(hardwareDevices.getDisplay());
        try {
            clipboard.setContents(new Object[] {device.path()}, new Transfer[] {TextTransfer.getInstance()});
        } finally { clipboard.dispose(); }
    }


    /**
     * TabFolder#setSelection does not reliably notify SWT.Selection. Both user
     * clicks and programmatic navigation must restore the pane split; otherwise
     * the DTS browser can remain a clipped tab strip (or hide the chip planner).
     */
    private void selectHardwareDetails(int index) {
        if (hardwareDetails == null || hardwareDetails.isDisposed()) return;
        hardwareDetails.setSelection(index);
        updateHardwareDetailsLayout();
    }

    private void updateHardwareDetailsLayout() {
        if (hardwareDetails == null || hardwareDetails.isDisposed()
                || !(hardwareDetails.getParent() instanceof SashForm vertical)) return;
        boolean sourceTab = hardwareDetails.getSelectionIndex() == 6
                || hardwareDetails.getSelectionIndex() == 7;
        vertical.setMaximizedControl(sourceTab ? hardwareDetails : null);
        // The selected TabItem can still have ZERO height when the enclosing
        // Eclipse workbench has not yet painted it. Laying out only the inner
        // SashForm preserves that zero-sized ancestor. Relayout from the real
        // workbench shell so the Hardware tab is allocated before its children.
        // Do not depend on screenshots, timers, or a later SWT paint to fix it.
        if (sourceTab) {
            var shell = hardwareDetails.getShell();
            if (!shell.isDisposed()) shell.layout(true, true);
        }
        vertical.layout(true);
        if (sourceTab && hardwareDetails.getSelection().length != 0) {
            if (hardwareDetails.getSelection()[0].getControl() instanceof Composite page)
                page.layout(true, true);
            if (sourceFileContent != null && !sourceFileContent.isDisposed())
                sourceFileContent.getParent().layout(true, true);
            if (sourceTraceViews != null && !sourceTraceViews.isDisposed())
                sourceTraceViews.layout(true, true);
            // A newly selected Eclipse part can have zero bounds until the
            // SWT event loop processes the workbench's pending resize.
            // Schedule one final layout on that normal UI cycle. Never pump
            // readAndDispatch from a Selection callback (reentrant SWT).
            if (sourceFileContent != null && !sourceFileContent.isDisposed()
                    && sourceFileContent.getBounds().height == 0) {
                hardwareDetails.getDisplay().asyncExec(() -> {
                    if (hardwareDetails.isDisposed() || sourceFileContent.isDisposed()
                            || hardwareDetails.getSelectionIndex() != 6) return;
                    var shell = hardwareDetails.getShell();
                    if (!shell.isDisposed()) shell.layout(true, true);
                    sourceFileContent.getParent().layout(true, true);
                });
            }
        }
    }

    /** The selected generated property is the ONLY authoritative built value. */
    private DevicetreeParameters.Property traceSelectedProperty() {
        if (hardwareParameters == null || hardwareParameters.isDisposed()) return null;
        if (hardwareParameters.getSelectionCount() == 1)
            return (DevicetreeParameters.Property) hardwareParameters.getSelection()[0].getData();
        return hardwareParameters.getItemCount() > 0
                ? (DevicetreeParameters.Property) hardwareParameters.getItem(0).getData() : null;
    }

    private void showSourceTrace() {
        selectHardwareDetails(7);
        refreshSourceTrace();
    }

    private void refreshSourceTrace() {
        if (sourceTraceMatches == null || sourceTraceMatches.isDisposed()) return;
        sourceTraceMatches.removeAll();
        sourceTraceContent.setText("No declaration selected.");
        sourceTraceSave.setEnabled(false);
        sourceTraceDraft = null;
        var property = traceSelectedProperty();
        if (project == null || snapshot == null || snapshot.devicetree().isBlank()
                || property == null) {
            sourceTraceNotice.setText("Select an actual built node/property; a generated zephyr.dts is required.");
            sourceTraceComparison.setText("Nothing to compare without an exact generated property.");
            return;
        }
        try {
            if (sourceFileInventory == null)
                sourceFileInventory = DevicetreeSourceExplorer.inspect(
                        project.getLocation().toFile().toPath(), inspectionBuild);
            var report = DevicetreeSourceTrace.inspect(snapshot.devicetree(),
                    sourceFileInventory, property.path(), property.name());
            sourceTraceNotice.setText(property.path() + " / " + property.name()
                    + " — " + report.hits().size() + " candidate declaration(s)"
                    + (buildOutdated ? " | STALE BUILD: rebuild required" : ""));
            sourceTraceComparison.setText("BUILT / GENERATED (read-only)"
                    + "\nNode: " + report.node() + "\nProperty: " + report.property()
                    + "\nValue: " + (report.builtPropertyPresent() ? report.builtValue() : "(absent)")
                    + "\n\nPROPOSED SOURCE OVERLAY: none\n"
                    + "\nThe generated value does not change until Zephyr has configured the exact build."
                    + "\n\n" + report.notice());
            for (var hit : report.hits()) {
                var row = new TableItem(sourceTraceMatches, SWT.NONE);
                row.setText(new String[] {
                        hit.source().name() + ":" + hit.line(),
                        hit.evidence(), hit.sourceValue()});
                row.setData(hit);
            }
            if (sourceTraceMatches.getItemCount() > 0) {
                sourceTraceMatches.setSelection(0);
                displaySourceTraceMatch();
            } else sourceTraceContent.setText(
                    "No matching source property could be located safely.\n"
                    + "This is NOT proof the peripheral is unavailable. Source may be generated "
                    + "by macros, includes or syntax not handled by this conservative navigator.");
            sourceTraceViews.setSelection(0);
        } catch (Exception error) {
            sourceTraceNotice.setText("Source trace unavailable: " + error.getMessage());
            sourceTraceComparison.setText("Generated values remain read-only. No source changed.");
        }
    }

    private void displaySourceTraceMatch() {
        if (sourceTraceMatches == null || sourceTraceMatches.getSelectionCount() == 0) return;
        var hit = (DevicetreeSourceTrace.Hit) sourceTraceMatches.getSelection()[0].getData();
        try {
            String prefix = "SOURCE: " + hit.source().path() + "\n"
                    + "EVIDENCE: " + hit.evidence()
                    + "\nCandidate declaration, NOT a proven winning override.\n\n";
            String original = DevicetreeSourceExplorer.sourceText(hit.source());
            sourceTraceContent.setText(prefix + original);
            int offset = prefix.length();
            for (int i = 1; i < hit.line(); i++) {
                int end = original.indexOf('\n', offset - prefix.length());
                if (end < 0) break;
                offset = prefix.length() + end + 1;
            }
            int end = sourceTraceContent.getText().indexOf('\n', offset);
            sourceTraceContent.setSelection(offset, end < 0
                    ? sourceTraceContent.getCharCount() : end);
            sourceTraceContent.showSelection();
        } catch (Exception error) {
            sourceTraceContent.setText("Source no longer readable: " + error.getMessage());
        }
    }

    private void draftSourceTraceOverlay() {
        var property = traceSelectedProperty();
        if (project == null || snapshot == null || property == null || buildOutdated) {
            MessageDialog.openInformation(getSite().getShell(), "Rebuild required",
                    "Select a built property from the exact target and rebuild stale changes first.");
            return;
        }
        var dialog = new InputDialog(getSite().getShell(), "Propose application overlay",
                property.path() + " / " + property.name()
                + "\nEffective generated value: " + property.displayValue()
                + "\nConservative syntax check, not binding or runtime validation.",
                property.value(), value -> {
                    try { DevicetreeParameters.overlayRequest(property, value); return null; }
                    catch (IllegalArgumentException invalid) { return invalid.getMessage(); }
                });
        if (dialog.open() != Window.OK) return;
        try {
            Path app = project.getLocation().toFile().toPath();
            Path target = app.resolve(selectedOverlay).normalize();
            var preview = DevicetreeOverlayChanges.preview(app, target,
                    property, dialog.getValue());
            sourceTraceDraft = new StudioOverlayDraft(project, snapshot.devicetree(),
                    selectedOverlay, property.path(), property.name(), dialog.getValue(), preview);
            sourceTraceComparison.setText("EXACT BUILT VALUE (unchanged)\n"
                    + property.path() + " / " + property.name() + " = " + property.displayValue()
                    + "\n\nPROPOSED APPLICATION OVERLAY (NOT BUILT)\n"
                    + property.name() + " = " + dialog.getValue()
                    + "\n\nTARGET: " + preview.file()
                    + "\nPREVIEW - append only:\n\n" + preview.addition()
                    + "\nExisting source bytes are retained. Saving requires confirmation, "
                    + "performs atomic update with backup of existing files, and does NOT "
                    + "prove overlay activation, binding acceptance, pinmux or live hardware."
                    + "\nNext: Real configure + overlay, then Build & validate.");
            sourceTraceViews.setSelection(1);
            sourceTraceSave.setEnabled(preview.changed());
            sourceTraceNotice.setText("UNSAVED DRAFT — compare built vs proposed; review before saving.");
        } catch (Exception error) {
            sourceTraceDraft = null;
            sourceTraceSave.setEnabled(false);
            MessageDialog.openError(getSite().getShell(), "Overlay proposal rejected",
                    error.getMessage());
        }
    }

    private void saveSourceTraceOverlay() {
        var draft = sourceTraceDraft;
        var property = traceSelectedProperty();
        if (draft == null || project == null || snapshot == null || property == null
                || !project.equals(draft.project())
                || !snapshot.devicetree().equals(draft.generatedDts())
                || buildOutdated || !selectedOverlay.equals(draft.overlayName())
                || !property.path().equals(draft.node())
                || !property.name().equals(draft.property())) {
            sourceTraceSave.setEnabled(false);
            sourceTraceDraft = null;
            MessageDialog.openError(getSite().getShell(), "Stale overlay review",
                    "Project, selected node, overlay, or generated build changed. Review again; nothing saved.");
            return;
        }
        if (!MessageDialog.openConfirm(getSite().getShell(), "Save reviewed overlay source",
                "ONLY this application source file will change:\n" + draft.preview().file()
                + "\n\nAppend:\n" + draft.preview().addition()
                + "\nNo Zephyr configure, driver validation, flash or hardware access has occurred."
                + "\nConfirm this source addition?")) return;
        try {
            Path app = project.getLocation().toFile().toPath();
            Path backup = DevicetreeOverlayChanges.commit(app, draft.preview());
            project.refreshLocal(IResource.DEPTH_INFINITE, null);
            sourceTraceDraft = null;
            sourceTraceSave.setEnabled(false);
            buildOutdated = true;
            refreshSourceBrowser();
            sourceTraceNotice.setText("SAVED SOURCE — build is now stale; run real configure and rebuild."
                    + (backup == null ? "" : " Backup: " + backup));
            sourceTraceComparison.append("\n\nSOURCE SAVED, NOT YET BUILT. "
                    + "Rebuild/configure before reporting an effective result.");
            status.setText("Application overlay saved with review. Generated values remain unchanged until rebuild.");
        } catch (Exception error) {
            MessageDialog.openError(getSite().getShell(), "Overlay not saved", error.getMessage());
        }
    }

    private void refreshSourceBrowser() {
        if (sourceFileList == null || sourceFileList.isDisposed()) return;
        sourceFileList.removeAll();
        sourceFileInventory = null;
        if (project == null) {
            sourceFileNotice.setText("Select a Zephyr application to browse its DTS / DTSI inputs.");
            sourceFileContent.setText("No source selected.");
            return;
        }
        try {
            Path app = project.getLocation().toFile().toPath();
            sourceFileInventory = DevicetreeSourceExplorer.inspect(app, inspectionBuild);
            sourceFileNotice.setText(sourceFileInventory.notice());
            for (var source : sourceFileInventory.sources()) {
                var row = new TableItem(sourceFileList, SWT.NONE);
                row.setText(new String[] {source.name(), source.kind(), source.evidence()});
                row.setData(source);
            }
            sourceFileContent.setText(sourceFileInventory.sources().isEmpty()
                    ? "No evidenced DTS/DTSI sources found. Configure/build the selected target. "
                            + "Do not assume a source file or overlay is active until verified."
                    : "Select a file on the left. Build dependencies are read-only evidence from the selected build; "
                            + "use Create / open app.overlay to configure an application override.");
        } catch (Exception error) {
            sourceFileNotice.setText("DTS/DTSI source discovery unavailable: " + error.getMessage());
            sourceFileContent.setText("No source file is verified. Check the selected build and refresh.");
        }
        sourceFileNotice.getParent().layout(true, true);
    }

    private void displaySelectedSource() {
        if (sourceFileList == null || sourceFileList.getSelectionCount() == 0) return;
        var source = (DevicetreeSourceExplorer.Source) sourceFileList.getSelection()[0].getData();
        try {
            sourceFileContent.setText("PATH: " + source.path()
                    + "\nEVIDENCE: " + source.evidence()
                    + "\nROLE: " + source.kind()
                    + "\nThe original is read-only here; an overlay must be configured and rebuilt to take effect.\n\n"
                    + DevicetreeSourceExplorer.sourceText(source));
            sourceFileContent.setSelection(0);
        } catch (Exception error) {
            sourceFileContent.setText("Unable to display source: " + error.getMessage());
        }
    }

    private void editSelectedSourceOverlay() {
        if (project == null || sourceFileList == null || sourceFileList.getSelectionCount() == 0) return;
        var source = (DevicetreeSourceExplorer.Source) sourceFileList.getSelection()[0].getData();
        try {
            Path app = project.getLocation().toFile().toPath().toRealPath();
            if (!source.applicationOverlay() || !source.path().startsWith(app))
                throw new java.io.IOException("Only application-owned overlays may be edited. Vendor DTS/DTSI are read-only.");
            Path relative = app.relativize(source.path());
            selectedOverlay = relative.toString().replace('\\', '/');
            openResource(selectedOverlay);
        } catch (Exception error) {
            MessageDialog.openError(getSite().getShell(), "Open application overlay", error.getMessage());
        }
    }

    private void refreshHardware() {
        if (hardwareDevices == null || hardwareDevices.isDisposed()) return;
        String previous = hardwareDevices.getSelectionCount() == 0 ? null
                : ((DevicetreeHardware.Device) hardwareDevices.getSelection()[0].getData()).path();
        var expanded = new java.util.HashSet<String>();
        for (var item : hardwareItems()) if (item.getExpanded())
            expanded.add(((DevicetreeHardware.Device) item.getData()).path());
        var devices = snapshot == null ? List.<DevicetreeHardware.Device>of()
                : DevicetreeHardware.inspect(snapshot.devicetree());
        var matches = new java.util.LinkedHashSet<String>();
        for (var device : devices)
            if (PeripheralCoverage.matches(device, devices, hardwareKind.getText(),
                    hardwareState.getText(), hardwareSearch.getText()))
                matches.add(device.path());
        var visible = new java.util.HashSet<>(matches);
        // Keep ADC channels and other descendants accessible under matching controllers.
        if (hardwareKind.getSelectionIndex() > 0 && !"Other".equals(hardwareKind.getText())) {
            for (var device : devices) {
                String ancestor = device.path();
                for (int slash = ancestor.lastIndexOf('/'); slash > 0; slash = ancestor.lastIndexOf('/', slash - 1)) {
                    ancestor = ancestor.substring(0, slash);
                    if (matches.contains(ancestor)) {
                        visible.add(device.path());
                        break;
                    }
                }
            }
        }
        for (String path : matches) {
            for (int slash = path.lastIndexOf('/'); slash > 0; slash = path.lastIndexOf('/', slash - 1))
                visible.add(path.substring(0, slash));
            visible.add("/");
        }
        hardwareDevices.setRedraw(false);
        try {
            hardwareDevices.removeAll();
            var nodes = new java.util.LinkedHashMap<String, org.eclipse.swt.widgets.TreeItem>();
            for (var device : devices) {
                if (!visible.contains(device.path())) continue;
                int slash = device.path().lastIndexOf('/');
                String parent = slash <= 0 ? "/" : device.path().substring(0, slash);
                var parentItem = device.path().equals("/") ? null : nodes.get(parent);
                var item = parentItem == null ? new org.eclipse.swt.widgets.TreeItem(hardwareDevices, SWT.NONE)
                        : new org.eclipse.swt.widgets.TreeItem(parentItem, SWT.NONE);
                item.setText(new String[] {device.name(), device.compatible(),
                        PeripheralCoverage.effectiveEnabled(device, devices)
                                ? (device.status().isBlank() ? "DTS default (okay)" : device.status())
                                : "DTS disabled/inherited"});
                item.setData(device);
                item.setExpanded(expanded.contains(device.path()) || device.path().equals("/")
                        || !hardwareSearch.getText().isBlank() || hardwareState.getSelectionIndex() != 0);
                if (!matches.contains(device.path()))
                    item.setForeground(hardwareDevices.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
                nodes.put(device.path(), item);
            }
            for (int i = 0; i < hardwareDevices.getColumnCount(); i++) {
                hardwareDevices.getColumn(i).pack();
                hardwareDevices.getColumn(i).setWidth(Math.max(110,
                        Math.min(i == 0 ? 360 : 240, hardwareDevices.getColumn(i).getWidth())));
            }
            var selection = nodes.get(previous);
            if (selection == null) selection = nodes.values().stream()
                    .filter(item -> matches.contains(((DevicetreeHardware.Device) item.getData()).path()))
                    .filter(item -> !HardwarePeripheralIndex.category(
                            (DevicetreeHardware.Device) item.getData()).equals("Other"))
                    .findFirst().orElse(null);
            if (selection == null) selection = nodes.values().stream()
                    .filter(item -> matches.contains(((DevicetreeHardware.Device) item.getData()).path()))
                    .findFirst().orElse(null);
            if (selection != null) {
                hardwareDevices.setSelection(selection);
                hardwareDevices.showSelection();
                describeHardware();
            } else {
                showHardwareParameters(null);
                var coverage = PeripheralCoverage.explain(devices, hardwareKind.getText(),
                        hardwareState.getText(), hardwareSearch.getText(), buildOutdated);
                hardwareDescription.setText(coverage.explanation());
                hardwareInspector.setText(coverage.explanation());
                hardwareValidation.setText(coverage.explanation());
                // Preserve the actionable empty-filter message when built DTS evidence exists.
                // Coverage explains why no matches exist in the other evidence panes.
                boolean builtDtsPresent = snapshot != null && !snapshot.devicetree().isBlank();
                parameterTitle.setText(builtDtsPresent
                        ? "No peripheral matches this filter. Change category/state or clear the search."
                        : coverage.explanation());
            }
            var coverage = PeripheralCoverage.explain(devices, hardwareKind.getText(),
                    hardwareState.getText(), hardwareSearch.getText(), buildOutdated);
            var available = PeripheralCoverage.allCounts(devices);
            StringBuilder categories = new StringBuilder();
            for (String kind : HardwarePeripheralIndex.FILTERS) {
                if (HardwarePeripheralIndex.ALL.equals(kind) || "Other".equals(kind)) continue;
                var count = available.get(kind);
                if (categories.length() > 0) categories.append(" · ");
                categories.append(kind).append(" ").append(count.present())
                        .append(" (").append(count.enabled()).append(" okay/")
                        .append(count.disabled()).append(" disabled)");
            }
            hardwareKind.setToolTipText("Generated DTS counts (not silicon availability): " + categories);
            hardwareSummary.setText(coverage.explanation() + "\n" + matches.size()
                    + " matching / " + devices.size()
                    + " generated DTS nodes · silicon capabilities require an exact vendor SVD.");
            refreshPeripheralNavigation(devices);
            refreshVendorFeatures(devices);
            refreshStartGuide();
        } finally { hardwareDevices.setRedraw(true); }
    }

    private void updateMetrics() {
        if (totalMetric == null || totalMetric.isDisposed()) return;
        if (snapshot == null) {
            totalMetric.setText("—");
            changesMetric.setText("—");
            issuesMetric.setText("—");
            targetMetric.setText("Not selected");
        } else {
            long assigned = snapshot.options().stream().filter(Option::overridden).count();
            long unresolved = snapshot.options().stream()
                    .filter(o -> !ConfigurationStudioPresentation.review(o).isBlank()).count();
            totalMetric.setText(Integer.toString(snapshot.options().size()));
            changesMetric.setText(Long.toString(assigned));
            issuesMetric.setText(Long.toString(unresolved));
            targetMetric.setText(buildOutdated ? "Build required"
                    : hasGeneratedConfiguration ? "Available" : "Not built");
        }
        for (Label metric : new Label[] {totalMetric, changesMetric, issuesMetric, targetMetric})
            metric.getParent().layout(true, true);
    }

    private void refreshOptions() {
        if (options == null || options.isDisposed()) return;
        if (inlineToggle != null && !inlineToggle.isDisposed()) inlineToggle.setVisible(false);
        Option previousSelection = selected();
        String previousSymbol = previousSelection == null ? null : previousSelection.symbol();
        options.setRedraw(false);
        int shown = 0;
        int keepIndex = -1;
        int matches = 0;
        int total = snapshot == null ? 0 : snapshot.options().size();
        try {
            options.removeAll();
            if (snapshot != null) {
                String active = category == null || category.isDisposed()
                        ? ConfigurationStudioPresentation.ALL : category.getText();
                for (Option option : snapshot.options().stream()
                        .sorted(Comparator.comparing(Option::symbol)).toList()) {
                    if (!ConfigurationStudioDomains.matches(activeDomain, option)
                            || !ConfigurationStudioPresentation.matches(option, active, filter)) continue;
                    matches++;
                    if (shown >= 750) continue;
                    TableItem row = new TableItem(options, SWT.NONE);
                    row.setText(new String[] {
                            "CONFIG_" + option.symbol(),
                            ConfigurationStudioPresentation.title(option.symbol()),
                            ConfigurationStudioPresentation.inlineValue(option),
                            ConfigurationStudioPresentation.requested(option),
                            ConfigurationStudioPresentation.review(option)
                    });
                    row.setData(option);
                    if (option.symbol().equals(previousSymbol)) keepIndex = shown;
                    ++shown;
                }
            }
            if (options.getItemCount() > 0) options.select(keepIndex >= 0 ? keepIndex : 0);
            sizeColumns();
        } finally {
            options.setRedraw(true);
        }
        if (overview != null && !overview.isDisposed()) {
            overview.setText("Showing " + shown + " of " + matches + " matching settings"
                    + "   •   " + total + " values in this project"
                    + (shown < matches ? "   •   Narrow your search to see more" : "")
                    + "   •   Double-click an overridden setting to open prj.conf");
        }
        describeSelected();
        if (navigationPosition < 0) rememberSelection();
    }

    private void selectDomain(Domain domain) {
        activeDomain = domain;
        if (peripheralCategories != null && !peripheralCategories.isDisposed())
            peripheralCategories.select(ConfigurationStudioDomains.TABS.indexOf(domain));
        refreshPeripheralInfo();
    }

    private void refreshPeripheralInfo() {
        if (peripheralDescription == null || peripheralDescription.isDisposed()) return;
        peripheralDescription.setText(ConfigurationStudioDomains.inspect(activeDomain, snapshot));
        peripheralParameters.setEnabled(!activeDomain.driverSymbol().isBlank());
        if (codeExample != null && !codeExample.isDisposed())
            codeExample.setText(activeDomain.example().isBlank()
                    ? "A board-specific example is not available for this category yet.\n\n"
                    + "Check Zephyr's chosen driver API, board bindings and Devicetree first.\n"
                    + "No source files or hardware assignments are generated automatically."
                    : activeDomain.example());
    }

    private void sizeColumns() {
        if (options == null || options.isDisposed() || options.getColumnCount() != 5) return;
        int width = options.getClientArea().width - 18;
        if (width <= 0) return;
        int[] minimum = {230, 145, 165, 120, 95};
        double[] fractions = {0.30, 0.19, 0.22, 0.16, 0.13};
        for (int i = 0; i < 5; i++)
            options.getColumn(i).setWidth(Math.max(minimum[i], (int) (width * fractions[i])));
    }

    private Option selected() {
        if (options == null || options.isDisposed() || options.getSelectionCount() == 0) return null;
        return (Option) options.getSelection()[0].getData();
    }

    private void describeSelected() {
        if (helpJob != null) helpJob.cancel();
        Option o = selected();
        if (navigationLocation != null && !navigationLocation.isDisposed()) {
            navigationLocation.setText(o == null ? "No matching setting · clear filters to start again"
                    : activeDomain.title() + "  /  " + ConfigurationStudioPresentation.category(o.symbol())
                        + "  /  CONFIG_" + o.symbol());
            navigationLocation.getParent().layout(true, true);
        }
        boolean togglable = ConfigurationStudioPresentation.isToggle(o);
        KconfigInspector.Result checked = o == null ? null : kconfigCache.get(o.symbol());
        if (enableButton != null && !enableButton.isDisposed()) {
            enableButton.setEnabled(togglable && !"y".equals(o.requested())
                    && (checked == null || !checked.available() || checked.details().canRequest("y")));
            disableButton.setEnabled(togglable && !"n".equals(o.requested())
                    && (checked == null || !checked.available() || checked.details().canRequest("n")));
            String displayed = o == null ? null
                    : o.requested() != null ? o.requested() : o.effective();
            enableButton.setSelection(togglable && "y".equals(displayed));
            disableButton.setSelection(togglable && "n".equals(displayed));
            editButton.setEnabled(o != null && !o.unresolved() && !togglable);
            removeButton.setEnabled(o != null && o.requested() != null);
            if (inlineToggle != null && !inlineToggle.isDisposed()) {
                if (togglable && options.getSelectionCount() > 0) {
                    inlineToggleEditor.setEditor(inlineToggle, options.getSelection()[0], 2);
                    inlineToggle.setVisible(true);
                } else inlineToggle.setVisible(false);
            }
        }
        if (o == null) {
            detailTitle.setText("No setting selected");
            detailMeta.setText("Search another name or choose a filter.");
            detailNotice.setText("No configuration files will be changed.");
            help.setText("Select a feature to inspect its Kconfig definition.");
            detailTitle.getParent().layout(true, true);
            return;
        }
        detailTitle.setText(ConfigurationStudioPresentation.title(o.symbol()));
        detailMeta.setText("CONFIG_" + o.symbol() + "   •   Effective: "
                + ConfigurationStudioPresentation.current(o)
                + "   •   Requested: " + ConfigurationStudioPresentation.requested(o));
        detailNotice.setText(o.unresolved()
                ? "Needs review: no effective value in the last generated .config."
                : buildOutdated
                    ? "Build required: prj.conf or app.overlay changed since the generated build."
                    : ConfigurationStudioPresentation.review(o).isBlank()
                        ? "The last generated build agrees with this request."
                        : "Requested value differs from the last build; rebuild to verify.");
        detailTitle.getParent().layout(true, true);
        String explanation = switch (o.symbol()) {
            case "LOG" -> "Enables the Zephyr logging subsystem. Logging can increase firmware size and runtime work.";
            case "LOG_MODE_DEFERRED" -> "Queues log messages for deferred processing. Requires the logging subsystem.";
            case "DEBUG_THREAD_INFO" -> "Provides thread debug metadata for supported debugging integrations.";
            case "THREAD_NAME" -> "Enables human-readable Zephyr thread names; has memory overhead.";
            case "DEBUG_OPTIMIZATIONS" -> "Requests debug-friendly compiler optimization (-Og). Not equivalent to enabling every debug feature.";
            case "TRACING" -> "Enables Zephyr tracing infrastructure. Select a compatible tracing backend separately.";
            case "TRACING_CTF" -> "Selects the Common Trace Format encoder when supported and its dependencies are satisfied.";
            default -> "No curated explanation for this option yet. Refer to its definition in the "
                    + "selected Zephyr checkout and upstream Kconfig help; the effective value is authoritative.";
        };
        String base = "Source: " + ConfigurationStudioPresentation.source(o)
                + "\nRequested in prj.conf: " + ConfigurationStudioPresentation.requested(o)
                + (o.requestedLine() > 0 ? " (line " + o.requestedLine() + ")" : "")
                + "\n\nWhat this setting does\n" + explanation
                + (o.unresolved() ? "\n\nThis request has no corresponding entry in the last generated "
                        + ".config. It may be undefined or the generated configuration may be stale."
                        + " Examine Zephyr's Kconfig diagnostics before changing it." : "")
                + "\n\nEnable / Disable writes an explicit prj.conf request. The effective "
                + "value is determined by Zephyr during the next configuration/build.";
        if (checked != null)
            base += checked.available() ? checked.details().describe()
                    : "\n\nKconfiglib inspection unavailable: " + checked.unavailable();
        help.setText(base);
        // The source declaration is supplementary; dependency/visibility data,
        // when available, comes from the selected build's actual Kconfiglib.
        // Both operations run off the SWT UI thread.
        if (project == null) return;
        final int generation = helpGeneration.incrementAndGet();
        final org.eclipse.swt.widgets.Display display = help.getDisplay();
        try {
            Path workspace = Path.of(ProjectContext.settings(project).getProperty("workspace"));
            String key = workspace + "#" + o.symbol();
            String cached = definitionCache.get(key);
            if (cached != null && checked != null) { help.setText(base + cached); return; }
            final String sourceBase = base;
            final Path selectedBuild = inspectionBuild;
            final java.util.Properties selectedSettings = inspectionSettings;
            Job lookup = new Job("Explain CONFIG_" + o.symbol()) {
                @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    String result;
                    try {
                        var definition = KconfigSourceHelp.firstDefinition(workspace, o.symbol(), monitor::isCanceled);
                        result = definition == null
                                ? "\n\nNo source definition found in the bounded scan. "
                                  + "This does not prove the symbol is undefined in the active build."
                                : "\n\nSource declaration (raw text, not resolved dependency state):\n"
                                  + definition.file() + ":" + definition.line() + "\n"
                                  + definition.sourceExcerpt();
                    } catch (Exception error) {
                        result = "\n\nSource explanation unavailable: " + error.getMessage();
                    }
                    definitionCache.putIfAbsent(key, result);
                    KconfigInspector.Result metadata = checked;
                    if (metadata == null && selectedBuild != null && selectedSettings != null) {
                        try {
                            var python = Path.of(selectedSettings.getProperty("python"));
                            var ninja = Path.of(selectedSettings.getProperty("tools.ninja"));
                            metadata = KconfigInspector.inspect(python, ninja, selectedBuild,
                                    o.symbol(), ConfigurationStudioView.class.getResourceAsStream(
                                            "/scripts/kconfig_inspect.py"));
                        } catch (Exception unavailable) {
                            metadata = KconfigInspector.Result.missing(
                                    "The selected build's Kconfig environment is not available.");
                        }
                        kconfigCache.putIfAbsent(o.symbol(), metadata);
                    }
                    final KconfigInspector.Result inspected = metadata;
                    final String sourceText = result;
                    if (!display.isDisposed()) display.asyncExec(() -> {
                        if (!help.isDisposed() && generation == helpGeneration.get()
                                && selected() != null && o.symbol().equals(selected().symbol())) {
                            help.setText(sourceBase + sourceText + (
                                    inspected == null || checked != null ? ""
                                    : inspected.available() ? inspected.details().describe()
                                        : "\n\nKconfiglib inspection unavailable: " + inspected.unavailable()));
                            if (inspected != null && inspected.available()) {
                                // Update the existing inline control only when an
                                // independently evaluated visible prompt allows it.
                                enableButton.setEnabled(togglable && !"y".equals(o.requested())
                                        && inspected.details().canRequest("y"));
                                disableButton.setEnabled(togglable && !"n".equals(o.requested())
                                        && inspected.details().canRequest("n"));
                            }
                        }
                    });
                    return Status.OK_STATUS;
                }
            };
            lookup.setSystem(true);
            helpJob = lookup;
            lookup.schedule();
        } catch (Exception unavailable) {
            // Basic effective/requested values remain usable without source indexing.
        }
    }

    /** Direct Enable/Disable controls stay visible; only resolved switches are actionable. */
    private void setToggle(String value) {
        Option current = selected();
        if (!ConfigurationStudioPresentation.isToggle(current)) {
            MessageDialog.openWarning(getSite().getShell(), "Cannot change as a switch",
                    "This option is not a resolved on/off setting. "
                    + "Inspect its Kconfig definition or use Edit value for non-switch types.");
            return;
        }
        var evaluated = kconfigCache.get(current.symbol());
        if (evaluated != null && evaluated.available()
                && !evaluated.details().canRequest(value)) {
            MessageDialog.openWarning(getSite().getShell(), "Blocked Kconfig request",
                    "CONFIG_" + current.symbol() + " cannot be set to " + value
                    + " in the last built Kconfig state.\n\nDependency: "
                    + evaluated.details().dependencies()
                    + "\nExpression result: " + evaluated.details().dependencyState()
                    + "\n\nResolve the prerequisites and rebuild before changing this setting.");
            return;
        }
        try {
            saveSelected(current, value);
        } finally {
            // SWT.TOGGLE changes its pressed state before review is confirmed.
            // Restore it when the dialog is cancelled or validation rejects it.
            if (options != null && !options.isDisposed()) describeSelected();
        }
    }

    private void editSelected(boolean remove) {
        Option current = selected();
        if (project == null || current == null) return;
        if (remove) {
            if (current.requested() == null) return;
            saveSelected(current, null);
            return;
        }
        if (current.unresolved()) {
            MessageDialog.openWarning(getSite().getShell(), "Unresolved setting",
                    "This setting has no resolved value in the last .config. "
                    + "Remove the invalid override or fix Kconfig dependencies and build first.");
            return;
        }
        if (ConfigurationStudioPresentation.isToggle(current)) {
            MessageDialog.openInformation(getSite().getShell(), "On/off setting",
                    "Use the Enable or Disable controls for this setting.");
            return;
        }
        var dialog = new InputDialog(getSite().getShell(),
                "Edit " + ConfigurationStudioPresentation.title(current.symbol()),
                "Enter a value for CONFIG_" + current.symbol()
                + ".\nZephyr validates it during the next build.",
                current.requested() != null ? current.requested() : current.effective(),
                value -> value == null || value.isBlank() || value.indexOf('\n') >= 0
                        || value.indexOf('\r') >= 0 ? "Enter one nonempty line." : null);
        if (dialog.open() == Window.OK) saveSelected(current, dialog.getValue());
    }

    /** Every GUI action shares review, optimistic locking and backed-up atomic save. */
    private void saveSelected(Option current, String value) {
        if (project == null || current == null) return;
        try {
            Path app = project.getLocation().toFile().toPath();
            Path build = null;
            try {
                build = ProjectContext.buildDirectory(project, ProjectContext.settings(project));
            } catch (Exception notConfigured) {
                if (current.requested() == null)
                    throw new java.io.IOException("Select an environment and build the exact board before "
                            + "adding a new symbol.", notConfigured);
                // Existing prj.conf assignments can be repaired without a build.
            }
            var preview = ConfigurationStudioFiles.plan(app, build, current.symbol(), value);
            if (!preview.changed()) {
                status.setText("No change: CONFIG_" + current.symbol() + " already has that request.");
                return;
            }
            String before = preview.before() == null ? "(no prj.conf assignment)"
                    : "CONFIG_" + current.symbol() + "=" + preview.before();
            String after = value == null ? "(remove assignment)"
                    : "CONFIG_" + current.symbol() + "=" + value;
            if (!MessageDialog.openConfirm(getSite().getShell(), "Review Kconfig change",
                    "Project: " + project.getName()
                    + "\nFile: " + preview.file()
                    + "\n\nBefore: " + before + "\nAfter:  " + after
                    + "\n\nThis edits the request in prj.conf, not Zephyr's generated .config."
                    + "\nA timestamped backup will be saved. Effective settings and dependencies "
                    + "are verified only by the next Zephyr build."
                    + "\n\nApply this change?")) return;
            Path backup = ConfigurationStudioFiles.commit(app, preview);
            project.refreshLocal(IResource.DEPTH_INFINITE, null);
            reload();
            status.setText("Saved CONFIG_" + current.symbol()
                    + " to prj.conf; build to validate. Backup: " + backup);
        } catch (Exception error) {
            MessageDialog.openError(getSite().getShell(), "Configuration not saved", error.getMessage());
        }
    }

    private void openStartGuide(String overrideCategory) {
        if (startGuideCategory == null || startGuideCategory.isDisposed()) return;
        String kind = overrideCategory;
        if (kind == null && hardwareDevices.getSelectionCount() == 1) {
            var d = (DevicetreeHardware.Device) hardwareDevices.getSelection()[0].getData();
            kind = HardwarePeripheralIndex.category(d);
        }
        if (kind == null || "Other".equals(kind)) kind = hardwareKind.getText();
        if (kind == null || HardwarePeripheralIndex.ALL.equals(kind) || "Other".equals(kind))
            kind = "ADC";
        int index = startGuideCategory.indexOf(kind);
        if (index >= 0) startGuideCategory.select(index);
        selectHardwareDetails(5);
        refreshStartGuide();
        status.setText("Read-only " + kind + " guide. Review Devicetree, Kconfig and application wiring.");
    }

    private void refreshStartGuide() {
        if (startGuideSteps == null || startGuideSteps.isDisposed()
                || startGuideCategory == null || startGuideCategory.isDisposed()) return;
        String path = null;
        if (hardwareDevices != null && !hardwareDevices.isDisposed()
                && hardwareDevices.getSelectionCount() == 1) {
            var selected = (DevicetreeHardware.Device) hardwareDevices.getSelection()[0].getData();
            if (selected != null) path = selected.path();
        }
        String kconfig = "";
        if (snapshot != null) {
            kconfig = snapshot.options().stream()
                    .filter(o -> "y".equals(o.effective()))
                    .map(o -> "CONFIG_" + o.symbol() + "=y")
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
        currentStartGuide = PeripheralStartGuide.inspect(
                snapshot == null ? "" : snapshot.devicetree(),
                startGuideCategory.getText(), path, kconfig, buildOutdated);
        startGuideStatus.setText(currentStartGuide.category() + " — "
                + currentStartGuide.state() + " | " + currentStartGuide.explanation());
        startGuideSteps.setText(currentStartGuide.steps() + "\n\nOfficial upstream reference: "
                + currentStartGuide.upstreamSample());
        startGuideC.setText(currentStartGuide.cExample().isBlank()
                ? "No verified ADC wiring scaffold is available. Follow the prerequisites on the left."
                : currentStartGuide.cExample());
        startGuideConfig.setText(currentStartGuide.configExample());
        startGuideOverlay.setText(currentStartGuide.overlayExample().isBlank()
                ? "No new overlay is proposed. DTS files are read-only and no pads/channels are guessed."
                : currentStartGuide.overlayExample());
        copyStartCode.setEnabled(currentStartGuide.state()
                == PeripheralStartGuide.State.ADC_WIRING_FOUND);
    }

    private void copyStartGuideText(String value) {
        if (value == null || value.isBlank()) {
            status.setText("No evidence-qualified example for this selection. Follow the guide first.");
            return;
        }
        var clipboard = new Clipboard(getSite().getShell().getDisplay());
        try {
            clipboard.setContents(new Object[] {value}, new Transfer[] {TextTransfer.getInstance()});
        } finally { clipboard.dispose(); }
        status.setText("Copied reference text only. No source modified; compile and binding verification required.");
    }

    private void openExistingStartFile(String path) {
        if (project == null || !project.isAccessible() || !project.getFile(path).exists()) {
            status.setText("No " + path + " in this project. Select its actual file in Project Explorer; "
                    + "nothing has been created or overwritten.");
            return;
        }
        openResource(path);
    }

    private void openStartGuideSample() {
        if (currentStartGuide == null) return;
        String url = currentStartGuide.upstreamSample();
        if (!url.startsWith("https://github.com/zephyrproject-rtos/zephyr/tree/")
                && !url.startsWith("https://docs.zephyrproject.org/latest/")) {
            status.setText("No verified upstream sample URL for this peripheral. "
                    + "Inspect the Zephyr samples directory for this exact version.");
            return;
        }
        if (!org.eclipse.swt.program.Program.launch(url)) {
            status.setText("Could not open browser. Official sample: " + url);
        }
    }

    private void openProjectSettings() {
        if (project == null) return;
        if (!ZephyrProjectLifecycle.supports(project)) {
            MessageDialog.openInformation(getSite().getShell(), "Existing Eclipse project",
                    "This Zephyr application uses another Eclipse build system. "
                    + "Configuration Studio can inspect prj.conf without taking over that builder. "
                    + "For native West build and debug, use File > Import > Zephyr > "
                    + "Existing Zephyr Application and complete the target setup.");
            return;
        }
        var dialog = org.eclipse.ui.dialogs.PreferencesUtil.createPropertyDialogOn(
                getSite().getShell(), project, "io.github.niwantha33.zew.projectProperties", null, null);
        if (dialog != null && dialog.open() == Window.OK) reload();
    }

    private void openResource(String path) {
        if (project == null) return;
        try {
            var file = project.getFile(path);
            if (!file.exists()) throw new java.io.IOException("File not found: " + path);
            IDE.openEditor(getSite().getPage(), file, true);
        } catch (Exception error) {
            MessageDialog.openError(getSite().getShell(), "Open source", error.getMessage());
        }
    }

    private void openOverlay() {
        if (project == null) return;
        try {
            Path app = project.getLocation().toFile().toPath().toRealPath();
            Path overlay = app.resolve(selectedOverlay).normalize();
            if (!overlay.startsWith(app)) throw new java.io.IOException("Overlay is outside the application.");
            if (!Files.exists(overlay) && !"app.overlay".equals(selectedOverlay))
                throw new java.io.IOException("The selected overlay no longer exists: " + selectedOverlay);
            if (!Files.exists(overlay) && MessageDialog.openConfirm(getSite().getShell(),
                    "Create application overlay", "Create an empty app.overlay in this project?\n"
                            + "The file will be recognised as an application hardware overlay on the next "
                            + "Zephyr configure. No pins, clocks or peripheral nodes will be changed automatically."))
                Files.writeString(overlay, "/* Application hardware overlay. Validate against this board's bindings. */\n",
                        java.nio.charset.StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            project.refreshLocal(IResource.DEPTH_INFINITE, null);
            if (Files.isRegularFile(overlay)) openResource(selectedOverlay);
        } catch (Exception error) {
            MessageDialog.openError(getSite().getShell(), "Devicetree overlay", error.getMessage());
        }
    }

    private void refreshAnalysis(Path build) throws java.io.IOException {
        if (snapshot == null) return;
        long requested = snapshot.options().stream().filter(Option::overridden).count();
        long unresolved = snapshot.options().stream().filter(Option::unresolved).count();
        Path elf = build.resolve("zephyr/zephyr.elf");
        Path bin = build.resolve("zephyr/zephyr.bin");
        String report = "Project: " + project.getName() + "\nProfile: " + snapshot.profile()
                + "\nGenerated Kconfig settings: " + snapshot.options().size()
                + "\nExplicit prj.conf settings: " + requested
                + "\nRequests absent from last generated .config: " + unresolved
                + "\nCompiled Devicetree: " + (snapshot.devicetree().isEmpty() ? "not available" : "available")
                + "\nELF file: " + (Files.isRegularFile(elf) ? Files.size(elf) + " bytes on disk" : "not built")
                + "\nBIN file: " + (Files.isRegularFile(bin) ? Files.size(bin) + " bytes on disk" : "not generated")
                + "\n\nThe ELF file size is not flash or RAM utilisation. Use Zephyr's "
                + "memory region report/map data for target memory usage."
                + "\n\nChanges to configuration require a new build before comparing firmware impact."
                + "\n\nRTOS analysis prerequisite checklist:\n"
                + "CONFIG_DEBUG_THREAD_INFO, CONFIG_THREAD_NAME, CONFIG_TRACING, "
                + "CONFIG_TRACING_CTF (subject to target/backend availability)."
                + "\n\nGenerated output may be stale after edits or an unsuccessful configure.";
        analysis.setText(report);
    }

    private void build() {
        if (project == null) return;
        if (!ZephyrProjectLifecycle.supports(project)) {
            openProjectSettings();
            return;
        }
        try {
            ProjectContext.settings(project);
        } catch (Exception notReady) {
            MessageDialog.openInformation(getSite().getShell(), "Configure Zephyr project",
                    "Select a ready Zephyr environment, exact target and build profile."
                            + "\n\n" + notReady.getMessage());
            openProjectSettings();
            return;
        }
        IProject target = project;
        org.eclipse.swt.widgets.Display display = options.getDisplay();
        Job job = new Job("Validate and build Zephyr configuration — " + target.getName()) {
            @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                try {
                    target.build(IncrementalProjectBuilder.FULL_BUILD, Activator.ID + ".zephyrBuilder",
                            java.util.Map.of(), monitor);
                    if (!monitor.isCanceled() && !display.isDisposed())
                        display.asyncExec(() -> {
                            if (options != null && !options.isDisposed() && project == target) reload();
                        });
                    return monitor.isCanceled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
                } catch (org.eclipse.core.runtime.CoreException error) { return error.getStatus(); }
            }
        };
        job.setUser(true);
        job.schedule();
    }

    @Override public void dispose() {
        diagnosisGeneration.incrementAndGet();
        if (diagnosisJob != null) diagnosisJob.cancel();
        bindingGeneration.incrementAndGet();
        if (bindingJob != null) bindingJob.cancel();
        peripheralEvidenceGeneration.incrementAndGet();
        if (peripheralEvidenceJob != null) peripheralEvidenceJob.cancel();
        realConfigureGeneration.incrementAndGet();
        if (realConfigureJob != null) realConfigureJob.cancel();
        guidedOverlayGeneration.incrementAndGet();
        if (guidedOverlayJob != null) guidedOverlayJob.cancel();
        unsavedDraftGeneration.incrementAndGet();
        if (unsavedDraftJob != null) unsavedDraftJob.cancel();
        helpGeneration.incrementAndGet();
        if (helpJob != null) helpJob.cancel();
        if (headingFont != null && !headingFont.isDisposed()) headingFont.dispose();
        if (metricFont != null && !metricFont.isDisposed()) metricFont.dispose();
        if (inlineToggleEditor != null) inlineToggleEditor.dispose();
        super.dispose();
    }

    @Override public void setFocus() {
        if (search != null && !search.isDisposed()) search.setFocus();
    }
}
