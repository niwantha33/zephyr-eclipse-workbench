// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.ui.IFolderLayout;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IPerspectiveFactory;

/** A calm layout that never eagerly opens advanced hardware or trace views. */
public final class ZephyrBeginnerPerspective implements IPerspectiveFactory {
    public static final String ID = Activator.ID + ".beginnerPerspective";

    @Override public void createInitialLayout(IPageLayout layout) {
        String editor = layout.getEditorArea();
        layout.setEditorAreaVisible(true);

        IFolderLayout projects = layout.createFolder(
                "zew.beginner.projects", IPageLayout.LEFT, 0.22f, editor);
        projects.addView("org.eclipse.ui.navigator.ProjectExplorer");

        IFolderLayout guide = layout.createFolder(
                "zew.beginner.guide", IPageLayout.RIGHT, 0.73f, editor);
        guide.addView(ZephyrBeginnerView.ID);

        IFolderLayout output = layout.createFolder(
                "zew.beginner.output", IPageLayout.BOTTOM, 0.76f, editor);
        output.addView("org.eclipse.ui.console.ConsoleView");
        output.addView("org.eclipse.ui.views.ProblemView");

        layout.addShowViewShortcut(ConfigurationHomeView.ID);
        layout.addShowViewShortcut(Activator.ID + ".dtsGraph");
        layout.addShowViewShortcut(Activator.ID + ".yamlStructure");
        layout.addShowViewShortcut(SocRegistersHandler.VIEW_ID);
        layout.addShowViewShortcut(ZephyrTraceConsoleView.ID);
        layout.addNewWizardShortcut("io.github.niwantha33.zew.newProject");
        layout.addPerspectiveShortcut(ZephyrHardwareDebugPerspective.ID);
        layout.addPerspectiveShortcut("org.eclipse.debug.ui.DebugPerspective");
    }
}
