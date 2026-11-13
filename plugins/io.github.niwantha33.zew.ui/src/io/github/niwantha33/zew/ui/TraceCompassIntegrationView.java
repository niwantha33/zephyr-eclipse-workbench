// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
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
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.WorkbenchException;
import org.eclipse.ui.handlers.IHandlerService;
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
    private Button nativePerspective, installInEclipse, launchStandalone, inspectWorkbench;
    private static final Pattern BRIDGE_STATUS =
            Pattern.compile("\"status\"\\s*:\\s*\"([a-z-]+)\"");
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
        installedActions.setLayout(new GridLayout(2, false));
        nativePerspective = button(installedActions, "Open CTF in installed Trace Compass", this::openPerspective);
        button(installedActions, "Check installation", this::refreshInstallation);
        installInEclipse = button(installedActions, "Install Trace Compass in Eclipse...", this::openInstallWizard);
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

        inspectWorkbench = button(parent, "Inspect saved CTF events in Workbench", this::inspectSavedTrace);
        inspectWorkbench.setEnabled(false);
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
        return TraceCompassNativeBridge.installed();
    }

    private void refreshInstallation() {
        boolean present = installed();
        installation.setText(present
                ? "Trace Compass CTF reader and Tracing perspective: INSTALLED in this Eclipse."
                : "Trace Compass: " + TraceCompassNativeBridge.availability()
                        + ". The native Workbench CTF event viewer remains available.");
        nativePerspective.setEnabled(present);
        installInEclipse.setEnabled(!present);
        installation.getParent().layout(true, true);
    }

    /**
     * Open Eclipse's supported p2 installer. The user explicitly chooses the
     * Trace Compass feature, approves its licences and authorises changes.
     * Nothing is installed, downloaded or added to p2 on view creation.
     * No compile-time dependency on optional Trace Compass bundles.
     */
    private void openInstallWizard() {
        if (installed()) {
            refreshInstallation();
            status.setText("Trace Compass Tracing perspective is already installed in this Eclipse.");
            return;
        }
        status.setText("Install New Software: click Add..., set Name = Trace Compass stable "
                + "and Location = " + UPDATE_SITE + ". Select the Trace Compass feature, "
                + "review dependencies/licences and restart this Eclipse. "
                + "Installation is not automatic.");
        try {
            IHandlerService service = getSite().getService(IHandlerService.class);
            if (service == null)
                throw new IllegalStateException("Eclipse p2 Install New Software command is unavailable");
            service.executeCommand("org.eclipse.equinox.p2.ui.sdk.install", null);
            refreshInstallation();
        } catch (Exception error) {
            status.setText("Could not open native Eclipse software installer: "
                    + error.getMessage() + ". Use Help > Install New Software > Add... "
                    + UPDATE_SITE + ". Never run an unknown external installer.");
        }
    }

    private void openPerspective() {
        if (!installed()) {
            refreshInstallation();
            status.setText(TraceCompassNativeBridge.availability()
                    + ". Restart Eclipse or install the missing Trace Compass CTF feature.");
            return;
        }
        final Path ctf = savedCapture == null ? null : savedCapture.resolve("ctf");
        try {
            PlatformUI.getWorkbench().showPerspective(
                    TRACING_PERSPECTIVE, getSite().getWorkbenchWindow());
            if (ctf == null) {
                status.setText("Tracing perspective opened. Select latest capture and click again to import.");
                return;
            }
            // Trace Compass's own public openTraceFromPath API links and opens
            // this exact CTF. No separate executable and no Zephyr project mutation.
            // A validated Zephyr RAM stream may still be unsupported by TC;
            // show the status rather than inventing an imported trace.
            org.eclipse.core.runtime.IStatus result =
                    TraceCompassNativeBridge.open(ctf, getSite().getShell());
            status.setText(result.isOK() && result.getCode() != org.eclipse.core.runtime.IStatus.INFO
                    ? "Trace Compass CTF open requested: " + ctf
                    : "Trace Compass could not open this CTF: " + result.getMessage()
                            + ". Use Inspect saved CTF events in Workbench.");
        } catch (Exception error) {
            status.setText("Trace Compass import failed: " + error.getMessage()
                    + ". Use native Workbench Observed CTF Events for this Zephyr RAM stream.");
        }
    }

    private void chooseCapture() {
        var dialog = new FileDialog(getSite().getShell(), SWT.OPEN);
        dialog.setText("Select capture.json from a saved Zephyr CTF snapshot");
        dialog.setFilterNames(new String[] { "Zephyr capture.json", "All files" });
        dialog.setFilterExtensions(new String[] { "capture.json", "*" });
        String chosen = dialog.open();
        if (chosen == null) return;
        Path file = Path.of(chosen).toAbsolutePath().normalize();
        if (file.getFileName() == null || !"capture.json".equals(file.getFileName().toString())
                || !Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            status.setText("Select capture.json inside a saved trace-* directory.");
            return;
        }
        selectCapture(file.getParent());
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
            inspectWorkbench.setEnabled(false);
            status.setText("Select a saved trace-* directory containing capture.json and "
                    + "ctf/metadata + ctf/channel0_0. Hashes are verified before handoff.");
            return;
        }
        savedCapture = path;
        selection.setText("Selected capture (not yet verified): " + path);
        launchStandalone.setEnabled(true);
        inspectWorkbench.setEnabled(true);
        status.setText("Capture selected. Inspect events in Workbench without Trace Compass, "
                + "or explicitly open the optional standalone viewer.");
    }

    /** Built-in read-only viewing remains usable even when Trace Compass is absent. */
    private void inspectSavedTrace() {
        IProject selected = project();
        if (savedCapture == null || selected == null) {
            status.setText("Select a saved capture and its imported Zephyr application project.");
            return;
        }
        try {
            var view = (ThreadTimelineView) getSite().getPage().showView(ThreadTimelineView.ID);
            view.displayProject(selected);
            view.loadSavedCapture(savedCapture.resolve("ctf"));
            status.setText("Opening saved CTF in Workbench Thread Timeline > Observed CTF Events. "
                    + "RAM-buffer-full traces can show events but not qualified CPU percentages.");
        } catch (org.eclipse.ui.PartInitException error) {
            status.setText("Could not open native CTF event viewer: " + error.getMessage());
        }
    }

    /**
     * Exit 2 is an expected optional-viewer absence, not corrupt CTF.
     * Require one unambiguous bridge status and its matching exit code.
     * Other codes or truncated output remain failures, never success.
     */
    static String classifyHandoff(String output, int exitCode, boolean truncated) throws IOException {
        if (truncated || output == null || output.length() > 16_384)
            throw new IOException("Trace Compass bridge returned incomplete or oversized output.");
        Matcher status = BRIDGE_STATUS.matcher(output);
        if (!status.find()) throw new IOException("Trace Compass bridge omitted its status.");
        String value = status.group(1);
        if (status.find()) throw new IOException("Trace Compass bridge reported multiple statuses.");
        if (exitCode == 0 && "launch-requested".equals(value)) return value;
        if (exitCode == 2 && "viewer-unavailable".equals(value)) return value;
        throw new IOException("Trace Compass bridge returned unexpected status " + value
                + " (exit " + exitCode + ").");
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
                    var process = new ProcessRunner().run(command, temporary, Duration.ofSeconds(20),
                            monitor::isCanceled, ignored -> {},
                            ProjectContext.environment(settings));
                    String bridgeState;
                    try {
                        bridgeState = classifyHandoff(
                                process.output(), process.exitCode(), process.truncated());
                    } catch (IOException invalid) {
                        // Preserve the actual tool's diagnostic for malformed capture,
                        // corrupt receipt, invalid launcher or a Python failure.
                        process.requireCompleteOutput();
                        throw invalid;
                    }
                    if ("viewer-unavailable".equals(bridgeState)) {
                        outcome = "Saved CTF verified. No standalone tracecompass.exe found. "
                                + "This is NOT a CTF decode failure. Select an existing "
                                + "Trace Compass executable, or use Install Trace Compass "
                                + "in Eclipse / Open installed Tracing perspective. "
                                + "The native Workbench event viewer also works without it.";
                    } else {
                        outcome = "Standalone Trace Compass launch requested for "
                                + capture.resolve("ctf")
                                + ". Verify that the viewer imported the trace; "
                                + "the launch alone does not prove successful decoding.";
                    }
                    if (process.output().contains("\"capture_full\": true"))
                        outcome += " RAM tracing buffer is FULL: CPU/timing completeness is unavailable.";
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
