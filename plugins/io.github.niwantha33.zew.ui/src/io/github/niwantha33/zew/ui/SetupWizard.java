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
import java.util.ArrayList;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
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
        private Combo release;
        private Label target;
        private Label releaseStatus;
        private Button check;
        private Job catalogJob;
        private String suggested;
        private List<String> tags = List.of(SetupReleaseCatalog.PINNED);
        String tag() { return tags.get(Math.max(0, release.getSelectionIndex())); }
        boolean firstBuild() { return SetupReleaseCatalog.PINNED.equals(tag()); }
        String marker() { return firstBuild() ? "first-build-win-x64-v1\n" : "source-only-win-x64-v1:" + tag() + "\n"; }
        private String suggestedFolder() {
            return Path.of(System.getProperty("user.home"), "ZephyrWorkbench",
                    (firstBuild() ? "first-build-" : "source-") + tag().substring(1)).toString();
        }
        LocationPage() { super("location"); setTitle("Your first Zephyr environment"); setDescription("Windows x64 • Isolated setup • No terminal required"); }
        @Override public void createControl(Composite parent) {
            var body = new Composite(parent, SWT.NONE); body.setLayout(new GridLayout(3, false));
            var intro = new Label(body, SWT.WRAP);
            intro.setText("Choose a Zephyr release. The pinned first-build profile installs the reviewed tools and compiles Hello World. Other official releases can be downloaded with their West modules into a separate source workspace. SDK and build qualification for those releases are separate steps. Existing environments stay unchanged.");
            var introduction = new GridData(SWT.FILL, SWT.TOP, true, false, 3, 1); introduction.widthHint = 650; intro.setLayoutData(introduction);
            new Label(body, SWT.NONE).setText("Install folder:");
            folder = new Text(body, SWT.BORDER); folder.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            folder.setText(Path.of(System.getProperty("user.home"), "ZephyrWorkbench", "first-build-4.4.0").toString());
            suggested = folder.getText();
            var browse = new Button(body, SWT.PUSH); browse.setText("Browse...");
            browse.addListener(SWT.Selection, e -> { var picker = new DirectoryDialog(getShell()); picker.setFilterPath(folder.getText()); String value = picker.open(); if (value != null) folder.setText(value); });
            new Label(body, SWT.NONE).setText("Zephyr release:");
            release = new Combo(body, SWT.READ_ONLY);
            release.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            release.setItems(SetupReleaseCatalog.label(SetupReleaseCatalog.PINNED)); release.select(0);
            check = new Button(body, SWT.PUSH); check.setText("Check releases...");
            check.setToolTipText("Contact the official Zephyr GitHub release API; no installation.");
            check.addListener(SWT.Selection, e -> refreshReleases());
            release.addListener(SWT.Selection, e -> selectionChanged());
            releaseStatus = new Label(body, SWT.WRAP);
            releaseStatus.setText("Offline: the pinned profile is available. Check releases to discover other stable versions.");
            var statusData = new GridData(SWT.FILL, SWT.TOP, true, false, 3, 1); statusData.widthHint = 650;
            releaseStatus.setLayoutData(statusData);
            new Label(body, SWT.NONE).setText("Setup target:");
            target = new Label(body, SWT.WRAP);
            target.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
            var existing = new Link(body, SWT.WRAP);
            existing.setText("Already have Zephyr? <a>Open Install / Manage Zephyr Tools</a> to reuse it.");
            existing.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 3, 1));
            existing.addListener(SWT.Selection, e -> new ToolsDialog(getShell()).open());
            body.addDisposeListener(e -> { if (catalogJob != null) catalogJob.cancel(); });
            folder.addModifyListener(e -> validate()); setControl(body); selectionChanged();
        }
        private void selectionChanged() {
            String next = suggestedFolder();
            if (folder.getText().equals(suggested)) folder.setText(next);
            suggested = next;
            target.setText(firstBuild() ? "No hardware — qemu_x86/atom (compile only)"
                    : "Source and West modules only — SDK and first build not included");
            target.getParent().layout(true, true);
            validate();
        }
        private void refreshReleases() {
            check.setEnabled(false);
            releaseStatus.setText("Checking official stable releases...");
            var display = check.getDisplay();
            catalogJob = new Job("Check official Zephyr releases") {
                @Override protected IStatus run(IProgressMonitor monitor) {
                    try {
                        var found = SetupReleaseCatalog.fetch();
                        if (!monitor.isCanceled()) display.asyncExec(() -> {
                            if (release.isDisposed()) return;
                            String selected = tag();
                            tags = found;
                            release.setItems(tags.stream().map(SetupReleaseCatalog::label).toArray(String[]::new));
                            int index = tags.indexOf(selected);
                            release.select(index < 0 ? tags.indexOf(SetupReleaseCatalog.PINNED) : index);
                            releaseStatus.setText("Official stable releases loaded. Only the pinned profile includes a qualified first-build installer.");
                            check.setEnabled(true); selectionChanged();
                        });
                        return Status.OK_STATUS;
                    } catch (Exception failure) {
                        if (!monitor.isCanceled()) display.asyncExec(() -> {
                            if (release.isDisposed()) return;
                            releaseStatus.setText("Release check failed. Existing choices remain available; retry when online.");
                            check.setEnabled(true); releaseStatus.getParent().layout(true, true);
                        });
                        return new Status(IStatus.ERROR, Activator.ID, "Official release check failed", failure);
                    }
                }
            };
            catalogJob.setSystem(true); catalogJob.schedule();
        }
        void validate() {
            String error = null;
            if (!System.getProperty("os.name").startsWith("Windows") || !System.getProperty("os.arch").equals("amd64")) error = "This bootstrap profile supports native Windows x64. Use Tools Manager for existing environments on other hosts.";
            try {
                Path root = Path.of(folder.getText().trim());
                if (folder.getText().isBlank() || !root.isAbsolute()) error = "Choose an absolute installation folder.";
                else if (Files.exists(root) && !Files.isDirectory(root)) error = "The selected path is a file.";
                else if (Files.isRegularFile(root.resolve(".zew-managed"))
                        && !Files.readString(root.resolve(".zew-managed")).equals(marker()))
                    error = "This folder belongs to another release or setup profile. Choose a separate folder.";
                else if (Files.isDirectory(root) && !Files.isRegularFile(root.resolve(".zew-managed"))) {
                    try (var files = Files.list(root)) { if (files.findAny().isPresent()) error = "Choose an empty folder, or a previous workbench setup folder to retry. Existing files will not be replaced."; }
                }
            } catch (Exception exception) { error = "Choose a valid accessible installation folder."; }
            setErrorMessage(error); setPageComplete(error == null);
        }
    }
    private final class ReviewPage extends WizardPage {
        Text plan;
        Button agreement, sourceSdk;
        ReviewPage() { super("review"); setTitle("Review and install"); setDescription("Review the selected release, tools, licences and destination before starting."); }
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
            sourceSdk = new Button(body, SWT.CHECK);
            sourceSdk.setText("Also provision the reviewed SDK 1.0.1 and x86 compiler if recommended by the selected release.");
            sourceSdk.addListener(SWT.Selection, e -> { agreement.setSelection(false); setPageComplete(false); });
            agreement = new Button(body, SWT.CHECK); agreement.setText("I have reviewed the installation plan and licence records. Proceed with the selected setup.");
            agreement.addListener(SWT.Selection, e -> setPageComplete(agreement.getSelection()));
            setControl(body); setPageComplete(false);
        }
        @Override public void setVisible(boolean visible) {
            if (visible && plan != null) {
                agreement.setSelection(false); setPageComplete(false);
                sourceSdk.setSelection(false);
                sourceSdk.setEnabled(!location.firstBuild());
                if (!location.firstBuild()) {
                    plan.setText("Destination: " + location.folder.getText().trim()
                            + "\n\nSelected official stable Zephyr release: " + location.tag()
                            + "\n\nInstall the reviewed portable Python 3.13.7, MinGit 2.50.1 and pinned Python packages as SOURCE PREPARATION tools."
                            + "\nDownload the selected Zephyr tag and West modules, verify the source revision and record the frozen manifest."
                            + "\n\nOptional SDK provisioning: choose the checkbox below to install reviewed SDK 1.0.1 and its x86 compiler only if the verified source recommends it. Other SDK versions are refused; sources are preserved."
                            + "\nThis does NOT qualify Python dependencies for firmware, create a project or build firmware."
                            + "\nThe selected release and its upstream modules have their own licence terms; review the linked bootstrap and SDK licence records (GCC runtime exceptions apply)."
                            + "\n\nNetwork required; source and modules can require several gigabytes. Existing environments remain unchanged."
                            + "\nResult: a separate source-only workspace, not a build-ready environment.");
                } else plan.setText("Destination: " + location.folder.getText().trim()
                    + "\n\nPython 3.13.7 (PSF and bundled third-party notices)\nMinGit 2.50.1 (GPL and bundled component licences)"
                    + "\nWest 1.5.0, CMake 3.31.10, Ninja 1.13.2\nPinned Python dependencies; licences include MIT, BSD, Apache, PSF and LGPL"
                    + "\nZephyr 4.4.0 at immutable revision 684c9e8f32e4373a21098559f748f06915f950c9"
                    + "\nZephyr SDK 1.0.1; x86_64-zephyr-elf compiler only (GCC runtime exceptions apply)"
                    + "\n\nAllow approximately 1 GB of downloads and at least 5 GiB free disk space. Internet speed affects installation time."
                    + "\n\nVerified downloads are cached for retries. Interrupted installation stays marked incomplete. No administrator account, global PATH edits, vendor blobs or probe packages are required."
                    + "\n\nResult: editable Hello World source in Project Explorer and a verified firmware build. Emulator execution and physical hardware support are not part of this first profile.");
            }
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
        String tag = location.tag();
        String marker = location.marker();
        boolean firstBuild = location.firstBuild();
        boolean sourceSdk = !firstBuild && review.sourceSdk.getSelection();
        var console = new MessageConsole("Zephyr Beginner Setup", null); ConsolePlugin.getDefault().getConsoleManager().addConsoles(new org.eclipse.ui.console.IConsole[] {console});
        ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
        try (var output = console.newMessageStream()) {
            getContainer().run(true, true, monitor -> {
                try {
                    monitor.beginTask(firstBuild ? "Set up Zephyr and build Hello World" : "Prepare Zephyr " + tag + " sources", IProgressMonitor.UNKNOWN);
                    Files.createDirectories(root);
                    if (!Files.isRegularFile(root.resolve(".zew-managed"))) {
                        try (var files = Files.list(root)) { if (files.findAny().isPresent()) throw new java.io.IOException("Installation folder is not empty; existing files were preserved."); }
                        Files.writeString(root.resolve(".zew-managed"), marker, StandardOpenOption.CREATE_NEW);
                    }
                    try (var channel = FileChannel.open(root.resolve(".zew-install.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.tryLock()) {
                        if (lock == null) throw new java.io.IOException("Another Eclipse instance is using this installation folder.");
                        var report = (java.util.function.Consumer<String>) text -> { monitor.subTask(text); output.println(text); };
                        if (!Files.readString(root.resolve(".zew-managed")).equals(marker)) throw new java.io.IOException("This directory belongs to a different setup profile.");
                        if (firstBuild && Files.isRegularFile(root.resolve(".zew-ready")) && Files.isRegularFile(root.resolve("environment.properties"))
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
                        var command = new ArrayList<>(List.of(root.resolve("python/python.exe").toString(), "-u", root.resolve("installer/setup.py").toString(),
                                "--root", root.toString(), "--lock", root.resolve("installer/setup-lock.json").toString()));
                        if (!firstBuild) {
                            copyResource("tools/release_sources.py", root.resolve("installer/release_sources.py"));
                            copyResource("tools/project_versions.py", root.resolve("installer/project_versions.py"));
                            command.addAll(List.of("--source-release", tag));
                            if (sourceSdk) command.add("--source-sdk");
                        }
                        new ProcessRunner().run(command, root, Duration.ofHours(3), monitor::isCanceled, report).requireSuccess();
                        Path proof = root.resolve(firstBuild ? ".zew-ready" : "workspace/.zew-source-ready");
                        if (!Files.isRegularFile(proof)) throw new java.io.IOException("Setup did not produce the expected verification marker.");
                        if (sourceSdk && !Files.isRegularFile(root.resolve("sdk/.zew-source-sdk.json")))
                            throw new java.io.IOException("SDK provisioning did not produce its source receipt.");
                    }
                } catch (java.io.InterruptedIOException error) { throw new InterruptedException("Setup cancelled. Completed downloads are preserved for retry."); }
                catch (InterruptedException error) { throw error; }
                catch (Exception error) { throw new java.lang.reflect.InvocationTargetException(error); }
                finally { monitor.done(); }
            });
            if (firstBuild) importProject(root);
            else MessageDialog.openInformation(getShell(), "Zephyr sources prepared",
                    "Verified sources and West modules for " + tag + " are in:\n" + root.resolve("workspace")
                    + (sourceSdk ? "\n\nReviewed SDK 1.0.1 and x86 compiler provisioned in " + root.resolve("sdk")
                            + ". Release-specific dependency and firmware qualification are still required."
                            : "\n\nSDK selection and a successful firmware build are still required.")
                    + " No existing project was changed.");
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


