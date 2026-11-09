// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.ui.IFolderLayout;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IPerspectiveFactory;

/**
 * A separate user-selectable perspective; never resets or overwrites the
 * user's normal Eclipse CDT Debug perspective or its saved layout.
 */
public final class ZephyrHardwareDebugPerspective implements IPerspectiveFactory {
    public static final String ID = Activator.ID + ".hardwareDebugPerspective";

    @Override public void createInitialLayout(IPageLayout layout) {
        String editor = layout.getEditorArea();
        layout.setEditorAreaVisible(true);

        IFolderLayout execution = layout.createFolder(
                "zew.debug.execution", IPageLayout.LEFT, 0.25f, editor);
        execution.addView("org.eclipse.debug.ui.DebugView");

        IFolderLayout inspection = layout.createFolder(
                "zew.debug.inspection", IPageLayout.RIGHT, 0.70f, editor);
        inspection.addView(SocRegistersHandler.VIEW_ID);
        inspection.addView("org.eclipse.debug.ui.RegisterView");
        inspection.addView("org.eclipse.debug.ui.VariableView");
        inspection.addView("org.eclipse.debug.ui.ExpressionView");

        IFolderLayout output = layout.createFolder(
                "zew.debug.output", IPageLayout.BOTTOM, 0.68f, editor);
        output.addView("org.eclipse.ui.console.ConsoleView");
        output.addPlaceholder(ZephyrTraceConsoleView.ID);
        output.addPlaceholder(Activator.ID + ".threadTimeline");
        output.addPlaceholder(Activator.ID + ".cpuLoad");

        layout.addShowViewShortcut(SocRegistersHandler.VIEW_ID);
        layout.addShowViewShortcut("org.eclipse.debug.ui.RegisterView");
        layout.addShowViewShortcut("org.eclipse.debug.ui.VariableView");
        layout.addShowViewShortcut(ZephyrTraceConsoleView.ID);
        layout.addShowViewShortcut(Activator.ID + ".threadTimeline");
        layout.addShowViewShortcut(Activator.ID + ".cpuLoad");
        layout.addPerspectiveShortcut("org.eclipse.debug.ui.DebugPerspective");
    }
}
