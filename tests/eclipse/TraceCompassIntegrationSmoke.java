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
            // In Eclipse the perspective alone is insufficient: a usable
            // CTF reader bundle must also be installed and resolvable.
            var bridge = org.eclipse.core.runtime.Platform.getBundle(
                    "io.github.niwantha33.zew.ui").loadClass(
                    "io.github.niwantha33.zew.ui.TraceCompassNativeBridge");
            var isInstalled = bridge.getDeclaredMethod("installed");
            isInstalled.setAccessible(true);
            boolean installed = Boolean.TRUE.equals(isInstalled.invoke(null));
            var availability = bridge.getDeclaredMethod("availability");
            availability.setAccessible(true);
            Object problem = availability.invoke(null);
            if (installed != (problem == null))
                throw new AssertionError("Trace Compass installed state and capability diagnosis disagree");
            var traceOpen = bridge.getDeclaredMethod("open",
                    java.nio.file.Path.class, org.eclipse.swt.widgets.Shell.class);
            if (!java.lang.reflect.Modifier.isStatic(traceOpen.getModifiers()))
                throw new AssertionError("Direct installed Trace Compass CTF opener missing");
            // Passive smoke never calls open: that would link into the workspace.

            if (button.isEnabled() != installed)
                throw new AssertionError("Tracing perspective must reflect installed plug-ins, "
                        + "not a bundled dependency or an assumed installation");
            var installField = type.getDeclaredField("installInEclipse");
            installField.setAccessible(true);
            var installButton = (Button) installField.get(view);
            if (!installButton.getText().contains("Install Trace Compass")
                    || installButton.isEnabled() == installed)
                throw new AssertionError("Native p2 install must be offered only when Trace Compass is absent");
            var nativeFallbackField = type.getDeclaredField("inspectWorkbench");
            nativeFallbackField.setAccessible(true);
            var fallback = (Button) nativeFallbackField.get(view);
            if (fallback.isEnabled() || !fallback.getText().contains("Workbench"))
                throw new AssertionError("Native CTF viewer must be visible but not load until a trace is selected");
            var classify = type.getDeclaredMethod("classifyHandoff",
                    String.class, int.class, boolean.class);
            classify.setAccessible(true);
            String missingViewer = "{\"capture_full\": true, \"decode\": \"validated\", "
                    + "\"status\": \"viewer-unavailable\"}";
            if (!"viewer-unavailable".equals(classify.invoke(null, missingViewer, 2, false)))
                throw new AssertionError("The expected missing optional executable must be a handled state");
            if (!"launch-requested".equals(classify.invoke(
                    null, "{\"status\": \"launch-requested\"}", 0, false)))
                throw new AssertionError("Verified standalone launch request must remain supported");
            for (var rejected : new Object[][] {
                    {missingViewer, 0, false}, // cannot claim a valid launch
                    {missingViewer, 2, true},  // partial receipt cannot be trusted
                    {"{\"status\": \"launch-requested\"}", 2, false},
                    {"{\"status\": \"viewer-unavailable\", \"status\": \"launch-requested\"}", 2, false},
                    {"{\"status\": \"blocked\"}", 3, false},
            }) {
                boolean failedClosed = false;
                try {
                    classify.invoke(null, rejected);
                } catch (java.lang.reflect.InvocationTargetException exception) {
                    failedClosed = exception.getCause() instanceof java.io.IOException;
                }
                if (!failedClosed)
                    throw new AssertionError("Invalid Trace Compass bridge state must fail closed");
            }
            // Do NOT click: smoke must not open a wizard or download software.
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
