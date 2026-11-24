// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;

/** Installed-Eclipse view/learning-layout smoke; no network or project required. */
public final class QuickHelpSmoke {
    private QuickHelpSmoke() {}

    public static void run() throws Exception {
        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        String id = "io.github.niwantha33.zew.ui.quickHelp";
        var view = page.showView(id);
        if (view == null || !id.equals(view.getSite().getId()))
            throw new AssertionError("Offline Quick Help view extension was not loaded");
        var tabField = view.getClass().getDeclaredField("detailTabs");
        tabField.setAccessible(true);
        var tabs = (TabFolder) tabField.get(view);
        if (tabs == null || tabs.isDisposed() || tabs.getItemCount() != 3
                || !"Overview".equals(tabs.getItem(0).getText())
                || !"C example".equals(tabs.getItem(1).getText())
                || !"API reference".equals(tabs.getItem(2).getText()))
            throw new AssertionError("Quick Help must expose three readable learning tabs");
        var searchField = view.getClass().getDeclaredField("search");
        searchField.setAccessible(true);
        var search = (Text) searchField.get(view);
        if (search == null || search.isDisposed() || !search.getMessage().contains("create_task"))
            throw new AssertionError("Quick Help task search is missing");
        var codeField = view.getClass().getDeclaredField("example");
        codeField.setAccessible(true);
        var code = (StyledText) codeField.get(view);
        if (code == null || code.isDisposed() || (code.getStyle() & SWT.READ_ONLY) == 0)
            throw new AssertionError("Quick Help code should be a read-only, styled editor");
        view.setFocus();
        page.hideView(view);
    }
}
