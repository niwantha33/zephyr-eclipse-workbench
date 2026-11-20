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
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.*;
import org.eclipse.ui.ide.IDE;
import io.github.niwantha33.zew.core.*;

/** Create/import independent applications without changing environment-owned source. */
public class ZephyrProjectWizard extends Wizard implements INewWizard, IImportWizard {
    private Page page;
    private ReviewPage review;
    private String selectedRoot;
    protected boolean importing;
    public ZephyrProjectWizard() { setWindowTitle("Zephyr Project"); setNeedsProgressMonitor(true); }
    @Override public void init(IWorkbench workbench, IStructuredSelection selection) {
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            try { selectedRoot = project.getPersistentProperty(new QualifiedName(Activator.ID, "setupRoot")); if (selectedRoot != null) break; }
            catch (CoreException ignored) { }
        }
    }
    @Override public void addPages() {
        page = new Page();
        review = new ReviewPage();
        addPage(page);
        addPage(review);
    }
    /** Confirm the exact selected hardware and destination before any file writes. */
    /** Explicit review before project creation or import and any optional build. */
    private final class ReviewPage extends WizardPage {
        Label summary;
        Button buildAfterFinish;
        Boolean lastImportMode;

        ReviewPage() {
            super("review");
            setTitle("Review before finishing");
            setDescription("Step 2 of 2: Confirm the application, exact board and build preference.");
        }

        @Override public void createControl(Composite parent) {
            Composite body = new Composite(parent, SWT.NONE);
            GridLayout layout = new GridLayout(1, false);
            layout.marginWidth = 16;
            layout.marginHeight = 16;
            layout.verticalSpacing = 14;
            body.setLayout(layout);

            Label heading = new Label(body, SWT.NONE);
            heading.setText("Please check these details");
            heading.setFont(org.eclipse.jface.resource.JFaceResources.getFontRegistry()
                    .getBold(org.eclipse.jface.resource.JFaceResources.DIALOG_FONT));

            summary = new Label(body, SWT.WRAP);
            summary.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

            buildAfterFinish = new Button(body, SWT.CHECK);
            buildAfterFinish.setText("Build firmware after Finish");
            buildAfterFinish.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            buildAfterFinish.setToolTipText("May prepare pinned SDK and West modules. "
                    + "Leave unchecked to import or create without building.");
            Label buildHint = new Label(body, SWT.WRAP);
            buildHint.setText("Building can download verified dependencies. "
                    + "You can always build later from Project > Build Project.");
            buildHint.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

            setControl(body);
            setPageComplete(true);
        }

        @Override public void setVisible(boolean visible) {
            if (visible && summary != null && !summary.isDisposed()) {
                String[] target = page.targets.getSelectionCount() == 1
                        ? (String[]) page.targets.getSelection()[0].getData() : null;
                boolean importMode = page.existing.getSelection();
                if (lastImportMode == null || lastImportMode.booleanValue() != importMode) {
                    // New projects retain the familiar one-click initial build.
                    // Imported applications default to no automatic build.
                    buildAfterFinish.setSelection(!importMode);
                    lastImportMode = importMode;
                }
                summary.setText("Action: " + (importMode
                        ? "Import existing application in its original folder"
                        : "Create new standalone application")
                        + "\nProject name: " + page.name.getText().trim()
                        + "\nFolder: " + page.location.getText().trim()
                        + "\nChip vendor: " + (target == null ? "not selected" : target[0])
                        + "\nSoC: " + (target == null ? "not selected" : target[2])
                        + "\nCPU / qualifier: " + (target == null ? "not selected" : target[5])
                        + "\nExact target: " + (target == null ? "not selected" : target[6])
                        + "\n\n" + (importMode
                        ? "The application source files remain in this folder. "
                                + "The Workbench adds project metadata when needed."
                        : "The Workbench creates initial Zephyr source and configuration files "
                                + "in an empty destination."));
                summary.getParent().layout(true, true);
            }
            super.setVisible(visible);
        }
    }
    private final class Page extends WizardPage {
        Text name, location, environment, search;
        Button existing, createNew, continueButton, reloadBoards;
        Label modeHelp, resultsHelp, selectedTarget;
        String suggestedLocation = "";
        static final int RESULT_LIMIT = 120;
        Combo vendor, soc;
        Table targets;
        List<String[]> rows = new ArrayList<>();
        Path loadedRoot;
        Page() { super("project"); setTitle("Create or import a Zephyr application"); setDescription("Step 1 of 2: Choose the application, environment and target board."); }
        @Override public void createControl(Composite parent) {
            var scroll = new ScrolledComposite(parent, SWT.V_SCROLL | SWT.H_SCROLL);
            scroll.setExpandHorizontal(true);
            scroll.setExpandVertical(true);
            var body = new Composite(scroll, SWT.NONE);
            GridLayout pageLayout = new GridLayout(1, false);
            pageLayout.marginWidth = 10;
            pageLayout.marginHeight = 10;
            pageLayout.verticalSpacing = 10;
            body.setLayout(pageLayout);

            Group task = section(body, "1   What do you want to do?", 2);
            createNew = new Button(task, SWT.RADIO);
            createNew.setText("Create a new application");
            createNew.setToolTipText("Create a standalone Zephyr application in a new or empty folder.");
            createNew.setSelection(!importing);
            existing = new Button(task, SWT.RADIO);
            existing.setText("Import an existing application");
            existing.setToolTipText("Use the existing CMakeLists.txt and source in its original folder.");
            existing.setSelection(importing);
            modeHelp = new Label(task, SWT.WRAP);
            modeHelp.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));

            Group projectDetails = section(body, "2   Project details", 3);
            name = field(projectDetails, "Project name", "MyZephyr", false);
            location = field(projectDetails, "Application folder", "", true);
            new Label(projectDetails, SWT.WRAP).setText(
                    "For an import, browse to the folder containing CMakeLists.txt. Files are not copied.");
            GridData detailsHint = new GridData(SWT.FILL, SWT.TOP, true, false, 3, 1);
            projectDetails.getChildren()[projectDetails.getChildren().length - 1].setLayoutData(detailsHint);

            Group tools = section(body, "3   Zephyr environment", 3);
            String workspace = Activator.getDefault().getPreferenceStore().getString("workspace");
            String initial = selectedRoot != null ? selectedRoot
                    : workspace.isBlank() ? "" : Path.of(workspace).getParent().toString();
            environment = field(tools, "Ready setup folder", initial, true);
            reloadBoards = new Button(tools, SWT.PUSH);
            reloadBoards.setText("Reload available boards");
            reloadBoards.setToolTipText("Read boards from the selected ready Zephyr installation.");
            reloadBoards.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 3, 1));
            reloadBoards.addListener(SWT.Selection, event -> load());

            Group hardware = section(body, "4   Select an exact target board", 3);
            vendor = combo(hardware, "Chip vendor");
            soc = combo(hardware, "SoC");
            search = field(hardware, "Search board or processor", "", false);
            search.setMessage("e.g. mspm0g3507, nrf5340, esp32s3");
            Label hint = new Label(hardware, SWT.WRAP);
            hint.setText("Search by board, chip or CPU. Click a row to select the exact build target.");
            hint.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 3, 1));
            resultsHelp = new Label(hardware, SWT.WRAP);
            resultsHelp.setText("Board list not loaded.");
            resultsHelp.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 3, 1));
            targets = new Table(hardware, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION
                    | SWT.V_SCROLL | SWT.H_SCROLL);
            targets.setHeaderVisible(true);
            var targetData = new GridData(SWT.FILL, SWT.FILL, true, true, 3, 1);
            targetData.heightHint = 192;
            targetData.minimumHeight = 150;
            targets.setLayoutData(targetData);
            for (String title : List.of("Board maker", "Board", "Revision", "Exact target")) {
                var column = new TableColumn(targets, SWT.NONE);
                column.setText(title);
                column.setWidth(title.equals("Exact target") ? 330 : 130);
            }
            selectedTarget = new Label(hardware, SWT.WRAP);
            selectedTarget.setText("Selected target: none");
            selectedTarget.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
            continueButton = new Button(hardware, SWT.PUSH);
            continueButton.setText("Review selected project  >");
            continueButton.setToolTipText("Review the destination and exact target before Finish.");
            continueButton.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false, 3, 1));
            continueButton.setEnabled(false);
            continueButton.addListener(SWT.Selection, event -> continueToReview());

            scroll.setContent(body);
            scroll.setMinSize(body.computeSize(720, SWT.DEFAULT));
            setControl(scroll);
            setPageComplete(false);

            vendor.addListener(SWT.Selection, event -> { fillSoc(); filter(); });
            soc.addListener(SWT.Selection, event -> filter());
            search.addModifyListener(event -> filter());
            targets.addListener(SWT.Selection, event -> validatePage());
            targets.addListener(SWT.DefaultSelection, event -> continueToReview());
            name.addModifyListener(event -> {
                updateSuggestedLocation();
                validatePage();
            });
            location.addModifyListener(event -> {
                restoreSavedTarget();
                validatePage();
            });
            environment.addModifyListener(event -> {
                updateSuggestedLocation();
                validatePage();
            });
            createNew.addListener(SWT.Selection, event -> { if (createNew.getSelection()) changeMode(); });
            existing.addListener(SWT.Selection, event -> { if (existing.getSelection()) changeMode(); });
            modeHelp.setText(modeDescription());
            updateSuggestedLocation();
            validatePage();

            // Local board discovery from the already selected environment.
            body.getDisplay().asyncExec(() -> {
                if (!body.isDisposed() && !environment.getText().isBlank()) load();
            });
        }

        private Group section(Composite parent, String title, int columns) {
            var group = new Group(parent, SWT.NONE);
            group.setText(title);
            GridLayout layout = new GridLayout(columns, false);
            layout.marginWidth = 12;
            layout.marginHeight = 10;
            layout.verticalSpacing = 8;
            group.setLayout(layout);
            group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
            return group;
        }

        private Text field(Composite parent, String title, String value, boolean browse) {
            new Label(parent, SWT.NONE).setText(title);
            var text = new Text(parent, SWT.BORDER);
            text.setText(value);
            text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            if (browse) {
                var browseButton = new Button(parent, SWT.PUSH);
                browseButton.setText("Browse...");
                browseButton.addListener(SWT.Selection, event -> {
                    var dialog = new DirectoryDialog(getShell());
                    if (!text.getText().isBlank()) dialog.setFilterPath(text.getText());
                    String chosen = dialog.open();
                    if (chosen != null) text.setText(chosen);
                });
            } else {
                new Label(parent, SWT.NONE);
            }
            return text;
        }

        private Combo combo(Composite parent, String title) {
            new Label(parent, SWT.NONE).setText(title);
            var combo = new Combo(parent, SWT.READ_ONLY);
            combo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            new Label(parent, SWT.NONE);
            return combo;
        }

        private String modeDescription() {
            return existing.getSelection()
                    ? "Import: select a folder with CMakeLists.txt. Existing source stays in place; "
                            + "the Workbench adds project metadata when needed."
                    : "Create: make a basic standalone Zephyr application in a new or empty folder. "
                            + "Existing files will not be overwritten.";
        }

        private void changeMode() {
            if (existing.getSelection() && location.getText().equals(suggestedLocation))
                location.setText("");
            if (createNew.getSelection()) updateSuggestedLocation();
            modeHelp.setText(modeDescription());
            setDescription(existing.getSelection()
                    ? "Step 1 of 2: Import an existing application without copying its source."
                    : "Step 1 of 2: Create a new standalone application.");
            restoreSavedTarget();
            modeHelp.getParent().layout(true, true);
            validatePage();
        }

        private void updateSuggestedLocation() {
            if (existing == null || existing.getSelection() || name == null || environment == null)
                return;
            String root = environment.getText().trim();
            String next = root.isBlank() ? "" : Path.of(root, "projects", name.getText().trim()).toString();
            if (location.getText().isBlank() || location.getText().equals(suggestedLocation)) {
                suggestedLocation = next;
                if (!next.isBlank()) location.setText(next);
            }
        }

        private void restoreSavedTarget() {
            if (existing == null || !existing.getSelection() || loadedRoot == null
                    || location == null || location.getText().isBlank() || search == null
                    || targets == null || rows.isEmpty()) return;
            try {
                Path application = Path.of(location.getText().trim());
                String saved = null;
                if (Files.isRegularFile(application.resolve(".zew/project.json")))
                    saved = ProjectProfile.read(application).board();
                else if (Files.isRegularFile(application.resolve(".zew/project.properties")))
                    saved = ZephyrProjectFiles.readBoard(application);
                if (saved == null || saved.isBlank()) return;
                for (String[] row : rows) {
                    if (!saved.equals(row[6])) continue;
                    // Search covers the exact target, avoiding a huge unrelated board list.
                    if (!search.getText().equals(saved)) search.setText(saved);
                    for (TableItem item : targets.getItems()) {
                        if (saved.equals(((String[]) item.getData())[6])) {
                            targets.setSelection(item);
                            validatePage();
                            return;
                        }
                    }
                }
            } catch (Exception ignored) { /* Preserve validation; do not guess. */ }
        }

        private void continueToReview() {
            validatePage();
            if (isPageComplete()) getContainer().showPage(review);
        }

        void load() {
            reloadBoards.setEnabled(false);
            setErrorMessage(null);
            setMessage("Reading available boards from the selected Zephyr environment...",
                    IMessageProvider.INFORMATION);
            setPageComplete(false);
            try {
                if (environment.getText().isBlank())
                    throw new IllegalArgumentException("Choose your ready Zephyr setup folder first.");
                Path root = Path.of(environment.getText().trim()).toAbsolutePath().normalize();
                var settings = ProjectContext.load(root);
                var discovered = new ArrayList<String[]>();
                getContainer().run(true, true, monitor -> {
                    try {
                        var helper = Path.of(FileLocator.toFileURL(Activator.getDefault()
                                .getBundle().getEntry("tools/hardware_catalog.py")).toURI());
                        monitor.beginTask("Discover Zephyr hardware", IProgressMonitor.UNKNOWN);
                        new ProcessRunner().run(
                                List.of(settings.getProperty("python"), helper.toString(),
                                        "--zephyr",
                                        Path.of(settings.getProperty("workspace"), "zephyr").toString()),
                                Path.of(settings.getProperty("workspace")), Duration.ofMinutes(3),
                                monitor::isCanceled, line -> {
                                    String[] cells = line.split("\t", -1);
                                    if (cells.length == 7) discovered.add(cells);
                                }, ProjectContext.environment(settings)).requireSuccess();
                    } catch (InterruptedException error) {
                        throw error;
                    } catch (Exception error) {
                        throw new java.lang.reflect.InvocationTargetException(error);
                    } finally {
                        monitor.done();
                    }
                });
                if (discovered.isEmpty())
                    throw new java.io.IOException("No boards found in this Zephyr environment.");
                rows = discovered;
                loadedRoot = root;
                vendor.setItems(java.util.stream.Stream.concat(
                        java.util.stream.Stream.of("All vendors"),
                        rows.stream().map(row -> row[0]).distinct().sorted())
                        .toArray(String[]::new));
                vendor.select(0);
                fillSoc();
                filter();
                restoreSavedTarget();
                validatePage();
            } catch (Exception error) {
                loadedRoot = null;
                rows = new ArrayList<>();
                vendor.removeAll();
                soc.removeAll();
                targets.removeAll();
                resultsHelp.setText("Board list unavailable. Check the setup folder, then reload.");
                selectedTarget.setText("Selected target: none");
                continueButton.setEnabled(false);
                String cause = error.getCause() == null ? error.getMessage() : error.getCause().getMessage();
                setErrorMessage(cause == null ? "Unable to load board list." : cause);
                setPageComplete(false);
            } finally {
                reloadBoards.setEnabled(true);
            }
        }

        void fillSoc() {
            soc.setItems(java.util.stream.Stream.concat(
                    java.util.stream.Stream.of("All SoCs"),
                    rows.stream().filter(row -> vendor.getSelectionIndex() == 0
                            || row[0].equals(vendor.getText())).map(row -> row[2]).distinct().sorted())
                    .toArray(String[]::new));
            soc.select(0);
        }

        void filter() {
            if (targets == null || resultsHelp == null) return;
            String priorTarget = targets.getSelectionCount() == 1
                    ? ((String[]) targets.getSelection()[0].getData())[6] : null;
            targets.removeAll();
            String query = search.getText().trim().toLowerCase(Locale.ROOT);
            int found = 0;
            for (String[] row : rows) {
                if (vendor.getSelectionIndex() > 0 && !row[0].equals(vendor.getText())) continue;
                if (soc.getSelectionIndex() > 0 && !row[2].equals(soc.getText())) continue;
                if (!String.join(" ", row).toLowerCase(Locale.ROOT).contains(query)) continue;
                found++;
                if (found > RESULT_LIMIT) continue;
                var item = new TableItem(targets, SWT.NONE);
                item.setText(new String[] {row[1], row[3], row[4], row[6]});
                item.setData(row);
                if (row[6].equals(priorTarget)) targets.setSelection(item);
            }
            if (rows.isEmpty()) {
                resultsHelp.setText("No boards loaded. Select a ready environment and reload.");
            } else if (found == 0) {
                resultsHelp.setText("No matching boards. Try a shorter search or All vendors.");
            } else if (found > RESULT_LIMIT) {
                resultsHelp.setText("Showing first " + RESULT_LIMIT + " of " + found
                        + " targets. Type your board or SoC to narrow the results.");
            } else {
                resultsHelp.setText(found + (found == 1 ? " matching target" : " matching targets")
                        + ". Click a row below to select.");
            }
            validatePage();
        }

        void validatePage() {
            if (targets == null || selectedTarget == null) return;
            String error = null;
            String guidance = null;
            boolean ready = false;
            try {
                String projectName = name.getText().trim();
                String pathText = location.getText().trim();
                if (!ResourcesPlugin.getWorkspace().validateName(projectName, IResource.PROJECT).isOK())
                    error = "Enter a valid Eclipse project name.";
                else if (ResourcesPlugin.getWorkspace().getRoot().getProject(projectName).exists())
                    error = "That project name already exists in Eclipse.";
                else if (pathText.isBlank())
                    guidance = existing.getSelection()
                            ? "Browse to your existing Zephyr application (folder with CMakeLists.txt)."
                            : "Choose a folder for your new application.";
                else if (!Path.of(pathText).isAbsolute())
                    error = "Choose an absolute application folder.";
                else if (environment.getText().isBlank())
                    guidance = "Choose a ready Zephyr environment, then load the board list.";
                else if (loadedRoot == null || !loadedRoot.equals(
                        Path.of(environment.getText().trim()).toAbsolutePath().normalize()))
                    guidance = "Reload boards from the selected Zephyr environment.";
                else if (targets.getSelectionCount() != 1)
                    guidance = "Search for your board and select one exact target in the list.";
                else {
                    Path source = Path.of(pathText).toAbsolutePath().normalize();
                    Path zephyr = Path.of(ProjectContext.load(loadedRoot)
                            .getProperty("workspace"), "zephyr").toRealPath();
                    Path ancestor = source;
                    while (ancestor != null && !Files.exists(ancestor)) ancestor = ancestor.getParent();
                    if (ancestor != null && ancestor.toRealPath().startsWith(zephyr)) {
                        error = "Choose an application folder outside the Zephyr source tree.";
                    } else if (existing.getSelection()
                            && !Files.isRegularFile(source.resolve("CMakeLists.txt"))) {
                        error = "This folder is not a Zephyr application (CMakeLists.txt is missing).";
                    } else if (!existing.getSelection() && Files.exists(source)) {
                        try (var files = Files.list(source)) {
                            if (files.findAny().isPresent())
                                error = "Folder contains files. Choose an empty folder or Import.";
                        }
                    }
                    if (error == null && existing.getSelection()
                            && Files.exists(source.resolve(".project"))) {
                        error = "This folder has Eclipse metadata. Use File > Import > Existing Projects.";
                    }
                    String selectedBoard = ((String[]) targets.getSelection()[0].getData())[6];
                    if (error == null && existing.getSelection()
                            && Files.isRegularFile(source.resolve(".zew/project.properties"))
                            && !ZephyrProjectFiles.readBoard(source).equals(selectedBoard)) {
                        error = "Selected target conflicts with the application's saved board.";
                    }
                    if (error == null && existing.getSelection()
                            && Files.isRegularFile(source.resolve(".zew/project.json"))
                            && !ProjectProfile.read(source).board().equals(selectedBoard)) {
                        error = "Selected target conflicts with the application's saved board.";
                    }
                    ready = error == null;
                }
            } catch (Exception errorCause) {
                error = errorCause.getMessage() == null
                        ? "Check the application path and environment." : errorCause.getMessage();
            }
            String exact = targets.getSelectionCount() == 1
                    ? ((String[]) targets.getSelection()[0].getData())[6] : null;
            selectedTarget.setText("Selected target: " + (exact == null ? "none" : exact));
            if (error == null && guidance == null && exact != null
                    && exact.contains("/esp32s3/appcpu")) {
                setMessage("APPCPU is the secondary core; for standalone Hello World choose "
                        + "esp32s3/procpu.", IMessageProvider.WARNING);
            } else {
                setMessage(guidance == null
                        ? "Select Next or Review selected project to verify the destination and hardware."
                        : guidance, IMessageProvider.INFORMATION);
            }
            setErrorMessage(error);
            setPageComplete(ready);
            if (continueButton != null && !continueButton.isDisposed())
                continueButton.setEnabled(ready);
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
            // Only the explicitly selected review option may schedule a build.
            // Importing an existing app defaults to preserving its source without
            // launching downloads or compilation.
            if (review.buildAfterFinish != null && review.buildAfterFinish.getSelection()) {
                var initialBuild = new org.eclipse.core.runtime.jobs.Job("Prepare and build Zephyr — " + project.getName()) {
                    @Override protected org.eclipse.core.runtime.IStatus run(IProgressMonitor monitor) {
                        try {
                            project.build(IncrementalProjectBuilder.FULL_BUILD,
                                Activator.ID + ".zephyrBuilder", java.util.Map.of(), monitor);
                            return monitor.isCanceled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
                        } catch (CoreException error) {
                            return error.getStatus();
                        }
                    }
                };
                initialBuild.setUser(true);
                initialBuild.schedule();
            }
            return true;
        } catch (Exception error) { MessageDialog.openError(getShell(), "Zephyr project", error.getMessage()); return false; }
    }
}
