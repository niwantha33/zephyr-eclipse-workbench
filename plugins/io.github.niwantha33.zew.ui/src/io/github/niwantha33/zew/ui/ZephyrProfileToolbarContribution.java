// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.List;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.menus.WorkbenchWindowControlContribution;
import io.github.niwantha33.zew.core.BuildProfile;
import io.github.niwantha33.zew.core.BuildProfileChoices;
import io.github.niwantha33.zew.core.BuildProfileChoices.Choice;

/**
 * One quick selection shared by the existing Build Project and Flash buttons.
 * The menu can also explicitly Build or Flash without opening Project Properties.
 * No selection starts a target operation: only an explicit Build/Flash action does.
 */
public final class ZephyrProfileToolbarContribution extends WorkbenchWindowControlContribution {
    private ToolBar toolbar;
    private ToolItem selector;
    private ISelectionListener selectionListener;

    public ZephyrProfileToolbarContribution() { super(); }

    @Override protected Control createControl(Composite parent) {
        toolbar = new ToolBar(parent, SWT.FLAT | SWT.HORIZONTAL);
        selector = new ToolItem(toolbar, SWT.DROP_DOWN);
        selector.setText("Profile: select project");
        selector.setToolTipText("Choose the Zephyr build profile used by Build and Flash");
        selector.addListener(SWT.Selection, event -> showMenu());
        // Read from the currently selected project, never a previously used project.
        IWorkbenchWindow window = getWorkbenchWindow();
        if (window != null) {
            selectionListener = (part, selection) -> refresh();
            window.getSelectionService().addSelectionListener(selectionListener);
            toolbar.addDisposeListener(event -> {
                window.getSelectionService().removeSelectionListener(selectionListener);
                selectionListener = null;
            });
        }
        toolbar.addListener(SWT.MouseEnter, ignored -> refresh());
        refresh();
        return toolbar;
    }

    private IProject selectedProject() {
        IWorkbenchWindow window = getWorkbenchWindow();
        if (window == null) return null;
        var selection = window.getSelectionService().getSelection();
        if (selection instanceof IStructuredSelection items) {
            if (items.size() == 1)
                return SelectedProject.importedProject(items.getFirstElement());
            if (!items.isEmpty()) return null; // ambiguous multi-selection
        }
        var page = window.getActivePage();
        return page != null && page.getActiveEditor() != null
                ? SelectedProject.importedProject(page.getActiveEditor().getEditorInput()) : null;
    }

    private List<Choice> choices(IProject project) throws Exception {
        return BuildProfileChoices.available(project.getLocation().toFile().toPath());
    }

    private Choice active(IProject project, List<Choice> choices) throws Exception {
        return BuildProfileChoices.selected(choices,
                project.getPersistentProperty(ProjectContext.BUILD_CONFIGURATION),
                project.getPersistentProperty(ProjectContext.NAMED_PROFILE));
    }

    private void refresh() {
        if (selector == null || selector.isDisposed()) return;
        IProject project = selectedProject();
        if (project == null) {
            selector.setText("Profile: select project");
            selector.setEnabled(false);
            selector.setToolTipText("Select one imported Zephyr project or open its editor");
            toolbar.pack();
            return;
        }
        selector.setEnabled(true);
        try {
            Choice chosen = active(project, choices(project));
            selector.setText("Profile: " + chosen.label());
            selector.setToolTipText(project.getName() + " — " + chosen.label()
                    + "\nSelect once for Build Project and Flash, or use this menu for an immediate action.");
        } catch (Exception error) {
            selector.setText("Profile: unavailable");
            selector.setToolTipText(project.getName() + " — " + error.getMessage());
        }
        toolbar.pack();
    }

    private void showMenu() {
        if (toolbar == null || toolbar.isDisposed()) return;
        IProject project = selectedProject();
        if (project == null) {
            MessageDialog.openInformation(toolbar.getShell(), "Zephyr Profile",
                    "Select one imported Zephyr project in Project Explorer or open its editor.");
            return;
        }

        Menu menu = new Menu(toolbar.getShell(), SWT.POP_UP);
        // SWT owns MenuItem children; release each short-lived popup when closed.
        menu.addListener(SWT.Hide, event -> toolbar.getDisplay().asyncExec(() -> {
            if (!menu.isDisposed()) menu.dispose();
        }));
        try {
            List<Choice> all = choices(project);
            Choice selected = null;
            try { selected = active(project, all); }
            catch (Exception missing) {
                MenuItem warning = new MenuItem(menu, SWT.PUSH);
                warning.setText("Saved profile missing — choose another");
                warning.setEnabled(false);
                new MenuItem(menu, SWT.SEPARATOR);
            }
            for (Choice choice : all) {
                MenuItem option = new MenuItem(menu, SWT.RADIO);
                option.setText((choice.named() ? "Profile: " : "Built-in: ") + choice.label());
                option.setSelection(selected != null && choice.key().equals(selected.key()));
                option.addListener(SWT.Selection, event -> {
                    if (!option.getSelection()) return;
                    try {
                        activate(project, choice);
                        refresh();
                    } catch (Exception error) {
                        MessageDialog.openError(toolbar.getShell(), "Cannot select build profile", error.getMessage());
                        refresh();
                    }
                });
            }
            new MenuItem(menu, SWT.SEPARATOR);
            MenuItem build = new MenuItem(menu, SWT.PUSH);
            build.setText("Build selected profile");
            build.addListener(SWT.Selection, event -> run(project, false));
            MenuItem flash = new MenuItem(menu, SWT.PUSH);
            flash.setText("Flash selected profile...");
            flash.addListener(SWT.Selection, event -> run(project, true));
            new MenuItem(menu, SWT.SEPARATOR);
            MenuItem manage = new MenuItem(menu, SWT.PUSH);
            manage.setText("Manage profiles in Project Properties...");
            manage.addListener(SWT.Selection, event -> {
                var dialog = org.eclipse.ui.dialogs.PreferencesUtil.createPropertyDialogOn(
                        toolbar.getShell(), project, "io.github.niwantha33.zew.projectProperties", null, null);
                if (dialog != null) dialog.open();
                refresh();
            });
        } catch (Exception error) {
            MenuItem warning = new MenuItem(menu, SWT.PUSH);
            warning.setText("Profiles unavailable: " + error.getMessage());
            warning.setEnabled(false);
        }
        Rectangle bounds = selector.getBounds();
        Point where = toolbar.toDisplay(new Point(bounds.x, bounds.y + bounds.height));
        menu.setLocation(where);
        menu.setVisible(true);
    }

    /** Recheck project context before performing any asynchronous firmware operation. */
    private void run(IProject originalProject, boolean flash) {
        if (!originalProject.equals(selectedProject())) {
            MessageDialog.openError(toolbar.getShell(), "Zephyr Profile",
                    "Project selection changed. Select the intended project and try again.");
            return;
        }
        try {
            // No target action if the active profile was deleted or the environment is unavailable.
            Choice choice = active(originalProject, choices(originalProject));
            var settings = ProjectContext.settings(originalProject);
            if (!choice.identity().equals(ProjectContext.profileIdentity(settings)))
                throw new java.io.IOException("The selected build profile changed. Select it again.");
            if (flash) ZephyrFlashShortcut.launchProject(originalProject, toolbar.getShell());
            else ZephyrBuildHandler.schedule(originalProject, false, false);
        } catch (Exception error) {
            MessageDialog.openError(toolbar.getShell(), flash ? "Zephyr Flash" : "Zephyr Build",
                    error.getMessage());
        }
    }

    private void activate(IProject project, Choice choice) throws Exception {
        if (!project.equals(selectedProject()))
            throw new java.io.IOException("Project selection changed. No profile was modified.");
        // Re-read the selected ID before persisting; stale UI entries may have been deleted.
        BuildProfileChoices.choose(choices(project), choice.key());
        if (choice.named())
            BuildProfile.read(project.getLocation().toFile().toPath(), choice.namedId());
        // Both metadata properties are stored within one workspace scheduling rule.
        // Named profiles leave the last built-in selection intact for later reuse.
        ResourcesPlugin.getWorkspace().run(monitor -> {
            if (choice.named()) {
                project.setPersistentProperty(ProjectContext.NAMED_PROFILE, choice.namedId());
            } else {
                project.setPersistentProperty(ProjectContext.BUILD_CONFIGURATION, choice.builtInId());
                project.setPersistentProperty(ProjectContext.NAMED_PROFILE, null);
            }
        }, project, IWorkspace.AVOID_UPDATE, null);
        Choice confirmed = active(project, choices(project));
        if (!confirmed.key().equals(choice.key()))
            throw new java.io.IOException("Could not confirm the selected firmware profile.");
    }
}
