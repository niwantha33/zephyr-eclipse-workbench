// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.QualifiedName;
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
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IImportWizard;
import org.eclipse.ui.INewWizard;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;

import io.github.niwantha33.zew.core.ZephyrProjectFiles;

/** New/Import Zephyr Application: reuse a verified installer without modifying user source. */
public final class ZephyrProjectWizard extends Wizard implements INewWizard, IImportWizard {
    private ProjectPage page;
    private String selectedRoot;

    public ZephyrProjectWizard() {
        setWindowTitle("Zephyr Project");
        setNeedsProgressMonitor(true);
    }

    @Override public void init(IWorkbench workbench, IStructuredSelection selection) {
        Object first = selection == null ? null : selection.getFirstElement();
        if (first instanceof IProject project) selectedRoot = knownRoot(project);
        if (selectedRoot == null) {
            for (var project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                selectedRoot = knownRoot(project);
                if (selectedRoot != null) break;
            }
        }
    }

    private static String knownRoot(IProject project) {
        try {
            String value = project.getPersistentProperty(new QualifiedName(Activator.ID, "setupRoot"));
            return value == null || value.isBlank() ? null : value;
        } catch (Exception ignored) { return null; }
    }

    @Override public void addPages() { page = new ProjectPage(); addPage(page); }

    private final class ProjectPage extends WizardPage {
        private Text installation;
        private Text name;
        private Text folder;
        private Button importExisting;
        private boolean changingDefault;

        ProjectPage() {
            super("project");
            setTitle("New or existing Zephyr application");
            setDescription("Use an existing verified Windows setup. No terminal or PATH changes; current compiler supports qemu_x86/atom only.");
        }

        @Override public void createControl(Composite parent) {
            var body = new Composite(parent, SWT.NONE);
            body.setLayout(new GridLayout(3, false));

            new Label(body, SWT.NONE).setText("Mode:");
            var create = new Button(body, SWT.RADIO); create.setText("Create new application");
            create.setSelection(true);
            var existing = new Button(body, SWT.RADIO); existing.setText("Import existing application");
            importExisting = existing;

            new Label(body, SWT.NONE).setText("Managed Zephyr installation:");
            installation = new Text(body, SWT.BORDER);
            installation.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            installation.setText(selectedRoot != null ? selectedRoot
                    : Path.of(System.getProperty("user.home"), "ZephyrWorkbench", "first-build-4.4.0").toString());
            addDirectoryPicker(body, installation);

            new Label(body, SWT.NONE).setText("Eclipse project name:");
            name = new Text(body, SWT.BORDER);
            name.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            name.setText("ZephyrApp");
            new Label(body, SWT.NONE);

            new Label(body, SWT.NONE).setText("Application folder:");
            folder = new Text(body, SWT.BORDER);
            folder.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            addDirectoryPicker(body, folder);

            var board = new Label(body, SWT.WRAP);
            board.setText("Build target: qemu_x86/atom (Zephyr 4.4.0; managed x86 toolchain). Other boards need a verified matching SDK and are not silently enabled.");
            board.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 3, 1));

            create.addListener(SWT.Selection, event -> modeChanged());
            existing.addListener(SWT.Selection, event -> modeChanged());
            installation.addModifyListener(event -> { updateDefaultPath(); validate(); });
            name.addModifyListener(event -> { updateDefaultPath(); validate(); });
            folder.addModifyListener(event -> validate());
            updateDefaultPath();
            setControl(body);
            validate();
        }

        private void modeChanged() { updateDefaultPath(); validate(); }

        private void updateDefaultPath() {
            if (folder == null || folder.isDisposed() || installation == null || name == null) return;
            if (importExisting.getSelection()) return;
            String value = name.getText().trim();
            if (value.isEmpty()) return;
            // Populate the beginner default only when the current folder is empty
            // or still under this installation's projects/ location.
            String current = folder.getText().trim();
            Path projects;
            try { projects = Path.of(installation.getText().trim(), "projects").toAbsolutePath().normalize(); }
            catch (RuntimeException ignored) { return; }
            if (!current.isBlank() && !Path.of(current).toAbsolutePath().normalize().startsWith(projects)) return;
            if (changingDefault) return;
            changingDefault = true;
            try { folder.setText(projects.resolve(value).toString()); }
            finally { changingDefault = false; }
        }

        private void addDirectoryPicker(Composite body, Text value) {
            var browse = new Button(body, SWT.PUSH); browse.setText("Browse...");
            browse.addListener(SWT.Selection, event -> {
                var chooser = new DirectoryDialog(getShell());
                chooser.setFilterPath(value.getText());
                String selected = chooser.open();
                if (selected != null) value.setText(selected);
            });
        }

        void validate() {
            String error = null;
            try {
                ZephyrProjectFiles.validateName(name.getText().trim());
                Path root = Path.of(installation.getText().trim()).toAbsolutePath().normalize();
                ZephyrProjectFiles.requireManagedEnvironment(root);
                Path source = Path.of(folder.getText().trim()).toAbsolutePath().normalize();
                if (folder.getText().isBlank() || !source.isAbsolute())
                    throw new IllegalArgumentException("Choose an absolute application folder.");
                if (source.startsWith(root.resolve("workspace/zephyr")))
                    throw new IllegalArgumentException("Keep applications outside the upstream Zephyr source checkout.");
                if (importExisting.getSelection()) {
                    if (!Files.isRegularFile(source.resolve("CMakeLists.txt")))
                        throw new IllegalArgumentException("Existing application needs CMakeLists.txt.");
                } else if (Files.exists(source)) {
                    if (!Files.isDirectory(source))
                        throw new IllegalArgumentException("Application path is a file.");
                    try (var children = Files.list(source)) {
                        if (children.findAny().isPresent())
                            throw new IllegalArgumentException("Folder is not empty. Choose Import Existing instead.");
                    }
                }
                IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name.getText().trim());
                if (project.exists() && !project.getLocation().toFile().toPath().toAbsolutePath().normalize().equals(source))
                    throw new IllegalArgumentException("That Eclipse project name already refers to another folder.");
            } catch (Exception exception) {
                error = exception.getMessage() == null ? "Check the environment and application paths." : exception.getMessage();
            }
            setErrorMessage(error);
            setPageComplete(error == null);
        }

        Path installation() { return Path.of(installation.getText().trim()).toAbsolutePath().normalize(); }
        Path folder() { return Path.of(folder.getText().trim()).toAbsolutePath().normalize(); }
        String projectName() { return name.getText().trim(); }
        boolean importing() { return importExisting.getSelection(); }
    }

    @Override public boolean performFinish() {
        try {
            if (!page.isPageComplete()) return false;
            ZephyrProjectFiles.requireManagedEnvironment(page.installation());
            Path source = page.importing()
                    ? ZephyrProjectFiles.importExisting(page.folder())
                    : ZephyrProjectFiles.createNew(page.folder(), page.projectName());
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(page.projectName());
            if (!project.exists()) {
                var desc = ResourcesPlugin.getWorkspace().newProjectDescription(page.projectName());
                desc.setLocationURI(source.toUri());
                project.create(desc, null);
            }
            project.open(null);
            project.setPersistentProperty(new QualifiedName(Activator.ID, "setupRoot"), page.installation().toString());
            var desc = project.getDescription();
            String builder = Activator.ID + ".zephyrBuilder";
            if (Arrays.stream(desc.getBuildSpec()).noneMatch(command -> builder.equals(command.getBuilderName()))) {
                ICommand newCommand = desc.newCommand();
                newCommand.setBuilderName(builder);
                var spec = new java.util.ArrayList<ICommand>(Arrays.asList(desc.getBuildSpec()));
                spec.add(newCommand);
                desc.setBuildSpec(spec.toArray(ICommand[]::new));
                project.setDescription(desc, null);
            }
            project.refreshLocal(IResource.DEPTH_INFINITE, null);
            var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window != null) {
                var workbenchPage = window.getActivePage();
                workbenchPage.showView("org.eclipse.ui.navigator.ProjectExplorer");
                if (!page.importing() && project.getFile("src/main.c").exists())
                    IDE.openEditor(workbenchPage, project.getFile("src/main.c"));
            }
            MessageDialog.openInformation(getShell(), "Zephyr project ready",
                    "Project " + page.projectName() + " uses qemu_x86/atom. Build it using Project > Build Project. Existing application source was preserved.");
            return true;
        } catch (Exception error) {
            MessageDialog.openError(getShell(), "Zephyr project", error.getMessage() == null ? error.toString() : error.getMessage());
            return false;
        }
    }
}
