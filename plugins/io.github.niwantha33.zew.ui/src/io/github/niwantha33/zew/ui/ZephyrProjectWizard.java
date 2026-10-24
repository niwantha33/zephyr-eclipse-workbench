// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.List;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.*;
import org.eclipse.ui.ide.IDE;
import io.github.niwantha33.zew.core.*;

/** Create/import independent applications without changing environment-owned source. */
public class ZephyrProjectWizard extends Wizard implements INewWizard, IImportWizard {
    private Page page;
    private String selectedRoot;
    protected boolean importing;
    public ZephyrProjectWizard() { setWindowTitle("Zephyr Project"); setNeedsProgressMonitor(true); }
    @Override public void init(IWorkbench workbench, IStructuredSelection selection) {
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            try { selectedRoot = project.getPersistentProperty(new QualifiedName(Activator.ID, "setupRoot")); if (selectedRoot != null) break; }
            catch (CoreException ignored) { }
        }
    }
    @Override public void addPages() { page = new Page(); addPage(page); }
    private final class Page extends WizardPage {
        Text name, location, environment, search;
        Button existing;
        Combo vendor, soc;
        Table targets;
        List<String[]> rows = new ArrayList<>();
        Path loadedRoot;
        Page() { super("project"); setTitle("New or existing Zephyr application"); setDescription("Select a ready tool environment, load its hardware, then choose an exact target."); }
        @Override public void createControl(Composite parent) {
            var body = new Composite(parent, SWT.NONE); body.setLayout(new GridLayout(3, false));
            name = field(body, "Project name", "MyZephyr", false);
            location = field(body, "Application folder", "", true);
            String workspace = Activator.getDefault().getPreferenceStore().getString("workspace");
            String initial = selectedRoot != null ? selectedRoot : workspace.isBlank() ? "" : Path.of(workspace).getParent().toString();
            environment = field(body, "Ready setup folder", initial, true);
            if (!initial.isBlank() && !importing) location.setText(Path.of(initial, "projects", name.getText()).toString());
            existing = new Button(body, SWT.CHECK); existing.setText("Import existing application in place"); existing.setSelection(importing);
            existing.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
            var load = new Button(body, SWT.PUSH); load.setText("Load hardware from environment");
            load.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1)); load.addListener(SWT.Selection, e -> load());
            vendor = combo(body, "Chip vendor"); soc = combo(body, "SoC");
            search = field(body, "Search board / core / revision", "", false);
            targets = new Table(body, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL); targets.setHeaderVisible(true);
            var data = new GridData(SWT.FILL, SWT.FILL, true, true, 3, 1); data.heightHint = 240; targets.setLayoutData(data);
            for (String title : List.of("Board maker", "Board", "Revision", "Exact target")) { var column = new TableColumn(targets, SWT.NONE); column.setText(title); column.setWidth(title.equals("Exact target") ? 340 : 120); }
            vendor.addListener(SWT.Selection, e -> { fillSoc(); filter(); }); soc.addListener(SWT.Selection, e -> filter());
            search.addModifyListener(e -> filter()); targets.addListener(SWT.Selection, e -> validatePage());
            for (Text text : List.of(name, location, environment)) text.addModifyListener(e -> validatePage());
            existing.addListener(SWT.Selection, e -> validatePage());
            setControl(body); setPageComplete(false);
        }
        Text field(Composite parent, String title, String value, boolean browse) {
            new Label(parent, SWT.NONE).setText(title);
            var text = new Text(parent, SWT.BORDER); text.setText(value); text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            if (browse) { var button = new Button(parent, SWT.PUSH); button.setText("Browse..."); button.addListener(SWT.Selection, e -> { var dialog = new DirectoryDialog(getShell()); dialog.setFilterPath(text.getText()); String selected = dialog.open(); if (selected != null) text.setText(selected); }); }
            else new Label(parent, SWT.NONE);
            return text;
        }
        Combo combo(Composite parent, String title) {
            new Label(parent, SWT.NONE).setText(title); var combo = new Combo(parent, SWT.READ_ONLY); combo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false)); new Label(parent, SWT.NONE); return combo;
        }
        void load() {
            try {
                Path root = Path.of(environment.getText().trim()).toAbsolutePath().normalize();
                var settings = ProjectContext.load(root); var discovered = new ArrayList<String[]>();
                getContainer().run(true, true, monitor -> {
                    try {
                        var helper = Path.of(FileLocator.toFileURL(Activator.getDefault().getBundle().getEntry("tools/hardware_catalog.py")).toURI());
                        monitor.beginTask("Discover Zephyr hardware", IProgressMonitor.UNKNOWN);
                        new ProcessRunner().run(List.of(settings.getProperty("python"), helper.toString(), "--zephyr", Path.of(settings.getProperty("workspace"), "zephyr").toString()),
                            Path.of(settings.getProperty("workspace")), Duration.ofMinutes(3), monitor::isCanceled, line -> {
                                String[] cells = line.split("\t", -1); if (cells.length == 7) discovered.add(cells);
                            }, ProjectContext.environment(settings)).requireSuccess();
                    } catch (InterruptedException error) { throw error; }
                    catch (Exception error) { throw new java.lang.reflect.InvocationTargetException(error); }
                    finally { monitor.done(); }
                });
                if (discovered.isEmpty()) throw new java.io.IOException("No hardware found. Check this environment's Zephyr installation.");
                rows = discovered; loadedRoot = root;
                vendor.setItems(java.util.stream.Stream.concat(java.util.stream.Stream.of("All vendors"), rows.stream().map(r -> r[0]).distinct().sorted()).toArray(String[]::new)); vendor.select(0); fillSoc(); filter();
                if (existing.getSelection() && !location.getText().isBlank()) {
                    Path application = Path.of(location.getText().trim());
                    if (Files.isRegularFile(application.resolve(".zew/project.json"))) {
                        String board = ProjectProfile.read(application).board();
                        search.setText(board);
                        for (var item : targets.getItems()) if (((String[]) item.getData())[6].equals(board)) { targets.setSelection(item); break; }
                        setMessage("Target loaded from .zew/project.json. Source files will be preserved.");
                    } else setMessage("No saved workbench target. Select the exact hardware used by this application.");
                }
                validatePage();
            } catch (Exception error) { loadedRoot = null; setErrorMessage(error.getCause() == null ? error.getMessage() : error.getCause().getMessage()); setPageComplete(false); }
        }
        void fillSoc() {
            soc.setItems(java.util.stream.Stream.concat(java.util.stream.Stream.of("All SoCs"), rows.stream().filter(r -> vendor.getSelectionIndex() == 0 || r[0].equals(vendor.getText())).map(r -> r[2]).distinct().sorted()).toArray(String[]::new)); soc.select(0);
        }
        void filter() {
            if (targets == null) return;
            targets.removeAll(); String query = search.getText().toLowerCase(Locale.ROOT);
            for (String[] row : rows) {
                if (vendor.getSelectionIndex() > 0 && !row[0].equals(vendor.getText())) continue;
                if (soc.getSelectionIndex() > 0 && !row[2].equals(soc.getText())) continue;
                if (!String.join(" ", row).toLowerCase(Locale.ROOT).contains(query)) continue;
                var item = new TableItem(targets, SWT.NONE); item.setText(new String[] {row[1], row[3], row[4], row[6]}); item.setData(row);
            }
            validatePage();
        }
        void validatePage() {
            if (targets == null) return;
            String error = null;
            try {
                if (!ResourcesPlugin.getWorkspace().validateName(name.getText().trim(), IResource.PROJECT).isOK()) error = "Enter a valid Eclipse project name.";
                else if (ResourcesPlugin.getWorkspace().getRoot().getProject(name.getText().trim()).exists()) error = "An Eclipse project already uses this name.";
                else if (location.getText().isBlank() || !Path.of(location.getText().trim()).isAbsolute()) error = "Choose an absolute application folder.";
                else if (loadedRoot == null || !loadedRoot.equals(Path.of(environment.getText().trim()).toAbsolutePath().normalize())) error = "Load hardware from the selected ready environment.";
                else if (targets.getSelectionCount() != 1) error = "Select one exact board target.";
                else {
                    Path source = Path.of(location.getText().trim()).toAbsolutePath().normalize();
                    Path zephyr = Path.of(ProjectContext.load(loadedRoot).getProperty("workspace"), "zephyr").toRealPath();
                    Path ancestor = source; while (ancestor != null && !Files.exists(ancestor)) ancestor = ancestor.getParent();
                    if (ancestor != null && ancestor.toRealPath().startsWith(zephyr)) error = "Choose a standalone application outside the upstream Zephyr tree.";
                    else if (existing.getSelection() && !Files.isRegularFile(source.resolve("CMakeLists.txt"))) error = "Existing application must contain CMakeLists.txt.";
                    else if (!existing.getSelection() && Files.exists(source)) { try (var files = Files.list(source)) { if (files.findAny().isPresent()) error = "Folder is not empty. Select Import existing application."; } }
                    else if (existing.getSelection() && Files.exists(source.resolve(".project"))) error = "This folder already contains Eclipse metadata. Use Eclipse's Existing Projects import to preserve it.";
                    if (existing.getSelection() && Files.isRegularFile(source.resolve(".zew/project.properties"))
                            && !ZephyrProjectFiles.readBoard(source).equals(((String[]) targets.getSelection()[0].getData())[6])) error = "Selected hardware conflicts with the saved project target.";
                    if (existing.getSelection() && Files.isRegularFile(source.resolve(".zew/project.json")) && !ProjectProfile.read(source).board().equals(((String[]) targets.getSelection()[0].getData())[6])) error = "Selected hardware conflicts with the saved project target.";
                }
            } catch (Exception e) { error = e.getMessage(); }
            if (targets.getSelectionCount() == 1 && ((String[]) targets.getSelection()[0].getData())[6].contains("/esp32s3/appcpu"))
                setMessage("APPCPU is the secondary core and requires primary-core firmware. For standalone Hello World choose esp32s3/procpu.", org.eclipse.jface.dialogs.IMessageProvider.WARNING);
            else setMessage("Missing ESP32-S3 compiler and build tools are installed automatically on the first project build.");
            setErrorMessage(error); setPageComplete(error == null);
        }
    }
    @Override public boolean performFinish() {
        page.validatePage(); if (!page.isPageComplete()) return false;
        try {
            Path source = Path.of(page.location.getText().trim()).toAbsolutePath().normalize();
            String target = ((String[]) page.targets.getSelection()[0].getData())[6];
            if (!page.existing.getSelection()) ProjectProfile.createApplication(source, target);
            else if (!Files.exists(source.resolve(".zew/project.json")) && !Files.exists(source.resolve(".zew/project.properties"))) new ProjectProfile(target).write(source);
            var workspace = ResourcesPlugin.getWorkspace(); var project = workspace.getRoot().getProject(page.name.getText().trim());
            workspace.run(monitor -> {
                var description = workspace.newProjectDescription(project.getName()); description.setLocationURI(source.toUri());
                var command = description.newCommand(); command.setBuilderName(Activator.ID + ".zephyrBuilder"); description.setBuildSpec(new ICommand[] {command});
                project.create(description, monitor); project.open(monitor);
                project.setPersistentProperty(new QualifiedName(Activator.ID, "setupRoot"), page.loadedRoot.toString());
                project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
            }, workspace.getRoot(), IWorkspace.AVOID_UPDATE, null);
            var active = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage(); active.showView("org.eclipse.ui.navigator.ProjectExplorer");
            if (Platform.getBundle("org.eclipse.cdt.core") != null) CdtEditor.ensureProject(project, null);
            if (project.getFile("src/main.c").exists()) IDE.openEditor(active, project.getFile("src/main.c"));
            EditorIntegration.schedule(project);
            return true;
        } catch (Exception error) { MessageDialog.openError(getShell(), "Zephyr project", error.getMessage()); return false; }
    }
}
