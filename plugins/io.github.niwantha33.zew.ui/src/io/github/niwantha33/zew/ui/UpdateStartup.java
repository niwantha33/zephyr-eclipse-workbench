// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.ui.IStartup;

public final class UpdateStartup implements IStartup {
    @Override public void earlyStartup() {
        var job = new Job("Zephyr workbench update schedule") {
            @Override public boolean belongsTo(Object family) { return family == UpdateController.FAMILY; }
            @Override protected IStatus run(IProgressMonitor monitor) {
                if (!monitor.isCanceled() && Activator.getDefault() != null) {
                    var preferences = Activator.getDefault().getPreferenceStore();
                    if (preferences.getBoolean(UpdateConnection.AUTO) && preferences.getBoolean(UpdateConnection.CONFIGURED)) UpdateController.check(false);
                    if (!monitor.isCanceled() && Activator.getDefault() != null) schedule(6 * 60 * 60 * 1000L);
                }
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.schedule(20_000);
    }
}
