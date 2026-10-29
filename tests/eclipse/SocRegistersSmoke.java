// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import java.nio.file.Files;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.PlatformUI;

/** Run on the workbench UI thread: real SWT view, parser and no-session guard. */
public final class SocRegistersSmoke {
    public static void run() throws Exception {
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        var view = page.showView("io.github.niwantha33.zew.ui.socRegisters");
        var type = view.getClass();
        var load = type.getDeclaredMethod("load", String.class); load.setAccessible(true);
        var fixture = Files.createTempFile("zew-soc-registers-", ".svd");
        try {
            Files.writeString(fixture, "<device><name>SmokeChip</name><size>32</size><peripherals><peripheral>"
                    + "<name>GPIO</name><baseAddress>0x40000000</baseAddress><registers>"
                    + "<register><name>CTRL</name><addressOffset>4</addressOffset><fields>"
                    + "<field><name>EN</name><bitOffset>0</bitOffset><bitWidth>1</bitWidth></field>"
                    + "</fields></register></registers></peripheral></peripherals></device>");
            load.invoke(view, fixture.toString());
            var treeField = type.getDeclaredField("tree"); treeField.setAccessible(true);
            var tree = (Tree) treeField.get(view);
            if (tree.getItemCount() != 1 || tree.getItem(0).getItemCount() != 1)
                throw new AssertionError("SoC tree did not load");
            var register = tree.getItem(0).getItem(0);
            if (!register.getText(1).equals("0x40000004") || register.getItemCount() != 1
                    || !register.getItem(0).getText().equals("EN") || !register.getText(3).equals("—"))
                throw new AssertionError("Register address/field/undefined reset rendering");
            tree.setSelection(register);
            var refresh = type.getDeclaredMethod("refresh"); refresh.setAccessible(true); refresh.invoke(view);
            var statusField = type.getDeclaredField("status"); statusField.setAccessible(true);
            if (!((Label) statusField.get(view)).getText().contains("suspended"))
                throw new AssertionError("No-session read was not rejected");
            var searchField = type.getDeclaredField("search"); searchField.setAccessible(true);
            var search = (Text) searchField.get(view);
            search.setText("not-present");
            if (tree.getItemCount() != 0) throw new AssertionError("Search failed to filter");
            search.setText("EN");
            if (tree.getItemCount() != 1) throw new AssertionError("Field search did not restore register");
            System.out.println("SocRegistersSmoke PASS: view, SVD, fields, search, no-session guard");
        } finally {
            page.hideView(view);
            Files.deleteIfExists(fixture);
        }
    }
}
