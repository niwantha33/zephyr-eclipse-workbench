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
import org.eclipse.cdt.dsf.datamodel.DMContexts;
import org.eclipse.cdt.dsf.datamodel.IDMContext;
import org.eclipse.cdt.dsf.debug.service.IExpressions;
import org.eclipse.cdt.dsf.debug.service.IFormattedValues;
import org.eclipse.cdt.dsf.debug.service.IRunControl;
import org.eclipse.cdt.dsf.debug.service.IProcesses;
import org.eclipse.cdt.dsf.debug.service.IStack;
import org.eclipse.cdt.dsf.gdb.launching.GdbLaunch;
import org.eclipse.cdt.dsf.gdb.service.IGDBControl;
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
import io.github.niwantha33.zew.core.VendorSvdProvisioner;
import io.github.niwantha33.zew.core.GitHubSvdProvisioner;
import io.github.niwantha33.zew.core.RegisterHistory;
import io.github.niwantha33.zew.core.RegisterSparkline;
import io.github.niwantha33.zew.core.SvdDevice.Register;

/** Read-only peripheral inspection through the user's selected native DSF session. */
public final class SocRegistersView extends ViewPart {
    private Tree tree;
    private Label status;
    private Label summary;
    private Label contextInfo;
    private org.eclipse.swt.widgets.Display ui;
    private volatile DsfSession listenedSession;
    private Text search;
    private SvdDevice device;
    private String svdPath;
    private String selectedProjectName;
    private String selectedBoard;
    private String discoveryExplanation = "No exact project / SVD selected.";
    private volatile long discoveryGeneration;
    private final Map<String, BigInteger> values = new HashMap<>();
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

    @Override public void init(IViewSite site, IMemento memento) throws PartInitException {
        super.init(site, memento);
        if (memento != null) svdPath = memento.getString("svdPath");
    }
    @Override public void createPartControl(Composite parent) {
        ui = parent.getDisplay();
        parent.setLayout(new GridLayout(1, false));
        var bar = new Composite(parent, SWT.NONE);
        bar.setLayout(new GridLayout(4, false));
        bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        button(bar, "Find Device Definitions", this::chooseProjectForDiscovery);
        button(bar, "Get Device Registers…", this::downloadVendorSvd);
        button(bar, "Import Register Definitions…", () -> {
            var dialog = new FileDialog(parent.getShell(), SWT.OPEN);
            dialog.setFilterExtensions(new String[] { "*.svd;*.xml", "*.*" });
            String path = dialog.open();
            if (path != null) load(path);
        });
        button(bar, "Refresh Live Values", this::refresh);
        var options = new Composite(parent, SWT.NONE);
        options.setLayout(new GridLayout(5, false));
        options.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        button(options, "Pin baseline", this::pinBaseline);
        button(options, "Export history CSV…", this::exportHistory);
        watchButton = new Button(options, SWT.PUSH);
        watchButton.setText("Start Live Watch (opt-in)");
        watchButton.addListener(SWT.Selection, e -> toggleLiveWatch());
        button(options, "Watch / unwatch", () -> {
            var selected = selected();
            if (selected != null) {
                if (!watches.remove(selected.key())) watches.add(selected.key());
                render();
            }
        });
        search = new Text(options, SWT.SEARCH | SWT.ICON_SEARCH | SWT.CANCEL);
        search.setMessage("Find peripheral, register or field");
        search.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        search.addModifyListener(e -> render());
        status = new Label(parent, SWT.WRAP);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        status.setText("Automatically detecting exact vendor SVD from the selected Zephyr build. No generic MCU model is substituted.");
        contextInfo = new Label(parent, SWT.WRAP);
        contextInfo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        contextInfo.setText("No selected debug frame. CPU registers, Variables and Expressions are separate native Debug views.");
        summary = new Label(parent, SWT.WRAP);
        summary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        summary.setText("No SVD model loaded yet. Find Device Definitions searches installed SDKs and packs; register values require a halted manual read.");
        historyChart = new Canvas(parent, SWT.BORDER | SWT.DOUBLE_BUFFERED);
        var chartData = new GridData(SWT.FILL, SWT.TOP, true, false);
        chartData.heightHint = 105;
        chartData.exclude = true;
        historyChart.setLayoutData(chartData);
        historyChart.setVisible(false);
        historyChart.addPaintListener(e -> paintHistory(e.gc));
        tree = new Tree(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
        tree.setHeaderVisible(true); tree.setLinesVisible(true);
        tree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        String[] headings = { "Peripheral / register / field", "Address / bits", "Value (hex)", "Reset (hex)", "Access / status" };
        int[] widths = { 310, 155, 140, 140, 260 };
        for (int i = 0; i < headings.length; i++) {
            var column = new TreeColumn(tree, SWT.LEFT); column.setText(headings[i]); column.setWidth(widths[i]);
        }
        tree.addListener(SWT.Expand, e -> {
            if (!(e.item instanceof TreeItem item)) return;
            if (item.getData("registers") instanceof java.util.List<?>) populatePeripheral(item);
            else if (item.getData() instanceof Register r) populateFields(item, r);
        });
        tree.addListener(SWT.Selection, e -> historyChart.redraw());
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
        stopLiveWatch();
        selectedProjectName = project.getName();
        selectedBoard = null;
        discoveryExplanation = "Finding vendor register descriptions for project " + selectedProjectName + "…";
        device = null; svdPath = null; svdIdentity = null;
        values.clear(); errors.clear(); watches.clear(); history.clear();
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
                    Path workspace = Path.of(settings.getProperty("workspace"));
                    // Probe focused exact-chip/vendor-pack directories first;
                    // avoid double-scanning all West modules before local packs.
                    roots.add(project.getLocation().toFile().toPath().resolve(".zew/svd"));
                    Path root = Path.of(settings.getProperty("zew.setupRoot"));
                    roots.add(root.resolve("vendor-svd"));
                    roots.add(root.resolve("svd"));
                    Path userHome = Path.of(System.getProperty("user.home"));
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
                    roots.add(workspace.resolve("modules/hal/espressif"));
                    roots.add(workspace.resolve("modules/hal/stm32"));
                    roots.add(workspace.resolve("modules/hal/nordic"));
                    roots.add(workspace.resolve("modules/hal/ti"));

                    roots.add(userHome.resolve("AppData/Local/Arm/Packs"));
                    roots.add(workspace.resolve("modules/hal"));
                    roots.add(workspace.resolve("zephyr"));
                    roots.add(Path.of(settings.getProperty("tools.sdk")));
                    if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
                        roots.add(Path.of("C:/ti"));
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    result = target.usable()
                            ? SvdAutoDiscovery.resolve(target.target(), roots, userHome.resolve(".zew/svd-cache"))
                            : new SvdAutoDiscovery.Result(SvdAutoDiscovery.State.INVALID_TARGET,
                                    "", null, 0, false, target.explanation(), List.of());
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
            board = settings.getProperty("board", "");
            vendorDir = Path.of(settings.getProperty("zew.setupRoot")).resolve("vendor-svd");
        } catch (Exception error) {
            status.setText("Cannot determine exact board or vendor-pack directory: " + error.getMessage());
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

    /** No implicit peripheral read: selection/resume/step only invalidates snapshots. */
    private void contextChanged() {
        quietUntil = System.currentTimeMillis() + 1500;
        generation++; busy = false; values.clear(); errors.clear(); render();
        var target = debugTarget();
        var selectedSession = target == null ? null : target.session();
        listenTo(selectedSession);
        contextInfo.setText(selectedSession == null
                ? "No unambiguous native debug session. Start Zephyr Debug or select a stack frame; "
                    + "multiple active targets require an explicit selection."
                : "Native debug session " + selectedSession.getId()
                    + (target.context() == null
                        ? "; no frame selected — Refresh resolves a suspended GDB thread without halting the CPU."
                        : "; selected context: " + target.context().getClass().getSimpleName()
                            + ". Refresh uses its suspended frame/thread."));
        contextInfo.getParent().layout(true, true);
        status.setText(device == null ? discoveryExplanation
                : "Exact SVD model " + device.name()
                    + " loaded for " + (selectedProjectName == null ? "manual selection" : selectedProjectName)
                    + "; select a safe register and Refresh while halted.");
    }

    private final RunListener runListener = new RunListener();

    /** Public because DSF invokes annotated listener methods reflectively across bundles. */
    public final class RunListener {
        @DsfServiceEventHandler public void eventDispatched(IRunControl.IResumedDMEvent event) {
            quietUntil = Long.MAX_VALUE;
            invalidateSnapshot(event.getDMContext(), "Target resumed. Previous register values were cleared; halt and Refresh for a new snapshot.");
        }
        @DsfServiceEventHandler public void eventDispatched(IRunControl.ISuspendedDMEvent event) {
            quietUntil = System.currentTimeMillis() + 1500;
            invalidateSnapshot(event.getDMContext(), "Target suspended. Select its stack frame, then Refresh selected registers and watches.");
        }
        @DsfServiceEventHandler public void eventDispatched(IRunControl.IExitedDMEvent event) {
            quietUntil = Long.MAX_VALUE;
            invalidateSnapshot(event.getDMContext(), "Debug execution exited. No live peripheral values are available.");
        }
    }

    private void invalidateSnapshot(IDMContext context, String message) {
        var session = listenedSession;
        if (closed || session == null || !session.getId().equals(context.getSessionId()) || ui.isDisposed()) return;
        ui.asyncExec(() -> {
            if (tree.isDisposed() || listenedSession != session) return;
            generation++; busy = false; values.clear(); errors.clear(); render();
            status.setText(message);
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
                                && !SvdPartCompatibility.matches(SvdAutoDiscovery.partFromBoard(board), loaded.name())) {
                            status.setText("Rejected SVD model: register definitions are for "
                                    + loaded.name() + ", not the selected "
                                    + SvdAutoDiscovery.partFromBoard(board)
                                    + ". Previous definitions were kept; debug session unchanged.");
                            return;
                        }
                        generation++; busy = false; device = loaded; svdPath = path;
                        svdIdentity = identity;
                        values.clear(); errors.clear(); watches.clear(); history.clear();
                        render();
                        discoveryExplanation = (exactPackDeviceMapping
                                ? "Installed CMSIS pack maps " + board + " to " + loaded.name()
                                : "Register model: " + loaded.name() + "; selected board: " + board)
                                + ". Live readings require an explicit refresh while halted.";
                        status.setText(loaded.name() + " — " + loaded.registers().size()
                                + " definitions loaded. Debug execution was not changed.");
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
        if (device == null || watches.isEmpty() || watches.size() > 8 || debugTarget() == null) {
            status.setText("Load an exact SVD; watch 1–8 safe registers; start a unique native GDB session and halt the target.");
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
        ui.timerExec(1500, () -> {
            if (closed || tree.isDisposed() || !liveWatch || epoch != watchEpoch) return;
            if (watches.isEmpty() || watches.size() > 8) {
                stopLiveWatch();
                status.setText("Live Watch stopped: watch count outside 1–8.");
                return;
            }
            if (!busy && debugTarget() != null && System.currentTimeMillis() >= quietUntil) refresh();
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
    private Register selected() {
        if (tree.getSelectionCount() == 0) return null;
        var item = tree.getSelection()[0];
        return item.getData() instanceof Register register ? register : null;
    }
    private void render() {
        if (device == null || tree == null || tree.isDisposed()) return;
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
                if (expandedPeripherals.contains(entry.getKey())) {
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
            int count = matches.values().stream().mapToInt(java.util.List::size).sum();
            summary.setText("All " + matches.size() + " peripherals; " + count
                    + " matching registers available. Expand a peripheral to see its registers. "
                    + watches.size() + " watches; " + history.samples().size()
                    + " captured register snapshots (max " + RegisterHistory.LIMIT + ")."
                    + (history.baseline() == null ? " No baseline pinned."
                            : " Baseline: " + history.baseline().timestamp() + ".")
                    + (liveWatch ? " Halted-only sampled Live Watch; NOT continuous hardware tracing."
                            : " No automatic polling."));
        } finally { tree.setRedraw(true); }
    }

    private void populatePeripheral(TreeItem peripheral) {
        if (peripheral.isDisposed() || Boolean.TRUE.equals(peripheral.getData("populated"))) return;
        @SuppressWarnings("unchecked")
        java.util.List<Register> registers = (java.util.List<Register>) peripheral.getData("registers");
        if (registers == null) return;
        peripheral.removeAll();
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
            if (history.changes().stream().anyMatch(change -> change.registerKey().equals(r.key())))
                access += " — CHANGED since pinned snapshot";
            item.setText(new String[] { (watches.contains(r.key()) ? "★ " : "") + r.name(),
                    "0x" + r.address().toString(16),
                    value == null ? "—" : hex(value, r.size()),
                    r.reset() == null ? "—" : hex(r.reset(), r.size()), access });
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
            field.setData("description", f.description());
            var mask = BigInteger.ONE.shiftLeft(f.width()).subtract(BigInteger.ONE);
            field.setText(new String[] { f.name(), "[" + (f.offset() + f.width() - 1)
                    + ":" + f.offset() + "]",
                    value == null ? "—" : hex(value.shiftRight(f.offset()).and(mask), f.width()),
                    r.reset() == null ? "—" : hex(r.reset().shiftRight(f.offset()).and(mask), f.width()),
                    f.access() + (f.readAction().isEmpty() ? "" : " / readAction: " + f.readAction()) });
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
        if (element instanceof IDMVMContext vm) return vm.getDMContext();
        if (element instanceof IAdaptable adaptable) {
            var vm = adaptable.getAdapter(IDMVMContext.class);
            if (vm != null) return vm.getDMContext();
        }
        return null;
    }

    /** A selected CDT frame wins; otherwise only a unique Zephyr GDB launch may be inferred. */
    private record DebugTarget(DsfSession session, IDMContext context) {}

    private DebugTarget debugTarget() {
        IDMContext selected = debugContext();
        if (selected != null) {
            DsfSession session = DsfSession.getSession(selected.getSessionId());
            if (session != null && (selectedProjectName == null
                    || launchMatchesProject(session, selectedProjectName))) {
                return new DebugTarget(session, selected);
            }
        }
        GdbLaunch launch = uniqueZephyrLaunch();
        return launch == null ? null : new DebugTarget(launch.getSession(), null);
    }

    private static boolean launchMatchesProject(DsfSession session, String projectName) {
        for (var launch : DebugPlugin.getDefault().getLaunchManager().getLaunches()) {
            if (!(launch instanceof GdbLaunch gdb) || launch.isTerminated()
                    || gdb.getSession() != session) continue;
            try {
                return projectName.equals(launch.getLaunchConfiguration()
                        .getAttribute(ZephyrLaunchDelegate.PROJECT, ""));
            } catch (org.eclipse.core.runtime.CoreException unavailable) {
                return false;
            }
        }
        return false;
    }

    /**
     * Never take an arbitrary GDB session when several targets are attached.
     * The project filter also avoids reading a different chip with this view's SVD.
     */
    private GdbLaunch uniqueZephyrLaunch() {
        GdbLaunch unique = null;
        for (var launch : DebugPlugin.getDefault().getLaunchManager().getLaunches()) {
            if (!(launch instanceof GdbLaunch gdb) || launch.isTerminated()) continue;
            try {
                String project = launch.getLaunchConfiguration()
                        .getAttribute(ZephyrLaunchDelegate.PROJECT, "");
                if (project.isBlank() || (selectedProjectName != null
                        && !selectedProjectName.equals(project))) continue;
                if (!DsfSession.isSessionActive(gdb.getSession().getId())) continue;
                if (unique != null) return null; // ambiguous; require explicit Debug selection
                unique = gdb;
            } catch (org.eclipse.core.runtime.CoreException unavailable) {
                // Not a usable Zephyr launch.
            }
        }
        return unique;
    }

    private void refresh() {
        if (device == null) {
            status.setText("Load the exact chip's vendor SVD first. Native CPU registers do not require an SVD; use the Registers view.");
            return;
        }
        if (busy) { status.setText("A register refresh is already in progress."); return; }
        var chosen = selected();
        var targets = device.registers().stream()
                .filter(r -> watches.contains(r.key()) || r.equals(chosen)).toList();
        if (targets.isEmpty()) { status.setText("Select a register or add a watch first."); return; }
        if (targets.size() > 64) { status.setText("Refresh is limited to 64 watches. Remove some watches first."); return; }
        DebugTarget target = debugTarget();
        if (target == null) {
            status.setText("No unambiguous native GDB session. Start Zephyr Debug, or select a suspended "
                    + "frame in Debug when multiple targets are present.");
            return;
        }
        DsfSession session = target.session();
        listenTo(session);
        busy = true;
        long request = ++generation;
        status.setText("Resolving suspended GDB frame for read-only register snapshot…");
        try {
            session.getExecutor().execute(() -> resolveReadFrame(session, target.context(), targets, request));
        } catch (RejectedExecutionException ended) {
            finish(request, "Debug session ended before register refresh.");
        }
    }

    /** DSF services and frame enumeration must run only on the session's executor. */
    private void resolveReadFrame(DsfSession session, IDMContext selected,
            java.util.List<Register> targets, long request) {
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
                        readInFrame(session, expressions, run, thread, frame, targets, request);
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
                                java.util.List.of(execution), 0, targets, request);
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
                            readFromThreads(session, stack, run, expressions,
                                    suspended, 0, targets, request);
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
            int index, java.util.List<Register> targets, long request) {
        if (closed || request != generation) return;
        if (index >= threads.size()) {
            finish(request, "No suspended GDB thread supplies a stack frame. "
                    + "Select a halted thread in Debug and retry.");
            return;
        }
        var execution = threads.get(index);
        if (!run.isSuspended(execution)) {
            readFromThreads(session, stack, run, expressions, threads, index + 1, targets, request);
            return;
        }
        stack.getFrames(execution, 0, 0,
                new DataRequestMonitor<IStack.IFrameDMContext[]>(session.getExecutor(), null) {
            @Override protected void handleCompleted() {
                if (closed || request != generation) return;
                if (isSuccess() && getData() != null && getData().length > 0) {
                    readInFrame(session, expressions, run, execution, getData()[0], targets, request);
                } else {
                    readFromThreads(session, stack, run, expressions,
                            threads, index + 1, targets, request);
                }
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
        if (index == targets.size()) { finish(request, "Refresh complete. Values are snapshots; Refresh after stepping."); return; }
        var r = targets.get(index);
        if (!run.isSuspended(execution)) { finish(request, "Target resumed; refresh stopped."); return; }
        if (!r.readable()) {
            deliver(request, r, null, "Read blocked: " + r.readBlockReason());
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
                deliver(request, r, value, error);
                readNext(session, expressions, run, execution, context, targets, index + 1, request);
            }
        });
    }
    private void deliver(long request, Register r, BigInteger value, String error) {
        if (closed || ui.isDisposed()) return;
        ui.asyncExec(() -> {
            if (tree.isDisposed() || request != generation) return;
            values.remove(r.key()); errors.remove(r.key());
            if (value != null) values.put(r.key(), value);
            if (error != null) errors.put(r.key(), error);
            render();
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
                status.setText(message + " Captured history: " + history.samples().size()
                        + "; compare only within the same debug session and exact SVD.");
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
        super.dispose();
    }
}
