// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.ui.IFolderLayout;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IPerspectiveFactory;

/** Coding-focused workspace with a compact development dashboard, not the beginner guide. */
public final class ZephyrDeveloperPerspective implements IPerspectiveFactory {
    public static final String ID = Activator.ID + ".developerPerspective";

    @Override public void createInitialLayout(IPageLayout layout) {
        String editor = layout.getEditorArea();
        layout.setEditorAreaVisible(true);
        IFolderLayout projects = layout.createFolder(
                "zew.developer.projects", IPageLayout.LEFT, 0.23f, editor);
        projects.addView("org.eclipse.ui.navigator.ProjectExplorer");
        IFolderLayout output = layout.createFolder(
                "zew.developer.output", IPageLayout.BOTTOM, 0.75f, editor);
        output.addView("org.eclipse.ui.console.ConsoleView");
        output.addView("org.eclipse.ui.views.ProblemView");

        // Keep the editor dominant while showing helpful project actions when no file is open.
        IFolderLayout guide = layout.createFolder(
                "zew.developer.home", IPageLayout.RIGHT, 0.70f, editor);
        guide.addView(ZephyrDeveloperHomeView.ID);

        layout.addShowViewShortcut(ZephyrDeveloperHomeView.ID);
        layout.addShowViewShortcut(ZephyrBeginnerView.ID);
        layout.addShowViewShortcut(QuickHelpView.ID);
        layout.addShowViewShortcut(ConfigurationHomeView.ID);
        layout.addShowViewShortcut(HardwareStudioView.ID);
        layout.addShowViewShortcut(WorkflowStudioView.ID);
        layout.addShowViewShortcut(EmbeddedHubBoardCatalogueView.ID);
        layout.addShowViewShortcut(HardwareStudioView.ID);
        layout.addShowViewShortcut(Activator.ID + ".dtsGraph");
        layout.addShowViewShortcut(SocRegistersHandler.VIEW_ID);
        layout.addShowViewShortcut(ZephyrTraceConsoleView.ID);
        layout.addNewWizardShortcut("io.github.niwantha33.zew.newProject");
        layout.addPerspectiveShortcut(ZephyrBeginnerPerspective.ID);
        layout.addPerspectiveShortcut(ZephyrHardwareDebugPerspective.ID);
    }
}
