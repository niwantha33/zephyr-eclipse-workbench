// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.dialogs.PropertyPage;
import io.github.niwantha33.zew.core.ProcessRunner;
import io.github.niwantha33.zew.core.ProjectProfile;
import io.github.niwantha33.zew.core.ZephyrProjectFiles;

/** Project Properties > Zephyr: real SoC/CPU board qualifiers from the active checkout. */
public final class ZephyrProjectPropertyPage extends PropertyPage {
    private IProject project;
    private Combo vendor, soc, buildConfiguration;
    private Text search, environment;
    private Path loadedEnvironment;
    private Table targets;
    private Label current;
    private String originalBoard = "";
    private List<String[]> hardware = List.of();

    @Override protected Control createContents(Composite parent) {
        project = getElement() instanceof IProject selected ? selected : getElement().getAdapter(IProject.class);
        var panel = new Composite(parent, SWT.NONE);
        panel.setLayout(new GridLayout(3, false));
        if (project == null) {
            setErrorMessage("Select an Eclipse project.");
            setValid(false);
            return panel;
        }
        if (!ZephyrProjectLifecycle.supports(project)) {
            setErrorMessage("Select a Zephyr application. Projects with other vendor builders keep their native settings.");
            setValid(false);
            return panel;
        }
        try {
            Path application = project.getLocation().toFile().toPath();
            if (Files.isRegularFile(application.resolve(".zew/project.json"))) originalBoard = ProjectProfile.read(application).board();
            else if (Files.isRegularFile(application.resolve(".zew/project.properties"))) originalBoard = ZephyrProjectFiles.readBoard(application);
            else {
                try { originalBoard = ProjectContext.settings(project).getProperty("board", ""); }
                catch (Exception missing) { /* Board selection is part of normal project configuration. */ }
            }
        } catch (Exception error) {
            setErrorMessage(error.getMessage()); setValid(false); return panel;
        }
        new Label(panel, SWT.NONE).setText("Zephyr environment");
        environment = new Text(panel, SWT.BORDER);
        environment.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Path detected = ZephyrProjectLifecycle.environment(project);
        environment.setText(detected == null ? "" : detected.toString());
        Button browse = new Button(panel, SWT.PUSH); browse.setText("Browse...");
        browse.addListener(SWT.Selection, event -> {
            var dialog = new org.eclipse.swt.widgets.DirectoryDialog(getShell());
            dialog.setMessage("Select a completed Zephyr setup folder."); dialog.setFilterPath(environment.getText());
            String value = dialog.open(); if (value != null) { environment.setText(value); loadHardware(); }
        });
        environment.addModifyListener(event -> {
            loadedEnvironment = null;
            if (targets != null) targets.removeAll();
            setErrorMessage("Reload hardware for the selected environment."); setValid(false);
        });
        new Label(panel, SWT.NONE).setText("Current board / CPU:");
        current = new Label(panel, SWT.WRAP);
        current.setText(originalBoard.isBlank() ? "Select a target below" : originalBoard);
        current.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        new Label(panel, SWT.NONE).setText("Build configuration");
        buildConfiguration = new Combo(panel, SWT.READ_ONLY);
        buildConfiguration.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        var profiles = io.github.niwantha33.zew.core.BuildConfiguration.values();
        buildConfiguration.setItems(java.util.Arrays.stream(profiles).map(p -> p.label()).toArray(String[]::new));
        try {
            var selectedProfile = io.github.niwantha33.zew.core.BuildConfiguration.fromId(
                    project.getPersistentProperty(ProjectContext.BUILD_CONFIGURATION));
            buildConfiguration.select(selectedProfile.ordinal());
        } catch (Exception error) {
            setErrorMessage("Invalid build configuration: " + error.getMessage());
            setValid(false);
            return panel;
        }
        new Label(panel, SWT.NONE);
        new Label(panel, SWT.NONE).setText("Chip vendor");
        vendor = new Combo(panel, SWT.READ_ONLY);
        vendor.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        new Label(panel, SWT.NONE);
        new Label(panel, SWT.NONE).setText("SoC");
        soc = new Combo(panel, SWT.READ_ONLY);
        soc.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        new Label(panel, SWT.NONE);
        new Label(panel, SWT.NONE).setText("Filter board / CPU");
        search = new Text(panel, SWT.BORDER | SWT.SEARCH);
        search.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button reload = new Button(panel, SWT.PUSH);
        reload.setText("Reload hardware");
        reload.addListener(SWT.Selection, e -> loadHardware());
        targets = new Table(panel, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
        targets.setHeaderVisible(true);
        var layout = new GridData(SWT.FILL, SWT.FILL, true, true, 3, 1);
        layout.heightHint = 260;
        targets.setLayoutData(layout);
        for (String header : List.of("Board vendor", "Board", "Revision", "CPU / qualifier", "Exact build target")) {
            TableColumn column = new TableColumn(targets, SWT.LEFT);
            column.setText(header);
            column.setWidth(header.equals("Exact build target") ? 320 : 125);
        }
        var information = new Label(panel, SWT.WRAP);
        information.setText("Each configuration has its own build folder. Application settings keeps your existing build; Debug selects -Og, Release selects -O2. Build to generate the selected configuration. Project source is preserved.");
        information.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        vendor.addListener(SWT.Selection, e -> { fillSocs(); filterTargets(); });
        soc.addListener(SWT.Selection, e -> filterTargets());
        search.addModifyListener(e -> filterTargets());
        targets.addListener(SWT.Selection, e -> validateSelection());
        setValid(false);
        panel.getDisplay().asyncExec(() -> { if (!panel.isDisposed()) loadHardware(); });
        return panel;
    }

    private void loadHardware() {
        try {
            loadedEnvironment = null;
            targets.removeAll(); setValid(false);
            Path root = Path.of(environment.getText().trim()).toAbsolutePath().normalize();
            var settings = ProjectContext.load(root);
            settings.setProperty("zew.setupRoot", root.toString());
            var found = new ArrayList<String[]>();
            new ProgressMonitorDialog(getShell()).run(true, true, monitor -> {
                try {
                    monitor.beginTask("Reading board/SoC/CPU metadata", IProgressMonitor.UNKNOWN);
                    Path helper = Path.of(FileLocator.toFileURL(Activator.getDefault().getBundle().getEntry("tools/hardware_catalog.py")).toURI());
                    new ProcessRunner().run(List.of(settings.getProperty("python"), helper.toString(), "--zephyr",
                                    Path.of(settings.getProperty("workspace"), "zephyr").toString()),
                            Path.of(settings.getProperty("workspace")), Duration.ofMinutes(3), monitor::isCanceled,
                            line -> { String[] row = line.split("\t", -1); if (row.length == 7) found.add(row); },
                            ProjectContext.environment(settings)).requireSuccess();
                } catch (InterruptedException error) { throw error; }
                catch (Exception error) { throw new java.lang.reflect.InvocationTargetException(error); }
                finally { monitor.done(); }
            });
            if (found.isEmpty()) throw new java.io.IOException("No hardware metadata returned from this Zephyr checkout.");
            loadedEnvironment = root;
            hardware = found;
            vendor.setItems(java.util.stream.Stream.concat(java.util.stream.Stream.of("All chip vendors"),
                    hardware.stream().map(row -> row[0]).distinct().sorted()).toArray(String[]::new));
            vendor.select(0);
            fillSocs();
            // A verified ROM chip mismatch can suggest a SoC filter only.
            // Exact board maker, revision and CPU still require user selection.
            String suggested = (String) project.getSessionProperty(ZephyrChipMismatchRecovery.SUGGESTED_SOC);
            search.setText(suggested == null ? originalBoard : suggested);
            filterTargets();
            if (suggested == null) {
                for (TableItem item : targets.getItems()) {
                    if (((String[]) item.getData())[6].equals(originalBoard)) {
                        targets.setSelection(item);
                        break;
                    }
                }
            } else {
                setMessage("Detected " + suggested.toUpperCase(Locale.ROOT)
                    + ": choose your actual board/CPU from the filtered hardware catalogue."
                    + " The chip alone cannot identify the exact PCB.", org.eclipse.jface.dialogs.IMessageProvider.INFORMATION);
            }
            validateSelection();
        } catch (Exception error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            setErrorMessage("Hardware discovery failed: " + cause.getMessage());
            setValid(false);
        }
    }

    private void fillSocs() {
        soc.setItems(java.util.stream.Stream.concat(java.util.stream.Stream.of("All SoCs"),
                hardware.stream().filter(row -> vendor.getSelectionIndex() == 0 || row[0].equals(vendor.getText()))
                        .map(row -> row[2]).distinct().sorted()).toArray(String[]::new));
        soc.select(0);
    }

    private void filterTargets() {
        if (targets == null) return;
        targets.removeAll();
        String needle = search.getText().toLowerCase(Locale.ROOT);
        for (String[] row : hardware) {
            if (vendor.getSelectionIndex() > 0 && !row[0].equals(vendor.getText())) continue;
            if (soc.getSelectionIndex() > 0 && !row[2].equals(soc.getText())) continue;
            if (!String.join(" ", row).toLowerCase(Locale.ROOT).contains(needle)) continue;
            var item = new TableItem(targets, SWT.NONE);
            item.setText(new String[] {row[1], row[3], row[4], row[5], row[6]});
            item.setData(row);
        }
        validateSelection();
    }

    private void validateSelection() {
        if (loadedEnvironment == null || targets.getSelectionCount() != 1) {
            setErrorMessage("Choose an exact board / SoC / CPU target.");
            setValid(false);
            return;
        }
        setErrorMessage(null);
        String selected = ((String[]) targets.getSelection()[0].getData())[6];
        setMessage(selected.contains("/esp32s3/appcpu")
                ? "APPCPU requires the primary CPU firmware; use procpu for a standalone ESP32-S3 application."
                : "Changing the board preserves other build outputs. Rebuild for the new target.");
        setValid(true);
    }

    @Override public boolean performOk() {
        if (targets == null || loadedEnvironment == null) return false;
        if (project == null || !isValid() || targets.getSelectionCount() != 1) return false;
        String selected = ((String[]) targets.getSelection()[0].getData())[6];
        int selectedIndex = buildConfiguration.getSelectionIndex();
        if (selectedIndex < 0) return false;
        var configuration = io.github.niwantha33.zew.core.BuildConfiguration.values()[selectedIndex];
        try {
            Path application = project.getLocation().toFile().toPath();
            ResourcesPlugin.getWorkspace().run(monitor -> {
                try {
                    if (selected.equals(originalBoard)) {
                        // Keep existing project profile untouched.
                    } else if (Files.isRegularFile(application.resolve(".zew/project.json"))) {
                        new ProjectProfile(selected).replace(application, originalBoard);
                    } else if (Files.isRegularFile(application.resolve(".zew/project.properties"))) {
                        // Schema-1 legacy profiles remain intact for rollback. JSON wins on reload.
                        new ProjectProfile(selected).write(application);
                    } else {
                        new ProjectProfile(selected).write(application);
                    }
                    ZephyrProjectImport.bind(application, project.getName(), selected, loadedEnvironment, monitor);
                    project.setPersistentProperty(ProjectContext.BUILD_CONFIGURATION, configuration.id());
                    ZephyrProjectLifecycle.configure(project, monitor);
                    project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
                } catch (Exception error) {
                    throw new org.eclipse.core.runtime.CoreException(new Status(IStatus.ERROR, Activator.ID, error.getMessage(), error));
                }
            }, project, org.eclipse.core.resources.IWorkspace.AVOID_UPDATE, null);
            Activator.getDefault().getPreferenceStore().setValue(ZephyrProjectLifecycle.ENVIRONMENT, loadedEnvironment.toString());
            originalBoard = selected;
            EditorIntegration.schedule(project);
            return true;
        } catch (Exception error) {
            MessageDialog.openError(getShell(), "Zephyr board selection", error.getMessage());
            return false;
        }
    }
}
