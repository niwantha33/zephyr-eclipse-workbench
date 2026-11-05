// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.ViewPart;
import io.github.niwantha33.zew.core.BuildFailureInvestigator;
import io.github.niwantha33.zew.core.BuildFailureLogs;

/** Native, offline investigation of saved build evidence; rebuild is an explicit separate action. */
public final class BuildFailureInvestigationView extends ViewPart {
    public static final String ID = Activator.ID + ".buildFailureInvestigation";
    private final AtomicInteger generation = new AtomicInteger();
    private Text reportText;
    private Label context;
    private Button rebuild;
    private Button open;
    private IProject project;
    private Path build;
    private Path log;
    private Job reader;

    @Override public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));
        var controls = new Composite(parent, SWT.NONE);
        controls.setLayout(new GridLayout(5, false));
        controls.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        button(controls, "Show latest failed build", this::displayLastFailure);
        button(controls, "Import another log…", this::importLog);
        open = button(controls, "Open complete log", this::openLog);
        open.setEnabled(false);
        button(controls, "Copy report", this::copyReport);
        rebuild = button(controls, "Rebuild with detailed output", this::rebuild);
        rebuild.setEnabled(false);
        rebuild.setToolTipText("Run the managed build for this exact project, board and profile with verbose output and one build job.");
        context = new Label(parent, SWT.WRAP);
        context.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        context.setText("Failed builds select their log automatically. Use Show latest failed build to reopen the last failure.");
        reportText = new Text(parent, SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        reportText.setFont(JFaceResources.getTextFont());
        reportText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        reportText.setText("A failed managed build opens this view automatically.\n\n"
            + "Inspect the reported error, failed command and complete log.\n"
            + "If only Ninja's final failure message is available, use Rebuild with detailed output.\n"
            + "The last Building line is not evidence of which source failed.\n\n"
            + "Saved evidence describes a past attempt. Opening a report does not rebuild or change project settings.");
        if (!Activator.getDefault().getPreferenceStore().getString(BuildFailureInvestigationHandler.LAST_PROJECT).isBlank())
            displayLastFailure();
    }
    private Button button(Composite parent, String label, Runnable action) {
        var result = new Button(parent, SWT.PUSH);
        result.setText(label);
        result.addListener(SWT.Selection, event -> action.run());
        return result;
    }
    /** Restore only an explicitly recorded failed project/build, never guess a workspace project. */
    public void displayLastFailure() {
        clear();
        var preferences = Activator.getDefault().getPreferenceStore();
        String name = preferences.getString(BuildFailureInvestigationHandler.LAST_PROJECT);
        String directory = preferences.getString(BuildFailureInvestigationHandler.LAST_BUILD);
        if (name.isBlank() || directory.isBlank()) {
            context.setText("No failed build has been recorded yet. Build your project; its next captured failure selects the log automatically.");
            return;
        }
        try {
            IProject recorded = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getProject(name);
            if (!recorded.isAccessible()) {
                context.setText("The last failed project is closed or unavailable: " + name + ". Select your current project to investigate it.");
                return;
            }
            project = recorded;
            build = Path.of(directory).toAbsolutePath().normalize();
            context.setText("Automatically finding the recorded failed build — " + name + " | " + build);
            load(recorded, build, null);
        } catch (Exception error) {
            context.setText("The recorded failed build is unavailable: " + error.getMessage());
        }
    }
    public void displayProject(IProject selected) {
        clear();
        project = selected;
        if (selected == null || !selected.isAccessible()) {
            context.setText("No project selected. Import a saved log, or select a Zephyr project and run Investigate Build Failure again.");
            return;
        }
        try {
            var settings = ProjectContext.settings(selected);
            build = ProjectContext.buildDirectory(selected, settings);
            Path directory = build;
            context.setText("Loading recorded failure for " + selected.getName() + " | " + settings.getProperty("board")
                + " | " + settings.getProperty("zew.buildConfiguration") + " | " + directory);
            load(selected, directory, null);
        } catch (Exception error) { context.setText(error.getMessage()); }
    }
    /** Called on the UI thread only, after the failed attempt's log writer closed. */
    public void displayFailure(IProject selected, Path directory, Path source, String report) {
        clear();
        project = selected;
        build = directory;
        log = source;
        BuildFailureInvestigationHandler.rememberFailure(selected, directory);
        context.setText("Recorded failed attempt — " + selected.getName() + " | " + directory);
        reportText.setText("Recorded log: " + source + "\n\n" + report);
        open.setEnabled(Files.isRegularFile(source));
        rebuild.setEnabled(selected.isAccessible());
    }
    private void clear() {
        generation.incrementAndGet();
        if (reader != null) reader.cancel();
        project = null;
        build = null;
        log = null;
        open.setEnabled(false);
        rebuild.setEnabled(false);
        reportText.setText("");
    }
    private void importLog() {
        var picker = new FileDialog(getSite().getShell(), SWT.OPEN);
        picker.setText("Investigate a saved UTF-8 build log (up to 64 MiB)");
        picker.setFilterExtensions(new String[] {"*.log;*.txt", "*"});
        String source = picker.open();
        if (source == null) return;
        clear();
        context.setText("Reading saved log…");
        load(null, null, Path.of(source));
    }
    private void load(IProject selected, Path directory, Path explicitLog) {
        int token = generation.incrementAndGet();
        if (reader != null) reader.cancel();
        var display = reportText.getDisplay();
        reader = new Job("Investigate saved Zephyr build failure") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                try {
                    Path source = explicitLog;
                    if (source == null) source = BuildFailureLogs.latestFailure(
                        selected.getLocation().toFile().toPath(), directory).orElse(null);
                    if (source == null) {
                        complete(token, () -> {
                            reportText.setText("No recorded failed attempt exists for this exact build directory.\n"
                                + "A future failed managed build saves its log here automatically.\n"
                                + "Use Rebuild with detailed output to collect a new attempt, or import an earlier console log.");
                            context.setText("No saved failure — " + selected.getName() + " | " + directory);
                            rebuild.setEnabled(selected.isAccessible());
                        });
                        return Status.OK_STATUS;
                    }
                    if (!Files.isRegularFile(source) || Files.size(source) > 64L * 1024 * 1024)
                        throw new java.io.IOException("Select a regular UTF-8 log no larger than 64 MiB.");
                    var investigator = new BuildFailureInvestigator();
                    var digest = MessageDigest.getInstance("SHA-256");
                    long bytes = 0;
                    try (var raw = new DigestInputStream(Files.newInputStream(source), digest);
                         var input = new java.io.BufferedReader(new java.io.InputStreamReader(raw, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = input.readLine()) != null) {
                            if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                            bytes += line.length() + 1;
                            if (bytes > 64L * 1024 * 1024) throw new java.io.IOException("Log grew beyond the inspection limit.");
                            investigator.accept(line);
                        }
                    }
                    Path loaded = source;
                    String explanation = explicitLog == null ? BuildFailureLogs.report(source) : investigator.summary();
                    String report = "Saved log: " + source + "\nLog SHA-256: "
                        + HexFormat.of().formatHex(digest.digest()) + "\n\n" + explanation
                        + "\nHeadless equivalent:\n" + cliCommand(source);
                    complete(token, () -> {
                        log = loaded;
                        reportText.setText(report);
                        context.setText("Saved log inspected — " + (selected == null ? "imported log (build outcome unverified)" : selected.getName() + " | " + directory)
                            + " | recorded evidence, not current build state");
                        open.setEnabled(true);
                        rebuild.setEnabled(selected != null && selected.isAccessible());
                    });
                } catch (Exception error) {
                    complete(token, () -> { context.setText("Investigation failed: " + error.getMessage()); reportText.setText("The log could not be inspected. No build was started."); });
                }
                return monitor.isCanceled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
            }
            private void complete(int expected, Runnable update) {
                if (display.isDisposed()) return;
                display.asyncExec(() -> { if (!reportText.isDisposed() && generation.get() == expected) update.run(); });
            }
        };
        reader.setUser(true);
        reader.schedule();
    }
    private static String cliCommand(Path source) {
        var bundle = org.eclipse.core.runtime.Platform.getBundle("io.github.niwantha33.zew.core");
        if (bundle == null) return "Core bundle location unavailable; see docs/BUILD_FAILURE_INVESTIGATOR.md.";
        return org.eclipse.core.runtime.FileLocator.getBundleFileLocation(bundle)
            .map(file -> "java -cp \"" + file.getAbsolutePath()
                + "\" io.github.niwantha33.zew.core.BuildFailureInvestigator --json \"" + source + "\"")
            .orElse("Core bundle location unavailable; see docs/BUILD_FAILURE_INVESTIGATOR.md.");
    }
    private void openLog() {
        if (log == null) return;
        try { IDE.openEditor(getSite().getPage(), log.toUri(), "org.eclipse.ui.DefaultTextEditor", true); }
        catch (Exception error) { context.setText("Could not open complete log: " + error.getMessage()); }
    }
    private void copyReport() {
        if (reportText.getText().isEmpty()) return;
        var clipboard = new Clipboard(reportText.getDisplay());
        try { clipboard.setContents(new Object[] {reportText.getText()}, new Transfer[] {TextTransfer.getInstance()}); }
        finally { clipboard.dispose(); }
    }
    private void rebuild() {
        if (project == null || build == null || !project.isAccessible()) return;
        try {
            if (!ProjectContext.buildDirectory(project, ProjectContext.settings(project)).toAbsolutePath().normalize()
                    .equals(build.toAbsolutePath().normalize())) {
                context.setText("The selected build profile changed. Run Investigate Build Failure for the current project/profile first.");
                rebuild.setEnabled(false);
                return;
            }
            ZephyrBuildHandler.schedule(project, false, true);
            context.setText("Detailed build scheduled for " + project.getName() + " | " + build + ". Follow the Zephyr console and Progress view.");
        } catch (Exception error) { context.setText("Could not start detailed build: " + error.getMessage()); }
    }
    @Override public void setFocus() { if (reportText != null && !reportText.isDisposed()) reportText.setFocus(); }
    @Override public void dispose() {
        generation.incrementAndGet();
        if (reader != null) reader.cancel();
        super.dispose();
    }
}
