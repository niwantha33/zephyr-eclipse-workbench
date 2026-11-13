// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.concurrent.RejectedExecutionException;
import org.eclipse.cdt.dsf.concurrent.DataRequestMonitor;
import org.eclipse.cdt.dsf.datamodel.DMContexts;
import org.eclipse.cdt.dsf.datamodel.IDMContext;
import org.eclipse.cdt.dsf.debug.service.ICachingService;
import org.eclipse.cdt.dsf.debug.service.IExpressions;
import org.eclipse.cdt.dsf.debug.service.IFormattedValues;
import org.eclipse.cdt.dsf.debug.service.IRunControl;
import org.eclipse.cdt.dsf.debug.service.IStack;
import org.eclipse.cdt.dsf.service.DsfServicesTracker;
import org.eclipse.cdt.dsf.service.DsfSession;
import org.eclipse.cdt.dsf.ui.viewmodel.datamodel.IDMVMContext;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;
import io.github.niwantha33.zew.core.DebugExpressionProbe;

/**
 * One-shot, manually requested inspection of a C lvalue in a selected halted
 * CDT DSF-GDB frame. No guessed values, variable mutation or automatic reads.
 * Does not call GDB inferior functions, write memory or follow pointers.
 */
public final class DebugExpressionProbeView extends ViewPart {
    public static final String ID = Activator.ID + ".debugExpressionProbe";
    private Text symbol, result;
    private Label status;
    private Button copyAddress;
    private String verifiedAddress = "";
    private Display display;
    private boolean closed;
    private volatile long generation;

    @Override public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        parent.setLayout(new GridLayout(1, false));
        new Label(parent, SWT.WRAP).setText("Read-only GDB variable and member-address probe. "
                + "Select a suspended Zephyr stack frame in Debug first.");
        var toolbar = new Composite(parent, SWT.NONE);
        toolbar.setLayout(new GridLayout(4, false));
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        new Label(toolbar, SWT.NONE).setText("Variable / member");
        symbol = new Text(toolbar, SWT.SINGLE | SWT.BORDER);
        symbol.setMessage("lab_stats.heartbeat");
        symbol.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button inspect = new Button(toolbar, SWT.PUSH);
        inspect.setText("Inspect address + size");
        inspect.addListener(SWT.Selection, e -> inspect());
        copyAddress = new Button(toolbar, SWT.PUSH);
        copyAddress.setText("Copy address");
        copyAddress.setEnabled(false);
        copyAddress.addListener(SWT.Selection, e -> {
            if (verifiedAddress.isEmpty()) return;
            var clipboard = new org.eclipse.swt.dnd.Clipboard(display);
            try {
                clipboard.setContents(new Object[] { verifiedAddress },
                        new org.eclipse.swt.dnd.Transfer[] {
                                org.eclipse.swt.dnd.TextTransfer.getInstance() });
            } finally { clipboard.dispose(); }
        });
        symbol.addListener(SWT.DefaultSelection, e -> inspect());
        status = new Label(parent, SWT.WRAP);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        status.setText("No target data read. Select a suspended stack frame and inspect a variable/member.");
        result = new Text(parent, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        result.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        result.setText("Example: lab_stats.heartbeat → address of the heartbeat member (not the value 16). "
                + "The probe checks value, storage address and sizeof independently. "
                + "A register-only temporary, bitfield or out-of-scope variable may have no address. "
                + "Copy the confirmed address into Eclipse's native Memory view to inspect bytes.");
    }

    private IDMContext selectedFrame() {
        var selection = DebugUITools.getDebugContextManager()
                .getContextService(getSite().getWorkbenchWindow()).getActiveContext();
        Object element = selection instanceof IStructuredSelection selected
                ? selected.getFirstElement() : null;
        if (element instanceof IDMVMContext vm) return vm.getDMContext();
        if (element instanceof IAdaptable adaptable) {
            var adapted = adaptable.getAdapter(IDMVMContext.class);
            if (adapted != null) return adapted.getDMContext();
        }
        return null;
    }

    /** Invoked by the native Expressions popup; invalid complex expressions remain editable. */
    public void inspectSelection(String candidate) {
        if (closed || symbol == null || symbol.isDisposed()) return;
        symbol.setText(candidate == null ? "" : candidate);
        inspect();
    }

    private void inspect() {
        generation++; // immediately invalidate any older in-flight GDB response
        verifiedAddress = "";
        if (copyAddress != null && !copyAddress.isDisposed()) copyAddress.setEnabled(false);
        if (result != null && !result.isDisposed()) result.setText("No current result. Inspection is pending or unavailable.");
        final String name;
        try { name = DebugExpressionProbe.validateSymbol(symbol.getText()); }
        catch (IllegalArgumentException invalid) {
            status.setText(invalid.getMessage());
            return;
        }
        var context = selectedFrame();
        if (context == null || DMContexts.getAncestorOfType(context, IStack.IFrameDMContext.class) == null) {
            status.setText("No selected native GDB frame. Select a suspended stack frame in Debug.");
            return;
        }
        var session = DsfSession.getSession(context.getSessionId());
        if (session == null) {
            status.setText("Selected debug session is no longer available.");
            return;
        }
        long request = ++generation;
        status.setText("Checking " + name + " against the selected halted GDB frame…");
        try {
            session.getExecutor().execute(() -> {
                var tracker = new DsfServicesTracker(Activator.getDefault().getBundle()
                        .getBundleContext(), session.getId());
                try {
                    var run = tracker.getService(IRunControl.class);
                    var expressions = tracker.getService(IExpressions.class);
                    var execution = DMContexts.getAncestorOfType(context, IRunControl.IExecutionDMContext.class);
                    if (run == null || expressions == null || execution == null
                            || !run.isSuspended(execution)) {
                        deliver(request, "Target is not halted in a selected stack frame.", null, "");
                        return;
                    }
                    if (expressions instanceof ICachingService cache) cache.flushCache(context);
                    evaluate(session, run, execution, expressions, context, name, request, 0, "", "", "", "");
                } finally { tracker.dispose(); }
            });
        } catch (RejectedExecutionException ended) {
            deliver(request, "Debug session ended during the request.", null, "");
        }
    }

    private void evaluate(DsfSession session, IRunControl run,
                          IRunControl.IExecutionDMContext execution,
                          IExpressions expressions, IDMContext context, String name,
                          long request, int phase, String value, String valueError,
                          String address, String addressError) {
        if (closed || request != generation) return;
        if (!run.isSuspended(execution)) {
            deliver(request, "Target resumed; earlier data was discarded. Halt and retry.", null, "");
            return;
        }
        // The expression is limited to a validated named C lvalue. No calls,
        // assignments, pointer dereferences or raw user GDB commands are sent.
        String code = switch (phase) {
            case 0 -> name;
            case 1 -> "&(" + name + ")";
            case 2 -> "sizeof(" + name + ")";
            default -> throw new IllegalArgumentException("Unexpected inspection phase");
        };
        var expression = expressions.createExpression(context, code);
        var formatted = expressions.getFormattedValueContext(expression, IFormattedValues.HEX_FORMAT);
        expressions.getFormattedExpressionValue(formatted,
                new DataRequestMonitor<IFormattedValues.FormattedValueDMData>(session.getExecutor(), null) {
            @Override protected void handleCompleted() {
                if (closed || request != generation || !run.isSuspended(execution)) {
                    deliver(request, "Target resumed or selection changed; discard the snapshot.", null, "");
                    return;
                }
                String answer = isSuccess() && getData() != null
                        ? bounded(getData().getFormattedValue()) : "";
                String error = isSuccess() ? "" : bounded(getStatus().getMessage());
                if (phase == 0) {
                    evaluate(session, run, execution, expressions, context, name, request,
                            1, answer, error, "", "");
                } else if (phase == 1) {
                    evaluate(session, run, execution, expressions, context, name, request,
                            2, value, valueError, answer, error);
                } else {
                    String report = DebugExpressionProbe.describe(name, value, address,
                            answer, valueError, addressError, error);
                    deliver(request, "GDB inspection complete for selected halted frame.",
                            report, DebugExpressionProbe.confirmedAddress(address));
                }
            }
        });
    }

    private static String bounded(String text) {
        if (text == null) return "";
        return text.length() > 512 ? text.substring(0, 512) + " [truncated]" : text;
    }

    private void deliver(long request, String message, String report, String address) {
        if (display == null || display.isDisposed()) return;
        display.asyncExec(() -> {
            if (closed || request != generation || status.isDisposed()) return;
            status.setText(message);
            if (report != null) {
                result.setText(report);
                verifiedAddress = address;
                copyAddress.setEnabled(!address.isEmpty());
            } else {
                result.setText("No current snapshot: " + message);
                verifiedAddress = "";
                copyAddress.setEnabled(false);
            }
            status.getParent().layout(true, true);
        });
    }

    @Override public void setFocus() {
        if (symbol != null && !symbol.isDisposed()) symbol.setFocus();
    }
    @Override public void dispose() {
        closed = true;
        generation++;
        super.dispose();
    }
}
