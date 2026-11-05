// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.ViewPart;
import io.github.niwantha33.zew.core.ConfigurationStudioFiles;
import io.github.niwantha33.zew.core.ConfigurationEvidenceReceipt;
import io.github.niwantha33.zew.core.ConfigurationFailureEvidence;
import io.github.niwantha33.zew.core.KconfigTraceEvidence;
import io.github.niwantha33.zew.core.DevicetreeHardware;
import io.github.niwantha33.zew.core.DevicetreeParameters;
import io.github.niwantha33.zew.core.FocusedConfigurationModel;
import io.github.niwantha33.zew.core.HardwarePeripheralIndex;
import io.github.niwantha33.zew.core.PeripheralStartGuide;
import io.github.niwantha33.zew.core.PeripheralWorkflow;

/** Three-section configuration entrypoint, backed by existing evidence engines. */
public final class ConfigurationHomeView extends ViewPart {
    public static final String ID = Activator.ID + ".configurationHome";
    private record Evidence(String board, String profile, Path build, String dts,
                            String config, Properties setup, boolean stale, String warning,
                            FocusedConfigurationModel.Overview overview,
                            List<DevicetreeHardware.Device> nodes) {
        public Evidence { nodes = List.copyOf(nodes); }
    }

    private record Detail(Evidence snapshot, String category, String path,
                          PeripheralStartGuide.Guide guide, String properties) {}
    private static final int OVERVIEW = 0, HARDWARE = 1, BUILD = 2;
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicInteger detailGeneration = new AtomicInteger();
    private Detail detail;
    private Job detailJob;
    private IProject project;
    private Evidence evidence;
    private Job refreshJob;
    private TabFolder primary;
    private Label header, freshness, capacityFlash, capacityRam, overviewInfo, issueInfo;
    private Table peripheralRows, issueRows, hardwareNodes;
    private Text overviewSearch, hardwareSearch;
    private Combo categories;
    private Label navigationEmpty, editSupport;
    private List<FocusedConfigurationModel.Row> overviewRows = List.of();
    private String category = "ADC", controllerPath = "";
    private Composite stepHost;
    private StackLayout stepLayout;
    private Composite[] pages;
    private int step = 0;
    private Label breadcrumb, guideState;
    private Text[] pageText;
    private Button previous, next, validateApply, example;
    private Composite hardwareAdvanced, systemAdvanced;
    private Button showHardwareAdvanced, showSystemAdvanced;
    private Text buildDetails, kconfigOriginSymbol, kconfigOriginResult;
    private final AtomicInteger originGeneration = new AtomicInteger();
    private Job originJob;

    @Override public void createPartControl(Composite parent) {
        var shellLayout = new GridLayout(1, false);
        shellLayout.marginWidth = 14;
        shellLayout.marginHeight = 12;
        shellLayout.verticalSpacing = 10;
        parent.setLayout(shellLayout);
        var heading = bar(parent, 3);
        var title = new Composite(heading, SWT.NONE);
        title.setLayoutData(fillWidth());
        title.setLayout(new GridLayout(1, false));
        new Label(title, SWT.NONE).setText("ZEPHYR  /  CONFIGURATION STUDIO");
        header = new Label(title, SWT.WRAP);
        header.setLayoutData(fillWidth());
        header.setText("Select a Zephyr project  •  Board unknown  •  Profile unknown");
        freshness = new Label(heading, SWT.WRAP);
        freshness.setText("Build evidence: unknown");
        button(heading, "Refresh", this::refresh);

        primary = new TabFolder(parent, SWT.NONE);
        primary.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        overview(tab("Overview"));
        hardware(tab("Hardware"));
        build(tab("Build & System"));
        primary.setSelection(OVERVIEW);
        clear("Select a Zephyr project from Project Explorer.");
    }

    private Composite tab(String text) {
        var tab = new TabItem(primary, SWT.NONE);
        tab.setText(text);
        var content = new Composite(primary, SWT.NONE);
        var layout = new GridLayout(1, false);
        layout.marginWidth = 14;
        layout.marginHeight = 12;
        layout.verticalSpacing = 10;
        content.setLayout(layout);
        tab.setControl(content);
        return content;
    }

    private void overview(Composite root) {
        new Label(root, SWT.NONE).setText("Selected board and configuration");
        overviewInfo = new Label(root, SWT.WRAP);
        overviewInfo.setLayoutData(fillWidth());
        var sizes = bar(root, 3);
        var f = new Composite(sizes, SWT.NONE);
        f.setLayout(new GridLayout(1, false));
        f.setLayoutData(fillWidth());
        new Label(f, SWT.NONE).setText("FLASH CAPACITY — not usage");
        capacityFlash = new Label(f, SWT.NONE);
        var m = new Composite(sizes, SWT.NONE);
        m.setLayout(new GridLayout(1, false));
        m.setLayoutData(fillWidth());
        new Label(m, SWT.NONE).setText("RAM CAPACITY — not usage");
        capacityRam = new Label(m, SWT.NONE);
        button(sizes, "View memory map", this::memoryMap);

        // Place issues before the long hardware list so the next action is
        // visible at 1366×768 without scrolling or reading raw DTS.
        new Label(root, SWT.NONE).setText("Needs attention");
        issueRows = table(root, new String[]{"Issue", "Next action"},
                new int[]{240, 630}, false);
        issueInfo = new Label(root, SWT.WRAP);
        issueInfo.setLayoutData(fillWidth());
        issueRows.addListener(SWT.Selection, e -> {
            if (issueRows.getSelectionCount() == 1)
                issueInfo.setText(((FocusedConfigurationModel.Issue)
                        issueRows.getSelection()[0].getData()).detail());
        });
        button(root, "Review selected issue", this::reviewIssue);

        new Label(root, SWT.NONE).setText("Peripherals in the selected build");
        overviewSearch = new Text(root, SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH | SWT.CANCEL);
        overviewSearch.setLayoutData(fillWidth());
        overviewSearch.setMessage("Find a peripheral or configuration issue...");
        overviewSearch.addModifyListener(event -> populateOverviewRows());
        peripheralRows = table(root, new String[]{"Peripheral", "Status", "Next action"},
                new int[]{135, 165, 570}, true);
        peripheralRows.addListener(SWT.DefaultSelection, e -> choosePeripheral());
        button(root, "Open selected hardware", this::choosePeripheral);
        overviewInfo.setText("No generated build data. No chip/pin inference.");
        capacityFlash.setText("Unknown");
        capacityRam.setText("Unknown");
        issueInfo.setText("Choose an issue to review its evidence and next action.");
    }

    private void hardware(Composite root) {
        // One navigable selection, one focused detail. Raw evidence and
        // advanced editing are optional, never permanently visible.
        var split = new SashForm(root, SWT.HORIZONTAL);
        split.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        var left = new Composite(split, SWT.NONE);
        var leftLayout = new GridLayout(1, false);
        leftLayout.marginWidth = 4;
        leftLayout.marginHeight = 4;
        left.setLayout(leftLayout);
        new Label(left, SWT.NONE).setText("Find hardware");
        hardwareSearch = new Text(left, SWT.SEARCH | SWT.ICON_SEARCH | SWT.CANCEL | SWT.BORDER);
        hardwareSearch.setLayoutData(fillWidth());
        hardwareSearch.setMessage("Search name, address or compatible");
        hardwareSearch.addModifyListener(event -> {
            controllersForCategory();
            step = 0;
            renderGuide();
        });
        new Label(left, SWT.NONE).setText("Type");
        categories = new Combo(left, SWT.READ_ONLY | SWT.DROP_DOWN);
        categories.setLayoutData(fillWidth());
        categories.setItems(PeripheralWorkflow.categories().toArray(String[]::new));
        categories.select(categories.indexOf(category));
        categories.addListener(SWT.Selection, event -> {
            category = categories.getText();
            controllerPath = "";
            step = 0;
            controllersForCategory();
            renderGuide();
        });
        hardwareNodes = table(left, new String[]{"Controller", "Status"},
                new int[]{220, 125}, true);
        hardwareNodes.addListener(SWT.Selection, event -> {
            if (hardwareNodes.getSelectionCount() != 1) return;
            var node = (DevicetreeHardware.Device) hardwareNodes.getSelection()[0].getData();
            if (node == null) return;
            controllerPath = node.path();
            step = 0;
            renderGuide();
        });
        navigationEmpty = new Label(left, SWT.WRAP);
        navigationEmpty.setLayoutData(fillWidth());
        navigationEmpty.setText("Build the selected board to discover its controllers.");

        var right = new Composite(split, SWT.NONE);
        var rightLayout = new GridLayout(1, false);
        rightLayout.marginWidth = 12;
        rightLayout.marginHeight = 4;
        rightLayout.verticalSpacing = 9;
        right.setLayout(rightLayout);
        breadcrumb = new Label(right, SWT.WRAP);
        breadcrumb.setLayoutData(fillWidth());
        guideState = new Label(right, SWT.WRAP);
        guideState.setLayoutData(fillWidth());
        editSupport = new Label(right, SWT.WRAP);
        editSupport.setLayoutData(fillWidth());
        stepHost = new Composite(right, SWT.NONE);
        stepHost.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        stepLayout = new StackLayout();
        stepHost.setLayout(stepLayout);
        pages = new Composite[4];
        pageText = new Text[4];
        for (int i = 0; i < pages.length; i++) {
            pages[i] = new Composite(stepHost, SWT.NONE);
            pages[i].setLayout(new GridLayout(1, false));
            pageText[i] = new Text(pages[i], SWT.BORDER | SWT.READ_ONLY | SWT.MULTI
                    | SWT.WRAP | SWT.V_SCROLL);
            pageText[i].setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        }
        var nav = bar(right, 3);
        previous = button(nav, "Back", () -> {
            step = Math.max(0, step - 1);
            renderGuide();
        });
        next = button(nav, "Next: inspect details", () -> {
            step = Math.min(3, step + 1);
            renderGuide();
        });
        validateApply = button(nav, "Open verified review…", this::reviewSelected);
        showHardwareAdvanced = button(right, "Show advanced tools", () -> {
            boolean visible = !hardwareAdvanced.getVisible();
            setPanelVisible(hardwareAdvanced, visible);
            showHardwareAdvanced.setText(visible ? "Hide advanced tools" : "Show advanced tools");
            right.layout(true, true);
        });
        var advanced = bar(right, 2);
        hardwareAdvanced = advanced;
        button(advanced, "View pins", () -> advanced("pins"));
        button(advanced, "View memory map", this::memoryMap);
        button(advanced, "Inspect Devicetree sources", () -> advanced("sources"));
        button(advanced, "Inspect bindings / Kconfig", () -> advanced("guide"));
        example = button(advanced, "Examples and evidence", () -> advanced("guide"));
        setPanelVisible(hardwareAdvanced, false);
        split.setWeights(new int[]{37, 63});
        controllersForCategory();
        renderGuide();
    }

    private void build(Composite root) {
        new Label(root, SWT.NONE).setText("Build profile, toolchain & reproducibility");
        buildDetails = new Text(root, SWT.BORDER | SWT.READ_ONLY | SWT.MULTI
                | SWT.WRAP | SWT.V_SCROLL);
        buildDetails.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        var actions = bar(root, 4);
        button(actions, "Build & validate", () -> advanced("build"));
        button(actions, "Review failed configure", this::openRecordedFailure);
        button(actions, "Project settings & profiles", () -> advanced("settings"));
        button(actions, "Inspect build evidence", () -> advanced("evidence"));
        showSystemAdvanced = button(root, "Show advanced system settings", () -> {
            boolean visible = !systemAdvanced.getVisible();
            setPanelVisible(systemAdvanced, visible);
            showSystemAdvanced.setText(visible
                    ? "Hide advanced system settings" : "Show advanced system settings");
            root.layout(true, true);
        });
        systemAdvanced = new Composite(root, SWT.NONE);
        systemAdvanced.setLayoutData(fillWidth());
        systemAdvanced.setLayout(new GridLayout(1, false));
        var lower = bar(systemAdvanced, 3);
        button(lower, "Kconfig & prj.conf", () -> advanced("software"));
        button(lower, "RTOS & tracing", () -> advanced("rtos"));
        button(lower, "Devicetree & bindings", () -> advanced("sources"));
        new Label(systemAdvanced, SWT.NONE).setText("Zephyr Kconfig origin trace — optional, read-only");
        var origin = bar(systemAdvanced, 2);
        kconfigOriginSymbol = new Text(origin, SWT.BORDER | SWT.SEARCH);
        kconfigOriginSymbol.setLayoutData(fillWidth());
        kconfigOriginSymbol.setMessage("Exact CONFIG_ symbol, e.g. CONFIG_GPIO");
        button(origin, "Explain recorded origin", this::explainKconfigOrigin);
        kconfigOriginResult = new Text(systemAdvanced,
                SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        var originSize = fillWidth();
        originSize.heightHint = 82;
        kconfigOriginResult.setLayoutData(originSize);
        kconfigOriginResult.setText("No Kconfig origin requested. A clean Zephyr traceconfig "
                + "report must be generated explicitly; an incremental trace cannot prove source origin.");
        setPanelVisible(systemAdvanced, false);
        buildDetails.setText("Select a project to inspect its exact build and Zephyr environment.");
    }

    private static void setPanelVisible(Composite panel, boolean visible) {
        if (panel == null || panel.isDisposed()) return;
        panel.setVisible(visible);
        if (panel.getLayoutData() instanceof GridData data) data.exclude = !visible;
    }

    private static GridData fillWidth() { return new GridData(SWT.FILL, SWT.CENTER, true, false); }
    private static Composite bar(Composite parent, int columns) {
        var child = new Composite(parent, SWT.NONE);
        child.setLayoutData(fillWidth()); child.setLayout(new GridLayout(columns, false));
        return child;
    }
    private static Button button(Composite parent, String title, Runnable action) {
        Button result = new Button(parent, SWT.PUSH);
        result.setText(title);
        result.addListener(SWT.Selection, e -> action.run());
        return result;
    }
    private static Table table(Composite parent, String[] headings, int[] widths, boolean grow) {
        Table t = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL);
        var gd = new GridData(SWT.FILL, SWT.FILL, true, grow);
        if (!grow) gd.heightHint = 100;
        t.setLayoutData(gd);
        t.setHeaderVisible(true); t.setLinesVisible(true);
        for (int i = 0; i < headings.length; i++) {
            var col = new TableColumn(t, SWT.LEFT);
            col.setText(headings[i]); col.setWidth(widths[i]);
        }
        // SWT tables need to adapt when Eclipse splits/resizes the editor.
        // Fixed 870px column widths previously required horizontal scrolling
        // even when the table was the only visible content.
        t.addListener(SWT.Resize, event -> {
            int available = t.getClientArea().width - 8;
            if (available < 160) return;
            int total = java.util.Arrays.stream(widths).sum();
            int used = 0;
            for (int i = 0; i < widths.length; i++) {
                int width = i == widths.length - 1 ? Math.max(65, available - used)
                        : Math.max(55, available * widths[i] / total);
                t.getColumn(i).setWidth(width);
                used += width;
            }
        });
        return t;
    }

    public void displayMissingProject() {
        project = null;
        evidence = null;
        detail = null;
        generation.incrementAndGet();
        detailGeneration.incrementAndGet();
        if (refreshJob != null) refreshJob.cancel();
        if (detailJob != null) detailJob.cancel();
        setPartName("Zephyr Configuration Studio");
        clear("No Zephyr project selected. Select a project in Project Explorer, "
                + "then choose Refresh here. No hardware data is available until "
                + "the selected board/profile has generated a build.");
    }

    public void displayProject(IProject selected) {
        if (selected == null || !selected.isAccessible()) {
            displayMissingProject();
            return;
        }
        project = selected;
        setPartName("Configuration — " + selected.getName());
        primary.setSelection(OVERVIEW);
        refresh();
    }

    public void openSection(String section) {
        if (primary == null || primary.isDisposed()) return;
        primary.setSelection("hardware".equals(section) ? HARDWARE
                : "system".equals(section) ? BUILD : OVERVIEW);
    }

    private static boolean newer(Path source, Path target) throws Exception {
        return source != null && Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                && (target == null || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.getLastModifiedTime(source).compareTo(Files.getLastModifiedTime(target)) > 0);
    }

    /** All file/project IO is off the SWT event thread. */
    private void refresh() {
        if (header == null || header.isDisposed()) return;
        // Refresh after choosing a project must work without closing and
        // reopening the view. Discovery from the Eclipse selection is cheap;
        // all build-file inspection runs in a background Job.
        if (project == null || !project.isAccessible()) {
            // The Studio can be the active part while the selected project
            // still belongs to Project Explorer. Retrieve that view's saved
            // selection instead of relying on the active page selection.
            var selection = getSite().getWorkbenchWindow().getSelectionService()
                    .getSelection("org.eclipse.ui.navigator.ProjectExplorer");
            if (!(selection instanceof org.eclipse.jface.viewers.IStructuredSelection)
                    || ((org.eclipse.jface.viewers.IStructuredSelection) selection).size() != 1)
                selection = getSite().getPage().getSelection();
            if (selection instanceof org.eclipse.jface.viewers.IStructuredSelection items
                    && items.size() == 1) {
                var chosen = SelectedProject.fromObject(items.getFirstElement());
                if (chosen != null && ZephyrProjectLifecycle.canInspect(chosen)) {
                    displayProject(chosen);
                    return;
                }
            }
            displayMissingProject();
            return;
        }
        int ticket = generation.incrementAndGet();
        if (refreshJob != null) refreshJob.cancel();
        IProject selected = project;
        if (selected == null || !selected.isAccessible() || selected.getLocation() == null) {
            clear("Select an accessible Zephyr project in Project Explorer.");
            return;
        }
        // Do not leave a previous project's rows visible while a new build
        // is being read; the selected project and requested inputs are separate
        // from the last successful generated snapshot.
        evidence = null;
        detail = null;
        detailGeneration.incrementAndGet();
        if (detailJob != null) detailJob.cancel();
        clear("Loading selected project and generated build…");
        header.setText(selected.getName() + "  •  Reading board and profile…");
        freshness.setText("Build evidence: loading…");
        var display = getSite().getShell().getDisplay();
        refreshJob = new Job("Read selected Zephyr Configuration Studio build") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                Evidence observed;
                try {
                    Path app = selected.getLocation().toFile().toPath();
                    Properties settings = new Properties();
                    String board = "Not selected", profile = "default", warning = "";
                    Path build = null;
                    try {
                        settings = ProjectContext.settings(selected);
                        board = settings.getProperty("board", "Not selected");
                        profile = settings.getProperty("zew.buildConfiguration", "default");
                        build = ProjectContext.buildDirectory(selected, settings);
                    } catch (Exception unavailable) {
                        warning = "Project environment: " + unavailable.getMessage();
                    }
                    var snapshot = ConfigurationStudioFiles.inspect(app, build, profile);
                    Path effective = build == null ? null : build.resolve("zephyr/.config");
                    String config = effective != null && Files.isRegularFile(effective)
                            && Files.size(effective) <= 2_000_000
                            ? Files.readString(effective) : "";
                    // Capture-time SHA-256 receipts are the authority for
                    // exact selected project/board/profile freshness. File
                    // timestamps and the existence of generated outputs alone
                    // are not proof that these inputs were built.
                    var receipt = ConfigurationEvidenceReceipt.inspect(app, build, board, profile);
                    var freshnessState = switch (receipt.state()) {
                        case CURRENT -> FocusedConfigurationModel.Freshness.CURRENT_SNAPSHOT;
                        case CHANGED -> FocusedConfigurationModel.Freshness.STALE_SNAPSHOT;
                        case UNVERIFIED -> FocusedConfigurationModel.Freshness.UNVERIFIED_SNAPSHOT;
                        case NO_BUILD -> FocusedConfigurationModel.Freshness.NO_BUILD;
                    };
                    boolean stale = freshnessState != FocusedConfigurationModel.Freshness.CURRENT_SNAPSHOT;
                    warning += (warning.isBlank() ? "" : "\n")
                            + receipt.detail() + " Tracked source inputs: " + receipt.sourceCount() + ".";
                    var summary = FocusedConfigurationModel.inspect(
                            board, profile, snapshot.devicetree(), config, freshnessState);
                    // CS-02: a successful build is NOT a prerequisite for
                    // diagnosis. Reuse retained Workbench failures and
                    // source-only requested configuration, without claiming
                    // a saved failure still applies to current sources.
                    var failure = ConfigurationFailureEvidence.inspect(app, build);
                    var combined = new java.util.ArrayList<FocusedConfigurationModel.Issue>();
                    for (var finding : failure.findings()) {
                        if (combined.size() >= 7) break;
                        combined.add(new FocusedConfigurationModel.Issue(
                                finding.title(), finding.explanation(),
                                finding.nextAction(), ""));
                    }
                    for (var old : summary.issues()) {
                        if (combined.size() >= 8) break;
                        combined.add(old);
                    }
                    summary = new FocusedConfigurationModel.Overview(summary.freshness(),
                            summary.board(), summary.profile(), summary.flash(), summary.ram(),
                            summary.peripherals(), combined);
                    warning += (warning.isBlank() ? "" : "\n")
                            + "Configuration evidence: " + failure.mode()
                            + ", requested settings: " + failure.requestedSymbols()
                            + ", recorded failed attempt: " + failure.recordedFailure() + ".";
                    var nodes = DevicetreeHardware.inspect(snapshot.devicetree());
                    observed = new Evidence(board, profile, build, snapshot.devicetree(), config,
                            settings, stale, warning, summary, nodes);
                } catch (Exception problem) {
                    Activator plugin = Activator.getDefault();
                    if (plugin != null) plugin.getLog().log(
                            new Status(IStatus.ERROR, Activator.ID,
                                    "Configuration Studio could not read the selected build", problem));
                    observed = new Evidence("Unknown", "Unknown", null, "", "",
                            new Properties(), true, "Build inspection failed: " + problem.getMessage(),
                            FocusedConfigurationModel.inspect("Unknown", "Unknown", "", "", true),
                            List.of());
                }
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                final Evidence result = observed;
                display.asyncExec(() -> {
                    if (ticket == generation.get() && project == selected
                            && header != null && !header.isDisposed()) {
                        evidence = result; render();
                    }
                });
                return Status.OK_STATUS;
            }
        };
        refreshJob.setSystem(true); refreshJob.schedule();
    }

    private void clear(String description) {
        header.setText(description); freshness.setText("Build evidence: missing");
        overviewInfo.setText(description);
        overviewRows = List.of();
        peripheralRows.removeAll(); issueRows.removeAll();
        capacityFlash.setText("Unknown"); capacityRam.setText("Unknown");
        buildDetails.setText(description);
        if (hardwareNodes != null && !hardwareNodes.isDisposed()) hardwareNodes.removeAll();
        controllerPath = "";
        if (navigationEmpty != null && !navigationEmpty.isDisposed())
            navigationEmpty.setText(description);
        guideState.setText(description);
        if (editSupport != null && !editSupport.isDisposed())
            editSupport.setText("Editing support: Unknown. Build to identify a controller and binding.");
        if (pageText != null) for (Text page : pageText)
            if (page != null && !page.isDisposed()) page.setText(description);
        if (previous != null && !previous.isDisposed()) previous.setEnabled(false);
        if (next != null && !next.isDisposed()) next.setEnabled(false);
        if (validateApply != null && !validateApply.isDisposed()) validateApply.setEnabled(false);
    }

    private void render() {
        Evidence found = evidence;
        if (found == null) return;
        var summary = found.overview();
        header.setText(project.getName() + "  •  " + found.board() + "  •  " + found.profile()
                + "  •  Target qualifier: " + qualifier(found.board()));
        freshness.setText(switch (summary.freshness()) {
            case NO_BUILD -> "Build data MISSING — configure/build required";
            case STALE_SNAPSHOT -> "Build inputs CHANGED — rebuild required";
            case UNVERIFIED_SNAPSHOT -> "Build provenance UNVERIFIED — rebuild to capture";
            case CURRENT_SNAPSHOT -> "Tracked build inputs MATCH (not runtime)";
        });
        if (hasDirtyProjectEditor())
            freshness.setText("Pending unsaved project edits — generated build not current");
        freshness.setForeground(freshness.getDisplay().getSystemColor(
                summary.freshness() == FocusedConfigurationModel.Freshness.CURRENT_SNAPSHOT
                ? SWT.COLOR_DARK_GREEN : summary.freshness()
                == FocusedConfigurationModel.Freshness.STALE_SNAPSHOT
                || summary.freshness() == FocusedConfigurationModel.Freshness.UNVERIFIED_SNAPSHOT
                ? SWT.COLOR_DARK_YELLOW : SWT.COLOR_DARK_RED));
        overviewInfo.setText("States describe generated configuration, NOT runtime use."
                + " Capacities are not memory usage."
                + (found.warning().isBlank() ? "" : "\n" + found.warning()));
        capacityFlash.setText(summary.flash().value());
        capacityFlash.setToolTipText(summary.flash().evidence());
        capacityRam.setText(summary.ram().value());
        capacityRam.setToolTipText(summary.ram().evidence());
        overviewRows = summary.peripherals();
        populateOverviewRows();
        issueRows.removeAll();
        for (var issue : summary.issues()) {
            var item = new TableItem(issueRows, SWT.NONE);
            item.setText(new String[]{issue.title(), issue.nextAction()});
            item.setData(issue);
        }
        issueInfo.setText(summary.issues().isEmpty()
                ? "No deterministic issue identified; hardware still requires verification."
                : "Select an issue and choose Review issue.");
        controllersForCategory();
        renderGuide();
        buildDetails.setText("SELECTED PROJECT\n\nBoard: " + found.board()
                + "\nSoC/target qualifier: " + qualifier(found.board())
                + "\nProfile: " + found.profile()
                + "\nBuild folder: " + (found.build() == null ? "unavailable" : found.build())
                + "\nGenerated DTS: " + (found.dts().isBlank() ? "missing" : "present")
                + "\nGenerated Kconfig: " + (found.config().isBlank() ? "missing" : "present")
                + "\nBuild freshness: " + summary.freshness()
                + "\nToolchain/SDK: " + found.setup().getProperty("tools.sdk", "not configured")
                + "\nZephyr checkout: " + (found.setup().getProperty("workspace", "").isBlank()
                        ? "not configured" : Path.of(found.setup().getProperty("workspace"), "zephyr"))
                + "\nZephyr revision: not proven by this snapshot; inspect locked West manifest"
                + "\n\nBuild profiles and source revisions are selected independently."
                + "\nAdvanced Kconfig and RTOS options remain accessible below.");
        primary.getParent().layout(true, true);
    }

    private static String qualifier(String board) {
        return board != null && board.contains("/")
                ? board.substring(board.lastIndexOf('/') + 1) : "not specified";
    }

    private void populateOverviewRows() {
        if (peripheralRows == null || peripheralRows.isDisposed()) return;
        String filter = overviewSearch == null ? ""
                : overviewSearch.getText().trim().toLowerCase(java.util.Locale.ROOT);
        peripheralRows.removeAll();
        for (var row : overviewRows) {
            if (!filter.isBlank() && !row.category().toLowerCase(java.util.Locale.ROOT).contains(filter)
                    && !row.explanation().toLowerCase(java.util.Locale.ROOT).contains(filter)
                    && !row.nextAction().toLowerCase(java.util.Locale.ROOT).contains(filter))
                continue;
            var item = new TableItem(peripheralRows, SWT.NONE);
            item.setText(new String[]{row.category(),
                    FocusedConfigurationModel.display(row.state()), row.nextAction()});
            item.setForeground(1, peripheralRows.getDisplay().getSystemColor(
                    switch (row.state()) {
                        case CONFIGURED -> SWT.COLOR_DARK_GREEN;
                        case DISABLED, SETUP_INCOMPLETE -> SWT.COLOR_DARK_YELLOW;
                        case UNSUPPORTED -> SWT.COLOR_DARK_RED;
                        case UNKNOWN -> SWT.COLOR_DARK_GRAY;
                    }));
            item.setData(row);
        }
    }

    private void choosePeripheral() {
        if (peripheralRows.getSelectionCount() != 1) {
            MessageDialog.openInformation(getSite().getShell(), "Choose a peripheral",
                    "Select one row in the Overview first.");
            return;
        }
        var row = (FocusedConfigurationModel.Row) peripheralRows.getSelection()[0].getData();
        selectCategory(row.category());
    }

    private void reviewIssue() {
        if (issueRows.getSelectionCount() != 1) {
            MessageDialog.openInformation(getSite().getShell(), "Select an issue",
                    "Select an issue in the Overview before reviewing it.");
            return;
        }
        var row = (FocusedConfigurationModel.Issue) issueRows.getSelection()[0].getData();
        issueInfo.setText(row.detail() + "\nNext: " + row.nextAction());
        if (!row.category().isBlank()) selectCategory(row.category());
        else if (row.title().startsWith("Recorded failed build")
                || row.title().startsWith("Undefined Kconfig symbol"))
            openRecordedFailure();
        else primary.setSelection(BUILD);
    }

    private void selectCategory(String type) {
        int index = categories.indexOf(type);
        if (index < 0) return;
        categories.select(index); category = type; controllerPath = ""; step = 0;
        controllersForCategory();
        primary.setSelection(HARDWARE); renderGuide();
    }

    /** All selection state lives in one table; category and search only filter it. */
    private void controllersForCategory() {
        if (hardwareNodes == null || hardwareNodes.isDisposed()) return;
        String previous = controllerPath;
        String filter = hardwareSearch.getText().trim().toLowerCase(java.util.Locale.ROOT);
        hardwareNodes.setRedraw(false);
        try {
            hardwareNodes.removeAll();
            var nodes = evidence == null ? List.<DevicetreeHardware.Device>of()
                    : evidence.nodes();
            for (var node : nodes) {
                if (!PeripheralWorkflow.selectable(node, category)) continue;
                if (!filter.isBlank()
                        && !node.path().toLowerCase(java.util.Locale.ROOT).contains(filter)
                        && !node.name().toLowerCase(java.util.Locale.ROOT).contains(filter)
                        && !node.compatible().toLowerCase(java.util.Locale.ROOT).contains(filter))
                    continue;
                var item = new TableItem(hardwareNodes, SWT.NONE);
                boolean enabled = io.github.niwantha33.zew.core.PeripheralCoverage
                        .effectiveEnabled(node, nodes);
                item.setText(new String[]{node.name(), enabled ? "DTS enabled" : "Disabled"});
                item.setData(node);
            }
        } finally {
            hardwareNodes.setRedraw(true);
        }
        TableItem target = null;
        for (var row : hardwareNodes.getItems())
            if (((DevicetreeHardware.Device) row.getData()).path().equals(previous)) {
                target = row;
                break;
            }
        if (target == null && hardwareNodes.getItemCount() > 0)
            target = hardwareNodes.getItem(0);
        if (target == null) {
            controllerPath = "";
            navigationEmpty.setText(evidence == null || evidence.dts().isBlank()
                    ? "No generated build data. Build the selected target before selecting hardware."
                    : "No " + category + " controller matches this filter in the selected build. "
                        + "This does not prove silicon lacks it.");
        } else {
            controllerPath = ((DevicetreeHardware.Device) target.getData()).path();
            hardwareNodes.setSelection(target);
            hardwareNodes.showSelection();
            navigationEmpty.setText(hardwareNodes.getItemCount()
                    + " verified node(s) from generated DTS. Selection is build evidence, not runtime use.");
        }
    }


    /**
     * The selected page only displays cached, selected-build evidence. The
     * expensive DTS parsing and input/consumer analysis run off the SWT
     * thread. A generation + identity check prevents late results from an
     * old selected peripheral from appearing against the new one.
     */
    private void renderGuide() {
        if (stepHost == null || stepHost.isDisposed()) return;
        var snapshot = evidence;
        var plan = PeripheralWorkflow.forCategory(category);
        breadcrumb.setText("Hardware  /  " + category + "  /  " + plan.steps().get(step));
        stepLayout.topControl = pages[step];
        stepHost.layout(true, true);
        previous.setEnabled(step > 0 && !controllerPath.isBlank());
        boolean hasNode = snapshot != null && !controllerPath.isBlank();
        next.setEnabled(hasNode && step < 3);
        boolean reviewing = step == 3;
        validateApply.setVisible(reviewing);
        if (validateApply.getLayoutData() instanceof GridData gd) gd.exclude = !reviewing;
        next.setVisible(!reviewing);
        if (next.getLayoutData() instanceof GridData gd) gd.exclude = reviewing;
        next.getParent().layout(true, true);
        validateApply.setEnabled(hasNode && reviewing && !snapshot.stale()
                && !hasDirtyProjectEditor());
        example.setEnabled(hasNode);

        if (!hasNode) {
            guideState.setText(snapshot == null || snapshot.dts().isBlank()
                    ? "Requires build information — no generated Devicetree."
                    : "No " + category + " controller is selected in this build.");
            editSupport.setText("Editing support: Unknown — select a verified controller.");
            String message = "Select an actual controller in the left-hand list. "
                    + "For missing build data, use Build & System. An absent node "
                    + "does not prove the silicon lacks this peripheral.";
            for (Text text : pageText) text.setText(message);
            validateApply.setEnabled(false);
            return;
        }
        var current = detail;
        if (current == null || current.snapshot() != snapshot
                || !current.path().equals(controllerPath)
                || !current.category().equals(category)) {
            guideState.setText("Loading exact " + category
                    + " controller details from the selected build…");
            editSupport.setText("Evidence loading. No file is modified by selection.");
            for (Text text : pageText)
                text.setText("Reading selected Devicetree and effective Kconfig off the UI thread…");
            validateApply.setEnabled(false);
            scheduleDetail(snapshot, category, controllerPath);
            return;
        }
        showDetail(current, plan);
    }

    private void scheduleDetail(Evidence snapshot, String requestedCategory, String requestedPath) {
        int ticket = detailGeneration.incrementAndGet();
        if (detailJob != null) detailJob.cancel();
        var ui = getSite().getShell().getDisplay();
        detailJob = new Job("Inspect Zephyr peripheral " + requestedCategory) {
            @Override protected IStatus run(IProgressMonitor monitor) {
                Detail result;
                try {
                    var guide = PeripheralStartGuide.inspect(snapshot.dts(), requestedCategory,
                            requestedPath, snapshot.config(), snapshot.stale());
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    var values = PeripheralWorkflow.describeProperties(
                            snapshot.dts(), requestedPath, requestedCategory);
                    result = new Detail(snapshot, requestedCategory, requestedPath, guide, values);
                } catch (Exception failure) {
                    Activator plugin = Activator.getDefault();
                    if (plugin != null) plugin.getLog().log(new Status(IStatus.ERROR,
                            Activator.ID, "Peripheral detail inspection failed", failure));
                    var fallback = new PeripheralStartGuide.Guide(requestedCategory, requestedPath,
                            PeripheralStartGuide.State.BUILD_REQUIRED,
                            "Unable to inspect this controller: " + failure.getMessage(),
                            "Inspect the exact selected source and try Refresh.", "", "", "", "", -1);
                    result = new Detail(snapshot, requestedCategory, requestedPath,
                            fallback, "Inspection failed; no values have been guessed.");
                }
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                final Detail completed = result;
                ui.asyncExec(() -> {
                    if (ticket != detailGeneration.get()
                            || stepHost == null || stepHost.isDisposed()
                            || evidence != snapshot || !category.equals(requestedCategory)
                            || !controllerPath.equals(requestedPath))
                        return;
                    detail = completed;
                    renderGuide();
                });
                return Status.OK_STATUS;
            }
        };
        detailJob.setSystem(true);
        detailJob.schedule();
    }

    private void showDetail(Detail selected, PeripheralWorkflow.Plan plan) {
        var guide = selected.guide();
        var snapshot = selected.snapshot();
        String description = switch (guide.state()) {
            case BUILD_REQUIRED -> "Requires build information";
            case NOT_IN_DTS -> "Controller not found in generated Devicetree";
            case DTS_DISABLED -> "Disabled in Devicetree (controller or parent)";
            case SETUP_REQUIRED -> "Setup incomplete";
            case ADC_WIRING_FOUND -> "ADC consumer/channel described in build";
            case INSPECT_BINDING -> "Inspection required";
        };
        guideState.setText(description + "  •  " + selected.path()
                + (snapshot.stale() ? "  •  OUT OF DATE — rebuild" : ""));
        // A generic source editor is not an automatically supported form for
        // an arbitrary binding, and a successful DTS scan isn't validation.
        editSupport.setText("Editor support: Inspection first. Binding-checked source proposals "
                + "are available in the advanced editor, where supported. No physical pins verified.");
        pageText[0].setText("CONTROLLER\n\n" + guide.explanation()
                + "\n\nExact generated node: " + selected.path()
                + "\n\nNext: " + plan.connectionTitle()
                + ". Enabling a DTS node does not prove driver or runtime usage.");
        pageText[1].setText(plan.connectionTitle().toUpperCase(java.util.Locale.ROOT)
                + "\n\n" + plan.connectionHelp()
                + ("ADC".equals(selected.category()) ? "\n\n"
                    + FocusedConfigurationModel.pinEvidence(guide) : "")
                + "\n\nObserved values (last generated build):\n" + selected.properties()
                + "\n\nPhysical pad/package mapping and conflicts require exact board metadata.");
        pageText[2].setText("SETTINGS — " + selected.category()
                + "\n\n" + plan.settingsHelp()
                + "\n\nActual generated values (not proposed edits):\n"
                + selected.properties()
                + "\n\nDriver/Kconfig evidence:\n" + guide.configExample()
                + "\n\nOnly exact binding-allowed values may be proposed in the advanced editor.");
        pageText[3].setText("REVIEW — " + selected.category()
                + "\n\nNo source change has been proposed on this page."
                + "\nNo files will be written by selecting a controller or browsing its details."
                + "\n\n" + plan.reviewHelp()
                + "\n\nTo create an edit, use the verified source editor, review the actual file diff "
                + "and run binding/schema and real configure checks independently. "
                + "Only explicitly confirmed edits are written to the project-owned overlay."
                + "\n\nAfter applying, rebuild this board/profile; old generated data remain stale."
                + (snapshot.stale() ? "\n\nBLOCKED: current generated build is out of date." : ""));
    }

    /** Dirty in-memory source editors must never be overwritten by an apply flow. */
    private boolean hasDirtyProjectEditor() {
        if (project == null || getSite().getPage() == null) return false;
        for (var editor : getSite().getPage().getDirtyEditors()) {
            var input = editor.getEditorInput();
            if (input instanceof org.eclipse.ui.IFileEditorInput file
                    && project.equals(file.getFile().getProject()))
                return true;
        }
        return false;
    }

    private void reviewSelected() {
        if (evidence == null || controllerPath.isBlank() || evidence.stale()) {
            MessageDialog.openInformation(getSite().getShell(), "Build required",
                    "Select a verified controller and rebuild stale generated configuration before editing.");
            return;
        }
        if (hasDirtyProjectEditor()) {
            MessageDialog.openWarning(getSite().getShell(), "Unsaved project edits",
                    "Save or discard unsaved project editors before proposing a change. "
                    + "Nothing has been written.");
            return;
        }
        advanced("review");
    }

    private void explainKconfigOrigin() {
        if (kconfigOriginResult == null || kconfigOriginResult.isDisposed()) return;
        String query = kconfigOriginSymbol.getText().strip();
        String name = query.startsWith("CONFIG_") ? query.substring(7) : query;
        if (!name.matches("[A-Z0-9_]{1,160}")) {
            kconfigOriginResult.setText("Enter one exact CONFIG_ symbol. "
                    + "Unknown or misspelled names will not be guessed.");
            return;
        }
        var snapshot = evidence;
        if (snapshot == null || snapshot.build() == null) {
            kconfigOriginResult.setText("No selected generated build. "
                    + "Configure this board/profile before requesting a trace.");
            return;
        }
        int ticket = originGeneration.incrementAndGet();
        if (originJob != null) originJob.cancel();
        kconfigOriginResult.setText("Reading the selected build's upstream traceconfig report…");
        var ui = getSite().getShell().getDisplay();
        originJob = new Job("Inspect recorded Zephyr Kconfig origin") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                var result = KconfigTraceEvidence.inspect(snapshot.build(), query);
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                ui.asyncExec(() -> {
                    if (ticket != originGeneration.get()
                            || evidence != snapshot || kconfigOriginResult.isDisposed())
                        return;
                    kconfigOriginResult.setText((snapshot.stale()
                            ? "WARNING: selected build provenance is unverified or stale.\n" : "")
                            + result.describe());
                });
                return Status.OK_STATUS;
            }
        };
        originJob.setSystem(true);
        originJob.schedule();
    }

    private void openRecordedFailure() {
        if (project == null || !project.isAccessible()) {
            MessageDialog.openInformation(getSite().getShell(), "Select a project",
                    "Select a Zephyr project to inspect its recorded failed configuration.");
            return;
        }
        try {
            var part = getSite().getPage().showView(BuildFailureInvestigationView.ID);
            if (part instanceof BuildFailureInvestigationView history)
                history.displayProject(project);
        } catch (PartInitException e) {
            MessageDialog.openError(getSite().getShell(), "Failed-build evidence",
                    e.getMessage());
        }
    }

    private void memoryMap() {
        if (project == null || evidence == null) return;
        try {
            var part = getSite().getPage().showView(SocMemoryMapView.ID);
            if (part instanceof SocMemoryMapView map)
                map.displayProject(project, evidence.dts(), evidence.build(), evidence.stale());
        } catch (PartInitException e) {
            MessageDialog.openError(getSite().getShell(), "Memory map", e.getMessage());
        }
    }

    /** Dedicated advanced tools retain existing preview, real CMake and backup gates. */
    private void advanced(String destination) {
        if (project == null || !project.isAccessible()) {
            MessageDialog.openInformation(getSite().getShell(), "Select a project",
                    "Choose a Zephyr project to use this tool.");
            return;
        }
        try {
            var part = getSite().getPage().showView(ConfigurationStudioView.ID);
            if (part instanceof ConfigurationStudioView old) {
                old.displayProject(project,
                        List.of("pins","sources","guide","review","rtos").contains(destination),
                        null);
                old.openFocusedTool(destination, category, controllerPath);
            }
        } catch (PartInitException e) {
            MessageDialog.openError(getSite().getShell(), "Advanced tools", e.getMessage());
        }
    }

    @Override public void setFocus() {
        if (primary == null || primary.isDisposed()) return;
        primary.setFocus();
        // A user may have edited source while working in another Eclipse
        // editor. Expose pending unsaved changes upon returning.
        if (evidence != null && hasDirtyProjectEditor()) {
            freshness.setForeground(freshness.getDisplay().getSystemColor(SWT.COLOR_DARK_YELLOW));
            freshness.setText("Pending unsaved project edits — review/apply blocked");
        }
    }
    @Override public void dispose() {
        generation.incrementAndGet();
        if (refreshJob != null) refreshJob.cancel();
        detailGeneration.incrementAndGet();
        if (detailJob != null) detailJob.cancel();
        originGeneration.incrementAndGet();
        if (originJob != null) originJob.cancel();
        super.dispose();
    }
}
