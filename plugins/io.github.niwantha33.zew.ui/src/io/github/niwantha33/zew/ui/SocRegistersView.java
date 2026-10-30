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
import io.github.niwantha33.zew.core.SvdDevice;
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
    private final Map<String, BigInteger> values = new HashMap<>();
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
        button(bar, "Load SVD…", () -> {
            var dialog = new FileDialog(parent.getShell(), SWT.OPEN);
            dialog.setFilterExtensions(new String[] { "*.svd;*.xml", "*.*" });
            String path = dialog.open();
            if (path != null) load(path);
        });
        button(bar, "Refresh selected + watches", this::refresh);
        button(bar, "Watch / unwatch", () -> {
            var selected = selected();
            if (selected != null) {
                if (!watches.remove(selected.key())) watches.add(selected.key());
                render();
            }
        });
        search = new Text(bar, SWT.SEARCH | SWT.ICON_SEARCH | SWT.CANCEL);
        search.setMessage("Find peripheral, register or field");
        search.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        search.addModifyListener(e -> render());
        status = new Label(parent, SWT.WRAP);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        status.setText("Load the exact chip's vendor SVD to show named peripherals. This view works with the native Zephyr DSF debugger; an unrelated Peripherals view may remain empty.");
        contextInfo = new Label(parent, SWT.WRAP);
        contextInfo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        contextInfo.setText("No selected debug frame. CPU registers, Variables and Expressions are separate native Debug views.");
        summary = new Label(parent, SWT.NONE);
        summary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        summary.setText("No SVD model loaded. Use Load SVD…; chip/package models are never guessed or downloaded.");
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
        if (svdPath != null) load(svdPath);
        contextChanged();
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
        status.setText(device == null
                ? "Load the exact chip's vendor SVD. No peripheral model is automatically matched to this debug target."
                : "Debug context changed. Confirm model " + device.name() + " matches this target, then select a register and Refresh.");
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
    private void load(String path) {
        // Bound document size before the DOM allocation; vendor files are loaded offline.
        try {
            if (Files.size(Path.of(path)) > 32 * 1024 * 1024) throw new IllegalArgumentException("SVD exceeds 32 MiB");
            SvdDevice loaded;
            try (var input = Files.newInputStream(Path.of(path))) { loaded = SvdDevice.read(input); }
            generation++; busy = false; device = loaded; svdPath = path;
            values.clear(); errors.clear(); watches.clear(); render();
            status.setText(device.name() + " — " + device.registers().size() + " registers — " + path
                    + ". Confirm this SVD matches the physical chip. Select a register and Refresh.");
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
                    + watches.size() + " watches. Snapshot values; no automatic polling.");
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
            if (!tree.isDisposed() && request == generation) { busy = false; status.setText(message); }
        });
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
