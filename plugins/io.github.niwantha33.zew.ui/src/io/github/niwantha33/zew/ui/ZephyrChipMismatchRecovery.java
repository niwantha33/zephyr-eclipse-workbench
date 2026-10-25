// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.Map;
import java.util.Locale;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.PreferencesUtil;

import io.github.niwantha33.zew.core.EspressifFlashSafety.ChipMismatchException;

/**
 * A mismatch never changes board metadata or flashes firmware by itself.
 * Explicitly open board selection prefiltered by detected SoC; after an
 * accepted target change run a normal managed build only. Flash is a new
 * independently confirmed user action.
 */
final class ZephyrChipMismatchRecovery {
    static final QualifiedName SUGGESTED_SOC =
            new QualifiedName(Activator.ID, "detectedSocHint");

    private ZephyrChipMismatchRecovery() {}

    static void offer(Shell shell, IProject project, ChipMismatchException failure) {
        if (shell == null || shell.isDisposed() || !project.isAccessible()) return;
        String original;
        try {
            original = ProjectContext.settings(project).getProperty("board");
        } catch (Exception error) {
            MessageDialog.openError(shell, "Flash blocked", failure.getMessage());
            return;
        }
        var prompt = new MessageDialog(shell, "Flash blocked — wrong target", null,
                "The project targets " + failure.expectedChip()
                + ", but the connected board identifies as " + failure.connectedChip()
                + ".\n\nNo firmware was written.\n\n"
                + "Choose the exact board/CPU variant. The Workbench can then rebuild"
                + " for that target. It will NOT flash until you explicitly ask again.",
                MessageDialog.WARNING,
                new String[] {"Select Matching Board...", "Cancel"}, 0);
        if (prompt.open() != 0) return;
        String suggested = failure.connectedChip().toLowerCase(Locale.ROOT).replace("-", "");
        try {
            project.setSessionProperty(SUGGESTED_SOC, suggested);
            var dialog = PreferencesUtil.createPropertyDialogOn(shell, project,
                    "io.github.niwantha33.zew.projectProperties", null, null);
            if (dialog == null) throw new java.io.IOException("Zephyr target properties are unavailable.");
            if (dialog.open() != Window.OK) return;
            String selected = ProjectContext.settings(project).getProperty("board");
            if (original.equals(selected)) return;

            // Do not program hardware or silently retry the failed flash.
            // The normal builder provisions exact vendor/compiler dependencies.
            Job job = new Job("Build selected Zephyr target — " + project.getName()) {
                @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                    try {
                        project.build(IncrementalProjectBuilder.FULL_BUILD,
                                Activator.ID + ".zephyrBuilder", Map.of(), monitor);
                        return monitor.isCanceled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
                    } catch (CoreException error) { return error.getStatus(); }
                }
            };
            job.setUser(true);
            job.schedule();
        } catch (Exception error) {
            MessageDialog.openError(shell, "Zephyr target selection", error.getMessage());
        } finally {
            try { project.setSessionProperty(SUGGESTED_SOC, null); }
            catch (CoreException ignored) { /* project may have closed */ }
        }
    }
}
