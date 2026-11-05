// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import io.github.niwantha33.zew.core.ConfigurationStudioDiagnostics;
import java.nio.file.Path;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

/** Publishes only source observations; never overwrites compiler or user markers. */
public final class ConfigurationStudioProblems {
    public static final String TYPE = Activator.ID + ".studioProblem";
    public static final String RULE_ID = "zew.ruleId", SOURCE_SHA = "zew.sourceSha256";
    private ConfigurationStudioProblems() {}

    public static void replace(IProject project, ConfigurationStudioDiagnostics.Report report,
            IProgressMonitor monitor) throws CoreException {
        if (project == null || !project.isAccessible() || project.getLocation() == null
                || report == null || monitor.isCanceled()) return;
        Path root = project.getLocation().toFile().toPath().toAbsolutePath().normalize();
        if (!root.equals(report.application().toAbsolutePath().normalize())) return;
        ResourcesPlugin.getWorkspace().run(progress -> {
            if (progress.isCanceled()) return;
            project.deleteMarkers(TYPE, false, IResource.DEPTH_INFINITE);
            for (var finding : report.findings()) {
                if (progress.isCanceled()) return;
                IResource resource = project;
                boolean currentSource = finding.source() != null
                        && finding.source().toAbsolutePath().normalize().startsWith(root)
                        && ConfigurationStudioDiagnostics.sourceMatches(finding);
                if (currentSource) {
                    var source = project.getFile(root.relativize(
                            finding.source().toAbsolutePath().normalize()).toString());
                    if (source.exists()) resource = source;
                }
                IMarker marker = resource.createMarker(TYPE);
                marker.setAttribute(IMarker.MESSAGE, "[" + finding.ruleId() + "] "
                        + finding.message() + " " + finding.nextAction()
                        + (currentSource || finding.source() == null ? ""
                                : " Recorded source changed; refresh diagnosis before navigating."));
                marker.setAttribute(IMarker.SEVERITY, "warning".equals(finding.severity())
                        ? IMarker.SEVERITY_WARNING : IMarker.SEVERITY_INFO);
                marker.setAttribute(RULE_ID, finding.ruleId());
                marker.setAttribute(SOURCE_SHA, finding.sourceSha());
                marker.setAttribute("zew.confidence", finding.confidence());
                marker.setAttribute("zew.build", report.build() == null ? "" : report.build().toString());
                if (resource != project && currentSource) {
                    marker.setAttribute(IMarker.LINE_NUMBER, finding.line());
                    marker.setAttribute(IMarker.LOCATION, resource.getProjectRelativePath()
                            + ":" + finding.line());
                }
            }
        }, project, IWorkspace.AVOID_UPDATE, monitor);
    }
}
