// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;

/**
 * Installed SWT smoke for the passive Zephyr Debug Trace Console.
 * Does not connect to a UART, probe, RTT server, or reset firmware.
 */
public final class TraceConsoleSmoke {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void run() throws Exception {
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        var view = page.showView("io.github.niwantha33.zew.ui.traceConsole");
        try {
            var type = view.getClass();
            var contextField = type.getDeclaredField("context");
            contextField.setAccessible(true);
            var context = (Label) contextField.get(view);
            check(!context.isDisposed() && !context.getText().isBlank(),
                    "Trace Console must explain missing or discovered project context");

            var tableField = type.getDeclaredField("transports");
            tableField.setAccessible(true);
            var table = (Table) tableField.get(view);
            check(table.getItemCount() == 5, "All five trace backends are listed");

            var outputField = type.getDeclaredField("output");
            outputField.setAccessible(true);
            var output = (Text) outputField.get(view);
            check(output.getText().contains("host receive timestamps"),
                    "The trace text pane must not claim target-synchronised timestamps");

            var resolve = type.getDeclaredMethod("uniqueLiveDebugProject");
            resolve.setAccessible(true);
            // No native Zephyr debug launch has been started in this smoke.
            check(resolve.invoke(view) == null,
                    "The Trace Console must not select a random workspace project without a live launch");
            System.out.println("TraceConsoleSmoke PASS: passive view, five backends and no arbitrary project binding");
        } finally {
            page.hideView(view);
        }
    }
}
