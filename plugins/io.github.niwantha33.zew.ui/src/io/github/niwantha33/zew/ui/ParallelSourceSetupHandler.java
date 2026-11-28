// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
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
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
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
        private static final String CATALOG_SCRIPT = "tools/release_catalog.py";
        private final Path currentWorkspace;
        private final Path originalSetup;
        private final Properties settings;
        private Text release;
        private Text destination;
        private Table versionTable;
        private Text releaseDetails;
        private Label inventory;
        private Label checkStatus;
        private Button checkButton;
        private Button inspectButton;
        private String lastSuggested;
        private final List<ReleaseRow> rows = new ArrayList<>();

        private record ReleaseRow(String tag, String date, String status,
                                  String title, String notes, String url) { }

        SourceDialog(Shell shell, Path currentWorkspace, Path originalSetup, Properties settings) {
            super(shell);
            this.currentWorkspace = currentWorkspace;
            this.originalSetup = originalSetup;
            this.settings = settings;
            setHelpAvailable(false);
            setShellStyle(getShellStyle() | SWT.RESIZE);
        }

        @Override protected Point getInitialSize() { return new Point(920, 760); }

        @Override protected Control createDialogArea(Composite parent) {
            Composite main = (Composite) super.createDialogArea(parent);
            setTitle("Zephyr releases and version selection");
            setMessage("Choose an official stable version. Your current source, SDK, Python "
                    + "environment, modules and application stay unchanged.");
            Composite grid = new Composite(main, SWT.NONE);
            grid.setLayout(new GridLayout(3, false));
            grid.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

            inventory = new Label(grid, SWT.WRAP);
            inventory.setText("Reading the selected project's local source and SDK versions...");
            inventory.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 3, 1));

            checkButton = new Button(grid, SWT.PUSH);
            checkButton.setText("Check Official Zephyr Releases");
            checkButton.setToolTipText("Explicitly contact the official GitHub Zephyr release API.");
            checkButton.addListener(SWT.Selection, e -> refreshCatalog(true));
            inspectButton = new Button(grid, SWT.PUSH);
            inspectButton.setText("Inspect Selected Packages");
            inspectButton.setToolTipText("Read version-specific upstream SDK/Python/West requirements; no installation.");
            inspectButton.addListener(SWT.Selection, e -> inspectRequirements());
            checkStatus = new Label(grid, SWT.WRAP);
            checkStatus.setText("Offline by default. Checking releases contacts GitHub only when requested.");
            checkStatus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

            versionTable = new Table(grid, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL);
            versionTable.setHeaderVisible(true);
            versionTable.setLinesVisible(true);
            GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true, 3, 1);
            tableData.heightHint = 140;
            versionTable.setLayoutData(tableData);
            for (String[] column : new String[][] {
                    {"Version", "155"}, {"Published", "125"}, {"Local / update state", "520"}}) {
                TableColumn header = new TableColumn(versionTable, SWT.LEFT);
                header.setText(column[0]);
                header.setWidth(Integer.parseInt(column[1]));
            }
            versionTable.addListener(SWT.Selection, event -> {
                int index = versionTable.getSelectionIndex();
                if (index < 0 || index >= rows.size()) return;
                ReleaseRow selected = rows.get(index);
                release.setText(selected.tag());
                releaseDetails.setText(selected.title() + "\n" + selected.url()
                        + "\nPublished: " + selected.date()
                        + "\nStatus: " + selected.status()
                        + "\n\n" + selected.notes()
                        + "\n\nPackage checks: Zephyr source + West modules are pinned per selected "
                        + "tag. A new SDK, Python/West packages, CMake and per-board "
                        + "toolchains are NOT assumed compatible and are NOT installed here. "
                        + "A separate fully qualified setup and trial build are required.");
            });

            new Label(grid, SWT.NONE).setText("Selected stable tag");
            release = new Text(grid, SWT.BORDER);
            release.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
            release.setText("v4.5.0"); // Input example only; never claim this is the newest.

            new Label(grid, SWT.NONE).setText("New source folder");
            destination = new Text(grid, SWT.BORDER);
            destination.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            lastSuggested = suggestion(release.getText());
            destination.setText(lastSuggested);
            Button browse = new Button(grid, SWT.PUSH);
            browse.setText("Parent...");
            browse.addListener(SWT.Selection, event -> {
                DirectoryDialog picker = new DirectoryDialog(getShell());
                picker.setMessage("Choose an EXISTING parent. A new versioned child folder will be created.");
                try {
                    Path suggested = Path.of(destination.getText()).getParent();
                    if (suggested != null) picker.setFilterPath(suggested.toString());
                } catch (Exception ignored) { /* The user can correct the path. */ }
                String selected = picker.open();
                if (selected != null)
                    destination.setText(Path.of(selected, "zephyr-src-" + release.getText().trim()).toString());
            });

            releaseDetails = new Text(grid, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY
                    | SWT.WRAP | SWT.V_SCROLL);
            GridData detailsData = new GridData(SWT.FILL, SWT.FILL, true, true, 3, 1);
            detailsData.heightHint = 160;
            releaseDetails.setLayoutData(detailsData);
            releaseDetails.setText("Check official releases to select a version and read its notes. "
                    + "Existing source-only candidates will appear in the offline list.");

            Label explanation = new Label(grid, SWT.WRAP);
            explanation.setText("SAFETY: This prepares source and West modules in a NEW folder, never "
                    + "over the current checkout. SDK, Python/West dependencies, build profiles, "
                    + "application, flash and debug settings will not change. "
                    + "A source-only candidate is NOT a build-ready Zephyr environment.");
            explanation.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 3, 1));

            release.addModifyListener(event -> {
                String nextSuggestion = suggestion(release.getText());
                if (destination.getText().equals(lastSuggested))
                    destination.setText(nextSuggestion);
                lastSuggested = nextSuggestion;
                validateInputs();
            });
            destination.addModifyListener(event -> validateInputs());
            return main;
        }

        private String suggestion(String tag) {
            return originalSetup.resolveSibling("zephyr-src-" + tag.trim()).toString();
        }

        @Override protected void createButtonsForButtonBar(Composite parent) {
            createButton(parent, IDialogConstants.OK_ID, "Prepare Selected Sources...", true);
            createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
            validateInputs();
            refreshCatalog(false);
        }

        private static String decode(String encoded) {
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        }

        private void refreshCatalog(boolean online) {
            if (checkButton.isDisposed()) return;
            checkButton.setEnabled(false);
            inspectButton.setEnabled(false);
            checkStatus.setText(online ? "Checking official Zephyr releases..." : "Inspecting local versions...");
            Job catalog = new Job(online ? "Check official Zephyr releases" : "Inspect local Zephyr versions") {
                @Override protected IStatus run(IProgressMonitor monitor) {
                    List<ReleaseRow> found = new ArrayList<>();
                    String[] state = new String[4];
                    String[] message = new String[1];
                    try {
                        Path script = Path.of(FileLocator.toFileURL(
                                Activator.getDefault().getBundle().getEntry(CATALOG_SCRIPT)).toURI());
                        List<String> command = new ArrayList<>(List.of(
                                settings.getProperty("python"), script.toString(),
                                online ? "check" : "local",
                                "--workspace", currentWorkspace.toString(),
                                "--sources-parent", originalSetup.getParent().toString()));
                        String sdk = settings.getProperty("tools.sdk", "");
                        if (!sdk.isBlank()) {
                            command.add("--sdk");
                            command.add(sdk);
                        }
                        if (online) command.add("--allow-network");
                        var result = new ProcessRunner().run(command, currentWorkspace,
                                Duration.ofSeconds(40), monitor::isCanceled, line -> {
                                    try {
                                        String[] parts = line.split("\\t", -1);
                                        if (parts.length < 2) return;
                                        if ("STATE".equals(parts[0]) && parts.length == 5) {
                                            for (int i = 0; i < state.length; i++)
                                                state[i] = decode(parts[i + 1]);
                                        } else if ("RELEASE".equals(parts[0]) && parts.length == 7) {
                                            found.add(new ReleaseRow(decode(parts[1]), decode(parts[2]),
                                                    decode(parts[3]), decode(parts[4]), decode(parts[5]),
                                                    decode(parts[6])));
                                        } else if ("DONE".equals(parts[0]) || "ERROR".equals(parts[0])) {
                                            message[0] = decode(parts[1]);
                                        }
                                    } catch (IllegalArgumentException ignored) {
                                        // Malformed response must not be treated as a release.
                                    }
                                }, ProjectContext.environment(settings));
                        result.requireSuccess();
                        if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                        Display.getDefault().asyncExec(() -> {
                            if (versionTable.isDisposed()) return;
                            rows.clear();
                            rows.addAll(found);
                            versionTable.removeAll();
                            for (ReleaseRow item : found) {
                                TableItem row = new TableItem(versionTable, SWT.NONE);
                                row.setText(new String[] { item.tag(), item.date(), item.status() });
                            }
                            if (state[0] != null)
                                inventory.setText("Current Zephyr: " + state[0] + "    |    SDK: " + state[1]
                                        + "\nWorkspace: " + state[2] + "\nSDK selection: " + state[3]);
                            checkStatus.setText(message[0] == null
                                    ? "No releases were returned." : message[0]);
                            if (online && !found.isEmpty()) {
                                versionTable.setSelection(0);
                                versionTable.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
                            }
                            checkButton.setEnabled(true);
                            inspectButton.setEnabled(true);
                            inventory.getParent().layout(true, true);
                        });
                        return Status.OK_STATUS;
                    } catch (Exception failure) {
                        Display.getDefault().asyncExec(() -> {
                            if (checkStatus.isDisposed()) return;
                            checkStatus.setText("Version check failed: " + failure.getMessage()
                                    + ". Existing setup is unchanged.");
                            checkButton.setEnabled(true);
                            inspectButton.setEnabled(true);
                            checkStatus.getParent().layout(true, true);
                        });
                        return new Status(IStatus.ERROR, Activator.ID,
                                "Zephyr release discovery failed: " + failure.getMessage(), failure);
                    }
                }
            };
            catalog.setSystem(true);
            catalog.schedule();
        }

        private void inspectRequirements() {
            String selectedTag = release.getText().trim();
            if (!stableRelease(selectedTag)) {
                setErrorMessage("Select a stable release tag before inspecting its package requirements.");
                return;
            }
            inspectButton.setEnabled(false);
            checkStatus.setText("Inspecting " + selectedTag + " SDK/Python/West requirements...");
            Job job = new Job("Inspect Zephyr " + selectedTag + " dependencies") {
                @Override protected IStatus run(IProgressMonitor monitor) {
                    var packages = new StringBuilder();
                    String[] resultNote = new String[1];
                    try {
                        Path script = Path.of(FileLocator.toFileURL(
                                Activator.getDefault().getBundle().getEntry(CATALOG_SCRIPT)).toURI());
                        List<String> command = new ArrayList<>(List.of(
                                settings.getProperty("python"), script.toString(), "inspect",
                                "--workspace", currentWorkspace.toString(),
                                "--release", selectedTag, "--allow-network"));
                        String sdk = settings.getProperty("tools.sdk", "");
                        if (!sdk.isBlank()) {
                            command.add("--sdk");
                            command.add(sdk);
                        }
                        String cmake = settings.getProperty("tools.cmake", "");
                        if (!cmake.isBlank()) {
                            command.add("--cmake");
                            command.add(cmake);
                        }
                        new ProcessRunner().run(command, currentWorkspace,
                                Duration.ofSeconds(90), monitor::isCanceled, line -> {
                                    try {
                                        String[] parts = line.split("\\t", -1);
                                        if (parts.length == 6 && "PACKAGE".equals(parts[0])) {
                                            packages.append(decode(parts[1])).append(": ")
                                                    .append(decode(parts[2])).append("  ->  ")
                                                    .append(decode(parts[3])).append("\n")
                                                    .append("   ").append(decode(parts[4]))
                                                    .append("; ").append(decode(parts[5])).append("\n\n");
                                        } else if (parts.length == 2 &&
                                                ("DONE".equals(parts[0]) || "ERROR".equals(parts[0]))) {
                                            resultNote[0] = decode(parts[1]);
                                        }
                                    } catch (IllegalArgumentException ignored) {
                                        // Ignore malformed metadata rather than presenting it as a dependency.
                                    }
                                }, ProjectContext.environment(settings)).requireSuccess();
                        if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                        Display.getDefault().asyncExec(() -> {
                            if (releaseDetails.isDisposed()) return;
                            inspectButton.setEnabled(true);
                            if (!selectedTag.equals(release.getText().trim())) {
                                checkStatus.setText("Version changed; inspect the newly selected release again.");
                                return;
                            }
                            releaseDetails.setText("PACKAGE COMPARISON for " + selectedTag
                                    + "\nCurrent installation  ->  selected release requirements\n\n"
                                    + packages + "\n" + resultNote[0]
                                    + "\n\nMinimum versions alone never establish firmware build compatibility.");
                            checkStatus.setText("Package preview received. No installation or changes made.");
                            inspectButton.setEnabled(true);
                        });
                        return Status.OK_STATUS;
                    } catch (java.io.InterruptedIOException cancelled) {
                        return Status.CANCEL_STATUS;
                    } catch (Exception error) {
                        Display.getDefault().asyncExec(() -> {
                            if (checkStatus.isDisposed()) return;
                            checkStatus.setText("Package inspection failed: " + error.getMessage());
                            inspectButton.setEnabled(true);
                        });
                        return new Status(IStatus.ERROR, Activator.ID,
                                "Zephyr package inspection failed: " + error.getMessage(), error);
                    }
                }
            };
            job.setSystem(true);
            job.schedule();
        }

        private void validateInputs() {
            if (release == null || destination == null || getButton(IDialogConstants.OK_ID) == null) return;
            String error = null;
            try {
                if (!stableRelease(release.getText().trim()))
                    error = "Select an official stable version such as v4.5.0 (not main or an RC).";
                else {
                    Path proposed = Path.of(destination.getText().trim()).toAbsolutePath().normalize();
                    Path current = currentWorkspace.toRealPath();
                    if (!Path.of(destination.getText().trim()).isAbsolute())
                        error = "Choose an absolute destination.";
                    else if (proposed.startsWith(current) || current.startsWith(proposed)
                            || proposed.startsWith(originalSetup.toRealPath())
                            || originalSetup.toRealPath().startsWith(proposed))
                        error = "Keep the candidate outside the original setup and West workspace.";
                    else if (Files.exists(proposed) || Files.isSymbolicLink(proposed))
                        error = "The candidate already exists. Choose a new folder.";
                    else if (proposed.getParent() == null || !Files.isDirectory(proposed.getParent()))
                        error = "Create or select an existing parent folder first.";
                }
            } catch (Exception exception) {
                error = "Enter a valid release and absolute source folder path.";
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
        Path originalSetup = Path.of(settings.getProperty("zew.setupRoot"));
        var dialog = new SourceDialog(HandlerUtil.getActiveShell(event), currentWorkspace, originalSetup, settings);
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
                    var common = new ArrayList<>(List.of("--destination", destination.toString(),
                            "--original-workspace", currentWorkspace.toString(),
                            "--original-setup", originalSetup.toString(),
                            "--release", release, "--python", settings.getProperty("python"),
                            "--git", settings.getProperty("tools.git")));
                    if (ProjectContext.isManagedWindows(settings))
                        common.addAll(List.of("--managed-tools-root", originalSetup.toString()));
                    var plan = new ArrayList<String>(List.of(settings.getProperty("python"),
                            script.toString(), "plan"));
                    plan.addAll(common);
                    var runner = new ProcessRunner();
                    Path runIn = currentWorkspace;
                    runner.run(plan, runIn, Duration.ofSeconds(30), monitor::isCanceled,
                            stream::println, ProjectContext.buildEnvironment(settings)).requireSuccess();

                    var install = new ArrayList<String>(List.of(settings.getProperty("python"),
                            script.toString(), "install", "--allow-network"));
                    install.addAll(common);
                    runner.run(install, runIn, Duration.ofHours(2), monitor::isCanceled,
                            stream::println, ProjectContext.buildEnvironment(settings)).requireSuccess();

                    if (Files.isRegularFile(project.getLocation().toFile().toPath()
                            .resolve(".zew/project-lock.json"))) {
                        var compare = new ArrayList<String>(List.of(settings.getProperty("python"),
                                script.toString(), "compare", "--application",
                                project.getLocation().toFile().toPath().toString(),
                                "--destination", destination.toString(),
                                "--python", settings.getProperty("python"),
                                "--git", settings.getProperty("tools.git")));
                        if (ProjectContext.isManagedWindows(settings))
                            compare.addAll(List.of("--managed-tools-root", originalSetup.toString()));
                        stream.println("Comparing candidate source SHAs to the application's saved lock...");
                        try {
                            runner.run(compare, runIn, Duration.ofMinutes(3), monitor::isCanceled,
                                    stream::println, ProjectContext.buildEnvironment(settings)).requireSuccess();
                        } catch (java.io.InterruptedIOException cancelled) {
                            return Status.CANCEL_STATUS;
                        } catch (java.io.IOException comparisonError) {
                            // A valid source installation must not be marked failed
                            // because an optional application's lock is unavailable.
                            stream.println("Source checkout was prepared and verified; "
                                    + "optional project comparison failed: " + comparisonError.getMessage());
                        }
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
