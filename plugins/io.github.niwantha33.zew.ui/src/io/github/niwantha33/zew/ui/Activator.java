// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

public final class Activator extends AbstractUIPlugin {
    public static final String ID = "io.github.niwantha33.zew.ui";
    private static Activator instance;
    @Override public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
    }
    @Override public void stop(BundleContext context) throws Exception {
        WorkbenchHandler.cancelCurrent();
        instance = null;
        super.stop(context);
    }
    public static Activator getDefault() { return instance; }
}
