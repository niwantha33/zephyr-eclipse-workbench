// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.handlers.HandlerUtil;

import io.github.niwantha33.zew.core.ProcessRunner;

/**
 * Stage 1 of upgrade management: side-by-side upstream sources only.
 * No app rewrite, SDK provisioning or project environment rebinding.
 */
public final class ParallelSourceSetupHandler extends AbstractHandler {
    private static final String SCRIPT = "tools/release_sources.py";

    /** Deliberately accepts a release name, not an unreviewed branch/ref. */
    static boolean stableRelease(String value) {
        return value != null && value.matches("v(?:0|[1-9][0-9]*)\\.[0-9]+\\.[0-9]+");
    }

    static final class SourceDialog extends TitleAreaDialog {
        private final Path currentWorkspace;
        private Text release;
        private Text destination;

        SourceDialog(Shell shell, Path currentWorkspace) {
            super(shell);
            this.currentWorkspace = currentWorkspace;
            setHelpAvailable(false);
        }

        @Override protected Control createDialogArea(Composite parent) {
            Composite main = (Composite) super.createDialogArea(parent);
            setTitle("Prepare a separate Zephyr release");
            setMessage("Stage 1 — download source and modules with West. SDK, project "
                    + "migration and firmware builds are NOT included.");
            Composite grid = new Composite(main, SWT.NONE);
            grid.setLayout(new GridLayout(3, false));
            grid.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

            new Label(grid, SWT.NONE).setText("Release tag");
            release = new Text(grid, SWT.BORDER);
            release.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            release.setText("v4.5.0"); // Example, not an automatic latest-version claim.
            new Label(grid, SWT.NONE);

            new Label(grid, SWT.NONE).setText("New workspace folder");
            destination = new Text(grid, SWT.BORDER);
            destination.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            Path parentFolder = currentWorkspace.getParent();
            destination.setText((parentFolder == null
                    ? currentWorkspace.resolveSibling("zephyr-src-v4.5.0")
                    : parentFolder.resolve("zephyr-src-v4.5.0")).toString());
            Button browse = new Button(grid, SWT.PUSH);
            browse.setText("Parent...");
            browse.addListener(SWT.Selection, event -> {
                DirectoryDialog picker = new DirectoryDialog(getShell());
                picker.setMessage("Choose the EXISTING parent folder. A NEW release-specific folder will be created inside it.");
                String parentPath = destination.getText();
                try {
                    Path suggested = Path.of(parentPath).getParent();
                    if (suggested != null) picker.setFilterPath(suggested.toString());
                } catch (Exception ignored) { /* User may still correct the path. */ }
                String selected = picker.open();
                if (selected != null)
                    destination.setText(Path.of(selected, "zephyr-src-" + release.getText().trim()).toString());
            });

            Label explanation = new Label(grid, SWT.WRAP);
            explanation.setText("The original Zephyr workspace is protected. A new folder must not already exist. "
                    + "This operation downloads the selected official Zephyr tag and all West modules. "
                    + "Partial downloads are preserved for manual inspection. "
                    + "The result is SOURCE ONLY, not an SDK-equipped or build-ready setup.");
            explanation.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 3, 1));

            release.addModifyListener(event -> validateInputs());
            destination.addModifyListener(event -> validateInputs());
            return main;
        }

        @Override protected void createButtonsForButtonBar(Composite parent) {
            createButton(parent, IDialogConstants.OK_ID, "Prepare Sources...", true);
            createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
            validateInputs();
        }

        private void validateInputs() {
            if (release == null || destination == null || getButton(IDialogConstants.OK_ID) == null) return;
            String error = null;
            try {
                if (!stableRelease(release.getText().trim()))
                    error = "Enter a stable tag such as v4.5.0 (not main or an RC).";
                else {
                    Path proposed = Path.of(destination.getText().trim()).toAbsolutePath().normalize();
                    Path current = currentWorkspace.toRealPath();
                    if (!Path.of(destination.getText().trim()).isAbsolute())
                        error = "Choose an absolute destination.";
                    else if (proposed.startsWith(current) || current.startsWith(proposed))
                        error = "The candidate cannot contain or be inside the current workspace.";
                    else if (Files.exists(proposed))
                        error = "The candidate already exists. Choose a new folder.";
                    else if (proposed.getParent() == null || !Files.isDirectory(proposed.getParent()))
                        error = "Create or select an existing parent folder first.";
                }
            } catch (Exception exception) {
                error = "Enter a valid release and absolute workspace path.";
            }
            setErrorMessage(error);
            getButton(IDialogConstants.OK_ID).setEnabled(error == null);
        }

        String release() { return release.getText().trim(); }
        Path destination() { return Path.of(destination.getText().trim()).toAbsolutePath().normalize(); }
    }

    @Override public Object execute(ExecutionEvent event) {
        IProject project = SelectedProject.from(event);
        if (project == null || !ZephyrProjectLifecycle.canInspect(project)) {
            MessageDialog.openInformation(HandlerUtil.getActiveShell(event), "Parallel Zephyr sources",
                    "Select a Zephyr application in Project Explorer first.");
            return null;
        }
        final Properties settings;
        try {
            settings = ProjectContext.settings(project);
        } catch (Exception error) {
            MessageDialog.openInformation(HandlerUtil.getActiveShell(event), "Parallel Zephyr sources",
                    "Select a completed existing environment in Project Properties > Zephyr first. "
                    + error.getMessage());
            return null;
        }

        Path currentWorkspace = Path.of(settings.getProperty("workspace"));
        var dialog = new SourceDialog(HandlerUtil.getActiveShell(event), currentWorkspace);
        if (dialog.open() != Window.OK) return null;
        String release = dialog.release();
        Path destination = dialog.destination();
        if (!MessageDialog.openConfirm(HandlerUtil.getActiveShell(event), "Download parallel Zephyr sources?",
                "Download official Zephyr " + release + " and its West modules into:\n" + destination
                + "\n\nThe current workspace and your application will not be updated. "
                + "This DOES NOT install an SDK, migrate your application or build firmware. "
                + "It requires an internet connection and can download several gigabytes. Continue?"))
            return null;

        var job = new Job("Prepare separate Zephyr sources " + release) {
            @Override protected IStatus run(IProgressMonitor monitor) {
                var console = new MessageConsole("Zephyr source setup — " + release, null);
                ConsolePlugin.getDefault().getConsoleManager().addConsoles(new IConsole[] {console});
                ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
                try (var stream = console.newMessageStream()) {
                    monitor.beginTask("Download and verify separate Zephyr source", IProgressMonitor.UNKNOWN);
                    Path script = Path.of(FileLocator.toFileURL(
                            Activator.getDefault().getBundle().getEntry(SCRIPT)).toURI());
                    var common = List.of("--destination", destination.toString(),
                            "--original-workspace", currentWorkspace.toString(),
                            "--release", release, "--python", settings.getProperty("python"),
                            "--git", settings.getProperty("tools.git"));
                    var plan = new ArrayList<String>(List.of(settings.getProperty("python"),
                            script.toString(), "plan"));
                    plan.addAll(common);
                    var runner = new ProcessRunner();
                    Path runIn = currentWorkspace;
                    runner.run(plan, runIn, Duration.ofSeconds(30), monitor::isCanceled,
                            stream::println, ProjectContext.environment(settings)).requireSuccess();

                    var install = new ArrayList<String>(List.of(settings.getProperty("python"),
                            script.toString(), "install", "--allow-network"));
                    install.addAll(common);
                    runner.run(install, runIn, Duration.ofHours(2), monitor::isCanceled,
                            stream::println, ProjectContext.environment(settings)).requireSuccess();

                    if (Files.isRegularFile(project.getLocation().toFile().toPath()
                            .resolve(".zew/project-lock.json"))) {
                        var compare = new ArrayList<String>(List.of(settings.getProperty("python"),
                                script.toString(), "compare", "--application",
                                project.getLocation().toFile().toPath().toString(),
                                "--destination", destination.toString(),
                                "--python", settings.getProperty("python"),
                                "--git", settings.getProperty("tools.git")));
                        stream.println("Comparing candidate source SHAs to the application's saved lock...");
                        runner.run(compare, runIn, Duration.ofMinutes(3), monitor::isCanceled,
                                stream::println, ProjectContext.environment(settings)).requireSuccess();
                    } else {
                        stream.println("Project comparison unavailable: no .zew/project-lock.json yet.");
                    }
                    if (!monitor.isCanceled()) Display.getDefault().asyncExec(() ->
                        MessageDialog.openInformation(Display.getDefault().getActiveShell(),
                            "Zephyr sources prepared",
                            "Verified source workspace: " + destination
                            + "\n\nSDK setup, firmware builds and project migration remain separate steps. "
                            + "Your original project was not changed."));
                    return monitor.isCanceled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
                } catch (java.io.InterruptedIOException error) {
                    return Status.CANCEL_STATUS;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return Status.CANCEL_STATUS;
                } catch (Exception error) {
                    return new Status(IStatus.ERROR, Activator.ID,
                            "Parallel source setup failed: " + error.getMessage(), error);
                } finally {
                    monitor.done();
                }
            }
        };
        job.setUser(true);
        job.schedule();
        return null;
    }
}
