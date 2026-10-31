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
 * One-shot, manually requested inspection of a C symbol in a selected halted
 * CDT DSF-GDB frame. No guessed values, variable mutation or automatic reads.
 * Does not call GDB inferior functions or dereference arbitrary memory.
 */
public final class DebugExpressionProbeView extends ViewPart {
    public static final String ID = Activator.ID + ".debugExpressionProbe";
    private Text symbol, result;
    private Label status;
    private Display display;
    private boolean closed;
    private volatile long generation;

    @Override public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        parent.setLayout(new GridLayout(1, false));
        new Label(parent, SWT.WRAP).setText("Read-only single-symbol GDB probe. "
                + "Select a suspended Zephyr thread stack frame in Debug first.");
        var toolbar = new Composite(parent, SWT.NONE);
        toolbar.setLayout(new GridLayout(3, false));
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        new Label(toolbar, SWT.NONE).setText("C symbol");
        symbol = new Text(toolbar, SWT.SINGLE | SWT.BORDER);
        symbol.setMessage("gpio_dev");
        symbol.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button inspect = new Button(toolbar, SWT.PUSH);
        inspect.setText("Check value + address");
        inspect.addListener(SWT.Selection, e -> inspect());
        symbol.addListener(SWT.DefaultSelection, e -> inspect());
        status = new Label(parent, SWT.WRAP);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        status.setText("No target data read. Select a suspended stack frame.");
        result = new Text(parent, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        result.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        result.setText("A global variable should not be diagnosed as a dead local merely because "
                + "CDT Expressions shows <optimized out>. Probe both its value and storage address, "
                + "then check debug ELF symbols and source/compiler identity if unavailable.");
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

    private void inspect() {
        final String name;
        try { name = DebugExpressionProbe.validateSymbol(symbol.getText()); }
        catch (IllegalArgumentException invalid) {
            status.setText(invalid.getMessage());
            return;
        }
        var context = selectedFrame();
        if (context == null) {
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
                        deliver(request, "Target is not halted in a selected stack frame.", null);
                        return;
                    }
                    if (expressions instanceof ICachingService cache) cache.flushCache(context);
                    evaluate(session, run, execution, expressions, context, name, request, false, "", "");
                } finally { tracker.dispose(); }
            });
        } catch (RejectedExecutionException ended) {
            deliver(request, "Debug session ended during the request.", null);
        }
    }

    private void evaluate(DsfSession session, IRunControl run,
                          IRunControl.IExecutionDMContext execution,
                          IExpressions expressions, IDMContext context, String name,
                          long request, boolean addressPhase, String value, String valueError) {
        if (closed || request != generation) return;
        if (!run.isSuspended(execution)) {
            deliver(request, "Target resumed; earlier data was discarded. Halt and retry.", null);
            return;
        }
        // Only a validated identifier or address-of identifier is evaluated.
        var expression = expressions.createExpression(context, addressPhase ? "&" + name : name);
        var formatted = expressions.getFormattedValueContext(expression, IFormattedValues.HEX_FORMAT);
        expressions.getFormattedExpressionValue(formatted,
                new DataRequestMonitor<IFormattedValues.FormattedValueDMData>(session.getExecutor(), null) {
            @Override protected void handleCompleted() {
                String answer = isSuccess() && getData() != null
                        ? bounded(getData().getFormattedValue()) : "";
                String error = isSuccess() ? "" : bounded(getStatus().getMessage());
                if (!addressPhase) {
                    evaluate(session, run, execution, expressions, context, name, request,
                            true, answer, error);
                    return;
                }
                String report = DebugExpressionProbe.describe(name, value, answer, valueError, error);
                deliver(request, "Probe completed for selected suspended frame.", report);
            }
        });
    }

    private static String bounded(String text) {
        if (text == null) return "";
        return text.length() > 512 ? text.substring(0, 512) + " [truncated]" : text;
    }

    private void deliver(long request, String message, String report) {
        if (display == null || display.isDisposed()) return;
        display.asyncExec(() -> {
            if (closed || request != generation || status.isDisposed()) return;
            status.setText(message);
            if (report != null) result.setText(report);
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
