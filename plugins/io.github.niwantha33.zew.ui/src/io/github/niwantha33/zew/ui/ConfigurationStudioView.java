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
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
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
import io.github.niwantha33.zew.core.ConfigurationStudioFiles;
import io.github.niwantha33.zew.core.DevicetreeHardware;
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
    private IProject project;
    private Snapshot snapshot;
    private Text search, help, tree, analysis, codeExample;
    private CTabFolder peripheralTabs;
    private Domain activeDomain = ConfigurationStudioDomains.OVERVIEW;
    private final java.util.Map<Domain, Text> peripheralInfo = new java.util.LinkedHashMap<>();
    private TableEditor inlineToggleEditor;
    private Composite inlineToggle;
    private Table options;
    private org.eclipse.swt.widgets.Tree hardwareDevices;
    private Text hardwareSearch;
    private Combo hardwareState;
    private Label hardwareSummary, navigationLocation;
    private Button backButton, forwardButton;
    private final java.util.List<String> navigationHistory = new java.util.ArrayList<>();
    private int navigationPosition = -1;
    private Text hardwareDescription;
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
        secondary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        secondary.setLayout(new GridLayout(4, false));
        action(secondary, "Open prj.conf", () -> openResource("prj.conf"))
                .setToolTipText("Open the exact application configuration source");
        action(secondary, "Edit hardware overlay", this::openOverlay)
                .setToolTipText("Open the selected overlay; no pins or clocks are generated automatically");
        action(secondary, "Refresh", this::reload)
                .setToolTipText("Reload project and generated configuration after editing or building");
        action(secondary, "Copy selected symbol", this::copySymbol)
                .setToolTipText("Copy CONFIG_<symbol> to the clipboard");

        Composite metrics = new Composite(outer, SWT.BORDER);
        metrics.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        metrics.setLayout(new GridLayout(4, true));
        totalMetric = metric(metrics, "AVAILABLE", "—");
        changesMetric = metric(metrics, "PROJECT OVERRIDES", "—");
        issuesMetric = metric(metrics, "NEEDS REVIEW", "—");
        targetMetric = metric(metrics, "BUILD SNAPSHOT", "Not built");

        tabs = new TabFolder(outer, SWT.NONE);
        tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        Composite software = tab(tabs, "Features");
        software.setLayout(new GridLayout(1, false));

        // Real SWT peripheral tabs, independent of the source-editing view.
        peripheralTabs = new CTabFolder(software, SWT.BORDER | SWT.FLAT);
        GridData domainLayout = new GridData(SWT.FILL, SWT.TOP, true, false);
        domainLayout.heightHint = 82;
        peripheralTabs.setLayoutData(domainLayout);
        peripheralTabs.setSimple(false);
        for (Domain domain : ConfigurationStudioDomains.TABS) {
            var tab = new CTabItem(peripheralTabs, SWT.NONE);
            tab.setText(domain.title());
            tab.setData(domain);
            Composite body = new Composite(peripheralTabs, SWT.NONE);
            body.setLayout(new GridLayout(1, false));
            Text information = new Text(body, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
            information.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            information.setText(domain.description());
            peripheralInfo.put(domain, information);
            tab.setControl(body);
        }
        peripheralTabs.setSelection(0);
        peripheralTabs.addListener(SWT.Selection, event -> {
            Domain chosen = (Domain) peripheralTabs.getSelection().getData();
            activeDomain = chosen == null ? ConfigurationStudioDomains.OVERVIEW : chosen;
            if (category != null && !category.isDisposed()) category.select(0);
            refreshOptions();
            refreshPeripheralInfo();
        });

        var shortcuts = new Composite(software, SWT.NONE);
        shortcuts.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        shortcuts.setLayout(new GridLayout(4, false));
        shortcut(shortcuts, "All features", ConfigurationStudioPresentation.ALL);
        shortcut(shortcuts, "My changes", ConfigurationStudioPresentation.MINE);
        shortcut(shortcuts, "Needs review", ConfigurationStudioPresentation.REVIEW);
        shortcut(shortcuts, "RTOS & tracing", ConfigurationStudioPresentation.DEBUG);

        var navigation = new Composite(software, SWT.NONE);
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
        search.addListener(SWT.KeyDown, event -> {
            if (event.keyCode == SWT.ESC) search.setText("");
        });
        getViewSite().getActionBars().setGlobalActionHandler(org.eclipse.ui.actions.ActionFactory.FIND.getId(),
                new org.eclipse.jface.action.Action() {
                    @Override public void run() { focusSearch(); }
                });

        overview = new Label(software, SWT.WRAP);
        overview.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        overview.setText("Select a feature to inspect its definition and change its project request.");

        var trail = new Composite(software, SWT.NONE);
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

        var sash = new SashForm(software, SWT.HORIZONTAL);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        options = new Table(sash, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
        options.setHeaderVisible(true);
        options.setLinesVisible(true);
        for (String header : new String[] {"Feature / Kconfig symbol", "Enable / Disable · Value", "Project request", "Review"}) {
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

        sash.setWeights(new int[] {58, 42});

        Composite hardware = tab(tabs, "Hardware");
        hardware.setLayout(new GridLayout(1, false));
        Label warning = new Label(hardware, SWT.WRAP);
        warning.setText("Hardware configuration = the board's Devicetree plus your app.overlay. "
                + "prj.conf selects software features; app.overlay selects actual devices, channels "
                + "and pinctrl overrides. The generated Devicetree below is read-only. "
                + "Do not assume an enabled driver means the board has an enabled controller.");
        warning.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        var hardwareSplit = new SashForm(hardware, SWT.VERTICAL);
        hardwareSplit.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        var devicePanel = new Composite(hardwareSplit, SWT.NONE);
        devicePanel.setLayout(new GridLayout(1, false));
        var deviceTitle = new Label(devicePanel, SWT.NONE);
        deviceTitle.setText("Detected hardware from the selected build");
        deviceTitle.setFont(metricFont);
        var hardwareNavigation = new Composite(devicePanel, SWT.NONE);
        hardwareNavigation.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        hardwareNavigation.setLayout(new GridLayout(4, false));
        hardwareSearch = new Text(hardwareNavigation, SWT.SEARCH | SWT.ICON_SEARCH | SWT.CANCEL | SWT.BORDER);
        hardwareSearch.setMessage("Find node, compatible, label or address…");
        hardwareSearch.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        hardwareState = new Combo(hardwareNavigation, SWT.DROP_DOWN | SWT.READ_ONLY);
        hardwareState.setItems("All nodes", "Okay/default", "Disabled");
        hardwareState.select(0);
        hardwareState.setToolTipText("Filter the node's own status; ancestors remain visible as context");
        action(hardwareNavigation, "Expand all", () -> expandHardware(true));
        action(hardwareNavigation, "Collapse all", () -> expandHardware(false));
        hardwareSummary = new Label(devicePanel, SWT.WRAP);
        hardwareSummary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        hardwareDevices = new org.eclipse.swt.widgets.Tree(devicePanel,
                SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
        hardwareDevices.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        hardwareDevices.setHeaderVisible(true);
        hardwareDevices.setLinesVisible(true);
        for (String title : new String[] {"Device / hierarchy", "Compatible", "Node status"}) {
            var column = new org.eclipse.swt.widgets.TreeColumn(hardwareDevices, SWT.LEFT);
            column.setText(title);
        }
        hardwareDescription = new Text(devicePanel, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.BORDER);
        GridData descriptionLayout = new GridData(SWT.FILL, SWT.CENTER, true, false);
        descriptionLayout.heightHint = 112;
        hardwareDescription.setLayoutData(descriptionLayout);
        hardwareDescription.setText("Build the exact target to discover hardware from zephyr.dts.");
        hardwareDevices.addListener(SWT.Selection, event -> describeHardware());
        hardwareDevices.addListener(SWT.DefaultSelection, event -> revealHardwareSource());
        hardwareSearch.addModifyListener(event -> refreshHardware());
        hardwareSearch.addListener(SWT.DefaultSelection, event -> hardwareDevices.setFocus());
        hardwareSearch.addListener(SWT.KeyDown, event -> {
            if (event.keyCode == SWT.ESC) hardwareSearch.setText("");
        });
        hardwareState.addListener(SWT.Selection, event -> refreshHardware());
        var sourcePanel = new Composite(hardwareSplit, SWT.NONE);
        sourcePanel.setLayout(new GridLayout(1, false));
        var sourceCaption = new Label(sourcePanel, SWT.NONE);
        sourceCaption.setText("Compiled Devicetree source (read-only)");
        tree = new Text(sourcePanel, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        tree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        hardwareSplit.setWeights(new int[] {60, 40});
        var hardwareActions = new Composite(hardware, SWT.NONE);
        hardwareActions.setLayout(new GridLayout(3, false));
        action(hardwareActions, "Reveal in source", this::revealHardwareSource);
        action(hardwareActions, "Copy node path", this::copyHardwarePath);
        action(hardwareActions, "Edit application overlay…", this::openOverlay);

        Composite reports = tab(tabs, "Insights");
        reports.setLayout(new GridLayout(1, false));
        analysis = new Text(reports, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        analysis.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        status = new Label(outer, SWT.WRAP);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        status.setText("Select a Zephyr project to inspect it safely.");
        reload();
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
            tabs.setSelection(hardware ? 1 : 0);
        }
    }

    /** Context action on prj.conf highlights only the project-owned choices. */
    public void focusProjectChanges() {
        if (category == null || category.isDisposed()) return;
        selectDomain(ConfigurationStudioDomains.OVERVIEW);
        category.select(1);
        tabs.setSelection(0);
        refreshOptions();
    }

    private void reload() {
        helpGeneration.incrementAndGet();
        if (helpJob != null) helpJob.cancel();
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
            refreshHardware();
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
            buildOutdated = hasGeneratedConfiguration && Files.isRegularFile(app.resolve("prj.conf"))
                    && Files.getLastModifiedTime(app.resolve("prj.conf"))
                            .compareTo(Files.getLastModifiedTime(effectiveFile)) > 0;
            context.setText(project.getName() + "   •   " + board + "   •   " + snapshot.profile());
            updateMetrics();
            refreshPeripheralInfo();
            refreshOptions();
            tree.setText(snapshot.devicetree().isEmpty()
                    ? "No resolved Devicetree is available. Select the board in Project settings "
                            + "and build to generate zephyr.dts. Existing overlays remain editable."
                    : snapshot.devicetree());
            refreshHardware();
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
                        ? "Build required: prj.conf is newer than the last generated .config."
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
            analysis.setText("Open Project settings or inspect the source file for configuration errors.");
            status.setText("Configuration unavailable. No source files were changed.");
        }
    }


    private void focusSearch() {
        if (tabs.getSelectionIndex() == 2) tabs.setSelection(0);
        Text target = tabs.getSelectionIndex() == 1 ? hardwareSearch : search;
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
    }

    private void revealHardwareSource() {
        if (hardwareDevices.getSelectionCount() == 0 || snapshot == null) return;
        var device = (DevicetreeHardware.Device) hardwareDevices.getSelection()[0].getData();
        String source = tree.getText();
        int start = 0;
        for (int line = 1; line < device.line(); line++) {
            int end = source.indexOf('\n', start);
            if (end < 0) return;
            start = end + 1;
        }
        int end = source.indexOf('\n', start);
        tree.setSelection(start, end < 0 ? source.length() : end);
        tree.showSelection();
        tree.setFocus();
        status.setText(device.path() + " · generated zephyr.dts:" + device.line());
    }

    private void copyHardwarePath() {
        if (hardwareDevices.getSelectionCount() == 0) return;
        var device = (DevicetreeHardware.Device) hardwareDevices.getSelection()[0].getData();
        var clipboard = new Clipboard(hardwareDevices.getDisplay());
        try {
            clipboard.setContents(new Object[] {device.path()}, new Transfer[] {TextTransfer.getInstance()});
        } finally { clipboard.dispose(); }
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
            if (DevicetreeHardware.matches(device, hardwareSearch.getText(), hardwareState.getText()))
                matches.add(device.path());
        var visible = new java.util.HashSet<>(matches);
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
                        device.status().isBlank() ? "Default (okay)" : device.status()});
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
                    .findFirst().orElse(null);
            if (selection != null) {
                hardwareDevices.setSelection(selection);
                hardwareDevices.showSelection();
                describeHardware();
            } else hardwareDescription.setText(devices.isEmpty()
                    ? "Build the exact target to generate zephyr.dts. Existing overlays remain editable."
                    : "No matching nodes. Clear the search or choose All nodes.");
            hardwareSummary.setText(matches.size() + " matching nodes · " + devices.size()
                    + " in this build · ancestors shown for context · double-click to reveal source");
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
                            ConfigurationStudioPresentation.title(option.symbol()) + "  ·  CONFIG_" + option.symbol(),
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
        if (peripheralTabs != null && !peripheralTabs.isDisposed()) {
            for (int i = 0; i < peripheralTabs.getItemCount(); i++) {
                if (peripheralTabs.getItem(i).getData() == domain) {
                    peripheralTabs.setSelection(i);
                    break;
                }
            }
        }
        refreshPeripheralInfo();
    }

    private void refreshPeripheralInfo() {
        if (peripheralTabs == null || peripheralTabs.isDisposed()) return;
        for (var entry : peripheralInfo.entrySet()) {
            Text info = entry.getValue();
            if (info != null && !info.isDisposed())
                info.setText(ConfigurationStudioDomains.inspect(entry.getKey(), snapshot));
        }
        if (codeExample != null && !codeExample.isDisposed())
            codeExample.setText(activeDomain.example().isBlank()
                    ? "A board-specific example is not available for this category yet.\n\n"
                    + "Check Zephyr's chosen driver API, board bindings and Devicetree first.\n"
                    + "No source files or hardware assignments are generated automatically."
                    : activeDomain.example());
    }

    private void sizeColumns() {
        if (options == null || options.isDisposed() || options.getColumnCount() != 4) return;
        int width = options.getClientArea().width - 18;
        if (width <= 0) return;
        int[] minimum = {170, 156, 98, 88};
        double[] fractions = {0.40, 0.29, 0.17, 0.14};
        for (int i = 0; i < 4; i++)
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
                    inlineToggleEditor.setEditor(inlineToggle, options.getSelection()[0], 1);
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
                    ? "Build required: prj.conf changed since the last generated .config."
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
