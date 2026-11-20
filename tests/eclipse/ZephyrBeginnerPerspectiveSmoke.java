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
        var developer = registry.findPerspectiveWithId(
                "io.github.niwantha33.zew.ui.developerPerspective");
        if (beginner == null || developer == null || advanced == null)
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
            var registers = page.findView("io.github.niwantha33.zew.ui.socRegisters");
            if (registers != null && page.isPartVisible(registers))
                throw new IllegalStateException("Advanced registers visible in beginner layout.");
            page.setPerspective(developer);
            if (page.findView("org.eclipse.ui.navigator.ProjectExplorer") == null
                    || page.findView("io.github.niwantha33.zew.ui.beginnerGuide") == null
                    || page.findView("org.eclipse.ui.console.ConsoleView") == null
                    || page.findView("org.eclipse.ui.views.ProblemView") == null)
                throw new IllegalStateException("Developer workspace views not visible.");
            // Invoke the registered command through the same Eclipse service as
            // the Zephyr menu. The installed plug-in does not export its internal
            // Java handler package to this independent OSGi smoke-test bundle.
            // Calling the class directly would fail even when the real menu works.
            page.hideView(page.findView("io.github.niwantha33.zew.ui.beginnerGuide"));
            var commands = window.getService(org.eclipse.ui.handlers.IHandlerService.class);
            if (commands == null)
                throw new IllegalStateException("Eclipse handler service unavailable.");
            try {
                commands.executeCommand("io.github.niwantha33.zew.developerPerspective", null);
            } catch (Exception error) {
                throw new IllegalStateException("Developer Workspace command failed.", error);
            }
            var guide = page.findView("io.github.niwantha33.zew.ui.beginnerGuide");
            if (guide == null || !page.isPartVisible(guide))
                throw new IllegalStateException("Developer Workspace did not restore guide.");
            // A board browser without a selected project must render a safe,
            // usable empty state. The view itself never starts hardware IO.
            try {
                var boards = page.showView("io.github.niwantha33.zew.ui.embeddedHubBoards");
                if (boards == null || !page.isPartVisible(boards))
                    throw new IllegalStateException("Board Browser failed to open.");
                page.hideView(boards);
            } catch (org.eclipse.ui.PartInitException error) {
                throw new IllegalStateException("Board Browser could not initialize.", error);
            }
            System.out.println("PASS ZEPHYR WORKSPACES: guides and Board Browser registered");
        } finally {
            if (previous != null) page.setPerspective(previous);
        }
    }
}
