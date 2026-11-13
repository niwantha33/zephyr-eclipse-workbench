// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.PlatformUI;

/**
 * Optional Trace Compass integration must open in a clean Eclipse installation
 * with no Trace Compass plug-ins, capture, trace viewer, Python or probe.
 */
public final class TraceCompassIntegrationSmoke {
    private TraceCompassIntegrationSmoke() {}

    public static void run() throws Exception {
        var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        var page = window.getActivePage();
        var view = page.showView("io.github.niwantha33.zew.ui.traceCompassIntegration");
        try {
            var type = view.getClass();
            var perspectiveField = type.getDeclaredField("nativePerspective");
            perspectiveField.setAccessible(true);
            var button = (Button) perspectiveField.get(view);
            boolean installed = PlatformUI.getWorkbench().getPerspectiveRegistry()
                    .findPerspectiveWithId("org.eclipse.linuxtools.tmf.ui.perspective") != null;
            if (button.isEnabled() != installed)
                throw new AssertionError("Tracing perspective must reflect installed plug-ins, "
                        + "not a bundled dependency or an assumed installation");
            var installation = type.getDeclaredField("installation");
            installation.setAccessible(true);
            var label = (Label) installation.get(view);
            if (label.getText().isBlank() || !label.getText().contains(
                    installed ? "INSTALLED" : "NOT INSTALLED"))
                throw new AssertionError("Trace Compass state must be explained without probing");
            var statusField = type.getDeclaredField("status");
            statusField.setAccessible(true);
            var status = (Label) statusField.get(view);
            if (!status.getText().equals("No Trace Compass operation started."))
                throw new AssertionError("Opening integration must remain passive");
            var jobField = type.getDeclaredField("handoff");
            jobField.setAccessible(true);
            if (jobField.get(view) != null)
                throw new AssertionError("No standalone viewer may start on page creation");
            var capture = type.getDeclaredField("savedCapture");
            capture.setAccessible(true);
            if (capture.get(view) != null)
                throw new AssertionError("No arbitrary project trace may be preselected");
            System.out.println("TraceCompassIntegrationSmoke PASS: optional plug-in detection and passive launch");
        } finally {
            page.hideView(view);
        }
    }
}
