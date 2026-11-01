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
import org.eclipse.cdt.dsf.debug.service.ICachingService;
import org.eclipse.cdt.dsf.service.DsfServicesTracker;
import org.eclipse.cdt.dsf.service.DsfSession;
import org.eclipse.cdt.dsf.service.DsfServiceEventHandler;
import org.eclipse.cdt.dsf.ui.viewmodel.datamodel.IDMVMContext;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.contexts.IDebugContextListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
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
import io.github.niwantha33.zew.core.VendorSvdProvisioner;
import io.github.niwantha33.zew.core.GitHubSvdProvisioner;
import io.github.niwantha33.zew.core.RegisterHistory;
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
        button(bar, "Find vendor SVD", this::chooseProjectForDiscovery);
        button(bar, "Download vendor SVD…", this::downloadVendorSvd);
        button(bar, "Load SVD…", () -> {
            var dialog = new FileDialog(parent.getShell(), SWT.OPEN);
            dialog.setFilterExtensions(new String[] { "*.svd;*.xml", "*.*" });
            String path = dialog.open();
            if (path != null) load(path);
        });
        button(bar, "Refresh selected + watches", this::refresh);
        var options = new Composite(parent, SWT.NONE);
        options.setLayout(new GridLayout(4, false));
        options.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        button(options, "Pin baseline", this::pinBaseline);
        button(options, "Export history CSV…", this::exportHistory);
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
        summary.setText("No SVD model loaded yet. Find vendor SVD searches installed SDKs and packs; register values require a halted manual read.");
        tree = new Tree(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
        tree.setHeaderVisible(true); tree.setLinesVisible(true);
        tree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        String[] headings = { "Peripheral / register / field", "Address / bits", "Value (hex)", "Reset (hex)", "Access / status" };
        int[] widths = { 310, 155, 140, 140, 260 };
        for (int i = 0; i < headings.length; i++) {
            var column = new TreeColumn(tree, SWT.LEFT); column.setText(headings[i]); column.setWidth(widths[i]);
        }
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
            discoveryExplanation = "Multiple live Zephyr debug projects found. Use Find vendor SVD to choose an exact project.";
            status.setText(discoveryExplanation);
        }
    }

    /**
     * Called by the successful native GDB launch with its exact imported project.
     * Searches installed local SDKs asynchronously, never the network or the MCU.
     */
    public void useLaunchProject(IProject project) {
        if (project == null || !project.isAccessible()) return;
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
        Job job = new Job("Find vendor SVD — " + project.getName()) {
            @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                final SvdAutoDiscovery.Result result;
                final String board;
                try {
                    var settings = ProjectContext.settings(project);
                    board = settings.getProperty("board", "");
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
                    result = SvdAutoDiscovery.resolve(board, roots, userHome.resolve(".zew/svd-cache"));
                } catch (Exception error) {
                    if (!closed && !ui.isDisposed()) ui.asyncExec(() -> {
                        if (request != discoveryGeneration || tree.isDisposed()) return;
                        discoveryExplanation = "SVD discovery failed for " + project.getName()
                                + ": " + error.getMessage() + ". Use Load SVD… for an exact device.";
                        status.setText(discoveryExplanation);
                    });
                    return new Status(IStatus.WARNING, Activator.ID, "SVD search failed", error);
                }
                if (!closed && !ui.isDisposed()) ui.asyncExec(() -> {
                    if (tree.isDisposed() || request != discoveryGeneration) return;
                    selectedBoard = board;
                    if (result.state() == SvdAutoDiscovery.State.MATCHED) {
                        load(result.path().toString(), result.mappedByPack());
                    } else {
                        discoveryExplanation = result.explanation();
                        status.setText(discoveryExplanation + " Looked in: "
                                + String.join("; ", result.inspected().stream().limit(8).toList()));
                        summary.setText("SoC " + result.part() + ": no verified SVD available. "
                                + "Install the vendor's CMSIS pack under the managed workspace, or Load SVD…");
                    }
                });
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.schedule();
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
                    + ". Use an official installed vendor CMSIS pack or Load SVD…");
            return;
        }
        boolean approved = org.eclipse.jface.dialogs.MessageDialog.openQuestion(
                getSite().getShell(), "Download official TI register definitions?",
                "Download TexasInstruments MSPM0G1X0X_G3X0X Device Family Pack 1.3.1"
                + " from keil.com into the managed workspace?\\n\\n"
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
        generation++; busy = false; values.clear(); errors.clear(); render();
        var context = debugContext();
        var selectedSession = context == null ? null : DsfSession.getSession(context.getSessionId());
        listenTo(selectedSession);
        contextInfo.setText(selectedSession == null
                ? "No selected DSF frame. Select a suspended stack frame in Debug to read values."
                : "Native debug session " + selectedSession.getId() + "; selected context: "
                    + context.getClass().getSimpleName() + ". Reads require a halted execution context.");
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
            invalidateSnapshot(event.getDMContext(), "Target resumed. Previous register values were cleared; halt and Refresh for a new snapshot.");
        }
        @DsfServiceEventHandler public void eventDispatched(IRunControl.ISuspendedDMEvent event) {
            invalidateSnapshot(event.getDMContext(), "Target suspended. Select its stack frame, then Refresh selected registers and watches.");
        }
        @DsfServiceEventHandler public void eventDispatched(IRunControl.IExitedDMEvent event) {
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
        // Bound document size before the DOM allocation; vendor files are loaded offline.
        try {
            if (Files.size(Path.of(path)) > 32 * 1024 * 1024) throw new IllegalArgumentException("SVD exceeds 32 MiB");
            SvdDevice loaded;
            try (var input = Files.newInputStream(Path.of(path))) { loaded = SvdDevice.read(input); }
            if (!exactPackDeviceMapping && selectedBoard != null && !selectedBoard.isBlank()
                    && !loaded.name().replaceAll("[^A-Za-z0-9]", "").equalsIgnoreCase(
                            SvdAutoDiscovery.partFromBoard(selectedBoard).replaceAll("[^A-Za-z0-9]", ""))) {
                status.setText("Rejected SVD model " + loaded.name() + ": expected exact SoC "
                        + SvdAutoDiscovery.partFromBoard(selectedBoard)
                        + ". A family-level or other-chip register map cannot be substituted.");
                return;
            }
            generation++; busy = false; device = loaded; svdPath = path;
            values.clear(); errors.clear(); watches.clear(); history.clear();
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(Path.of(path))) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
            }
            svdIdentity = java.util.HexFormat.of().formatHex(digest.digest());
            render();
            discoveryExplanation = (exactPackDeviceMapping
                    ? "Installed CMSIS pack maps " + selectedBoard + " to SVD model " + device.name()
                    : "SVD device name: " + device.name() + "; exact project part: " + selectedBoard)
                    + ". Register values are read only on explicit halted refresh.";
            status.setText(device.name() + " — " + device.registers().size() + " registers — " + path
                    + ". SVD is not proof of connected silicon identity. Select a safe register and Refresh while halted.");
        } catch (Exception ex) {
            status.setText("SVD not loaded (previous device retained): " + ex.getMessage());
        }
    }
    private Register selected() {
        if (tree.getSelectionCount() == 0) return null;
        var item = tree.getSelection()[0];
        return item.getData() instanceof Register register ? register : null;
    }
    private void render() {
        if (device == null || tree == null || tree.isDisposed()) return;
        Register selected = selected();
        var expanded = new HashSet<String>();
        for (var p : tree.getItems()) {
            if (p.getExpanded()) expanded.add(p.getText());
            for (var r : p.getItems()) if (r.getExpanded()) expanded.add(((Register)r.getData()).key());
        }
        tree.setRedraw(false);
        try {
            tree.removeAll();
            String query = search.getText().toLowerCase(Locale.ROOT).trim();
            var peripherals = new HashMap<String, TreeItem>();
            int matches = 0;
            for (var r : device.registers()) {
                if (!(r.key() + " " + r.description() + " " + r.fields()).toLowerCase(Locale.ROOT).contains(query)) continue;
                if (++matches > 2000) continue;
                var peripheral = peripherals.computeIfAbsent(r.peripheral(), name -> {
                    var item = new TreeItem(tree, SWT.NONE); item.setText(name);
                    item.setExpanded(expanded.contains(name) || !query.isEmpty()); return item;
                });
                var item = new TreeItem(peripheral, SWT.NONE); item.setData(r); item.setData("description", r.description());
                BigInteger value = values.get(r.key());
                String access = !r.readable() ? "Read blocked: " + r.readBlockReason() : r.access();
                if (errors.containsKey(r.key())) access = errors.get(r.key());
                else if (value != null && r.reset() != null && value.xor(r.reset()).and(r.resetMask()).signum() != 0) access += " — differs from reset";
                if (history.changes().stream().anyMatch(change -> change.registerKey().equals(r.key())))
                    access += " — CHANGED since pinned snapshot";
                item.setText(new String[] { (watches.contains(r.key()) ? "★ " : "") + r.name(), "0x" + r.address().toString(16),
                        value == null ? "—" : hex(value, r.size()), (r.reset() == null ? "—" : hex(r.reset(), r.size())), access });
                for (var f : r.fields()) {
                    var field = new TreeItem(item, SWT.NONE); field.setData(r); field.setData("description", f.description());
                    var mask = BigInteger.ONE.shiftLeft(f.width()).subtract(BigInteger.ONE);
                    field.setText(new String[] { f.name(), "[" + (f.offset() + f.width() - 1) + ":" + f.offset() + "]",
                            value == null ? "—" : hex(value.shiftRight(f.offset()).and(mask), f.width()),
                            (r.reset() == null ? "—" : hex(r.reset().shiftRight(f.offset()).and(mask), f.width())),
                            f.access() + (f.readAction().isEmpty() ? "" : " / readAction: " + f.readAction()) });
                }
                item.setExpanded(expanded.contains(r.key()) || !query.isEmpty());
                if (selected != null && selected.key().equals(r.key())) tree.setSelection(item);
            }
            for (var entry : peripherals.entrySet())
                entry.getValue().setExpanded(expanded.contains(entry.getKey()) || !query.isEmpty());
            summary.setText("Showing " + Math.min(matches, 2000) + " of " + matches
                    + " matching registers. " + (matches > 2000 ? "Narrow the search to see more. " : "")
                    + watches.size() + " watches; " + history.samples().size()
                    + " captured register snapshots (max " + RegisterHistory.LIMIT + ")."
                    + (history.baseline() == null ? " No baseline pinned."
                            : " Baseline: " + history.baseline().timestamp() + ".")
                    + " No automatic polling.");
        } finally { tree.setRedraw(true); }
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
    private void refresh() {
        if (device == null) {
            status.setText("Load the exact chip's vendor SVD first. Native CPU registers do not require an SVD; use the Registers view.");
            return;
        }
        if (busy) { status.setText("A register refresh is already in progress."); return; }
        var chosen = selected();
        var targets = device.registers().stream().filter(r -> watches.contains(r.key()) || r.equals(chosen)).toList();
        if (targets.isEmpty()) { status.setText("Select a register or add a watch first."); return; }
        if (targets.size() > 64) { status.setText("Refresh is limited to 64 watches. Remove some watches first."); return; }
        var context = debugContext();
        var session = context == null ? null : DsfSession.getSession(context.getSessionId());
        if (session == null) { status.setText("Select a suspended stack frame in the native Debug view."); return; }
        busy = true; long request = ++generation;
        status.setText("Reading selected register and watches…");
        try {
            session.getExecutor().execute(() -> {
                var tracker = new DsfServicesTracker(Activator.getDefault().getBundle().getBundleContext(), session.getId());
                var run = tracker.getService(IRunControl.class);
                var expressions = tracker.getService(IExpressions.class);
                var execution = DMContexts.getAncestorOfType(context, IRunControl.IExecutionDMContext.class);
                if (run == null || expressions == null || execution == null || !run.isSuspended(execution)) {
                    tracker.dispose(); finish(request, "Target must be halted; select a suspended stack frame."); return;
                }
                tracker.dispose();
                // Drop DSF expression caches so repeated reads observe the target.
                if (expressions instanceof ICachingService cache) cache.flushCache(context);
                readNext(session, expressions, run, execution, context, targets, 0, request);
            });
        } catch (RejectedExecutionException ex) { finish(request, "Debug session has ended."); }
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
            status.setText("No register snapshots to export. Refresh selected + watches first.");
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
        closed = true; generation++;
        listenTo(null);
        if (contextListener != null) DebugUITools.getDebugContextManager()
                .getContextService(getSite().getWorkbenchWindow()).removeDebugContextListener(contextListener);
        super.dispose();
    }
}
