// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import org.eclipse.ui.PlatformUI;

/** Native installed-workbench verification; no target operations are invoked. */
public final class ZephyrBeginnerPerspectiveSmoke {
    private ZephyrBeginnerPerspectiveSmoke() { }

    public static void run() {
        var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null || window.getActivePage() == null)
            throw new IllegalStateException("No active Eclipse workbench page.");
        var registry = window.getWorkbench().getPerspectiveRegistry();
        var beginner = registry.findPerspectiveWithId(
                "io.github.niwantha33.zew.ui.beginnerPerspective");
        var advanced = registry.findPerspectiveWithId(
                "io.github.niwantha33.zew.ui.hardwareDebugPerspective");
        if (beginner == null || advanced == null)
            throw new IllegalStateException("Zephyr beginner or debug perspective missing.");
        var page = window.getActivePage();
        var previous = page.getPerspective();
        try {
            page.setPerspective(beginner);
            if (page.findView("org.eclipse.ui.navigator.ProjectExplorer") == null
                    || page.findView("io.github.niwantha33.zew.ui.beginnerGuide") == null
                    || page.findView("org.eclipse.ui.console.ConsoleView") == null
                    || page.findView("org.eclipse.ui.views.ProblemView") == null)
                throw new IllegalStateException("Beginner essential views not visible.");
            if (page.findView("io.github.niwantha33.zew.ui.socRegisters") != null)
                throw new IllegalStateException("Advanced registers opened in beginner layout.");
            System.out.println("PASS BEGINNER PERSPECTIVE: essential views only; advanced registered");
        } finally {
            if (previous != null) page.setPerspective(previous);
        }
    }
}
