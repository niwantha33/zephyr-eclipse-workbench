// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.eclipse.cdt.dsf.concurrent.DataRequestMonitor;
import org.eclipse.cdt.dsf.concurrent.RequestMonitor;
import org.eclipse.cdt.dsf.datamodel.DMContexts;
import org.eclipse.cdt.dsf.datamodel.IDMContext;
import org.eclipse.cdt.dsf.debug.service.IExpressions;
import org.eclipse.cdt.dsf.debug.service.IFormattedValues;
import org.eclipse.cdt.dsf.debug.service.IRunControl;
import org.eclipse.cdt.dsf.debug.service.IProcesses;
import org.eclipse.cdt.dsf.debug.service.IStack;
import org.eclipse.cdt.dsf.gdb.launching.GdbLaunch;
import org.eclipse.cdt.dsf.gdb.service.command.IGDBControl;
import org.eclipse.cdt.dsf.debug.service.ICachingService;
import org.eclipse.cdt.dsf.service.DsfServicesTracker;
import org.eclipse.cdt.dsf.service.DsfSession;
import org.eclipse.cdt.dsf.service.DsfServiceEventHandler;
import org.eclipse.cdt.dsf.ui.viewmodel.datamodel.IDMVMContext;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.contexts.IDebugContextListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import io.github.niwantha33.zew.core.SvdDevice;
import io.github.niwantha33.zew.core.SvdAutoDiscovery;
import io.github.niwantha33.zew.core.SvdTargetEvidence;
import io.github.niwantha33.zew.core.BuildArtifacts;
import io.github.niwantha33.zew.core.SvdPartCompatibility;
import io.github.niwantha33.zew.core.SvdPreferredSource;
import io.github.niwantha33.zew.core.RegisterPresentation;
import org.eclipse.core.runtime.preferences.InstanceScope;
import io.github.niwantha33.zew.core.VendorSvdProvisioner;
import io.github.niwantha33.zew.core.GitHubSvdProvisioner;
import io.github.niwantha33.zew.core.RegisterHistory;
import io.github.niwantha33.zew.core.RegisterWriteValue;
import io.github.niwantha33.zew.core.RegisterSparkline;
import io.github.niwantha33.zew.core.DebugSessionSelection;
import io.github.niwantha33.zew.core.SvdDevice.Register;

/** Exact-SVD peripheral inspection through the native DSF-GDB/OpenOCD session. */
public final class SocRegistersView extends ViewPart {
    private Tree tree;
    private Text registerDetails;
    private Table watchTable;
    private Table fieldTable;
    private Register inspectedRegister;
    private Label watchHeading;
    private Spinner watchInterval;
    private Button autoReadOnHalt;
    private int watchIntervalMs = 1500;
    private static final int MAX_SNAPSHOT_REGISTERS = 4096;
    private enum ReadScope { SELECTION, WATCHES, ALL_SAFE }
    private boolean autoSnapshotEnabled = true;
    private Label status;
    private Label summary;
    private Label contextInfo;
    private org.eclipse.swt.widgets.Display ui;
    private volatile DsfSession listenedSession;
    private Text search;
    private SvdDevice device;
    private String svdPath;
    private String legacySavedPath;
    private String selectedProjectName;
    private String debugResolution = "No unique active Zephyr debug session.";
    private String selectedBoard;
    // Exact qualified target derived from the selected build, including board-only targets.
    private String selectedSvdTarget;
    private String discoveryExplanation = "No exact project / SVD selected.";
    private volatile long discoveryGeneration;
    private final Map<String, BigInteger> values = new HashMap<>();
    private final Map<String, BigInteger> previousValues = new HashMap<>();
    private int currentReadTotal;
    private int currentReadOk;
    private int currentReadFailed;
    private String lastReadError;
    private java.time.Instant lastReadAt;
    private final RegisterHistory history = new RegisterHistory();
    private boolean liveWatch; // always off at startup
    private int watchEpoch;
    private volatile long quietUntil;
    private Button watchButton;
    private Canvas historyChart;
    private String svdIdentity;
    private final Map<String, String> errors = new HashMap<>();
    private final Set<String> watches = new HashSet<>();
    private volatile long generation;
    private volatile boolean closed;
    private boolean busy;
    private IDebugContextListener contextListener;
    private AutoCloseable contextSubscription;
    private Button pinViewSelection;
    private Label linkedSelection;
    private Path selectedBuildContext;

    @Override public void init(IViewSite site, IMemento memento) throws PartInitException {
        super.init(site, memento);
        if (memento != null) legacySavedPath = memento.getString("svdPath");
    }
    @Override public void createPartControl(Composite parent) {
        ui = parent.getDisplay();
        parent.setLayout(new GridLayout(1, false));
        // Primary actions fit narrow Debug perspectives. Rare actions live in
        // the native More menu rather than occupying the whole top of the view.
        var bar = new Composite(parent, SWT.NONE);
        bar.setLayout(new GridLayout(5, false));
        bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        button(bar, "Choose project", this::chooseProjectForDiscovery);
        button(bar, "Get SVD", this::downloadVendorSvd);
        button(bar, "Refresh selected", this::refresh);
        button(bar, "Read available", this::refreshAll);
        var more = new Button(bar, SWT.PUSH);
        more.setText("More…");
        var advanced = new Menu(more);
        menuAction(advanced, "Import SVD file…", () -> {
            var dialog = new FileDialog(parent.getShell(), SWT.OPEN);
            dialog.setFilterExtensions(new String[] { "*.svd;*.xml", "*.*" });
            String path = dialog.open();
            if (path != null) load(path);
        });
        menuAction(advanced, "Pin snapshot as baseline", this::pinBaseline);
        menuAction(advanced, "Export register history as CSV…", this::exportHistory);
        more.addListener(SWT.Selection, e -> {
            advanced.setLocation(more.toDisplay(0, more.getSize().y));
            advanced.setVisible(true);
        });

        var options = new Composite(parent, SWT.NONE);
        options.setLayout(new GridLayout(7, false));
        options.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        search = new Text(options, SWT.SEARCH | SWT.ICON_SEARCH | SWT.CANCEL);
        search.setMessage("Search registers or pins…");
        search.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        search.addModifyListener(e -> render());
        button(options, "Watch selected", () -> toggleWatch(selected()));
        watchButton = new Button(options, SWT.PUSH);
        watchButton.setText("Start watch");
        watchButton.addListener(SWT.Selection, e -> toggleLiveWatch());
        var periodLabel = new Label(options, SWT.NONE);
        periodLabel.setText("Every (ms)");
        watchInterval = new Spinner(options, SWT.BORDER);
        watchInterval.setMinimum(250);
        watchInterval.setMaximum(60000);
        watchInterval.setIncrement(250);
        watchInterval.setPageIncrement(1000);
        watchInterval.setSelection(watchIntervalMs);
        watchInterval.setToolTipText("Poll watched registers only while the target is halted.");
        watchInterval.addModifyListener(e -> watchIntervalMs = watchInterval.getSelection());
        autoReadOnHalt = new Button(options, SWT.CHECK);
        autoReadOnHalt.setText("Read on halt");
        autoReadOnHalt.setToolTipText("Read SVD-safe registers after the debugger pauses. Disable for sensitive peripherals.");
        autoReadOnHalt.setSelection(autoSnapshotEnabled);
        autoReadOnHalt.addListener(SWT.Selection, e -> {
            autoSnapshotEnabled = autoReadOnHalt.getSelection();
            if (autoSnapshotEnabled) scheduleAutoSnapshot();
        });
        pinViewSelection = new Button(options, SWT.CHECK);
        pinViewSelection.setText("Pin project");
        pinViewSelection.setToolTipText("Keep the current project when another view changes its selection.");
        pinViewSelection.addListener(SWT.Selection, e -> {
            if (!pinViewSelection.getSelection())
                ZephyrViewContextHub.forWindow(getSite().getWorkbenchWindow())
                        .replay(this::followContext);
        });
        linkedSelection = new Label(parent, SWT.WRAP);
        var linkedData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        linkedData.exclude = true;
        linkedSelection.setLayoutData(linkedData);
        linkedSelection.setVisible(false);
        linkedSelection.setText("No linked Devicetree selection.");
        status = new Label(parent, SWT.WRAP);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        status.setText("Automatically detecting exact vendor SVD from the selected Zephyr build. No generic MCU model is substituted.");
        contextInfo = new Label(parent, SWT.WRAP);
        contextInfo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        contextInfo.setText("Project —  ›  Chip —  ›  Register map —  ›  Debugger —  ›  Values —");
        summary = new Label(parent, SWT.WRAP);
        summary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        summary.setText("Choose an MCU project. Register definitions are loaded automatically when available.");
        historyChart = new Canvas(parent, SWT.BORDER | SWT.DOUBLE_BUFFERED);
        var chartData = new GridData(SWT.FILL, SWT.TOP, true, false);
        chartData.heightHint = 105;
        chartData.exclude = true;
        historyChart.setLayoutData(chartData);
        historyChart.setVisible(false);
        historyChart.addPaintListener(e -> paintHistory(e.gc));
        // Native split explorer, bitwise inspector and independent watch panel.
        // Use SWT system colours: the panel follows Eclipse light/dark themes.
        var split = new org.eclipse.swt.custom.SashForm(parent, SWT.HORIZONTAL);
        split.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        tree = new Tree(split, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
        tree.setHeaderVisible(true); tree.setLinesVisible(false);
        var inspector = new Composite(split, SWT.NONE);
        inspector.setLayout(new GridLayout(1, false));
        registerDetails = new Text(inspector, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY
                | SWT.V_SCROLL | SWT.H_SCROLL | SWT.WRAP);
        var detailLayout = new GridData(SWT.FILL, SWT.TOP, true, false);
        detailLayout.heightHint = 165;
        registerDetails.setLayoutData(detailLayout);
        registerDetails.setFont(org.eclipse.jface.resource.JFaceResources.getTextFont());
        registerDetails.setText("Select a register to see its value, bits and purpose.\n"
                + "No value is assumed until the debugger successfully reads it.");
        var fieldHeading = new Label(inspector, SWT.NONE);
        fieldHeading.setText("Individual bits and fields");
        fieldTable = new Table(inspector, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
        fieldTable.setHeaderVisible(true);
        fieldTable.setLinesVisible(false);
        fieldTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        String[] fieldHeads = { "Bit / field", "Value", "What it does" };
        int[] fieldWidths = { 125, 85, 205 };
        for (int i = 0; i < fieldHeads.length; i++) {
            var column = new TableColumn(fieldTable, SWT.LEFT);
            column.setText(fieldHeads[i]); column.setWidth(fieldWidths[i]);
        }
        watchHeading = new Label(inspector, SWT.NONE);
        watchHeading.setText("My watches (halted only)");
        var watchLabelData = new GridData(SWT.FILL, SWT.BOTTOM, true, false);
        watchLabelData.exclude = true;
        watchHeading.setLayoutData(watchLabelData);
        watchHeading.setVisible(false);
        watchTable = new Table(inspector, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
        watchTable.setHeaderVisible(true);
        watchTable.setLinesVisible(true);
        var watchData = new GridData(SWT.FILL, SWT.BOTTOM, true, false);
        watchData.heightHint = 120;
        watchData.exclude = true;
        watchTable.setLayoutData(watchData);
        watchTable.setVisible(false);
        for (String label : new String[] { "Register", "Value", "Status" }) {
            var column = new TableColumn(watchTable, SWT.LEFT);
            column.setText(label);
        }
        watchTable.getColumn(0).setWidth(170);
        watchTable.getColumn(1).setWidth(110);
        watchTable.getColumn(2).setWidth(160);
        split.setWeights(new int[] { 54, 46 });
        String[] headings = { "Peripheral / register", "Value", "Status" };
        int[] widths = { 225, 105, 135 };
        for (int i = 0; i < headings.length; i++) {
            var column = new TreeColumn(tree, SWT.LEFT); column.setText(headings[i]); column.setWidth(widths[i]);
        }
        tree.addListener(SWT.Expand, e -> {
            if (!(e.item instanceof TreeItem item)) return;
            if (item.getData("registers") instanceof java.util.List<?>) populatePeripheral(item);
            else if (item.getData() instanceof Register r) populateFields(item, r);
        });
        tree.addListener(SWT.Selection, e -> {
            historyChart.redraw();
            if (!(e.item instanceof TreeItem item)) return;
            if (!(item.getData() instanceof Register r)) {
                if (registerDetails != null && !registerDetails.isDisposed())
                    registerDetails.setText("PERIPHERAL\n\n" + item.getText(0)
                            + "\n\nExpand to inspect its registers. No hardware has been read.");
                return;
            }
            showRegisterDetails(r);
            if (!r.readable()) {
                status.setText(RegisterPresentation.state(r, null, null) + " — "
                        + r.key() + ". " + RegisterPresentation.explanation(r, null));
            } else {
                status.setText("Selected " + r.name() + ". Refresh selected to read it while paused.");
            }
        });
        installRegisterMenu();
        installWatchMenu();
        installFieldMenu();
        tree.addListener(SWT.MouseHover, e -> {
            var item = tree.getItem(new org.eclipse.swt.graphics.Point(e.x, e.y));
            tree.setToolTipText(item == null ? null : (String) item.getData("description"));
        });
        contextListener = e -> {
            if (!ui.isDisposed()) ui.asyncExec(() -> {
                if (!tree.isDisposed()) contextChanged();
            });
        };
        DebugUITools.getDebugContextManager().getContextService(getSite().getWorkbenchWindow())
                .addDebugContextListener(contextListener);
        contextChanged();
        autoFindExistingDebugProject();
        contextSubscription = ZephyrViewContextHub.forWindow(getSite().getWorkbenchWindow())
                .subscribe(this::followContext);
    }

    /** Explicit jump from DTS Graph. Does not infer an MMIO base or read a peripheral. */
    public void inspectDtsSelection(org.eclipse.core.resources.IProject target,
            Path selectedBuild, String nodePath) {
        if (closed || target == null || !target.isAccessible() || selectedBuild == null
                || nodePath == null || !nodePath.startsWith("/")) return;
        var context = new io.github.niwantha33.zew.core.ZephyrSelectionIdentity(
                target.getName(), selectedBuild, nodePath);
        boolean different = !target.getName().equals(selectedProjectName)
                || !context.buildDirectory().equals(selectedBuildContext);
        selectedBuildContext = context.buildDirectory();
        linkedSelection.setText("DTS node: " + nodePath + " | exact build: "
                + selectedBuildContext.getFileName()
                + ". Opened by explicit graph navigation. No SVD peripheral mapping "
                + "or runtime register value is asserted.");
        linkedSelection.getParent().layout(true, true);
        if (different) useLaunchProject(target);
    }

    private void followContext(ZephyrViewContextHub.Selection selection) {
        if (closed || pinViewSelection == null || pinViewSelection.isDisposed()
                || pinViewSelection.getSelection() || !selection.project().isAccessible()) return;
        boolean different = !selection.project().getName().equals(selectedProjectName)
                || !selection.identity().buildDirectory().equals(selectedBuildContext);
        selectedBuildContext = selection.identity().buildDirectory();
        linkedSelection.setText("Selected build: " + selectedBuildContext.getFileName()
                + " | DTS node: " + (selection.identity().nodePath().isBlank()
                        ? "none" : selection.identity().nodePath())
                + ". Configuration selection only; no live peripheral registers read.");
        linkedSelection.getParent().layout(true, true);
        if (different) useLaunchProject(selection.project());
    }

    private void autoFindExistingDebugProject() {
        // A view may be opened after native Debug has already attached.
        // Bind only a unique live Zephyr launch; never pick the first project.
        var projects = new HashSet<IProject>();
        for (var launch : org.eclipse.debug.core.DebugPlugin.getDefault()
                .getLaunchManager().getLaunches()) {
            if (launch.isTerminated() || launch.getLaunchConfiguration() == null) continue;
            try {
                var config = launch.getLaunchConfiguration();
                String name = config.getAttribute(ZephyrLaunchDelegate.PROJECT, "");
                if (name.isBlank()) continue;
                var p = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
                if (SelectedProject.importedProject(p) != null) projects.add(p);
            } catch (org.eclipse.core.runtime.CoreException ignored) { /* Other debug adapter. */ }
        }
        if (projects.size() == 1) useLaunchProject(projects.iterator().next());
        else if (projects.isEmpty()) {
            var imported = java.util.Arrays.stream(ResourcesPlugin.getWorkspace().getRoot().getProjects())
                    .filter(p -> SelectedProject.importedProject(p) != null).toList();
            if (imported.size() == 1) useLaunchProject(imported.get(0));
        } else {
            discoveryExplanation = "Multiple live Zephyr debug projects found. Use Find Device Definitions to choose an exact project.";
            status.setText(discoveryExplanation);
        }
    }

    /**
     * Called by the successful native GDB launch with its exact imported project.
     * Searches installed local SDKs asynchronously, never the network or the MCU.
     */
    public void useLaunchProject(IProject project) {
        if (project == null || !project.isAccessible()) return;
        // Invalidate any asynchronous DSF reads from the previous project
        // before replacing its SVD and session association.
        generation++;
        busy = false;
        listenTo(null);
        stopLiveWatch();
        selectedProjectName = project.getName();
        selectedBoard = null;
        selectedSvdTarget = null;
        discoveryExplanation = "Finding vendor register descriptions for project " + selectedProjectName + "…";
        device = null; svdPath = null; svdIdentity = null;
        values.clear(); previousValues.clear(); errors.clear(); watches.clear(); history.clear();
                        lastReadAt = null;
        if (tree != null && !tree.isDisposed()) {
            tree.removeAll();
            summary.setText("Looking up exact SoC for " + selectedProjectName + "…");
            status.setText(discoveryExplanation);
        }
        final long request = ++discoveryGeneration;
        Job job = new Job("Find Device Definitions — " + project.getName()) {
            @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                final SvdAutoDiscovery.Result result;
                final SvdTargetEvidence.Selection target;
                final String board;
                try {
                    var settings = ProjectContext.settings(project);
                    board = settings.getProperty("board", "");
                    Path build = ProjectContext.buildDirectory(project, settings);
                    Map<String, String> effective = Files.isRegularFile(build.resolve("zephyr/.config"))
                            ? BuildArtifacts.configuration(build) : Map.of();
                    target = SvdTargetEvidence.select(board, effective);
                    Activator.getDefault().getLog().log(new Status(IStatus.INFO, Activator.ID,
                            "SVD target selection: " + target.state() + " — " + target.explanation()));
                    var roots = new ArrayList<Path>();
                    Path workspace = optionalRoot(settings.getProperty("workspace"));
                    // Probe focused exact-chip/vendor-pack directories first;
                    // avoid double-scanning all West modules before local packs.
                    roots.add(project.getLocation().toFile().toPath().resolve(".zew/svd"));
                    roots.add(project.getLocation().toFile().toPath().resolve(".zew/vendor-svd"));
                    Path root = optionalRoot(settings.getProperty("zew.setupRoot"));
                    if (root != null) {
                        roots.add(root.resolve("vendor-svd"));
                        roots.add(root.resolve("svd"));
                    }
                    Path userHome = Path.of(System.getProperty("user.home"));
                    // Older managed installs cached exact vendor SVDs here.
                    roots.add(userHome.resolve(".zew/svd-cache"));
                    roots.add(userHome.resolve(".cmsis/packs"));
                    roots.add(userHome.resolve(".cmsis-packs"));
                    // Standard Arm CMSIS-Pack managers and vendor IDE installations.
                    // Vendor model data remains subject to exact-SoC verification.
                    for (String variable : new String[] {
                            "CMSIS_PACK_ROOT", "CMSIS_PACK_PATH", "KEIL_PACK_ROOT",
                            "PACK_ROOT", "ZEW_VENDOR_SVD_DIRS" }) {
                        String configured = System.getenv(variable);
                        if (configured == null || configured.isBlank()) continue;
                        for (String item : configured.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                            if (!item.isBlank()) {
                                try { roots.add(Path.of(item)); }
                                catch (java.nio.file.InvalidPathException ignored) { /* Ignore malformed root. */ }
                            }
                        }
                    }
                    roots.add(userHome.resolve("AppData/Local/Arm/Packs"));
                    roots.add(userHome.resolve("AppData/Local/Keil/Packs"));
                    roots.add(userHome.resolve("AppData/Local/CMSIS/Packs"));
                    roots.add(userHome.resolve(".cache/arm/packs"));
                    roots.add(userHome.resolve("AppData/Local/Espressif"));
                    roots.add(userHome.resolve("esp/esp-idf"));
                    if (workspace != null) {
                        roots.add(workspace.resolve("modules/hal/espressif"));
                        roots.add(workspace.resolve("modules/hal/stm32"));
                        roots.add(workspace.resolve("modules/hal/nordic"));
                        roots.add(workspace.resolve("modules/hal/ti"));
                        roots.add(workspace.resolve("modules/hal"));
                        roots.add(workspace.resolve("zephyr"));
                    }
                    Path sdk = optionalRoot(settings.getProperty("tools.sdk"));
                    if (sdk != null) roots.add(sdk);
                    if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
                        roots.add(Path.of("C:/ti"));
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    if (!target.usable()) {
                        result = new SvdAutoDiscovery.Result(SvdAutoDiscovery.State.INVALID_TARGET,
                                "", null, 0, false, target.explanation(), List.of());
                    } else {
                        // Honour a previously validated per-device manual import first.
                        // Recheck SHA-256 and SVD model every time; stale paths are never trusted.
                        var remembered = preferredSvd(target.target(), legacySavedPath);
                        if (remembered != null) {
                            result = new SvdAutoDiscovery.Result(SvdAutoDiscovery.State.MATCHED,
                                    SvdAutoDiscovery.partFromBoard(target.target()), remembered.path(),
                                    remembered.registers(), false,
                                    "Reusing the verified register definitions you imported earlier.",
                                    List.of(remembered.path().toString()));
                        } else {
                            result = SvdAutoDiscovery.resolve(target.target(), roots,
                                    userHome.resolve(".zew/svd-cache"));
                        }
                    }
                    Activator.getDefault().getLog().log(new Status(IStatus.INFO, Activator.ID,
                            "SVD search: " + result.state() + " — " + result.explanation()
                            + "; scanned roots: " + result.inspected().size()));
                } catch (Exception error) {
                    if (!closed && !ui.isDisposed()) ui.asyncExec(() -> {
                        if (request != discoveryGeneration || tree.isDisposed()) return;
                        discoveryExplanation = "SVD discovery failed for " + project.getName()
                                + ": " + error.getMessage() + ". Use Import Register Definitions… for an exact device.";
                        status.setText(discoveryExplanation);
                    });
                    return new Status(IStatus.WARNING, Activator.ID, "SVD search failed", error);
                }
                if (!closed && !ui.isDisposed()) ui.asyncExec(() -> {
                    if (tree.isDisposed() || request != discoveryGeneration) return;
                    selectedBoard = board;
                    selectedSvdTarget = target.usable() ? target.target() : null;
                    discoveryExplanation = target.explanation();
                    if (result.state() == SvdAutoDiscovery.State.MATCHED) {
                        load(result.path().toString(), result.mappedByPack());
                    } else {
                        discoveryExplanation = result.explanation();
                        status.setText(discoveryExplanation + " " + result.explanation() + " Looked in: "
                                + String.join("; ", result.inspected().stream().limit(8).toList()));
                        summary.setText("SoC " + result.part() + ": no verified SVD available. "
                                + "Install the vendor's CMSIS pack under the managed workspace, or Import Register Definitions…");
                    }
                });
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.schedule();
    }

    /** Eclipse-local preferences: do not commit licensed vendor SVDs into projects. */
    private static String preferredKey(String qualifiedTarget) {
        String part = SvdAutoDiscovery.partFromBoard(qualifiedTarget);
        return part.isBlank() ? null : "svd.preferred." + part;
    }

    private static SvdPreferredSource.Verified preferredSvd(String target, String legacy) {
        String key = preferredKey(target);
        if (key == null) return null;
        var preferences = InstanceScope.INSTANCE.getNode(Activator.ID);
        String path = preferences.get(key + ".path", "");
        String sha = preferences.get(key + ".sha256", "");
        if (!path.isBlank()) {
            try {
                var resolved = SvdPreferredSource.reopen(target, Path.of(path), sha);
                if (resolved != null) return resolved;
            } catch (java.nio.file.InvalidPathException invalid) {
                // Fall through to ordinary exact-part vendor discovery.
            }
            // A cached file has changed or disappeared; never reuse a stale hash.
            return null;
        }
        if (legacy != null && !legacy.isBlank()) {
            try { return SvdPreferredSource.inspect(target, Path.of(legacy)); }
            catch (Exception invalid) { /* Saved Eclipse memento was stale. */ }
        }
        return null;
    }

    private static void rememberSvd(String target, Path file, String sha256, String model) {
        String key = preferredKey(target);
        if (key == null || !SvdPartCompatibility.matches(
                SvdAutoDiscovery.partFromBoard(target), model)) return;
        var preferences = InstanceScope.INSTANCE.getNode(Activator.ID);
        preferences.put(key + ".path", file.toAbsolutePath().normalize().toString());
        preferences.put(key + ".sha256", sha256);
        try { preferences.flush(); }
        catch (org.osgi.service.prefs.BackingStoreException failure) {
            Activator.getDefault().getLog().log(new Status(IStatus.WARNING,
                    Activator.ID, "Cannot persist verified SVD source", failure));
        }
    }

    /** Optional SDK roots must not prevent discovery in the remaining installed packs. */
    private static Path optionalRoot(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Path.of(value); }
        catch (java.nio.file.InvalidPathException invalid) { return null; }
    }

    /** Stable project-local fallback: never require a separately installed setupRoot. */
    static Path vendorPackLocation(String configuredSetupRoot, Path projectRoot) {
        Path setup = optionalRoot(configuredSetupRoot);
        if (setup != null) return setup.resolve("vendor-svd");
        if (projectRoot != null) return projectRoot.resolve(".zew/vendor-svd");
        throw new IllegalArgumentException("No project storage directory is available");
    }

    /** Resolve the actual SoC from generated build evidence for board-only targets. */
    static String verifiedSvdPart(String board, String target) {
        String part = SvdAutoDiscovery.partFromBoard(target);
        return part.isBlank() ? SvdAutoDiscovery.partFromBoard(board) : part;
    }

    private String officialSvdPage(String board) {
        String chip = SvdAutoDiscovery.partFromBoard(board);
        if (chip.startsWith("esp32")) return "https://github.com/espressif/svd";
        if (chip.startsWith("mspm0")) return "https://www.ti.com/tool/MSPM0-SDK";
        if (chip.startsWith("stm32")) return "https://www.st.com/en/embedded-software/stm32cube-mcu-mpu-packages.html";
        if (chip.startsWith("nrf")) return "https://www.nordicsemi.com/Products/Development-software/nRF-Connect-SDK";
        if (chip.startsWith("mimx") || chip.startsWith("lpc") || chip.startsWith("mcx"))
            return "https://mcuxpresso.nxp.com/";
        if (chip.startsWith("ra") || chip.startsWith("r7fa")) return "https://www.renesas.com/us/en/software-tool/flexible-software-package-fsp";
        if (chip.startsWith("efr32")) return "https://www.silabs.com/developers/simplicity-studio";
        return "https://www.keil.arm.com/packs/";
    }

    private void showSvdHelp(String board, String failure, String downloadUrl) {
        String page = officialSvdPage(board);
        String chip = SvdAutoDiscovery.partFromBoard(board);
        String device = chip.isBlank() ? board : chip.toUpperCase(Locale.ROOT);
        String friendly = "We couldn't load the register information for " + device + ".\n\n"
                + "1. Open the official device website below.\n"
                + "2. Find a register description (.svd) for this device.\n"
                + "3. Return here and choose Import Register Definitions.\n\n"
                + "Official website:\n" + page;
        String technical = "Device: " + device + "\n"
                + "Reason: " + failure + "\n"
                + "Attempted download: " + (downloadUrl == null ? "No automatic source available" : downloadUrl)
                + "\nVendor page: " + page + "\n"
                + "Live register values require a suspended debug session.";
        var dialog = new org.eclipse.jface.dialogs.MessageDialog(getSite().getShell(),
                "Device Registers Not Available", null, friendly,
                org.eclipse.jface.dialogs.MessageDialog.WARNING,
                new String[] { "Open Website", "Import Register Definitions", "Technical Details", "Close" }, 3);
        switch (dialog.open()) {
            case 0 -> org.eclipse.swt.program.Program.launch(page);
            case 1 -> {
                var picker = new FileDialog(getSite().getShell(), SWT.OPEN);
                picker.setText("Import Device Register Definitions (.svd)");
                picker.setFilterExtensions(new String[] { "*.svd;*.xml", "*.*" });
                String file = picker.open();
                if (file != null) {
                    selectedBoard = board;
                    load(file);
                }
            }
            case 2 -> {
                var details = new org.eclipse.jface.dialogs.MessageDialog(getSite().getShell(),
                        "Technical Details", null, technical,
                        org.eclipse.jface.dialogs.MessageDialog.INFORMATION,
                        new String[] { "Copy Details", "Close" }, 1);
                if (details.open() == 0) {
                    var clipboard = new org.eclipse.swt.dnd.Clipboard(ui);
                    try {
                        clipboard.setContents(new Object[] { technical },
                                new org.eclipse.swt.dnd.Transfer[] {
                                        org.eclipse.swt.dnd.TextTransfer.getInstance() });
                    } finally {
                        clipboard.dispose();
                    }
                }
            }
            default -> { /* User closed the guidance. */ }
        }
    }

    private void downloadVendorSvd() {
        IProject project = selectedProjectName == null ? null
                : ResourcesPlugin.getWorkspace().getRoot().getProject(selectedProjectName);
        if (project == null || !project.isAccessible()) {
            var available = java.util.Arrays.stream(ResourcesPlugin.getWorkspace().getRoot().getProjects())
                    .filter(p -> p.isAccessible() && SelectedProject.importedProject(p) != null).toList();
            if (available.size() == 1) project = available.get(0);
        }
        if (project == null || !project.isAccessible()) {
            status.setText("Select an imported Zephyr project with an exact board before downloading.");
            chooseProjectForDiscovery();
            return;
        }
        final IProject target = project;
        final String board;
        final Path vendorDir;
        try {
            var settings = ProjectContext.settings(target);
            String selected = settings.getProperty("board", "");
            Path build = ProjectContext.buildDirectory(target, settings);
            Map<String, String> config = Files.isRegularFile(build.resolve("zephyr/.config"))
                    ? BuildArtifacts.configuration(build) : Map.of();
            var identity = SvdTargetEvidence.select(selected, config);
            if (!identity.usable()) {
                status.setText("Cannot download SVD without verified SoC: "
                        + identity.explanation() + " Select the exact board or build the project first.");
                return;
            }
            board = identity.target();
            Path projectRoot = target.getLocation() == null ? null
                    : target.getLocation().toFile().toPath();
            vendorDir = vendorPackLocation(settings.getProperty("zew.setupRoot"), projectRoot);
        } catch (Exception error) {
            status.setText("Cannot determine exact SoC or vendor-pack directory: " + error.getMessage());
            return;
        }
        if (GitHubSvdProvisioner.supports(board)) {
            boolean approved = org.eclipse.jface.dialogs.MessageDialog.openQuestion(
                    getSite().getShell(), "Download official Espressif SVD?",
                    "Download the exact " + board + " register description from the"
                    + " official espressif/svd GitHub release into this workspace?\\n\\n"
                    + "The description may be incomplete. No target registers are accessed.");
            if (!approved) return;
            final long request = ++discoveryGeneration;
            status.setText("Downloading official Espressif SVD for " + board + "…");
            Job job = new Job("Download Espressif register description") {
                @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                    try {
                        var downloaded = GitHubSvdProvisioner.download(board, vendorDir);
                        if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                        if (!closed && !ui.isDisposed()) ui.asyncExec(() -> {
                            if (tree.isDisposed() || request != discoveryGeneration) return;
                            selectedBoard = board;
                            selectedProjectName = target.getName();
                            load(downloaded.file().toString(), false);
                            status.setText("Official Espressif SVD loaded (may be incomplete). SHA-256 "
                                    + downloaded.sha256() + ". Live values require suspended Debug.");
                        });
                        return Status.OK_STATUS;
                    } catch (Exception error) {
                        if (!closed && !ui.isDisposed()) ui.asyncExec(() -> {
                            if (tree.isDisposed() || request != discoveryGeneration) return;
                            status.setText("Espressif SVD download/validation failed: "
                                    + error.getMessage() + ". No unrelated chip map was loaded.");
                            showSvdHelp(board, error.getMessage(), "https://github.com/espressif/svd");
                        });
                        return new Status(IStatus.WARNING, Activator.ID,
                                "Espressif SVD provisioning failed", error);
                    }
                }
            };
            job.setUser(true);
            job.schedule();
            return;
        }
        if (!VendorSvdProvisioner.supports(board)) {
            status.setText("Automatic download is not yet verified for " + board
                    + ". Open official download guidance for the exact chip.");
            showSvdHelp(board, "No verified automatic download source configured for this SoC.", null);
            return;
        }
        boolean approved = org.eclipse.jface.dialogs.MessageDialog.openQuestion(
                getSite().getShell(), "Download official TI register definitions?",
                "Download TexasInstruments MSPM0G1X0X_G3X0X Device Family Pack 1.3.1"
                + " from Texas Instruments into the managed workspace?\n\n"
                + "The pack will be checked for an exact MSPM0G3507 device mapping. "
                + "No MCU registers are read or written by this action.");
        if (!approved) return;
        final long request = ++discoveryGeneration;
        status.setText("Downloading and verifying official TI CMSIS pack for " + board + "…");
        Job job = new Job("Download TI MSPM0 register description") {
            @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                try {
                    var installed = VendorSvdProvisioner.download(board, vendorDir);
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    if (!closed && !ui.isDisposed()) ui.asyncExec(() -> {
                        if (tree.isDisposed() || request != discoveryGeneration) return;
                        selectedBoard = board;
                        selectedProjectName = target.getName();
                        load(installed.device().path().toString(), installed.device().mappedByPack());
                        status.setText("Verified TI pack installed: SHA-256 "
                                + installed.sha256() + ". Live values require suspended Debug.");
                    });
                    return Status.OK_STATUS;
                } catch (Exception error) {
                    if (!closed && !ui.isDisposed()) ui.asyncExec(() -> {
                        if (tree.isDisposed() || request != discoveryGeneration) return;
                        status.setText("Official TI SVD download or verification failed: "
                                + error.getMessage() + ". No guessed register map was loaded.");
                        showSvdHelp(board, error.getMessage(), "https://software-dl.ti.com/msp430/esd/MSPM0-CMSIS/MSPM0G1X0X_G3X0X/latest/exports/TexasInstruments.MSPM0G1X0X_G3X0X_DFP.1.3.1.pack");
                    });
                    return new Status(IStatus.WARNING, Activator.ID, "Vendor SVD provisioning failed", error);
                }
            }
        };
        job.setUser(true);
        job.schedule();
    }

    private void chooseProjectForDiscovery() {
        IProject[] projects = java.util.Arrays.stream(ResourcesPlugin.getWorkspace().getRoot().getProjects())
                .filter(p -> p.isAccessible() && SelectedProject.importedProject(p) != null)
                .toArray(IProject[]::new);
        if (projects.length == 0) {
            status.setText("No imported Zephyr project available. Import a board project before discovering SVD.");
            return;
        }
        if (projects.length == 1) { useLaunchProject(projects[0]); return; }
        var picker = new ElementListSelectionDialog(getSite().getShell(), new LabelProvider() {
            @Override public String getText(Object item) {
                return item instanceof IProject p ? p.getName() : super.getText(item);
            }
        });
        picker.setTitle("Choose exact Zephyr project / SoC");
        picker.setMessage("Select the project whose built device you want to inspect.");
        picker.setElements(projects);
        if (picker.open() == org.eclipse.jface.window.Window.OK
                && picker.getFirstResult() instanceof IProject selected) useLaunchProject(selected);
    }

    /** UI frame selection does not invalidate a snapshot from the SAME debug session. */
    private void contextChanged() {
        var target = debugTarget();
        var selectedSession = target == null ? null : target.session();
        boolean changed = selectedSession != listenedSession;
        if (changed) {
            generation++; busy = false; values.clear(); previousValues.clear();
            errors.clear(); lastReadAt = null; render();
            quietUntil = System.currentTimeMillis() + 350;
            listenTo(selectedSession);
        }
        updateConnectionPath();
        if (changed) {
            status.setText(device == null ? discoveryExplanation
                    : "SVD " + device.name() + ": native debugger attached. "
                        + "SVD-safe registers are sampled after suspension.");
            if (selectedSession != null) scheduleAutoSnapshot();
        }
    }

    /** Visible diagnostic chain: build → exact MCU → SVD → active GDB → real reads. */
    private void updateConnectionPath() {
        if (contextInfo == null || contextInfo.isDisposed()) return;
        String part = selectedSvdTarget == null ? verifiedSvdPart(selectedBoard, null)
                : SvdAutoDiscovery.partFromBoard(selectedSvdTarget);
        String pipeline = "Project  " + (selectedProjectName == null ? "not selected" : selectedProjectName)
                + "   ›   Chip  " + (part.isBlank() ? "unknown" : part.toUpperCase(Locale.ROOT))
                + "   ›   SVD  " + (device == null ? "missing" : device.name())
                + "   ›   GDB  " + (listenedSession == null ? "disconnected" : "connected")
                + "   ›   Read  " + values.size();
        if (!pipeline.equals(contextInfo.getText())) {
            contextInfo.setText(pipeline);
            contextInfo.getParent().layout(true, true);
        }
        contextInfo.setToolTipText("Data route: Zephyr project/build → exact device SVD "
                + "→ existing CDT DSF-GDB session → debug server (OpenOCD if configured) "
                + "→ halted memory-mapped register. No second OpenOCD connection.");
    }

    /** Snapshot only after a real stop; no second probe connection or target halt. */
    private void scheduleAutoSnapshot() {
        if (!autoSnapshotEnabled || device == null || closed || ui == null || ui.isDisposed()) return;
        final long expectedGeneration = generation;
        final DsfSession expectedSession = listenedSession;
        if (expectedSession == null) return;
        ui.timerExec(400, () -> {
            if (closed || tree.isDisposed() || !autoSnapshotEnabled || busy
                    || generation != expectedGeneration || listenedSession != expectedSession) return;
            refresh(ReadScope.ALL_SAFE);
        });
    }

    private final RunListener runListener = new RunListener();

    /** Public because DSF invokes annotated listener methods reflectively across bundles. */
    public final class RunListener {
        @DsfServiceEventHandler public void eventDispatched(IRunControl.IResumedDMEvent event) {
            quietUntil = Long.MAX_VALUE;
            invalidateSnapshot(event.getDMContext(), "Target resumed. Halt to capture a new SVD snapshot.", false);
        }
        @DsfServiceEventHandler public void eventDispatched(IRunControl.ISuspendedDMEvent event) {
            quietUntil = System.currentTimeMillis() + 350;
            invalidateSnapshot(event.getDMContext(), "Target suspended. Taking SVD-safe register snapshot…", true);
        }
        @DsfServiceEventHandler public void eventDispatched(IRunControl.IExitedDMEvent event) {
            quietUntil = Long.MAX_VALUE;
            invalidateSnapshot(event.getDMContext(), "Debug execution exited. No live peripheral values are available.", false);
        }
    }

    private void invalidateSnapshot(IDMContext context, String message, boolean suspended) {
        var session = listenedSession;
        if (closed || session == null || !session.getId().equals(context.getSessionId())
                || ui.isDisposed()) return;
        ui.asyncExec(() -> {
            if (tree.isDisposed() || listenedSession != session) return;
            generation++; busy = false; values.clear(); previousValues.clear();
            errors.clear(); lastReadAt = null; render();
            status.setText(message);
            if (suspended) scheduleAutoSnapshot();
        });
    }

    private void listenTo(DsfSession next) {
        DsfSession previous = listenedSession;
        if (previous == next) return;
        // Never mix snapshot evidence across two different DSF debug sessions.
        history.clear();
        stopLiveWatch();
        listenedSession = next;
        if (previous != null) {
            try { previous.getExecutor().execute(() -> previous.removeServiceEventListener(runListener)); }
            catch (RejectedExecutionException ended) { /* session disposal removes its services/listeners */ }
        }
        if (next != null) {
            try { next.getExecutor().execute(() -> {
                if (!closed && listenedSession == next) next.addServiceEventListener(runListener, null);
            }); }
            catch (RejectedExecutionException ended) { listenedSession = null; }
        }
    }

    private static void menuAction(Menu menu, String label, Runnable action) {
        var item = new MenuItem(menu, SWT.PUSH);
        item.setText(label);
        item.addListener(SWT.Selection, e -> action.run());
    }

    private void button(Composite parent, String label, Runnable action) {
        var button = new Button(parent, SWT.PUSH); button.setText(label);
        button.addListener(SWT.Selection, e -> action.run());
    }
    private void load(String path) { load(path, false); }

    private void load(String path, boolean exactPackDeviceMapping) {
        stopLiveWatch();
        // Parsing a multi-megabyte vendor SVD on SWT's UI thread can starve
        // DSF/GDB progress and make a healthy debug session appear frozen.
        // This operation is offline: never resume, suspend or terminate GDB.
        final long request = ++discoveryGeneration;
        final String board = selectedBoard;
        final String project = selectedProjectName;
        status.setText("Loading register definitions in background; debug session remains unchanged…");
        Job job = new Job("Load register definitions") {
            @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                try {
                    Path file = Path.of(path);
                    if (Files.size(file) > 32 * 1024 * 1024)
                        throw new IllegalArgumentException("SVD exceeds 32 MiB");
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    SvdDevice loaded;
                    try (var input = Files.newInputStream(file)) { loaded = SvdDevice.read(input); }
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    var digest = java.security.MessageDigest.getInstance("SHA-256");
                    try (var input = Files.newInputStream(file)) {
                        byte[] buffer = new byte[4096];
                        int count;
                        while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
                    }
                    final String identity = java.util.HexFormat.of().formatHex(digest.digest());
                    if (!closed && !ui.isDisposed()) ui.asyncExec(() -> {
                        if (closed || tree.isDisposed() || request != discoveryGeneration) return;
                        if (!exactPackDeviceMapping && board != null && !board.isBlank()
                                && !SvdPartCompatibility.matches(verifiedSvdPart(board, selectedSvdTarget), loaded.name())) {
                            status.setText("Rejected SVD model: register definitions are for "
                                    + loaded.name() + ", not the selected "
                                    + (verifiedSvdPart(board, selectedSvdTarget).isBlank()
                                            ? "an unverified board-only target"
                                            : verifiedSvdPart(board, selectedSvdTarget))
                                    + ". Previous definitions were kept; debug session unchanged.");
                            return;
                        }
                        generation++; busy = false; device = loaded; svdPath = path;
                        // Direct/manual SVDs and exact-model pack SVDs can be
                        // safely revalidated and reopened on the next launch.
                        String verifiedTarget = selectedSvdTarget == null ? board : selectedSvdTarget;
                        rememberSvd(verifiedTarget, file, identity, loaded.name());
                        svdIdentity = identity;
                        values.clear(); previousValues.clear(); errors.clear(); watches.clear(); history.clear();
                        lastReadAt = null;
                        render();
                        discoveryExplanation = (exactPackDeviceMapping
                                ? "Installed CMSIS pack maps " + board + " to " + loaded.name()
                                : "Register model: " + loaded.name() + "; selected board: " + board)
                                + ". Live readings require an explicit refresh while halted.";
                        status.setText(loaded.name() + " — " + loaded.registers().size()
                                + " definitions loaded. Debug execution was not changed.");
                        // The session might have been suspended before the SVD was loaded.
                        // Bind its existing launch now, even if no new Debug selection event occurs.
                        contextChanged();
                        scheduleAutoSnapshot();
                    });
                    return Status.OK_STATUS;
                } catch (Exception ex) {
                    if (!closed && !ui.isDisposed()) ui.asyncExec(() -> {
                        if (closed || tree.isDisposed() || request != discoveryGeneration) return;
                        status.setText("Register definitions could not be loaded; previous model retained: "
                                + ex.getMessage());
                    });
                    return new Status(IStatus.WARNING, Activator.ID,
                            "Register definition loading failed", ex);
                }
            }
        };
        job.setSystem(true);
        job.schedule();
    }

    private void stopLiveWatch() {
        liveWatch = false;
        watchEpoch++;
        if (watchButton != null && !watchButton.isDisposed()) watchButton.setText("Start Live Watch (opt-in)");
        if (historyChart != null && !historyChart.isDisposed()) {
            historyChart.setVisible(false);
            ((GridData) historyChart.getLayoutData()).exclude = true;
            historyChart.getParent().layout(true, true);
        }
    }
    private void toggleLiveWatch() {
        if (liveWatch) { stopLiveWatch(); status.setText("Live Watch stopped."); return; }
        if (device == null || watches.isEmpty() || watches.size() > 64 || debugTarget() == null) {
            status.setText("Load an exact SVD; watch 1–64 safe registers; start a unique native GDB session and halt the target.");
            return;
        }
        liveWatch = true;
        quietUntil = System.currentTimeMillis() + 1500;
        watchButton.setText("Stop Live Watch");
        historyChart.setVisible(true);
        ((GridData) historyChart.getLayoutData()).exclude = false;
        historyChart.getParent().layout(true, true);
        status.setText("Live Watch: host-timed halted snapshots only; NOT continuous trace.");
        scheduleWatch(++watchEpoch);
    }
    private void scheduleWatch(int epoch) {
        if (closed || ui == null || ui.isDisposed()) return;
        ui.timerExec(watchIntervalMs, () -> {
            if (closed || tree.isDisposed() || !liveWatch || epoch != watchEpoch) return;
            if (watches.isEmpty() || watches.size() > 64) {
                stopLiveWatch();
                status.setText("Live Watch stopped: watch count outside 1–64.");
                return;
            }
            if (!busy && debugTarget() != null && System.currentTimeMillis() >= quietUntil)
                refreshWatches();
            scheduleWatch(epoch);
        });
    }
    private void paintHistory(org.eclipse.swt.graphics.GC gc) {
        if (!liveWatch) return;
        String key = watches.stream().sorted().findFirst().orElse(null);
        var selected = selected();
        if (selected != null && watches.contains(selected.key())) key = selected.key();
        var points = RegisterSparkline.series(history.samples(), key);
        var bounds = historyChart.getClientArea();
        gc.drawText("Sampled halted snapshots: " + key + " (not target timestamps)", 6, 5, true);
        if (points.size() < 2) { gc.drawText("Need two successful samples; missing data is not zero.", 6, 38, true); return; }
        int width = Math.max(1, bounds.width - 20), height = Math.max(1, bounds.height - 43);
        for (int i = 1; i < points.size(); i++)
            gc.drawLine(10 + (i - 1) * width / (points.size() - 1),
                    29 + (int) ((1 - points.get(i - 1).level()) * height),
                    10 + i * width / (points.size() - 1),
                    29 + (int) ((1 - points.get(i).level()) * height));
    }
    private void toggleWatch(Register register) {
        if (register == null) return;
        if (!watches.remove(register.key())) {
            if (!register.readable()) {
                status.setText("Watch unavailable: " + register.readBlockReason());
                return;
            }
            if (watches.size() >= 64) {
                status.setText("Watch is limited to 64 readable registers.");
                return;
            }
            watches.add(register.key());
        }
        render();
    }

    private SvdDevice.Field selectedField() {
        if (tree == null || tree.getSelectionCount() == 0) return null;
        Object field = tree.getSelection()[0].getData("svdField");
        return field instanceof SvdDevice.Field svd ? svd : null;
    }

    private void installRegisterMenu() {
        var menu = new Menu(tree);
        tree.setMenu(menu);
        var add = new MenuItem(menu, SWT.PUSH);
        add.setText("Add / remove watch");
        add.addListener(SWT.Selection, e -> toggleWatch(selected()));
        var write = new MenuItem(menu, SWT.PUSH);
        write.setText("Write register value…");
        write.addListener(SWT.Selection, e -> editRegister(selected()));
        var bit = new MenuItem(menu, SWT.PUSH);
        bit.setText("Toggle selected 1-bit field…");
        bit.addListener(SWT.Selection, e -> toggleFieldBit(selected(), selectedField()));
        menu.addListener(SWT.Show, e -> {
            Register reg = selected();
            add.setEnabled(reg != null && (watches.contains(reg.key()) || reg.readable()));
            write.setEnabled(reg != null && reg.writable());
            SvdDevice.Field field = selectedField();
            bit.setEnabled(reg != null && reg.writable() && values.containsKey(reg.key())
                    && field != null && field.width() == 1);
        });
        tree.addListener(SWT.MenuDetect, e -> {
            var point = tree.toControl(e.x, e.y);
            var hit = tree.getItem(point);
            if (hit != null) tree.setSelection(hit);
        });
    }

    private void installWatchMenu() {
        var menu = new Menu(watchTable);
        watchTable.setMenu(menu);
        var remove = new MenuItem(menu, SWT.PUSH);
        remove.setText("Remove watch");
        remove.addListener(SWT.Selection, e -> {
            Register reg = watchedSelection();
            if (reg != null) toggleWatch(reg);
        });
        var write = new MenuItem(menu, SWT.PUSH);
        write.setText("Write register value…");
        write.addListener(SWT.Selection, e -> editRegister(watchedSelection()));
        menu.addListener(SWT.Show, e -> {
            Register reg = watchedSelection();
            remove.setEnabled(reg != null);
            write.setEnabled(reg != null && reg.writable());
        });
        watchTable.addListener(SWT.MenuDetect, e -> {
            var point = watchTable.toControl(e.x, e.y);
            var hit = watchTable.getItem(point);
            if (hit != null) watchTable.setSelection(hit);
        });
        watchTable.addListener(SWT.Selection, e -> {
            Register reg = watchedSelection();
            if (reg != null) showRegisterDetails(reg);
        });
    }

    private void installFieldMenu() {
        var menu = new Menu(fieldTable);
        fieldTable.setMenu(menu);
        var toggle = new MenuItem(menu, SWT.PUSH);
        toggle.setText("Toggle this 1-bit field…");
        toggle.addListener(SWT.Selection, e -> {
            if (fieldTable.getSelectionCount() == 1
                    && fieldTable.getSelection()[0].getData() instanceof SvdDevice.Field field)
                toggleFieldBit(inspectedRegister, field);
        });
        menu.addListener(SWT.Show, e -> {
            boolean allowed = fieldTable.getSelectionCount() == 1
                    && fieldTable.getSelection()[0].getData() instanceof SvdDevice.Field field
                    && field.width() == 1 && inspectedRegister != null
                    && inspectedRegister.writable()
                    && values.containsKey(inspectedRegister.key());
            toggle.setEnabled(allowed);
        });
        fieldTable.addListener(SWT.MenuDetect, e -> {
            var item = fieldTable.getItem(fieldTable.toControl(e.x, e.y));
            if (item != null) fieldTable.setSelection(item);
        });
        fieldTable.addListener(SWT.MouseHover, e -> {
            var item = fieldTable.getItem(new org.eclipse.swt.graphics.Point(e.x, e.y));
            fieldTable.setToolTipText(item == null ? null : (String) item.getData("description"));
        });
    }

    private Register watchedSelection() {
        if (watchTable == null || watchTable.isDisposed() || watchTable.getSelectionCount() != 1)
            return null;
        Object data = watchTable.getSelection()[0].getData();
        return data instanceof Register reg ? reg : null;
    }

    private void renderWatches() {
        if (watchTable == null || watchTable.isDisposed()) return;
        boolean hasWatch = device != null && !watches.isEmpty();
        if (watchTable.getVisible() != hasWatch) {
            var tableData = (GridData) watchTable.getLayoutData();
            tableData.exclude = !hasWatch;
            watchTable.setVisible(hasWatch);
            var titleData = (GridData) watchHeading.getLayoutData();
            titleData.exclude = !hasWatch;
            watchHeading.setVisible(hasWatch);
            watchTable.getParent().layout(true, true);
        }
        String key = watchedSelection() == null ? null : watchedSelection().key();
        watchTable.setRedraw(false);
        try {
            watchTable.removeAll();
            if (device == null) return;
            for (Register reg : device.registers()) {
                if (!watches.contains(reg.key())) continue;
                var row = new TableItem(watchTable, SWT.NONE);
                row.setData(reg);
                var value = values.get(reg.key());
                boolean changed = value != null && previousValues.containsKey(reg.key())
                        && !previousValues.get(reg.key()).equals(value);
                row.setText(new String[] { reg.key(),
                        value == null ? "—" : hex(value, reg.size()),
                        changed ? "Changed" : RegisterPresentation.state(reg, value,
                                errors.get(reg.key())) });
                if (reg.key().equals(key)) watchTable.setSelection(row);
            }
        } finally { watchTable.setRedraw(true); }
    }

    private Register selected() {
        if (tree.getSelectionCount() == 0) return null;
        var item = tree.getSelection()[0];
        return item.getData() instanceof Register register ? register : null;
    }
    private void render() {
        if (tree == null || tree.isDisposed()) return;
        if (device == null) {
            tree.removeAll();
            inspectedRegister = null;
            if (fieldTable != null && !fieldTable.isDisposed()) fieldTable.removeAll();
            renderWatches();
            return;
        }
        Register chosen = selected();
        var expandedPeripherals = new HashSet<String>();
        var expandedRegisters = new HashSet<String>();
        for (TreeItem peripheral : tree.getItems()) {
            if (peripheral.getExpanded()) expandedPeripherals.add(peripheral.getText());
            for (TreeItem register : peripheral.getItems()) {
                if (register.getExpanded() && register.getData() instanceof Register r)
                    expandedRegisters.add(r.key());
            }
        }
        tree.setRedraw(false);
        try {
            tree.removeAll();
            String query = search.getText().toLowerCase(Locale.ROOT).trim();
            var matches = new java.util.LinkedHashMap<String, java.util.List<Register>>();
            for (Register r : device.registers()) {
                if (!(r.key() + " " + r.description() + " " + r.fields())
                        .toLowerCase(Locale.ROOT).contains(query)) continue;
                matches.computeIfAbsent(r.peripheral(), key -> new java.util.ArrayList<>()).add(r);
            }
            for (var entry : matches.entrySet()) {
                TreeItem peripheral = new TreeItem(tree, SWT.NONE);
                peripheral.setText(entry.getKey());
                peripheral.setData("registers", entry.getValue());
                // Lightweight placeholder: no register or field widgets until expanded.
                new TreeItem(peripheral, SWT.NONE).setText("Expand to show "
                        + entry.getValue().size() + " registers");
                // Search results should be immediately visible, not hidden
                // behind a collapsed peripheral requiring a second click.
                if (expandedPeripherals.contains(entry.getKey())
                        || (!query.isBlank() && matches.size() <= 12)) {
                    populatePeripheral(peripheral);
                    peripheral.setExpanded(true);
                    for (TreeItem child : peripheral.getItems()) {
                        if (child.getData() instanceof Register r && expandedRegisters.contains(r.key())) {
                            populateFields(child, r);
                            child.setExpanded(true);
                        }
                        if (child.getData() instanceof Register r && chosen != null
                                && chosen.key().equals(r.key())) tree.setSelection(child);
                    }
                }
            }
            if (chosen != null) showRegisterDetails(chosen);
            int count = matches.values().stream().mapToInt(java.util.List::size).sum();
            summary.setText(matches.size() + " peripherals  •  " + count + " matching registers  •  "
                    + values.size() + " values read  •  " + watches.size() + " watches"
                    + (liveWatch ? "  •  Polling while paused" : "")
                    + (history.baseline() != null ? "  •  Baseline pinned" : ""));
        } finally { tree.setRedraw(true); }
        renderWatches();
        updateConnectionPath();
    }

    private static String bits(BigInteger value, int width) {
        StringBuilder result = new StringBuilder();
        for (int bit = width - 1; bit >= 0; bit--) {
            result.append(value.testBit(bit) ? '1' : '0');
            if (bit > 0 && bit % 4 == 0) result.append(' ');
        }
        return result.toString();
    }

    private void showRegisterDetails(Register r) {
        if (registerDetails == null || registerDetails.isDisposed()) return;
        inspectedRegister = r;
        BigInteger value = values.get(r.key());
        String state = RegisterPresentation.state(r, value, errors.get(r.key()));
        String explanation = RegisterPresentation.explanation(r, errors.get(r.key()));
        var details = new StringBuilder(r.peripheral()).append("  /  ").append(r.name()).append("\n\n")
                .append("Live value     ")
                .append(value == null ? "—  (" + state + ")" : hex(value, r.size()))
                .append("\n")
                .append("Address        0x").append(r.address().toString(16)).append("\n")
                .append("Width          ").append(r.size()).append(" bits\n")
                .append("Reset value    ").append(r.reset() == null
                        ? "Not provided by vendor" : hex(r.reset(), r.size())).append("\n");
        if (value != null) {
            details.append("Binary         ").append(bits(value, r.size())).append("\n");
        }
        if (r.writable()) details.append("Edit           Right-click register to write\n");
        if (!r.readable() || value == null)
            details.append("\n").append(explanation).append("\n");
        if (!r.readable() && device != null) {
            var alternative = device.registers().stream()
                    .filter(candidate -> candidate.peripheral().equals(r.peripheral())
                            && candidate.readable()
                            && !candidate.key().equals(r.key())
                            && (candidate.name().toUpperCase(Locale.ROOT).contains("DIN")
                                || candidate.name().toUpperCase(Locale.ROOT).contains("INPUT")))
                    .findFirst();
            alternative.ifPresent(candidate -> details.append("\nPossible input register: ")
                    .append(candidate.name()).append(" (check its description)."));
        }
        registerDetails.setText(details.toString());

        if (fieldTable == null || fieldTable.isDisposed()) return;
        fieldTable.setRedraw(false);
        try {
            fieldTable.removeAll();
            for (var field : r.fields()) {
                var row = new TableItem(fieldTable, SWT.NONE);
                row.setData(field);
                row.setData("description", field.description());
                var mask = BigInteger.ONE.shiftLeft(field.width()).subtract(BigInteger.ONE);
                var fieldValue = value == null ? null
                        : value.shiftRight(field.offset()).and(mask);
                String label = field.name() + " [" + (field.offset() + field.width() - 1)
                        + ":" + field.offset() + "]";
                String formatted = fieldValue == null ? "—" :
                        field.width() == 1 ? (fieldValue.signum() == 0 ? "0 / OFF" : "1 / ON")
                                : hex(fieldValue, field.width());
                String meaning = field.description().isBlank()
                        ? field.access() : field.description().replace('\n', ' ').trim();
                if (meaning.length() > 90) meaning = meaning.substring(0, 87) + "…";
                row.setText(new String[] { label, formatted, meaning });
            }
        } finally { fieldTable.setRedraw(true); }
    }

    private void populatePeripheral(TreeItem peripheral) {
        if (peripheral.isDisposed() || Boolean.TRUE.equals(peripheral.getData("populated"))) return;
        @SuppressWarnings("unchecked")
        java.util.List<Register> registers = (java.util.List<Register>) peripheral.getData("registers");
        if (registers == null) return;
        peripheral.removeAll();
        Set<String> changedKeys = history.changes().stream()
                .map(RegisterHistory.Delta::registerKey)
                .collect(java.util.stream.Collectors.toSet());
        for (var previous : previousValues.entrySet()) {
            var current = values.get(previous.getKey());
            if (current != null && !current.equals(previous.getValue()))
                changedKeys.add(previous.getKey());
        }
        for (Register r : registers) {
            TreeItem item = new TreeItem(peripheral, SWT.NONE);
            item.setData(r);
            item.setData("description", r.description());
            BigInteger value = values.get(r.key());
            String access = !r.readable() ? "Read blocked: " + r.readBlockReason() : r.access();
            if (errors.containsKey(r.key())) access = errors.get(r.key());
            else if (value != null && r.reset() != null
                    && value.xor(r.reset()).and(r.resetMask()).signum() != 0)
                access += " — differs from reset";
            if (changedKeys.contains(r.key()))
                access += " — CHANGED since pinned snapshot";
            String state = RegisterPresentation.state(r, value, errors.get(r.key()));
            if (changedKeys.contains(r.key())) state += "  • changed";
            item.setText(new String[] { (watches.contains(r.key()) ? "★ " : "") + r.name(),
                    value == null ? "—" : hex(value, r.size()), state });
            if (!r.fields().isEmpty()) new TreeItem(item, SWT.NONE).setText("Expand for fields");
        }
        peripheral.setData("populated", Boolean.TRUE);
    }

    private void populateFields(TreeItem item, Register r) {
        if (item.isDisposed() || Boolean.TRUE.equals(item.getData("populated"))) return;
        item.removeAll();
        BigInteger value = values.get(r.key());
        for (var f : r.fields()) {
            TreeItem field = new TreeItem(item, SWT.NONE);
            field.setData(r);
            field.setData("svdField", f);
            field.setData("description", f.description());
            var mask = BigInteger.ONE.shiftLeft(f.width()).subtract(BigInteger.ONE);
            field.setText(new String[] { f.name() + " [" + (f.offset() + f.width() - 1)
                    + ":" + f.offset() + "]",
                    value == null ? "—" : hex(value.shiftRight(f.offset()).and(mask), f.width()),
                    RegisterPresentation.state(r, value, errors.get(r.key())) });
        }
        item.setData("populated", Boolean.TRUE);
    }

    private static String hex(BigInteger value, int bits) {
        return "0x" + String.format("%0" + ((bits + 3) / 4) + "X", value);
    }
    private IDMContext debugContext() {
        var selection = DebugUITools.getDebugContextManager().getContextService(getSite().getWorkbenchWindow())
                .getActiveContext();
        Object element = selection instanceof IStructuredSelection s ? s.getFirstElement() : null;
        if (element instanceof IDMContext dm) return dm;
        if (element instanceof IDMVMContext vm) return vm.getDMContext();
        if (element instanceof IAdaptable adaptable) {
            var vm = adaptable.getAdapter(IDMVMContext.class);
            if (vm != null) return vm.getDMContext();
            var dm = adaptable.getAdapter(IDMContext.class);
            if (dm != null) return dm;
        }
        return null;
    }

    /** Explicit DSF selection wins; another project's selection forbids fallback. */
    private record DebugTarget(DsfSession session, IDMContext context) {}

    private DebugTarget debugTarget() {
        IDMContext selected = debugContext();
        var decision = DebugSessionSelection.choose(selectedProjectName,
                selected == null ? null : selected.getSessionId(), zephyrCandidates());
        if (decision.state() != DebugSessionSelection.State.RESOLVED) {
            debugResolution = switch (decision.state()) {
                case AMBIGUOUS -> "Ambiguous native GDB sessions; select one halted thread/frame.";
                case FOREIGN_PROJECT -> "Unresolved: selected debug frame belongs to another Zephyr project.";
                default -> "Unresolved: no matching active Zephyr GDB session.";
            };
            return null;
        }
        DsfSession session = DsfSession.getSession(decision.sessionId());
        if (session == null) {
            debugResolution = "Unresolved: matching GDB session has ended.";
            return null;
        }
        debugResolution = "Native GDB session " + session.getId() + " selected.";
        return new DebugTarget(session, selected);
    }

    /** Inventory only project-labelled live DSF GDB sessions; no register access. */
    private List<DebugSessionSelection.Candidate> zephyrCandidates() {
        var candidates = new ArrayList<DebugSessionSelection.Candidate>();
        for (var launch : DebugPlugin.getDefault().getLaunchManager().getLaunches()) {
            if (!(launch instanceof GdbLaunch gdb) || launch.isTerminated()
                    || gdb.getSession() == null) continue;
            try {
                var config = launch.getLaunchConfiguration();
                if (config == null) continue;
                String project = config.getAttribute(ZephyrLaunchDelegate.PROJECT, "");
                if (project.isBlank()) continue; // not a Zephyr project launch
                candidates.add(new DebugSessionSelection.Candidate(
                        gdb.getSession().getId(), project,
                        DsfSession.isSessionActive(gdb.getSession().getId())));
            } catch (org.eclipse.core.runtime.CoreException ignored) {
                // Launch metadata without a proven project is unusable here.
            }
        }
        return candidates;
    }

    /**
     * Keep the existing installed-Eclipse remote-debug smoke contract intact.
     * This helper only identifies a uniquely project-matching active launch;
     * it uses the same fail-closed policy as the real context resolution.
     * It is NOT a second register-read path.
     */
    private GdbLaunch uniqueZephyrLaunch() {
        var decision = DebugSessionSelection.choose(selectedProjectName, null, zephyrCandidates());
        if (decision.state() != DebugSessionSelection.State.RESOLVED) return null;
        for (var launch : DebugPlugin.getDefault().getLaunchManager().getLaunches()) {
            if (launch instanceof GdbLaunch gdb && !launch.isTerminated()
                    && gdb.getSession() != null
                    && DsfSession.isSessionActive(gdb.getSession().getId())
                    && decision.sessionId().equals(gdb.getSession().getId()))
                return gdb;
        }
        return null;
    }

    private void refresh() { refresh(ReadScope.SELECTION); }
    private void refreshAll() { refresh(ReadScope.ALL_SAFE); }
    private void refreshWatches() { refresh(ReadScope.WATCHES); }

    /** Reuse the active GDB/DSF session, which already owns the OpenOCD connection. */
    private void refresh(ReadScope scope) {
        if (device == null) {
            status.setText("Load the verified chip SVD before reading peripheral registers.");
            return;
        }
        if (busy) {
            if (scope != ReadScope.ALL_SAFE) status.setText("A register snapshot is already running.");
            return;
        }
        var chosen = selected();
        var targets = device.registers().stream()
                .filter(r -> r.readable() && switch (scope) {
                    case ALL_SAFE -> true;
                    case WATCHES -> watches.contains(r.key());
                    case SELECTION -> watches.contains(r.key()) || r.equals(chosen);
                }).sorted(java.util.Comparator.comparingInt(
                        r -> watches.contains(r.key()) ? 0 : 1)).toList();
        if (targets.isEmpty()) {
            if (scope != ReadScope.ALL_SAFE)
                status.setText("Select an SVD-readable register or add one to Watch.");
            else status.setText("This SVD contains no registers declared safe to read.");
            return;
        }
        if (targets.size() > MAX_SNAPSHOT_REGISTERS) {
            status.setText("Snapshot refused: " + targets.size() + " SVD-readable registers exceed "
                    + MAX_SNAPSHOT_REGISTERS + ". Narrow the selection; no target access occurred.");
            return;
        }
        DebugTarget target = debugTarget();
        if (target == null) {
            status.setText(debugResolution + " Halt the matching native GDB target.");
            return;
        }
        DsfSession session = target.session();
        listenTo(session);
        busy = true;
        previousValues.clear();
        previousValues.putAll(values);
        currentReadTotal = targets.size();
        currentReadOk = 0;
        currentReadFailed = 0;
        lastReadError = null;
        long request = ++generation;
        status.setText("Reading " + targets.size() + " SVD-safe registers using existing halted "
                + "GDB/OpenOCD session (no second OpenOCD connection)…");
        try {
            session.getExecutor().execute(() -> resolveReadFrame(session, target.context(), targets, request));
        } catch (RejectedExecutionException ended) {
            finish(request, "Debug session ended before register refresh.");
        }
    }

    private void editRegister(Register register) {
        if (register == null || device == null) return;
        if (!register.writable()) {
            status.setText("Register write blocked by SVD: " + register.writeBlockReason());
            return;
        }
        var current = values.get(register.key());
        var dialog = new org.eclipse.jface.dialogs.InputDialog(getSite().getShell(),
                "Write " + register.key(),
                "Set an unsigned " + register.size() + "-bit value (0x... or decimal).\n"
                        + "This writes to the physical MCU through the active halted GDB/OpenOCD session.",
                current == null ? "" : hex(current, register.size()), entered -> {
                    try { RegisterWriteValue.parse(register, entered); return null; }
                    catch (IllegalArgumentException invalid) { return invalid.getMessage(); }
                });
        if (dialog.open() != org.eclipse.jface.window.Window.OK) return;
        try {
            submitRegisterWrite(register, RegisterWriteValue.parse(register, dialog.getValue()));
        } catch (IllegalArgumentException rejected) {
            status.setText(rejected.getMessage());
        }
    }

    private void toggleFieldBit(Register register, SvdDevice.Field field) {
        if (register == null || field == null || field.width() != 1) return;
        try {
            BigInteger toggled = RegisterWriteValue.toggle(
                    register, values.get(register.key()), field.offset());
            submitRegisterWrite(register, toggled);
        } catch (IllegalArgumentException rejected) {
            status.setText(rejected.getMessage());
        }
    }

    private record WriteIntent(Register register, BigInteger value) {}

    private void submitRegisterWrite(Register register, BigInteger value) {
        if (device == null || register == null || !device.registers().contains(register)
                || !register.writable()) {
            status.setText("Write blocked: the exact current SVD does not authorise this register.");
            return;
        }
        if (busy) { status.setText("Wait for the current register operation to finish."); return; }
        var target = debugTarget();
        if (target == null) {
            status.setText("Write blocked: no unique matching native GDB target. Halt it first.");
            return;
        }
        String intended = "0x" + RegisterWriteValue.parse(
                register, "0x" + value.toString(16)).toString(16);
        if (!org.eclipse.jface.dialogs.MessageDialog.openConfirm(getSite().getShell(),
                "Confirm physical MCU register write",
                "This will change real hardware. The target must be halted.\n\n"
                        + register.key() + " @ 0x" + register.address().toString(16)
                        + "\nNew value: " + intended
                        + "\n\nSome peripheral writes can affect clocks, interrupts, power or pins. "
                        + "Proceed only if the register is appropriate for this board and state."))
            return;
        var session = target.session();
        listenTo(session);
        busy = true;
        long request = ++generation;
        status.setText("Writing " + register.key() + " through the existing halted GDB/OpenOCD session…");
        var intent = new WriteIntent(register, value);
        try {
            session.getExecutor().execute(() -> resolveOperationFrame(
                    session, target.context(), java.util.List.of(register), request, intent));
        } catch (RejectedExecutionException ended) {
            finish(request, "Debug session ended; register was not written.");
        }
    }

    /** DSF services and frame enumeration must run only on the session's executor. */
    private void resolveReadFrame(DsfSession session, IDMContext selected,
            java.util.List<Register> targets, long request) {
        resolveOperationFrame(session, selected, targets, request, null);
    }

    private void resolveOperationFrame(DsfSession session, IDMContext selected,
            java.util.List<Register> targets, long request, WriteIntent write) {
        var tracker = new DsfServicesTracker(Activator.getDefault().getBundle()
                .getBundleContext(), session.getId());
        try {
            var run = tracker.getService(IRunControl.class);
            var expressions = tracker.getService(IExpressions.class);
            var stack = tracker.getService(IStack.class);
            if (run == null || expressions == null || stack == null) {
                finish(request, "Native GDB run-control, expressions or stack service unavailable.");
                return;
            }
            if (selected != null) {
                var frame = DMContexts.getAncestorOfType(selected, IStack.IFrameDMContext.class);
                if (frame != null) {
                    var thread = DMContexts.getAncestorOfType(frame, IRunControl.IExecutionDMContext.class);
                    if (thread == null || !run.isSuspended(thread)) {
                        finish(request, "Selected debug frame is running; halt the target before Refresh.");
                    } else {
                        operateInFrame(session, expressions, run, thread, frame, targets, request, write);
                    }
                    return;
                }
                var execution = DMContexts.getAncestorOfType(
                        selected, IRunControl.IExecutionDMContext.class);
                if (execution != null
                        && !(execution instanceof IRunControl.IContainerDMContext)) {
                    if (!run.isSuspended(execution)) {
                        finish(request, "Selected debug thread is running; halt it before Refresh.");
                    } else {
                        readFromThreads(session, stack, run, expressions,
                                java.util.List.of(execution), 0, targets, request, write);
                    }
                    return;
                }
            }
            // Selecting a launch/container (or nothing) is normal in the Debug perspective.
            // Discover the one connected inferior, then borrow a suspended thread's frame.
            // Peripheral MMIO addresses are absolute, not local variables in that frame.
            var control = tracker.getService(IGDBControl.class);
            var processes = tracker.getService(IProcesses.class);
            if (control == null || processes == null) {
                finish(request, "Cannot enumerate native GDB threads. Select a halted stack frame.");
                return;
            }
            processes.getProcessesBeingDebugged(control.getContext(),
                    new DataRequestMonitor<IDMContext[]>(session.getExecutor(), null) {
                @Override protected void handleCompleted() {
                    if (request != generation || closed) return;
                    if (!isSuccess() || getData() == null || getData().length != 1
                            || !(getData()[0] instanceof IRunControl.IContainerDMContext container)) {
                        finish(request, "GDB inferior is missing or ambiguous. Select a halted stack frame.");
                        return;
                    }
                    run.getExecutionContexts(container,
                            new DataRequestMonitor<IRunControl.IExecutionDMContext[]>(session.getExecutor(), null) {
                        @Override protected void handleCompleted() {
                            if (request != generation || closed) return;
                            if (!isSuccess() || getData() == null) {
                                finish(request, "Cannot enumerate GDB threads. Select a halted stack frame.");
                                return;
                            }
                            var suspended = java.util.Arrays.stream(getData())
                                    .filter(run::isSuspended).toList();
                            if (suspended.isEmpty()) {
                                finish(request, "Target is running. Suspend it in Debug; "
                                        + "the register explorer will not halt firmware automatically.");
                                return;
                            }
                            if (!DebugSessionSelection.uniqueHaltedThread(suspended.size())) {
                                finish(request, "Ambiguous halted GDB threads; select the exact suspended "
                                        + "thread or frame in Debug. No peripheral registers were read.");
                                return;
                            }
                            readFromThreads(session, stack, run, expressions,
                                    suspended, 0, targets, request, write);
                        }
                    });
                }
            });
        } finally {
            tracker.dispose();
        }
    }

    /** A stopped multi-thread inferior may have several threads; find a usable top frame. */
    private void readFromThreads(DsfSession session, IStack stack, IRunControl run,
            IExpressions expressions, java.util.List<IRunControl.IExecutionDMContext> threads,
            int index, java.util.List<Register> targets, long request, WriteIntent write) {
        if (closed || request != generation) return;
        if (index >= threads.size()) {
            finish(request, "No suspended GDB thread supplies a stack frame. "
                    + "Select a halted thread in Debug and retry.");
            return;
        }
        var execution = threads.get(index);
        if (!run.isSuspended(execution)) {
            readFromThreads(session, stack, run, expressions, threads, index + 1, targets, request, write);
            return;
        }
        stack.getFrames(execution, 0, 0,
                new DataRequestMonitor<IStack.IFrameDMContext[]>(session.getExecutor(), null) {
            @Override protected void handleCompleted() {
                if (closed || request != generation) return;
                if (isSuccess() && getData() != null && getData().length > 0) {
                    operateInFrame(session, expressions, run, execution, getData()[0], targets, request, write);
                } else {
                    readFromThreads(session, stack, run, expressions,
                            threads, index + 1, targets, request, write);
                }
            }
        });
    }

    private void operateInFrame(DsfSession session, IExpressions expressions, IRunControl run,
            IRunControl.IExecutionDMContext execution, IStack.IFrameDMContext frame,
            java.util.List<Register> targets, long request, WriteIntent write) {
        if (write == null) readInFrame(session, expressions, run, execution, frame, targets, request);
        else writeInFrame(session, expressions, run, execution, frame, write, request);
    }

    private void writeInFrame(DsfSession session, IExpressions expressions, IRunControl run,
            IRunControl.IExecutionDMContext execution, IStack.IFrameDMContext frame,
            WriteIntent write, long request) {
        if (closed || request != generation) return;
        Register reg = write.register();
        if (!run.isSuspended(execution) || !reg.writable() || device == null
                || !device.registers().contains(reg)) {
            finish(request, "Hardware write cancelled: stopped session/SVD identity not valid.");
            return;
        }
        String type = reg.size() == 8 ? "unsigned char"
                : reg.size() == 16 ? "unsigned short" : "unsigned int";
        String addressExpression = "*(volatile " + type + " *)0x"
                + reg.address().toString(16);
        var location = expressions.createExpression(frame, addressExpression);
        // DSF routes this write through the *existing* GDB remote target; it
        // never creates a second OpenOCD control socket or executes shell commands.
        expressions.writeExpression(location, "0x" + write.value().toString(16),
                IFormattedValues.HEX_FORMAT,
                new RequestMonitor(session.getExecutor(), null) {
                    @Override protected void handleCompleted() {
                        if (closed || request != generation) return;
                        if (!isSuccess()) {
                            finish(request, "GDB register write failed: " + getStatus().getMessage());
                            return;
                        }
                        if (!run.isSuspended(execution)) {
                            finish(request, "GDB write returned but target resumed; no readback performed.");
                            return;
                        }
                        if (expressions instanceof ICachingService cache) cache.flushCache(frame);
                        // A readback is an observation, not an atomic hardware proof.
                        readNext(session, expressions, run, execution, frame,
                                java.util.List.of(reg), 0, request);
                    }
                });
    }

    private void readInFrame(DsfSession session, IExpressions expressions, IRunControl run,
            IRunControl.IExecutionDMContext execution, IStack.IFrameDMContext frame,
            java.util.List<Register> targets, long request) {
        if (closed || request != generation) return;
        if (!run.isSuspended(execution)) {
            finish(request, "Target resumed while resolving its frame; no registers were read.");
            return;
        }
        if (expressions instanceof ICachingService cache) cache.flushCache(frame);
        readNext(session, expressions, run, execution, frame, targets, 0, request);
    }
    private void readNext(DsfSession session, IExpressions expressions, IRunControl run,
            IRunControl.IExecutionDMContext execution, IDMContext context, java.util.List<Register> targets,
            int index, long request) {
        // UI generation is checked on delivery; DSF also checks liveness before every command.
        if (closed || request != generation) return;
        if (index == targets.size()) { finish(request, "Refresh complete. Snapshot from halted GDB."); return; }
        var r = targets.get(index);
        if (!run.isSuspended(execution)) { finish(request, "Target resumed; refresh stopped."); return; }
        if (!r.readable()) {
            deliver(request, r, null, "Read blocked: " + r.readBlockReason(), index, targets.size());
            readNext(session, expressions, run, execution, context, targets, index + 1, request); return;
        }
        String type = r.size() == 8 ? "unsigned char" : r.size() == 16 ? "unsigned short" : "unsigned int";
        // GDB decodes target endianness; the width guard prevents ABI-dependent reads.
        String expression = "sizeof(" + type + ") == " + (r.size() / 8)
                + " ? (unsigned long long)(*(volatile " + type + " *)0x" + r.address().toString(16) + ") : -1LL";
        var expr = expressions.createExpression(context, expression);
        var formatted = expressions.getFormattedValueContext(expr, IFormattedValues.HEX_FORMAT);
        expressions.getFormattedExpressionValue(formatted,
                new DataRequestMonitor<IFormattedValues.FormattedValueDMData>(session.getExecutor(), null) {
            @Override protected void handleCompleted() {
                BigInteger value = null; String error = null;
                if (!isSuccess()) error = "Read failed: " + getStatus().getMessage();
                else try {
                    String raw = getData().getFormattedValue().trim();
                    if (!raw.matches("0[xX][0-9a-fA-F]+")) throw new IllegalArgumentException();
                    value = new BigInteger(raw.substring(2), 16);
                    if (value.signum() < 0 || value.bitLength() > r.size()) throw new IllegalArgumentException();
                } catch (Exception ex) { value = null; error = "Unsupported target ABI or nonnumeric GDB response"; }
                deliver(request, r, value, error, index, targets.size());
                readNext(session, expressions, run, execution, context, targets, index + 1, request);
            }
        });
    }
    private void deliver(long request, Register r, BigInteger value, String error, int index, int total) {
        if (closed || ui.isDisposed()) return;
        ui.asyncExec(() -> {
            if (tree.isDisposed() || request != generation) return;
            values.remove(r.key()); errors.remove(r.key());
            if (value != null) {
                values.put(r.key(), value);
                currentReadOk++;
            }
            if (error != null) {
                errors.put(r.key(), error);
                lastReadError = r.key() + ": " + error;
                currentReadFailed++;
            }
            // One render per 32 GDB commands rather than thousands of SWT rebuilds.
            if (index % 32 == 31 || index == total - 1) {
                render();
                var selected = selected();
                if (selected != null && selected.key().equals(r.key())) showRegisterDetails(r);
            }
        });
    }
    private void finish(long request, String message) {
        if (closed || ui.isDisposed()) return;
        ui.asyncExec(() -> {
            if (!tree.isDisposed() && request == generation) {
                busy = false;
                if (message.startsWith("Refresh complete.") && !values.isEmpty()
                        && svdIdentity != null && listenedSession != null) {
                    try {
                        history.record(new RegisterHistory.Sample(listenedSession.getId(),
                                svdIdentity, java.time.Instant.now(), new HashMap<>(values)));
                        render();
                        historyChart.redraw();
                    } catch (IllegalArgumentException invalid) {
                        status.setText("Register snapshot rejected: " + invalid.getMessage());
                        return;
                    }
                }
                if (message.startsWith("Refresh complete.")) {
                    lastReadAt = java.time.Instant.now();
                    String report = "Read " + currentReadOk + " of " + currentReadTotal
                            + " registers through halted debugger";
                    if (currentReadFailed > 0) report += "  •  " + currentReadFailed + " failed";
                    status.setText(report + ". Select a register to see its bits.");
                    status.setToolTipText(lastReadError == null ? "All attempted reads succeeded."
                            : "Last GDB read failure: " + lastReadError);
                } else {
                    status.setText(message);
                }
                updateConnectionPath();
            }
        });
    }
    private void pinBaseline() {
        if (history.pinLatest()) {
            render();
            status.setText("Pinned the last completed, halted-session register snapshot at "
                    + history.baseline().timestamp() + ". Refresh after stepping to compare values.");
        } else status.setText("No completed snapshot. Select a halted frame and Refresh first.");
    }
    private static String csv(String value) {
        return "\"" + value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"";
    }
    private void exportHistory() {
        if (history.samples().isEmpty()) {
            status.setText("No register snapshots to export. Refresh Live Values first.");
            return;
        }
        var dialog = new FileDialog(getSite().getShell(), SWT.SAVE);
        dialog.setText("Export bounded register history (includes local debug-session and SVD identifiers)");
        dialog.setFilterExtensions(new String[] { "*.csv" });
        dialog.setFileName("zew-register-history.csv");
        dialog.setOverwrite(true);
        String path = dialog.open();
        if (path == null) return;
        var text = new StringBuilder("captured_utc,debug_session,svd_sha256,register,value_hex\n");
        for (var sample : history.samples()) {
            for (var entry : sample.values().entrySet()) {
                text.append(csv(sample.timestamp().toString())).append(',')
                        .append(csv(sample.session())).append(',')
                        .append(csv(sample.svdIdentity())).append(',')
                        .append(csv(entry.getKey())).append(',')
                        .append(csv("0x" + entry.getValue().toString(16))).append('\n');
            }
        }
        try {
            Files.writeString(Path.of(path), text.toString(), java.nio.charset.StandardCharsets.UTF_8);
            status.setText("Exported " + history.samples().size() + " snapshot samples. "
                    + "These are selectively read registers, NOT simultaneous MCU-state samples.");
        } catch (Exception error) {
            status.setText("Cannot export register history: " + error.getMessage());
        }
    }
    @Override public void saveState(IMemento memento) {
        if (svdPath != null) memento.putString("svdPath", svdPath);
    }
    @Override public void setFocus() { if (tree != null) tree.setFocus(); }
    @Override public void dispose() {
        stopLiveWatch();
        closed = true; generation++;
        listenTo(null);
        if (contextListener != null) DebugUITools.getDebugContextManager()
                .getContextService(getSite().getWorkbenchWindow()).removeDebugContextListener(contextListener);
        if (contextSubscription != null) {
            try { contextSubscription.close(); }
            catch (Exception ignored) { /* No I/O in UI subscription disposal. */ }
        }
        super.dispose();
    }
}
