// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.ui.AbstractLaunchConfigurationTab;
import org.eclipse.debug.ui.AbstractLaunchConfigurationTabGroup;
import org.eclipse.debug.ui.ILaunchConfigurationDialog;
import org.eclipse.debug.ui.ILaunchConfigurationTab;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.dialogs.ListDialog;

/** One native configuration per project and runner action; no private launch UI. */
public final class ZephyrLaunchTabs extends AbstractLaunchConfigurationTabGroup {
    @Override public void createTabs(ILaunchConfigurationDialog dialog, String mode) {
        setTabs(new ILaunchConfigurationTab[] {new MainTab("debug".equals(mode))});
    }

    public static final class MainTab extends AbstractLaunchConfigurationTab {
        private final boolean debugMode;
        private Text project;
        private Combo action;
        private Text port;
        public MainTab(boolean debugMode) { this.debugMode = debugMode; }
        @Override public void createControl(Composite parent) {
            var group = new Composite(parent, SWT.NONE);
            group.setLayout(new GridLayout(3, false));
            setControl(group);
            new Label(group, SWT.NONE).setText("Zephyr project");
            project = new Text(group, SWT.BORDER);
            project.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            project.addModifyListener(e -> updateLaunchConfigurationDialog());
            Button browse = new Button(group, SWT.PUSH);
            browse.setText("Browse...");
            browse.addListener(SWT.Selection, e -> {
                List<IProject> projects = new ArrayList<>();
                for (IProject candidate : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                    if (!candidate.isAccessible()) continue;
                    try { ProjectContext.settings(candidate); projects.add(candidate); }
                    catch (Exception ignored) { }
                }
                var dialog = new ListDialog(getControl().getShell());
                dialog.setTitle("Select a Zephyr project");
                dialog.setMessage("Select one imported Zephyr application");
                dialog.setContentProvider(ArrayContentProvider.getInstance());
                dialog.setLabelProvider(new LabelProvider() {
                    @Override public String getText(Object item) { return ((IProject) item).getName(); }
                });
                dialog.setInput(projects);
                if (dialog.open() == IDialogConstants.OK_ID && dialog.getResult() != null && dialog.getResult().length == 1)
                    project.setText(((IProject) dialog.getResult()[0]).getName());
            });
            if (debugMode) {
                new Label(group, SWT.NONE).setText("GDB TCP port");
                port = new Text(group, SWT.BORDER);
                port.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
                port.addModifyListener(e -> updateLaunchConfigurationDialog());
                new Label(group, SWT.NONE);
                new Label(group, SWT.WRAP).setText("Native CDT DSF-GDB. Supported backend: OpenOCD on localhost. "
                        + "The selected Zephyr build supplies its matching GDB and ELF. "
                        + "This attaches to an owned OpenOCD debug server; it does not reflash firmware.");
            } else {
                new Label(group, SWT.NONE).setText("Runner action");
                action = new Combo(group, SWT.READ_ONLY);
                action.setItems(new String[] {"Run in QEMU", "Flash physical board", "Start debug server (external GDB)"});
                action.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
                action.select(0);
                action.addListener(SWT.Selection, e -> updateLaunchConfigurationDialog());
                new Label(group, SWT.NONE);
                new Label(group, SWT.WRAP).setText("Build the selected exact board target first. Eclipse owns process termination and console output. External Debug Server is not a native source-level debugger.");
            }
        }
        @Override public void setDefaults(ILaunchConfigurationWorkingCopy configuration) {
            configuration.setAttribute(ZephyrLaunchDelegate.PROJECT, "");
            configuration.setAttribute(ZephyrLaunchDelegate.ACTION, debugMode ? "debug" : "run");
            if (debugMode) configuration.setAttribute(ZephyrNativeDebugDelegate.PORT, 3333);
        }
        @Override public void initializeFrom(ILaunchConfiguration configuration) {
            try {
                project.setText(configuration.getAttribute(ZephyrLaunchDelegate.PROJECT, ""));
                if (debugMode) {
                    port.setText(Integer.toString(configuration.getAttribute(ZephyrNativeDebugDelegate.PORT, 3333)));
                } else {
                    String current = configuration.getAttribute(ZephyrLaunchDelegate.ACTION, "run");
                    action.select(switch (current) { case "flash" -> 1; case "debugserver" -> 2; default -> 0; });
                }
            } catch (CoreException error) { setErrorMessage(error.getMessage()); }
        }
        @Override public void performApply(ILaunchConfigurationWorkingCopy configuration) {
            configuration.setAttribute(ZephyrLaunchDelegate.PROJECT, project.getText().trim());
            if (debugMode) {
                configuration.setAttribute(ZephyrLaunchDelegate.ACTION, "debug");
                try { configuration.setAttribute(ZephyrNativeDebugDelegate.PORT, Integer.parseInt(port.getText().trim())); }
                catch (NumberFormatException ignored) { configuration.setAttribute(ZephyrNativeDebugDelegate.PORT, -1); }
            } else {
                configuration.setAttribute(ZephyrLaunchDelegate.ACTION,
                        switch (action.getSelectionIndex()) { case 1 -> "flash"; case 2 -> "debugserver"; default -> "run"; });
            }
        }
        @Override public boolean isValid(ILaunchConfiguration configuration) {
            String name = project.getText().trim();
            if (name.isEmpty()) { setErrorMessage("Choose a Zephyr project."); return false; }
            var candidate = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
            if (!candidate.isAccessible()) { setErrorMessage("Choose an open Zephyr project."); return false; }
            try { ProjectContext.settings(candidate); }
            catch (Exception e) { setErrorMessage("Choose an imported Zephyr project: " + e.getMessage()); return false; }
            if (debugMode) {
                try {
                    int selected = Integer.parseInt(port.getText().trim());
                    if (selected < 1024 || selected > 65535) throw new NumberFormatException();
                } catch (NumberFormatException error) {
                    setErrorMessage("Choose a valid GDB port (1024–65535).");
                    return false;
                }
            }
            setErrorMessage(null);
            return true;
        }
        @Override public String getName() { return debugMode ? "Zephyr Debug" : "Zephyr runner"; }
    }
}
