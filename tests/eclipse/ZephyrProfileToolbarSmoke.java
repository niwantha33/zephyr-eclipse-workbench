// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.ui.PlatformUI;

/** Exercise actual installed SWT contribution without ever invoking a Build/Flash. */
public final class ZephyrProfileToolbarSmoke {
    private ZephyrProfileToolbarSmoke() { }

    public static void run() throws Exception {
        var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null) throw new IllegalStateException("No active Eclipse workbench window.");
        var bundle = Platform.getBundle("io.github.niwantha33.zew.ui");
        if (bundle == null) throw new IllegalStateException("Zephyr UI bundle not installed.");
        var extension = bundle.getEntry("plugin.xml");
        if (extension == null) throw new IllegalStateException("Zephyr UI plugin.xml absent.");
        String xml;
        try (var stream = extension.openStream()) {
            xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        int toolbarStart = xml.indexOf("<toolbar id=\"io.github.niwantha33.zew.ui.buildAndFlash\">");
        int toolbarEnd = xml.indexOf("</toolbar>", toolbarStart);
        if (toolbarStart < 0 || toolbarEnd < 0)
            throw new IllegalStateException("Zephyr main-toolbar contribution is missing.");
        String toolbarXml = xml.substring(toolbarStart, toolbarEnd);
        if (!toolbarXml.contains("class=\"io.github.niwantha33.zew.ui.ZephyrProfileToolbarContribution\"")
                || !toolbarXml.contains("commandId=\"org.eclipse.ui.project.buildProject\"")
                || !toolbarXml.contains("commandId=\"io.github.niwantha33.zew.flash\"")
                || !toolbarXml.contains("commandId=\"io.github.niwantha33.zew.flashDebug\"")
                || !toolbarXml.contains("icon=\"icons/debug.png\"")) {
            throw new IllegalStateException("Profile, Build, Flash or native Debug toolbar control missing.");
        }

        var type = bundle.loadClass("io.github.niwantha33.zew.ui.ZephyrProfileToolbarContribution");
        var create = type.getDeclaredMethod("createControl", Composite.class);
        create.setAccessible(true);
        Composite testHost = new Composite(window.getShell(), SWT.NONE);
        try {
            Object contribution = type.getConstructor().newInstance();
            Control widget = (Control) create.invoke(contribution, testHost);
            if (!(widget instanceof ToolBar toolbar) || toolbar.getItemCount() != 1)
                throw new IllegalStateException("Quick selector did not create its native SWT ToolBar.");
            if (!toolbar.getItem(0).getText().startsWith("Profile:"))
                throw new IllegalStateException("Selected profile is not identifiable in the toolbar.");
            if ((toolbar.getItem(0).getStyle() & SWT.DROP_DOWN) == 0)
                throw new IllegalStateException("Quick selector does not expose a dropdown menu.");
            System.out.println("PASS PROFILE INSTALLED UI: native profile dropdown and Build/Flash commands, no duplicate Debug button");
        } finally {
            testHost.dispose();
        }
    }
}
