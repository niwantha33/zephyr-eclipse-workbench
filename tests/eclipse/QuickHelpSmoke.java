// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;

/** Installed-Eclipse view lifecycle smoke; no network or project required. */
public final class QuickHelpSmoke {
    private QuickHelpSmoke() {}

    public static void run() throws Exception {
        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        String id = "io.github.niwantha33.zew.ui.quickHelp";
        var view = page.showView(id);
        if (view == null || !id.equals(view.getSite().getId()))
            throw new AssertionError("Offline Quick Help view extension was not loaded");
        view.setFocus();
        page.hideView(view);
    }
}
