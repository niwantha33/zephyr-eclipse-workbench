// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.ide.IDE;

import io.github.niwantha33.zew.core.ProcessRunner;
import io.github.niwantha33.zew.core.SetupFiles;

/** Beginner entry point. Java bootstraps Python; no terminal/package-manager prerequisite. */
public final class SetupWizard extends Wizard {
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    public static boolean isOperationActive() { return ACTIVE.get(); }
    private LocationPage location;
    private ReviewPage review;
    public SetupWizard() { setWindowTitle("Set Up Zephyr"); setNeedsProgressMonitor(true); }
    @Override public void addPages() { location = new LocationPage(); review = new ReviewPage(); addPage(location); addPage(review); }
    @Override public boolean canFinish() { return getContainer().getCurrentPage() == review && location.isPageComplete() && review.isPageComplete(); }

    private final class LocationPage extends WizardPage {
        Text folder;
        LocationPage() { super("location"); setTitle("Your first Zephyr build"); setDescription("Windows x64 • Zephyr 4.4.0 • No hardware or terminal required"); }
        @Override public void createControl(Composite parent) {
            var body = new Composite(parent, SWT.NONE); body.setLayout(new GridLayout(3, false));
            var intro = new Label(body, SWT.WRAP);
            intro.setText("We will install a private Python runtime, Git, build tools and one compiler, obtain Zephyr, create Hello World and verify a firmware build. Your existing environment stays separate.\n\nThis first profile compiles for QEMU x86 without a board. Running the emulator, physical-board setup, flashing and debugging are separate steps.");
            var introduction = new GridData(SWT.FILL, SWT.TOP, true, false, 3, 1); introduction.widthHint = 650; intro.setLayoutData(introduction);
            new Label(body, SWT.NONE).setText("Install folder:");
            folder = new Text(body, SWT.BORDER); folder.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            folder.setText(Path.of(System.getProperty("user.home"), "ZephyrWorkbench", "first-build-4.4.0").toString());
            var browse = new Button(body, SWT.PUSH); browse.setText("Browse...");
            browse.addListener(SWT.Selection, e -> { var picker = new DirectoryDialog(getShell()); picker.setFilterPath(folder.getText()); String value = picker.open(); if (value != null) folder.setText(value); });
            new Label(body, SWT.NONE).setText("Zephyr release:"); var release = new Combo(body, SWT.READ_ONLY); release.setItems("4.4.0 — pinned first-build profile"); release.select(0);
            new Label(body, SWT.NONE);
            new Label(body, SWT.NONE).setText("First target:"); var board = new Combo(body, SWT.READ_ONLY); board.setItems("No hardware — qemu_x86/atom"); board.select(0);
            new Label(body, SWT.NONE);
            var existing = new Label(body, SWT.WRAP); existing.setText("Already have Zephyr? Close this wizard and use Zephyr > Install / Manage Zephyr Tools to reuse it."); existing.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 3, 1));
            folder.addModifyListener(e -> validate()); setControl(body); validate();
        }
        void validate() {
            String error = null;
            if (!System.getProperty("os.name").startsWith("Windows") || !System.getProperty("os.arch").equals("amd64")) error = "This bootstrap profile supports native Windows x64. Use Tools Manager for existing environments on other hosts.";
            try {
                Path root = Path.of(folder.getText().trim());
                if (folder.getText().isBlank() || !root.isAbsolute()) error = "Choose an absolute installation folder.";
                else if (Files.exists(root) && !Files.isDirectory(root)) error = "The selected path is a file.";
                else if (Files.isDirectory(root) && !Files.isRegularFile(root.resolve(".zew-managed"))) {
                    try (var files = Files.list(root)) { if (files.findAny().isPresent()) error = "Choose an empty folder, or a previous workbench setup folder to retry. Existing files will not be replaced."; }
                }
            } catch (Exception exception) { error = "Choose a valid accessible installation folder."; }
            setErrorMessage(error); setPageComplete(error == null);
        }
    }
    private final class ReviewPage extends WizardPage {
        Text plan;
        ReviewPage() { super("review"); setTitle("Review and install"); setDescription("Review the pinned tools, their licences and the destination before starting."); }
        @Override public void createControl(Composite parent) {
            var body = new Composite(parent, SWT.NONE); body.setLayout(new GridLayout());
            plan = new Text(body, SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
            var data = new GridData(SWT.FILL, SWT.FILL, true, true); data.heightHint = 280; plan.setLayoutData(data);
            var link = new Link(body, SWT.NONE); link.setText("<a>Review dependency versions and licence records</a>");
            link.addListener(SWT.Selection, e -> {
                try {
                    Path notes = Activator.getDefault().getStateLocation().toFile().toPath().resolve("setup-licences.txt");
                    copyResource("tools/setup-licences.txt", notes); Program.launch(notes.toString());
                } catch (Exception error) { setErrorMessage(error.getMessage()); }
            });
            var agreement = new Button(body, SWT.CHECK); agreement.setText("I have reviewed the installation plan and licence records. Install these tools and build Hello World.");
            agreement.addListener(SWT.Selection, e -> setPageComplete(agreement.getSelection()));
            setControl(body); setPageComplete(false);
        }
        @Override public void setVisible(boolean visible) {
            if (visible && plan != null) plan.setText("Destination: " + location.folder.getText().trim()
                    + "\n\nPython 3.13.7 (PSF and bundled third-party notices)\nMinGit 2.50.1 (GPL and bundled component licences)"
                    + "\nWest 1.5.0, CMake 3.31.10, Ninja 1.13.2\nPinned Python dependencies; licences include MIT, BSD, Apache, PSF and LGPL"
                    + "\nZephyr 4.4.0 at immutable revision 684c9e8f32e4373a21098559f748f06915f950c9"
                    + "\nZephyr SDK 1.0.1; x86_64-zephyr-elf compiler only (GCC runtime exceptions apply)"
                    + "\n\nAllow approximately 1 GB of downloads and at least 5 GiB free disk space. Internet speed affects installation time."
                    + "\n\nVerified downloads are cached for retries. Interrupted installation stays marked incomplete. No administrator account, global PATH edits, vendor blobs or probe packages are required."
                    + "\n\nResult: editable Hello World source in Project Explorer and a verified firmware build. Emulator execution and physical hardware support are not part of this first profile.");
            super.setVisible(visible);
        }
    }
    private static void copyResource(String resource, Path output) throws Exception {
        Files.createDirectories(output.getParent());
        try (var stream = Activator.getDefault().getBundle().getEntry(resource).openStream()) { Files.copy(stream, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
    }
    @Override public boolean performFinish() {
        if (WorkbenchHandler.isOperationActive() || ToolsDialog.isOperationActive()
                || Job.getJobManager().find(ResourcesPlugin.FAMILY_MANUAL_BUILD).length != 0
                || !ACTIVE.compareAndSet(false, true)) { MessageDialog.openInformation(getShell(), "Setup busy", "Finish the current build or setup operation first."); return false; }
        Path root = Path.of(location.folder.getText().trim()).toAbsolutePath().normalize();
        var console = new MessageConsole("Zephyr Beginner Setup", null); ConsolePlugin.getDefault().getConsoleManager().addConsoles(new org.eclipse.ui.console.IConsole[] {console});
        ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
        try (var output = console.newMessageStream()) {
            getContainer().run(true, true, monitor -> {
                try {
                    monitor.beginTask("Set up Zephyr and build Hello World", IProgressMonitor.UNKNOWN);
                    Files.createDirectories(root);
                    if (!Files.isRegularFile(root.resolve(".zew-managed"))) {
                        try (var files = Files.list(root)) { if (files.findAny().isPresent()) throw new java.io.IOException("Installation folder is not empty; existing files were preserved."); }
                        Files.writeString(root.resolve(".zew-managed"), "first-build-win-x64-v1\n", StandardOpenOption.CREATE_NEW);
                    }
                    try (var channel = FileChannel.open(root.resolve(".zew-install.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.tryLock()) {
                        if (lock == null) throw new java.io.IOException("Another Eclipse instance is using this installation folder.");
                        var report = (java.util.function.Consumer<String>) text -> { monitor.subTask(text); output.println(text); };
                        if (!Files.readString(root.resolve(".zew-managed")).equals("first-build-win-x64-v1\n")) throw new java.io.IOException("This directory belongs to a different setup profile.");
                        if (Files.isRegularFile(root.resolve(".zew-ready")) && Files.isRegularFile(root.resolve("environment.properties"))
                                && Files.isRegularFile(root.resolve("projects/hello_world/.zew-build/first/zephyr/zephyr.elf"))) {
                            report.accept("Reusing the completed managed environment. Existing tools and project files were preserved."); return;
                        }
                        report.accept("Downloading verified portable Python and Git");
                        var pythonZip = SetupFiles.download(URI.create("https://www.python.org/ftp/python/3.13.7/python-3.13.7-embed-amd64.zip"), root.resolve("cache/python.zip"),
                                10922561, "f6cca216a359be84797cabb54149ce5e062afb16cc7567eb7fc51cacb2d86b65", monitor::isCanceled, report);
                        var gitZip = SetupFiles.download(URI.create("https://github.com/git-for-windows/git/releases/download/v2.50.1.windows.1/MinGit-2.50.1-64-bit.zip"), root.resolve("cache/git.zip"),
                                47953778, "6f672aebe9e488a246efd6875f9197dbc0d9a40100e218acc3877cba2b206c45", monitor::isCanceled, report);
                        report.accept("Extracting isolated Python and Git");
                        SetupFiles.unzip(pythonZip, root.resolve("python"), monitor::isCanceled);
                        SetupFiles.unzip(gitZip, root.resolve("git"), monitor::isCanceled);
                        Files.writeString(root.resolve("python/python313._pth"), "python313.zip\n.\nLib/site-packages\nimport site\n");
                        Files.writeString(root.resolve("python/.zew-managed-python"), "Isolated workbench runtime\n");
                        copyResource("tools/setup.py", root.resolve("installer/setup.py"));
                        copyResource("tools/managed_sitecustomize.py", root.resolve("installer/managed_sitecustomize.py"));
                        copyResource("tools/setup-lock.json", root.resolve("installer/setup-lock.json"));
                        copyResource("tools/setup-licences.txt", root.resolve("installer/setup-licences.txt"));
                        new ProcessRunner().run(List.of(root.resolve("python/python.exe").toString(), "-u", root.resolve("installer/setup.py").toString(),
                                "--root", root.toString(), "--lock", root.resolve("installer/setup-lock.json").toString()), root,
                                Duration.ofHours(2), monitor::isCanceled, report).requireSuccess();
                        if (!Files.isRegularFile(root.resolve(".zew-ready"))) throw new java.io.IOException("Setup did not produce a verified first build.");
                    }
                } catch (java.io.InterruptedIOException error) { throw new InterruptedException("Setup cancelled. Completed downloads are preserved for retry."); }
                catch (InterruptedException error) { throw error; }
                catch (Exception error) { throw new java.lang.reflect.InvocationTargetException(error); }
                finally { monitor.done(); }
            });
            importProject(root);
            return true;
        } catch (InterruptedException error) { MessageDialog.openInformation(getShell(), "Setup cancelled", "Completed downloads were preserved. Open Set Up Zephyr again with the same folder to retry."); return false; }
        catch (Exception error) { Throwable cause = error.getCause() == null ? error : error.getCause(); MessageDialog.openError(getShell(), "Zephyr setup", cause.getMessage() + "\n\nSee the Zephyr Beginner Setup console. Retry the same folder to reuse verified downloads."); return false; }
        finally { ACTIVE.set(false); }
    }
    private void importProject(Path root) throws Exception {
        var properties = new Properties(); try (var reader = Files.newBufferedReader(root.resolve("environment.properties"), StandardCharsets.UTF_8)) { properties.load(reader); }
        Path source = Path.of(properties.getProperty("application"));
        var workspace = ResourcesPlugin.getWorkspace();
        String name = "HelloZephyr";
        var project = workspace.getRoot().getProject(name);
        if (project.exists() && !project.getLocationURI().equals(source.toUri())) {
            name = "HelloZephyr-" + Integer.toUnsignedString(root.toString().hashCode(), 16); project = workspace.getRoot().getProject(name);
            if (project.exists() && !project.getLocationURI().equals(source.toUri())) throw new java.io.IOException("A project with this name exists at a different location. Existing project preserved.");
        }
        if (!project.exists()) { var description = workspace.newProjectDescription(name); description.setLocationURI(source.toUri()); project.create(description, null); }
        project.open(null);
        project.setPersistentProperty(new QualifiedName(Activator.ID, "setupRoot"), root.toString());
        var description = project.getDescription();
        String builderId = Activator.ID + ".zephyrBuilder";
        if (java.util.Arrays.stream(description.getBuildSpec()).noneMatch(command -> command.getBuilderName().equals(builderId))) {
            var command = description.newCommand(); command.setBuilderName(builderId); var commands = new java.util.ArrayList<>(List.of(description.getBuildSpec())); commands.add(command); description.setBuildSpec(commands.toArray(org.eclipse.core.resources.ICommand[]::new)); project.setDescription(description, null);
        }
        var store = Activator.getDefault().getPreferenceStore();
        if (store.getString(WorkbenchPreferences.WORKSPACE).isBlank())
            for (String key : properties.stringPropertyNames()) store.setValue(key, properties.getProperty(key));
        project.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE, null);
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        page.showView("org.eclipse.ui.navigator.ProjectExplorer");
        EditorIntegration.schedule(project);
        IDE.openEditor(page, project.getFile("src/main.c"));
        MessageDialog.openInformation(getShell(), "Your first Zephyr build is ready", "Hello World compiled successfully for qemu_x86/atom.\n\nYour editable source is open. Use Project > Build Project to rebuild.\nEmulator execution, flashing and debugging are separate steps.");
    }
}


