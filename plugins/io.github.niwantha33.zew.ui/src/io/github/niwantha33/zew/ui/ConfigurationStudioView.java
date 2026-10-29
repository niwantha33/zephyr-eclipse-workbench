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
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Point;
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
import io.github.niwantha33.zew.core.ConfigurationStudioPresentation;
import io.github.niwantha33.zew.core.KconfigSourceHelp;
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
    private Text search, help, tree, analysis;
    private Table options;
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
        action(secondary, "Hardware overlay", this::openOverlay)
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
        search.setMessage("Search by feature, CONFIG_ name or value…");
        search.addModifyListener(event -> { filter = search.getText(); refreshOptions(); });

        overview = new Label(software, SWT.WRAP);
        overview.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        overview.setText("Select a feature to inspect its definition and change its project request.");

        var sash = new SashForm(software, SWT.HORIZONTAL);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        options = new Table(sash, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
        options.setHeaderVisible(true);
        options.setLinesVisible(true);
        for (String header : new String[] {"Feature / Kconfig symbol", "Effective", "Requested", "Status"}) {
            var column = new TableColumn(options, SWT.LEFT);
            column.setText(header);
        }
        options.addListener(SWT.Selection, event -> describeSelected());
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

        help = new Text(detail, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        help.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite actions = new Composite(detail, SWT.NONE);
        actions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        actions.setLayout(new GridLayout(2, true));
        enableButton = action(actions, "Enable", () -> setToggle("y"));
        enableButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        enableButton.setToolTipText("Request CONFIG_<symbol>=y in prj.conf; preview and backup before saving");
        disableButton = action(actions, "Disable", () -> setToggle("n"));
        disableButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        disableButton.setToolTipText("Request CONFIG_<symbol>=n in prj.conf; preview and backup before saving");
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
        warning.setText("Generated Devicetree is read-only. Use an application overlay to change hardware, "
                + "then validate the board's bindings, pin assignments and clocks with a Zephyr build.");
        warning.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        tree = new Text(hardware, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        tree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        action(hardware, "Open the selected application overlay…", this::openOverlay);

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
        category.select(1);
        tabs.setSelection(0);
        refreshOptions();
    }

    private void reload() {
        helpGeneration.incrementAndGet();
        if (helpJob != null) helpJob.cancel();
        definitionCache.clear();
        if (options == null || options.isDisposed()) return;
        if (project == null) {
            hasGeneratedConfiguration = false;
            buildOutdated = false;
            updateMetrics();
            options.removeAll();
            describeSelected();
            help.setText("Open Zephyr > Configuration Studio with an application selected in Project Explorer.");
            tree.setText("");
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
            refreshOptions();
            tree.setText(snapshot.devicetree().isEmpty()
                    ? "No resolved Devicetree is available. Select the board in Project settings "
                            + "and build to generate zephyr.dts. Existing overlays remain editable."
                    : snapshot.devicetree());
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
            analysis.setText("Open Project settings or inspect the source file for configuration errors.");
            status.setText("Configuration unavailable. No source files were changed.");
        }
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
                    if (!ConfigurationStudioPresentation.matches(option, active, filter)) continue;
                    matches++;
                    if (shown >= 750) continue;
                    TableItem row = new TableItem(options, SWT.NONE);
                    row.setText(new String[] {
                            ConfigurationStudioPresentation.title(option.symbol()) + "  ·  CONFIG_" + option.symbol(),
                            ConfigurationStudioPresentation.current(option),
                            ConfigurationStudioPresentation.requested(option),
                            ConfigurationStudioPresentation.review(option)
                    });
                    row.setData(option);
                    if (option.symbol().equals(previousSymbol)) keepIndex = shown;
                    ++shown;
                }
            }
            for (int i = 0; i < options.getColumnCount(); i++) {
                options.getColumn(i).pack();
                int cap = i == 0 ? 365 : 140;
                if (options.getColumn(i).getWidth() > cap) options.getColumn(i).setWidth(cap);
            }
            if (options.getItemCount() > 0) options.select(keepIndex >= 0 ? keepIndex : 0);
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
    }

    private Option selected() {
        if (options == null || options.isDisposed() || options.getSelectionCount() == 0) return null;
        return (Option) options.getSelection()[0].getData();
    }

    private void describeSelected() {
        if (helpJob != null) helpJob.cancel();
        Option o = selected();
        boolean togglable = ConfigurationStudioPresentation.isToggle(o);
        if (enableButton != null && !enableButton.isDisposed()) {
            enableButton.setEnabled(togglable && !"y".equals(o.requested()));
            disableButton.setEnabled(togglable && !"n".equals(o.requested()));
            editButton.setEnabled(o != null && !o.unresolved() && !togglable);
            removeButton.setEnabled(o != null && o.requested() != null);
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
        help.setText(base);
        // Source text is a supplementary explanation, not a Kconfig dependency
        // evaluation. Run filesystem searches off the SWT UI thread.
        if (project == null) return;
        final int generation = helpGeneration.incrementAndGet();
        final org.eclipse.swt.widgets.Display display = help.getDisplay();
        try {
            Path workspace = Path.of(ProjectContext.settings(project).getProperty("workspace"));
            String key = workspace + "#" + o.symbol();
            String cached = definitionCache.get(key);
            if (cached != null) { help.setText(base + cached); return; }
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
                    String sourceText = result;
                    if (!display.isDisposed()) display.asyncExec(() -> {
                        if (!help.isDisposed() && generation == helpGeneration.get()
                                && selected() != null && o.symbol().equals(selected().symbol()))
                            help.setText(base + sourceText);
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
        saveSelected(current, value);
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
        super.dispose();
    }

    @Override public void setFocus() {
        if (search != null && !search.isDisposed()) search.setFocus();
    }
}
