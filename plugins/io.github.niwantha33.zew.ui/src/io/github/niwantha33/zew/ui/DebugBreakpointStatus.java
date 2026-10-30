// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.cdt.debug.core.model.ICBreakpoint;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;

/** Read-only native breakpoint status; never overrides Skip All or individual enablement. */
final class DebugBreakpointStatus {
    private DebugBreakpointStatus() {}
    static String describe(IProject project) {
        var manager = DebugPlugin.getDefault().getBreakpointManager();
        int enabled = 0, disabled = 0, installed = 0, other = 0;
        for (var breakpoint : manager.getBreakpoints()) {
            var marker = breakpoint.getMarker();
            if (marker == null || !marker.exists() || !project.equals(marker.getResource().getProject())) continue;
            if (!(breakpoint instanceof ICBreakpoint nativeBreakpoint)) { other++; continue; }
            try {
                if (breakpoint.isEnabled()) enabled++; else disabled++;
                if (nativeBreakpoint.isInstalled()) installed++;
            } catch (CoreException ignored) { /* deleted concurrently; never invent installed state */ }
        }
        return "Breakpoint status for " + project.getName() + ": C/C++ enabled=" + enabled
                + ", disabled=" + disabled + ", installed in at least one target=" + installed
                + ", other-model markers=" + other + ".\n"
                + (!manager.isEnabled() ? "ACTION: Eclipse Skip All Breakpoints is ON. Turn it off in Run > Skip All Breakpoints.\n" : "")
                + "Use the Breakpoints view to inspect enablement, conditions, hit counts and pending/error markers. "
                + "An uninstalled marker can mean mismatched source/ELF, no executable instruction on that line, "
                + "or exhausted hardware breakpoint slots. Check GDB's insertion error; Resume reaches the next executable breakpoint. "
                + "A frame selected in the call stack is not itself proof of a breakpoint hit.";
    }
}
