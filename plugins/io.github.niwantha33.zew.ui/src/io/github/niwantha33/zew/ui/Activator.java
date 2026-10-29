// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

public final class Activator extends AbstractUIPlugin {
    public static final String ID = "io.github.niwantha33.zew.ui";
    private static Activator instance;
    private final ZephyrProjectLifecycle projects = new ZephyrProjectLifecycle();
    @Override public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
        org.eclipse.core.resources.ResourcesPlugin.getWorkspace().addResourceChangeListener(projects,
                org.eclipse.core.resources.IResourceChangeEvent.PRE_BUILD);
        getPreferenceStore().setDefault(UpdateConnection.AUTO, true);
    }
    @Override public void stop(BundleContext context) throws Exception {
        org.eclipse.core.resources.ResourcesPlugin.getWorkspace().removeResourceChangeListener(projects);
        WorkbenchHandler.cancelCurrent();
        org.eclipse.core.runtime.jobs.Job.getJobManager().cancel(UpdateController.FAMILY);
        instance = null;
        super.stop(context);
    }
    public static Activator getDefault() { return instance; }
}
