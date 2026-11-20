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
                    || page.findView("org.eclipse.ui.console.ConsoleView") == null
                    || page.findView("org.eclipse.ui.views.ProblemView") == null)
                throw new IllegalStateException("Developer workspace views not visible.");
            // Invoke the registered command through the real Eclipse handler to
            // migrate any saved layout and restore the distinct dashboard.
            var commands = window.getService(org.eclipse.ui.handlers.IHandlerService.class);
            if (commands == null)
                throw new IllegalStateException("Eclipse handler service unavailable.");
            try {
                commands.executeCommand("io.github.niwantha33.zew.developerPerspective", null);
            } catch (Exception error) {
                throw new IllegalStateException("Developer Workspace command failed.", error);
            }
            var home = page.findView("io.github.niwantha33.zew.ui.developerHome");
            if (home == null || !page.isPartVisible(home))
                throw new IllegalStateException("Development dashboard not visible.");
            var duplicate = page.findView("io.github.niwantha33.zew.ui.beginnerGuide");
            if (duplicate != null && page.isPartVisible(duplicate))
                throw new IllegalStateException("Beginner guide duplicated in developer layout.");
            // Closing and explicitly selecting the workspace must restore its
            // dashboard, without performing any build, flash or target IO.
            page.hideView(home);
            try {
                commands.executeCommand("io.github.niwantha33.zew.developerPerspective", null);
            } catch (Exception error) {
                throw new IllegalStateException("Developer dashboard restore failed.", error);
            }
            home = page.findView("io.github.niwantha33.zew.ui.developerHome");
            if (home == null || !page.isPartVisible(home))
                throw new IllegalStateException("Development dashboard did not reopen.");
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
            System.out.println("PASS ZEPHYR WORKSPACES: distinct dashboard and Board Browser registered");
        } finally {
            if (previous != null) page.setPerspective(previous);
        }
    }
}
