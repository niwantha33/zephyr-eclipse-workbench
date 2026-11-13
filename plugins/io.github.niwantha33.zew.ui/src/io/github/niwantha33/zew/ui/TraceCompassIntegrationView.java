// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.WorkbenchException;
import org.eclipse.ui.part.ViewPart;
import io.github.niwantha33.zew.core.ProcessRunner;

/**
 * Discoverable, opt-in Trace Compass integration. There is intentionally no
 * compile-time dependency on optional Trace Compass bundles. Opening this
 * view never installs software, starts external processes, touches a probe or
 * reads target memory. Saved handoff uses the existing receipt-verifying bridge.
 */
public final class TraceCompassIntegrationView extends ViewPart {
    public static final String ID = Activator.ID + ".traceCompassIntegration";
    // Verified against Trace Compass's TracingPerspectiveFactory.ID.
    static final String TRACING_PERSPECTIVE = "org.eclipse.linuxtools.tmf.ui.perspective";
    static final String UPDATE_SITE = "https://download.eclipse.org/tracecompass/stable/repository/";
    static final String DOWNLOADS = "https://eclipse.dev/tracecompass/download.html";

    private IProject contextProject;
    private Path savedCapture;
    private Label installation, selection, status;
    private Text standaloneExecutable;
    private Button nativePerspective, launchStandalone;
    private volatile Job handoff;
    private boolean disposed;

    @Override public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));
        Label heading = new Label(parent, SWT.WRAP);
        heading.setText("TRACE COMPASS  /  ECLIPSE PLUG-IN OR STANDALONE CTF VIEWER");
        heading.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        new Label(parent, SWT.WRAP).setText(
                "Zephyr saved CTF is separate from a running debug session. "
                + "Opening this page never resets, flashes, halts or scans the MCU.");

        installation = new Label(parent, SWT.WRAP);
        installation.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        var installedActions = new Composite(parent, SWT.NONE);
        installedActions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        installedActions.setLayout(new GridLayout(3, false));
        nativePerspective = button(installedActions, "Open installed Tracing perspective", this::openPerspective);
        button(installedActions, "Check installation", this::refreshInstallation);
        button(installedActions, "Official install / downloads...", () -> {
            if (!Program.launch(DOWNLOADS))
                status.setText("Could not open the browser. Use Help > Install New Software and enter: " + UPDATE_SITE);
        });
        new Label(parent, SWT.WRAP).setText("Eclipse plug-in install: Help > Install New Software > "
                + UPDATE_SITE + "  (restart Eclipse after installation).");

        new Label(parent, SWT.SEPARATOR | SWT.HORIZONTAL)
                .setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Label ctfHeading = new Label(parent, SWT.NONE);
        ctfHeading.setText("Saved Zephyr CTF capture");
        var captures = new Composite(parent, SWT.NONE);
        captures.setLayout(new GridLayout(2, false));
        captures.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        button(captures, "Use latest project capture", this::latest);
        button(captures, "Choose saved trace-* folder...", this::chooseCapture);
        selection = new Label(parent, SWT.WRAP);
        selection.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        selection.setText("No saved capture selected. Select an imported Zephyr project to use latest capture.");

        new Label(parent, SWT.WRAP).setText("Standalone Trace Compass executable (optional if on PATH "
                + "or ZEW_TRACE_COMPASS is set):");
        var executableRow = new Composite(parent, SWT.NONE);
        executableRow.setLayout(new GridLayout(2, false));
        executableRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        standaloneExecutable = new Text(executableRow, SWT.BORDER);
        standaloneExecutable.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        standaloneExecutable.setMessage("Select tracecompass.exe / tracecompass or leave blank for PATH");
        button(executableRow, "Browse executable...", this::chooseExecutable);

        launchStandalone = button(parent, "Open verified CTF in standalone Trace Compass...", this::openStandalone);
        launchStandalone.setEnabled(false);
        status = new Label(parent, SWT.WRAP);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        status.setText("No Trace Compass operation started.");

        new Label(parent, SWT.WRAP).setText("Only the saved CTF is handed to the optional viewer. "
                + "The bridge checks capture.json, byte lengths, hashes and read completeness first. "
                + "A viewer launch request does not prove successful CTF decoding. "
                + "To view the Zephyr-specific timeline, import the capture's "
                + "Zephyr_RTOS_Timeline.xml through Window > Preferences > Tracing > XML Analyses.");
        refreshInstallation();
    }

    private Button button(Composite parent, String text, Runnable action) {
        var control = new Button(parent, SWT.PUSH);
        control.setText(text);
        control.addListener(SWT.Selection, event -> action.run());
        return control;
    }

    public void displayProject(IProject project) {
        if (project != null && project.isAccessible()) contextProject = project;
    }

    private IProject project() {
        var resolved = TraceCaptureWorkflow.resolve(getSite().getWorkbenchWindow());
        if (resolved != null) contextProject = resolved;
        return contextProject != null && contextProject.isAccessible() ? contextProject : null;
    }

    private boolean installed() {
        return PlatformUI.getWorkbench().getPerspectiveRegistry()
                .findPerspectiveWithId(TRACING_PERSPECTIVE) != null;
    }

    private void refreshInstallation() {
        boolean present = installed();
        installation.setText(present
                ? "Trace Compass Tracing perspective: INSTALLED in this Eclipse."
                : "Trace Compass Tracing perspective: NOT INSTALLED in this Eclipse. "
                        + "Standalone viewer remains an option.");
        nativePerspective.setEnabled(present);
        installation.getParent().layout(true, true);
    }

    private void openPerspective() {
        if (!installed()) {
            refreshInstallation();
            status.setText("Tracing perspective is unavailable. Install the optional Eclipse plug-ins first.");
            return;
        }
        try {
            PlatformUI.getWorkbench().showPerspective(
                    TRACING_PERSPECTIVE, getSite().getWorkbenchWindow());
            status.setText("Opened Eclipse Tracing perspective. Import the saved CTF via "
                    + "Trace Compass Tracing Project Explorer; no trace was auto-imported.");
        } catch (WorkbenchException error) {
            status.setText("Unable to open installed Tracing perspective: " + error.getMessage());
        }
    }

    private void chooseCapture() {
        var dialog = new DirectoryDialog(getSite().getShell(), SWT.OPEN);
        dialog.setText("Select saved Zephyr RAM CTF capture");
        dialog.setMessage("Choose .zew-traces/trace-*/ or its ctf/ subfolder; capture.json is required.");
        String chosen = dialog.open();
        if (chosen == null) return;
        Path location = Path.of(chosen).toAbsolutePath().normalize();
        if (location.getFileName() != null && "ctf".equals(location.getFileName().toString()))
            location = location.getParent();
        selectCapture(location);
    }

    private void latest() {
        IProject selected = project();
        if (selected == null) {
            status.setText("Select an imported Zephyr project or its active Debug frame, "
                    + "then choose latest saved capture.");
            return;
        }
        try {
            var found = TraceCaptureWorkflow.latest(selected);
            if (found.isEmpty()) {
                status.setText("No saved CTF under " + selected.getName()
                        + "/.zew-traces; capture RAM CTF explicitly first.");
                return;
            }
            selectCapture(found.get().getParent());
        } catch (IOException error) {
            status.setText("Cannot find saved capture: " + error.getMessage());
        }
    }

    private void selectCapture(Path path) {
        if (path == null || !TraceCaptureWorkflow.isSavedCtf(path.resolve("ctf"))
                || !Files.isRegularFile(path.resolve("capture.json"))) {
            savedCapture = null;
            selection.setText("No verified capture selected.");
            launchStandalone.setEnabled(false);
            status.setText("Select a saved trace-* directory containing capture.json and "
                    + "ctf/metadata + ctf/channel0_0. Hashes are verified before handoff.");
            return;
        }
        savedCapture = path;
        selection.setText("Selected capture (not yet verified): " + path);
        launchStandalone.setEnabled(true);
        status.setText("Ready for explicit standalone handoff. No viewer has been launched.");
    }

    private void chooseExecutable() {
        var dialog = new FileDialog(getSite().getShell(), SWT.OPEN);
        dialog.setText("Choose existing Trace Compass standalone executable");
        String chosen = dialog.open();
        if (chosen != null) standaloneExecutable.setText(chosen);
    }

    private void openStandalone() {
        if (handoff != null) {
            status.setText("Trace Compass handoff already in progress.");
            return;
        }
        final Path capture = savedCapture;
        final IProject selected = project();
        if (capture == null || selected == null) {
            status.setText("Select a saved CTF capture and an imported Zephyr project "
                    + "to use its managed Python interpreter.");
            return;
        }
        final String executable = standaloneExecutable.getText().strip();
        final java.util.Properties settings;
        try {
            settings = ProjectContext.settings(selected);
        } catch (Exception error) {
            status.setText("Managed Python setup unavailable: " + error.getMessage());
            return;
        }
        launchStandalone.setEnabled(false);
        status.setText("Validating saved CTF and requesting standalone Trace Compass launch...");
        final var uiDisplay = getSite().getShell().getDisplay();
        Job job = new Job("Open verified saved Zephyr CTF in Trace Compass") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                Path temporary = null;
                String outcome;
                IStatus result = Status.OK_STATUS;
                try {
                    temporary = Files.createTempDirectory("zew-trace-compass-");
                    var entry = Activator.getDefault().getBundle()
                            .getEntry("tools/rtos_analysis/trace_compass_bridge.py");
                    if (entry == null) throw new IOException(
                            "Installed Workbench is missing trace_compass_bridge.py.");
                    Path bridge = temporary.resolve("trace_compass_bridge.py");
                    try (var input = entry.openStream()) { Files.copy(input, bridge); }
                    var command = new ArrayList<String>();
                    command.add(settings.getProperty("python"));
                    command.add(bridge.toString());
                    command.add("--capture");
                    command.add(capture.toString());
                    command.add("--open");
                    if (!executable.isBlank()) {
                        command.add("--launcher");
                        command.add(executable);
                    }
                    String raw = new ProcessRunner().run(command, temporary, Duration.ofSeconds(20),
                            monitor::isCanceled, ignored -> {},
                            ProjectContext.environment(settings)).requireCompleteOutput();
                    if (!raw.contains("\"status\": \"launch-requested\""))
                        throw new IOException("Trace Compass launch was not confirmed by its bridge: " + raw);
                    outcome = "Trace Compass launch requested for " + capture.resolve("ctf")
                            + ". Verify that the viewer imported the CTF; XML analysis is a separate step.";
                } catch (java.io.InterruptedIOException cancelled) {
                    result = Status.CANCEL_STATUS;
                    outcome = "Trace Compass handoff cancelled.";
                } catch (Exception error) {
                    outcome = "Trace Compass handoff unavailable: " + error.getMessage()
                            + ". For standalone use, choose an executable or configure ZEW_TRACE_COMPASS. "
                            + "For Eclipse use, install the official Trace Compass plug-ins.";
                    result = new Status(IStatus.ERROR, Activator.ID, outcome, error);
                } finally {
                    if (temporary != null) {
                        try (var files = Files.walk(temporary)) {
                            for (var path : files.sorted(Comparator.reverseOrder()).toList())
                                Files.deleteIfExists(path);
                        } catch (IOException ignored) { /* temp helper cleanup only */ }
                    }
                }
                String message = outcome;
                if (!uiDisplay.isDisposed()) uiDisplay.asyncExec(() -> {
                    if (disposed || status == null || status.isDisposed()) return;
                    status.setText(message);
                    if (launchStandalone != null && !launchStandalone.isDisposed())
                        launchStandalone.setEnabled(savedCapture != null);
                    handoff = null;
                    status.getParent().layout(true, true);
                });
                return result;
            }
        };
        handoff = job;
        job.setUser(true);
        job.schedule();
    }

    @Override public void setFocus() {
        if (nativePerspective != null && !nativePerspective.isDisposed())
            nativePerspective.setFocus();
    }

    @Override public void dispose() {
        disposed = true;
        if (handoff != null) handoff.cancel();
        super.dispose();
    }
}
