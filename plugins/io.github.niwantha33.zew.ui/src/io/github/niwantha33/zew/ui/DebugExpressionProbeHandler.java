// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.cdt.dsf.datamodel.DMContexts;
import org.eclipse.cdt.dsf.debug.service.IExpressions;
import org.eclipse.cdt.dsf.ui.viewmodel.datamodel.IDMVMContext;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.debug.core.model.IExpression;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;

/** Open the read-only probe, prefilled from a native Expressions row when available. */
public final class DebugExpressionProbeHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        var window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window == null || window.getActivePage() == null) return null;
        try {
            String selected = "";
            var active = window.getActivePage().getActivePart();
            // Never steal an unrelated Project Explorer/editor selection.
            if (active != null && IDebugUIConstants.ID_EXPRESSION_VIEW.equals(active.getSite().getId())) {
                var selection = window.getSelectionService().getSelection(IDebugUIConstants.ID_EXPRESSION_VIEW);
                if (selection instanceof IStructuredSelection rows && rows.size() == 1) {
                    Object item = rows.getFirstElement();
                    if (item instanceof IExpression expression) {
                        selected = expression.getExpressionText();
                    } else {
                        IDMVMContext vm = item instanceof IDMVMContext context ? context
                                : item instanceof IAdaptable adaptable
                                ? adaptable.getAdapter(IDMVMContext.class) : null;
                        if (vm != null) {
                            var expression = DMContexts.getAncestorOfType(
                                    vm.getDMContext(), IExpressions.IExpressionDMContext.class);
                            if (expression != null) selected = expression.getExpression();
                        }
                    }
                }
            }
            var view = window.getActivePage().showView(DebugExpressionProbeView.ID);
            if (!selected.isBlank() && view instanceof DebugExpressionProbeView probe)
                probe.inspectSelection(selected);
        } catch (org.eclipse.ui.PartInitException error) {
            throw new ExecutionException("Cannot open Zephyr Expression Probe", error);
        }
        return null;
    }
}
