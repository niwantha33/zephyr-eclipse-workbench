// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Path;
import java.util.regex.Pattern;
import org.eclipse.core.resources.*;

/** Own only our markers; never delete CDT or user diagnostics. */
final class BuildDiagnostics {
    static final String TYPE = Activator.ID + ".buildProblem";
    private static final Pattern COMPILER = Pattern.compile("^(.+?):(\\d+)(?::(\\d+))?:\\s*(fatal error|error|warning):\\s*(.*)$");
    static void clear(IProject project) throws Exception { project.deleteMarkers(TYPE, true, IResource.DEPTH_INFINITE); }
    static void accept(IProject project, Path build, String line) {
        var match = COMPILER.matcher(line); if (!match.matches()) return;
        try {
            Path root = project.getLocation().toFile().toPath().toAbsolutePath().normalize();
            Path source = Path.of(match.group(1)); if (!source.isAbsolute()) source = build.resolve(source);
            source = source.toAbsolutePath().normalize();
            IResource resource = project;
            if (source.startsWith(root)) {
                var file = project.getFile(root.relativize(source).toString());
                if (file.exists()) resource = file;
            }
            IMarker marker = resource.createMarker(TYPE);
            marker.setAttribute(IMarker.MESSAGE, match.group(5));
            marker.setAttribute(IMarker.SEVERITY, match.group(4).equals("warning") ? IMarker.SEVERITY_WARNING : IMarker.SEVERITY_ERROR);
            marker.setAttribute(IMarker.LINE_NUMBER, Integer.parseInt(match.group(2)));
            marker.setAttribute(IMarker.LOCATION, match.group(1) + ":" + match.group(2));
        } catch (Exception ignored) { /* Console retains complete output when a diagnostic cannot be mapped. */ }
    }
}
