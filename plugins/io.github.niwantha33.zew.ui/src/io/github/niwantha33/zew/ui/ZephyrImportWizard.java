// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IImportWizard;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import io.github.niwantha33.zew.core.ProcessRunner;
import io.github.niwantha33.zew.core.ProjectProfile;
import io.github.niwantha33.zew.core.ZephyrApplicationDiscovery;
import io.github.niwantha33.zew.core.ZephyrProjectFiles;

/** Parent-folder discovery and explicit project selection, separate from New Project. */
public final class ZephyrImportWizard extends Wizard implements IImportWizard {
    private static final QualifiedName SETUP = new QualifiedName(Activator.ID, "setupRoot");
    private final ApplicationsPage applications = new ApplicationsPage();
    private final TargetPage target = new TargetPage();
    private final ReviewPage review = new ReviewPage();
    private String initialEnvironment = "";
    private String initialSource = "";

    private static final class Entry {
        final Path folder;
        String name;
        String board = "";
        String problem = "";
        IProject existing;
        boolean repair;
        Entry(Path folder) { this.folder = folder; this.name = folder.getFileName().toString(); }
    }
    private record ImportItem(Path folder, String name, String board, boolean repair) {}

    public ZephyrImportWizard() { setWindowTitle("Import or Repair Zephyr Applications"); setNeedsProgressMonitor(true); }

    @Override public void init(IWorkbench workbench, IStructuredSelection selection) {
        if (selection.getFirstElement() instanceof IResource resource && resource.getLocation() != null) {
            initialSource = resource.getProject().getLocation().toOSString();
            try { initialEnvironment = resource.getProject().getPersistentProperty(SETUP); }
            catch (CoreException ignored) { }
        }
        if (initialEnvironment == null || initialEnvironment.isBlank()) {
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                try {
                    String value = project.getPersistentProperty(SETUP);
                    if (value != null && !value.isBlank()) { initialEnvironment = value; break; }
                } catch (CoreException ignored) { }
            }
        }
        if (initialEnvironment == null || initialEnvironment.isBlank()) {
            String workspace = Activator.getDefault().getPreferenceStore().getString("workspace");
            if (!workspace.isBlank() && Path.of(workspace).getParent() != null)
                initialEnvironment = Path.of(workspace).getParent().toString();
        }
        if (initialEnvironment == null) initialEnvironment = "";
    }

    @Override public void addPages() { addPage(applications); addPage(target); addPage(review); }
    @Override public boolean canFinish() {
        return getContainer() != null && getContainer().getCurrentPage() == review && applications.isPageComplete() && target.isPageComplete();
    }

    private Entry inspect(Path folder) {
        Entry entry = new Entry(folder);
        try {
            if (Files.isSymbolicLink(folder.resolve(".project")) || Files.isSymbolicLink(folder.resolve(".zew")))
                throw new IOException("Project metadata is a filesystem link; choose the real application folder.");
            if (Files.isRegularFile(folder.resolve(".project"))) {
                var description = ZephyrProjectImport.description(folder);
                entry.repair = ZephyrProjectImport.isCMake(description);
                entry.name = description.getName();
            }
            if (Files.isRegularFile(folder.resolve(".zew/project.json"))) entry.board = ProjectProfile.read(folder).board();
            else if (Files.isRegularFile(folder.resolve(".zew/project.properties"))) entry.board = ZephyrProjectFiles.readBoard(folder);
            entry.existing = ZephyrProjectImport.existingProject(folder);
            if (entry.existing != null) {
                entry.name = entry.existing.getName();
                String setup = entry.existing.getPersistentProperty(SETUP);
                if (!entry.repair && setup != null && !setup.isBlank()) {
                    try {
                        ProjectContext.settings(entry.existing);
                        entry.problem = "Already configured in this workspace";
                    } catch (Exception unavailable) { /* Allow rebinding an unavailable environment. */ }
                }
            }
        } catch (Exception error) { entry.problem = message(error); }
        return entry;
    }

    private final class ApplicationsPage extends WizardPage {
        Text root, name;
        Button nested;
        Table projects;
        Text status;
        Path searchedRoot;
        boolean updatingName;
        ApplicationsPage() { super("applications"); setTitle("Select existing Zephyr applications");
            setDescription("Choose a project folder or a parent folder, search, then check the applications to import in place."); }
        @Override public void createControl(Composite parent) {
            Composite body = new Composite(parent, SWT.NONE); body.setLayout(new GridLayout(3, false));
            root = folderField(body, "Root directory", initialSource, this::scan);
            root.addModifyListener(e -> { searchedRoot = null; if (projects != null) { projects.removeAll(); status.setText("Search the selected root directory."); validateSelection(); } });
            nested = new Button(body, SWT.CHECK); nested.setText("Search nested folders"); nested.setSelection(true);
            nested.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
            Button scan = new Button(body, SWT.PUSH); scan.setText("Search"); scan.addListener(SWT.Selection, e -> scan());
            nested.addListener(SWT.Selection, e -> { searchedRoot = null; projects.removeAll(); status.setText("Search again after changing nested-folder search."); validateSelection(); });
            projects = new Table(body, SWT.BORDER | SWT.CHECK | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
            projects.setHeaderVisible(true); projects.setLinesVisible(true);
            GridData data = new GridData(SWT.FILL, SWT.FILL, true, true, 3, 1); data.heightHint = 220; data.widthHint = 760; projects.setLayoutData(data);
            columns(projects, new String[] {"Project name", "Application folder", "Status / saved target"}, new int[] {180, 350, 330});
            projects.addListener(SWT.Selection, e -> {
                if (e.item instanceof TableItem item && !((Entry) item.getData()).problem.isBlank()) item.setChecked(false);
                editSelectedName(); validateSelection();
            });
            Composite buttons = new Composite(body, SWT.NONE); buttons.setLayout(new GridLayout(2, false));
            buttons.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
            Button all = new Button(buttons, SWT.PUSH); all.setText("Select All");
            all.addListener(SWT.Selection, e -> { for (TableItem item : projects.getItems()) item.setChecked(((Entry) item.getData()).problem.isBlank()); validateSelection(); });
            Button none = new Button(buttons, SWT.PUSH); none.setText("Deselect All");
            none.addListener(SWT.Selection, e -> { for (TableItem item : projects.getItems()) item.setChecked(false); validateSelection(); });
            new Label(body, SWT.NONE).setText("Selected row's project name");
            name = new Text(body, SWT.BORDER); name.setEnabled(false); name.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
            name.addModifyListener(e -> {
                if (!updatingName && projects.getSelectionCount() == 1) {
                    TableItem item = projects.getSelection()[0]; Entry entry = (Entry) item.getData();
                    entry.name = name.getText().trim(); item.setText(0, entry.name); validateSelection();
                }
            });
            status = new Text(body, SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
            GridData statusData = new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1); statusData.heightHint = 70; status.setLayoutData(statusData);
            status.setText("Import adds/binds Eclipse metadata. Source files stay in their current folders. Eclipse auto-build may build imported projects.");
            setControl(body); setPageComplete(false);
        }
        void editSelectedName() {
            updatingName = true;
            try {
                if (projects.getSelectionCount() == 1) {
                    Entry entry = (Entry) projects.getSelection()[0].getData();
                    name.setText(entry.name); name.setEnabled(entry.problem.isBlank() && entry.existing == null);
                } else { name.setText(""); name.setEnabled(false); }
            } finally { updatingName = false; }
        }
        void scan() {
            projects.removeAll(); searchedRoot = null; name.setEnabled(false); setPageComplete(false);
            try {
                if (root.getText().isBlank()) throw new IOException("Choose a root directory first.");
                Path source = Path.of(root.getText().trim());
                if (!source.isAbsolute()) throw new IOException("Choose an absolute root directory.");
                boolean recursive = nested.getSelection();
                List<Entry> found = new ArrayList<>(); List<String> warnings = new ArrayList<>();
                getContainer().run(true, true, monitor -> {
                    monitor.beginTask("Search for Zephyr applications", IProgressMonitor.UNKNOWN);
                    try {
                        var result = ZephyrApplicationDiscovery.scan(source, recursive, monitor::isCanceled);
                        for (Path folder : result.applications()) {
                            if (monitor.isCanceled()) throw new InterruptedException();
                            found.add(inspect(folder));
                        }
                        warnings.addAll(result.warnings());
                    } catch (InterruptedException error) { throw error; }
                    catch (Exception error) { throw new InvocationTargetException(error); }
                    finally { monitor.done(); }
                });
                searchedRoot = source.toRealPath();
                for (Entry entry : found) {
                    TableItem item = new TableItem(projects, SWT.NONE); item.setData(entry);
                    item.setText(new String[] {entry.name, entry.folder.toString(), entry.problem.isBlank()
                            ? entry.repair ? "Repair generic CMake builder" : entry.existing != null ? "Bind existing project to Zephyr"
                            : entry.board.isBlank() ? "Choose target on next page" : "Saved: " + entry.board : entry.problem});
                    item.setGrayed(!entry.problem.isBlank());
                }
                if (projects.getItemCount() == 1 && ((Entry) projects.getItem(0).getData()).problem.isBlank()) projects.getItem(0).setChecked(true);
                String report = found.size() + " application(s) found.";
                if (!warnings.isEmpty()) report += "\n" + String.join("\n", warnings);
                if (found.isEmpty()) report += "\nExtract ZIPs first. Select a folder containing a Zephyr CMakeLists.txt, or its parent. CCS/STM32 non-Zephyr projects use their native importer.";
                status.setText(report); status.getParent().layout(true, true); validateSelection();
            } catch (InterruptedException error) { status.setText("Search cancelled. Choose a folder and Search again."); setErrorMessage(null); }
            catch (Exception error) { setErrorMessage(message(error)); }
        }
        List<Entry> selected() {
            List<Entry> result = new ArrayList<>();
            for (TableItem item : projects.getItems()) if (item.getChecked()) result.add((Entry) item.getData());
            return result;
        }
        void validateSelection() {
            String error = null;
            try {
                if (searchedRoot == null) error = "Search the root directory to list available applications.";
                else if (selected().isEmpty()) error = "Check at least one application to import.";
                else {
                    Set<String> names = new HashSet<>();
                    List<Entry> chosen = selected();
                    for (Entry entry : chosen) {
                        if (!entry.problem.isBlank()) throw new IOException(entry.problem);
                        if (!ResourcesPlugin.getWorkspace().validateName(entry.name, IResource.PROJECT).isOK())
                            throw new IOException("Enter a valid project name for " + entry.folder);
                        if (!names.add(entry.name.toLowerCase(Locale.ROOT))) throw new IOException("Selected applications have the same project name. Select a row and rename it.");
                        for (IProject existing : ResourcesPlugin.getWorkspace().getRoot().getProjects())
                            if (!existing.equals(entry.existing) && existing.getName().equalsIgnoreCase(entry.name)) throw new IOException("Project name already exists: " + entry.name + ". Select the row and rename it.");
                        for (Entry other : chosen) if (other != entry && entry.folder.startsWith(other.folder))
                            throw new IOException("Selected application folders overlap. Choose the parent application or its child, not both.");
                    }
                }
            } catch (Exception failure) { error = message(failure); }
            setErrorMessage(error); setPageComplete(error == null);
        }
    }

    private final class TargetPage extends WizardPage {
        Text environment, filter;
        Table hardware;
        List<String[]> rows = new ArrayList<>();
        Path loadedRoot;
        TargetPage() { super("target"); setTitle("Environment and hardware");
            setDescription("Saved targets are preserved. Choose a default target for applications without saved Workbench metadata."); }
        @Override public void createControl(Composite parent) {
            Composite body = new Composite(parent, SWT.NONE); body.setLayout(new GridLayout(3, false));
            environment = folderField(body, "Ready setup folder", initialEnvironment, this::load);
            environment.addModifyListener(e -> { loadedRoot = null; rows.clear(); if (hardware != null) hardware.removeAll(); validateTarget(); });
            Button load = new Button(body, SWT.PUSH); load.setText("Load hardware from environment");
            load.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1)); load.addListener(SWT.Selection, e -> load());
            new Label(body, SWT.NONE).setText("Filter vendor / board / core");
            filter = new Text(body, SWT.BORDER); filter.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
            hardware = new Table(body, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
            hardware.setHeaderVisible(true); hardware.setLinesVisible(true);
            GridData data = new GridData(SWT.FILL, SWT.FILL, true, true, 3, 1); data.heightHint = 240; data.widthHint = 760; hardware.setLayoutData(data);
            columns(hardware, new String[] {"Chip vendor", "SoC", "Board", "Exact target"}, new int[] {130, 150, 180, 360});
            filter.addModifyListener(e -> filter()); hardware.addListener(SWT.Selection, e -> validateTarget());
            setControl(body); setPageComplete(false);
        }
        @Override public void setVisible(boolean visible) {
            super.setVisible(visible);
            if (visible && loadedRoot == null && !environment.getText().isBlank()) load();
            if (visible) validateTarget();
        }
        void load() {
            loadedRoot = null; rows.clear(); hardware.removeAll(); setPageComplete(false);
            try {
                if (environment.getText().isBlank()) throw new IOException("Choose a completed Set Up Zephyr installation.");
                Path root = Path.of(environment.getText().trim()).toRealPath();
                var settings = ProjectContext.load(root); List<String[]> found = new ArrayList<>();
                getContainer().run(true, true, monitor -> {
                    monitor.beginTask("Load available Zephyr hardware", IProgressMonitor.UNKNOWN);
                    try {
                        Path helper = Path.of(FileLocator.toFileURL(Activator.getDefault().getBundle().getEntry("tools/hardware_catalog.py")).toURI());
                        new ProcessRunner().run(List.of(settings.getProperty("python"), helper.toString(), "--zephyr",
                                Path.of(settings.getProperty("workspace"), "zephyr").toString()), Path.of(settings.getProperty("workspace")),
                                Duration.ofMinutes(3), monitor::isCanceled, line -> {
                                    String[] cells = line.split("\t", -1); if (cells.length == 7) found.add(cells);
                                }, ProjectContext.environment(settings)).requireSuccess();
                    } catch (InterruptedException error) { throw error; }
                    catch (Exception error) { throw new InvocationTargetException(error); }
                    finally { monitor.done(); }
                });
                if (found.isEmpty()) throw new IOException("No hardware found. Repair or select another ready environment.");
                rows = found; loadedRoot = root; filter(); validateTarget();
            } catch (InterruptedException error) { setErrorMessage("Hardware discovery cancelled. Load hardware to retry."); }
            catch (Exception error) { setErrorMessage(message(error)); }
        }
        void filter() {
            String selected = defaultBoard(); hardware.removeAll();
            String query = filter.getText().trim().toLowerCase(Locale.ROOT);
            for (String[] row : rows) if (String.join(" ", row).toLowerCase(Locale.ROOT).contains(query)) {
                TableItem item = new TableItem(hardware, SWT.NONE); item.setData(row);
                item.setText(new String[] {row[0], row[2], row[3], row[6]});
                if (row[6].equals(selected)) hardware.setSelection(item);
            }
            validateTarget();
        }
        String defaultBoard() { return hardware != null && hardware.getSelectionCount() == 1 ? ((String[]) hardware.getSelection()[0].getData())[6] : ""; }
        String board(Entry entry) { return entry.board.isBlank() ? defaultBoard() : entry.board; }
        void validateTarget() {
            if (hardware == null) return;
            String error = null;
            try {
                if (loadedRoot == null) throw new IOException("Load hardware from a ready setup folder.");
                Path zephyr = Path.of(ProjectContext.load(loadedRoot).getProperty("workspace"), "zephyr").toRealPath();
                for (Entry entry : applications.selected()) {
                    if (entry.folder.toRealPath().startsWith(zephyr)) throw new IOException("Import standalone applications outside the upstream Zephyr tree.");
                    String board = board(entry);
                    if (board.isBlank()) throw new IOException("Select a default board for applications without a saved target.");
                    if (rows.stream().noneMatch(row -> row[6].equals(board))) throw new IOException("Saved target is not available in this environment: " + board);
                }
            } catch (Exception failure) { error = message(failure); }
            setErrorMessage(error); setPageComplete(error == null);
            if (error == null && defaultBoard().contains("/esp32s3/appcpu"))
                setMessage("APPCPU needs primary-core firmware. For standalone ESP32-S3 applications choose procpu.", WARNING);
            else setMessage("First build prepares matching target dependencies. Saved application targets are not replaced.");
        }
    }

    private final class ReviewPage extends WizardPage {
        Label summary;
        ReviewPage() { super("review"); setTitle("Review import"); setDescription("Confirm application names, original locations and exact targets before importing."); }
        @Override public void createControl(Composite parent) {
            Composite body = new Composite(parent, SWT.NONE); body.setLayout(new GridLayout(1, false));
            summary = new Label(body, SWT.WRAP); GridData data = new GridData(SWT.FILL, SWT.FILL, true, true); data.widthHint = 680; summary.setLayoutData(data);
            setControl(body); setPageComplete(true);
        }
        @Override public void setVisible(boolean visible) {
            if (visible) {
                StringBuilder text = new StringBuilder("Import in place. Source files remain at their existing locations.\n\nEnvironment: " + target.loadedRoot + "\n");
                for (Entry entry : applications.selected()) {
                    text.append("\n").append(entry.name).append("\n  ").append(entry.folder).append("\n  Target: ").append(target.board(entry)).append("\n");
                    if (entry.repair) text.append("  Repair: replace generic CDT CMake with the managed West builder.\n"
                            + "  Eclipse metadata backed up in .zew/import-backup; existing build files retained.\n");
                    else if (entry.existing != null) text.append("  Bind the existing workspace project to this Zephyr environment.\n");
                }
                text.append("\nOnly Workbench/Eclipse project metadata is added or bound. Eclipse Build Automatically may build imported projects. No firmware is flashed.");
                summary.setText(text.toString()); summary.getParent().layout(true, true);
            }
            super.setVisible(visible);
        }
    }

    @Override public boolean performFinish() {
        applications.validateSelection(); target.validateTarget();
        if (!applications.isPageComplete() || !target.isPageComplete()) return false;
        List<ImportItem> plan = applications.selected().stream().map(e -> new ImportItem(e.folder, e.name, target.board(e), e.repair)).toList();
        Path environment = target.loadedRoot; List<IProject> imported = new ArrayList<>();
        try {
            getContainer().run(true, true, monitor -> {
                monitor.beginTask("Import Zephyr applications", plan.size());
                try {
                    var workspace = ResourcesPlugin.getWorkspace();
                    workspace.run(progress -> {
                        try {
                            // Validate the entire selection before creating any project.
                            for (ImportItem item : plan) {
                                if (!ZephyrApplicationDiscovery.isApplication(item.folder()))
                                    throw new IOException("Application changed: " + item.folder());
                                IProject existing = ZephyrProjectImport.existingProject(item.folder());
                                if (workspace.getRoot().getProject(item.name()).exists()
                                        && !workspace.getRoot().getProject(item.name()).equals(existing))
                                    throw new IOException("Project name now exists: " + item.name());
                                if (existing != null && !existing.getName().equals(item.name()))
                                    throw new IOException("Workspace project changed; search again: " + item.folder());
                                Entry current = inspect(item.folder());
                                if (!current.problem.isBlank()) throw new IOException(current.problem);
                                if (current.repair != item.repair()) throw new IOException("Eclipse builder changed; search and review again: " + item.folder());
                                if (!current.board.isBlank() && !current.board.equals(item.board())) throw new IOException("Saved board changed; search again: " + item.folder());
                            }
                            for (ImportItem item : plan) {
                                if (progress.isCanceled()) throw new org.eclipse.core.runtime.OperationCanceledException();
                                IProject project = ZephyrProjectImport.bind(item.folder(), item.name(), item.board(), environment, progress);
                                imported.add(project); progress.worked(1);
                            }
                        } catch (org.eclipse.core.runtime.OperationCanceledException error) { throw error; }
                        catch (CoreException error) { throw error; }
                        catch (Exception error) { throw new CoreException(new Status(IStatus.ERROR, Activator.ID, message(error), error)); }
                    }, workspace.getRoot(), IWorkspace.AVOID_UPDATE, monitor);
                } catch (org.eclipse.core.runtime.OperationCanceledException error) { throw new InterruptedException("Import cancelled."); }
                catch (Exception error) { throw new InvocationTargetException(error); }
                finally { monitor.done(); }
            });
            var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
            page.showView("org.eclipse.ui.navigator.ProjectExplorer");
            for (IProject project : imported) EditorIntegration.schedule(project);
            if (!imported.isEmpty() && imported.get(0).getFile("src/main.c").exists()) IDE.openEditor(page, imported.get(0).getFile("src/main.c"));
            return true;
        } catch (Exception error) {
            for (TableItem item : applications.projects.getItems()) {
                Entry entry = (Entry) item.getData();
                if (imported.stream().anyMatch(p -> p.getName().equals(entry.name))) {
                    entry.problem = "Already imported"; item.setChecked(false); item.setGrayed(true); item.setText(2, entry.problem);
                }
            }
            applications.validateSelection();
            String completed = imported.isEmpty() ? "" : "\nAlready imported and retained: " + String.join(", ", imported.stream().map(IProject::getName).toList());
            MessageDialog.openError(getShell(), "Import Zephyr applications", message(error) + completed);
            // A partial import changes the checked selection. Return to the list
            // so the next attempt gets a fresh target/review step, not stale text.
            getContainer().showPage(applications);
            return false;
        }
    }

    private Text folderField(Composite parent, String label, String initial, Runnable selected) {
        new Label(parent, SWT.NONE).setText(label);
        Text text = new Text(parent, SWT.BORDER); text.setText(initial); text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button browse = new Button(parent, SWT.PUSH); browse.setText("Browse...");
        browse.addListener(SWT.Selection, e -> {
            DirectoryDialog dialog = new DirectoryDialog(getShell()); dialog.setFilterPath(text.getText());
            dialog.setMessage(label.equals("Root directory") ? "Select an application folder or the parent containing your extracted projects." : "Select a completed Set Up Zephyr installation.");
            String value = dialog.open(); if (value != null) { text.setText(value); selected.run(); }
        });
        return text;
    }
    private static void columns(Table table, String[] names, int[] widths) {
        for (int i = 0; i < names.length; i++) { TableColumn column = new TableColumn(table, SWT.NONE); column.setText(names[i]); column.setWidth(widths[i]); }
    }
    private static String message(Throwable error) {
        while ((error instanceof InvocationTargetException || error instanceof CoreException) && error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }
}
