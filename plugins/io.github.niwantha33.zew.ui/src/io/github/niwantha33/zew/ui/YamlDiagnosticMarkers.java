// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.function.BooleanSupplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;

/**
 * Publishes only independently verified, temporary YAML validation markers.
 *
 * No source writes. Do not touch CDT, build, or other diagnostic marker types.
 * Run on an Eclipse Job, not the SWT UI thread; workspace rule serializes
 * the remove-and-replace operation.
 */
final class YamlDiagnosticMarkers {
    static final String TYPE = Activator.ID + ".yamlProblem";

    private YamlDiagnosticMarkers() {}

    /**
     * Clears only our own markers from the previous source, then replaces our
     * markers on the new source if this inspection is still current.
     * Returns false when the generation was superseded before mutation.
     */
    static boolean replace(IFile previous, IFile source,
                           YamlDiagnosticModel.Summary summary,
                           BooleanSupplier current) throws CoreException {
        final boolean[] applied = {false};
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        workspace.run((IWorkspaceRunnable) monitor -> {
            if (!current.getAsBoolean()) return;
            if (previous != null && previous.exists() && !previous.equals(source))
                previous.deleteMarkers(TYPE, false, IResource.DEPTH_ZERO);
            if (source == null || !source.exists()) {
                applied[0] = true;
                return;
            }
            source.deleteMarkers(TYPE, false, IResource.DEPTH_ZERO);
            if (summary != null && "invalid".equals(summary.state())) {
                for (YamlDiagnosticModel.Finding issue : summary.findings()) {
                    if (!current.getAsBoolean()) return;
                    // Unknown line/column is shown in the view but not marked
                    // at an invented position in the Eclipse text editor.
                    if (!issue.located()) continue;
                    IMarker marker = source.createMarker(TYPE);
                    marker.setAttribute(IMarker.MESSAGE, issue.message());
                    marker.setAttribute(IMarker.SEVERITY,
                            "error".equals(issue.severity())
                                    ? IMarker.SEVERITY_ERROR : IMarker.SEVERITY_WARNING);
                    marker.setAttribute(IMarker.LINE_NUMBER, issue.line());
                    marker.setAttribute(IMarker.LOCATION,
                            "line " + issue.line() + ", column " + issue.column());
                    marker.setAttribute(IMarker.SOURCE_ID, "zew.yaml-diagnostics/1");
                }
            }
            applied[0] = true;
        }, workspace.getRoot(), IWorkspace.AVOID_UPDATE, null);
        return applied[0];
    }
}
