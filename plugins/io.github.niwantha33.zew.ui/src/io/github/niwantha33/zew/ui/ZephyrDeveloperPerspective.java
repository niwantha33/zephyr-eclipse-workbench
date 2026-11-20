// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.ui.IFolderLayout;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IPerspectiveFactory;

/** Developer workspace with visible task guidance; technical views stay on demand. */
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

        // Avoid an unexplained empty centre in workspaces with no source editor.
        // The guide may be closed by the user and does not execute any command.
        IFolderLayout guide = layout.createFolder(
                "zew.developer.guide", IPageLayout.RIGHT, 0.53f, editor);
        guide.addView(ZephyrBeginnerView.ID);

        layout.addShowViewShortcut(ZephyrBeginnerView.ID);
        layout.addShowViewShortcut(ConfigurationHomeView.ID);
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
