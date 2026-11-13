// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.contexts.IDebugContextListener;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.MessageConsole;
import io.github.niwantha33.zew.core.RttTcpReader;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;
import io.github.niwantha33.zew.core.BuildArtifacts;
import io.github.niwantha33.zew.core.TraceConsoleCapabilities;
import io.github.niwantha33.zew.core.TraceConsoleCapabilities.Backend;
import io.github.niwantha33.zew.core.TraceConsoleCapabilities.Channel;
import io.github.niwantha33.zew.core.TraceConsoleCapabilities.Snapshot;
import io.github.niwantha33.zew.core.TraceConsoleCapabilities.State;

/**
 * One discoverable trace entrypoint, without claiming that .config proves probe support.
 * RAM CTF is binary evidence; the text pane only displays capture diagnostics.
 * The existing independently owned RAM and UART capture jobs remain the transport owners.
 */
public final class ZephyrTraceConsoleView extends ViewPart {
    public static final String ID = Activator.ID + ".traceConsole";
    private static final CopyOnWriteArrayList<ZephyrTraceConsoleView> OPEN =
            new CopyOnWriteArrayList<>();
    private static final int MAX_TEXT = 120_000;

    private final AtomicInteger generation = new AtomicInteger();
    private volatile IProject project;
    private Snapshot snapshot = TraceConsoleCapabilities.inspect(Map.of());
    private Label context;
    private Table transports;
    private Text explanation, output;
    private Button captureRam, captureUart, captureRtt;
    private Text rttPort;
    private volatile Job rttJob;
    private IDebugContextListener debugContextListener;
    private static final QualifiedName RTT_PORT = new QualifiedName(Activator.ID, "rtt.localPort");

    @Override public void createPartControl(Composite parent) {
        var layout = new GridLayout(1, false);
        layout.marginWidth = 12;
        layout.marginHeight = 10;
        parent.setLayout(layout);

        var heading = new Label(parent, SWT.WRAP);
        heading.setText("DEBUG TRACE CONSOLE  /  FIRMWARE + PROBE EVIDENCE");
        heading.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        var controls = new Composite(parent, SWT.NONE);
        controls.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        controls.setLayout(new GridLayout(4, false));
        button(controls, "Use selected project / debug session", () -> {
            IProject chosen = selectedProject();
            if (chosen == null) chosen = activeDebugProject();
            if (chosen == null) chosen = uniqueLiveDebugProject();
            if (chosen != null) displayProject(chosen);
            else context.setText("No unique imported Zephyr project/debug session. "
                    + "Select one in Project Explorer or Debug; no target access attempted.");
        });
        button(controls, "Refresh build evidence", this::refresh);
        button(controls, "Trace Compass...", () -> {
            try {
                var view = getSite().getPage().showView(TraceCompassIntegrationView.ID);
                if (view instanceof TraceCompassIntegrationView compass && project != null)
                    compass.displayProject(project);
            } catch (org.eclipse.ui.PartInitException error) {
                context.setText("Could not open Trace Compass integration: " + error.getMessage());
            }
        });
        button(controls, "Clear displayed output", () -> output.setText(""));

        context = new Label(parent, SWT.WRAP);
        context.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        context.setText("Finding an imported project or active Zephyr debug session. Inspection does not access target memory.");

        transports = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
        transports.setHeaderVisible(true);
        transports.setLinesVisible(true);
        GridData transportLayout = new GridData(SWT.FILL, SWT.TOP, true, false);
        transportLayout.heightHint = 160;
        transports.setLayoutData(transportLayout);
        column("Transport", 150);
        column("Effective build configuration", 230);
        transports.addListener(SWT.Selection, event -> explainSelection());

        explanation = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.READ_ONLY | SWT.V_SCROLL);
        GridData explanationLayout = new GridData(SWT.FILL, SWT.TOP, true, false);
        explanationLayout.heightHint = 90;
        explanation.setLayoutData(explanationLayout);

        var actions = new Composite(parent, SWT.NONE);
        actions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        actions.setLayout(new GridLayout(5, false));
        captureRam = button(actions, "Capture RAM CTF (halts/resets target)...", () -> {
            if (project != null && snapshot.get(Backend.RAM_CTF).state() == State.CONFIGURED)
                ZephyrTraceHandler.capture(project, getSite().getShell());
        });
        captureUart = button(actions, "Capture UART/USB text (no probe)...", () -> {
            if (project != null) ApplicationConsoleHandler.capture(project, getSite().getShell());
        });
        new Label(actions, SWT.NONE).setText("Local RTT TCP port:");
        rttPort = new Text(actions, SWT.BORDER);
        rttPort.setLayoutData(new GridData(90, SWT.DEFAULT));
        rttPort.setText("19021");
        captureRtt = button(actions, "Connect RTT (opt-in)", this::toggleRtt);

        var note = new Label(parent, SWT.WRAP);
        note.setText("This is a transport dashboard, not an automatic live text stream. "
                + "Capture UART/USB text from a selected serial port, or explicitly connect to an existing RTT TCP receiver. "
                + "RTT does not issue OpenOCD commands or scan target memory. "
                + "ITM/SWO remains build-intent only. RAM CTF capture can reset/halt the target; "
                + "never launch it unintentionally during an active debug session.");
        note.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        output = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL);
        output.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        output.setText("Capture diagnostics appear here when this view is open. "
                + "Timestamps displayed by UART capture are host receive timestamps.\n");
        OPEN.add(this);
        showSnapshot();
        // A trace view commonly opens with the Debug tree focused, not Project
        // Explorer. Resolve a unique active Zephyr launch instead of staying blank.
        IProject initial = activeDebugProject();
        if (initial == null) initial = selectedProject();
        if (initial == null) initial = uniqueLiveDebugProject();
        if (initial != null) displayProject(initial);
        else context.setText("No unambiguous active Zephyr project. Select a project or "
                + "suspended Zephyr debug frame, then use the selection button.");
        debugContextListener = event -> {
            var display = parent.getDisplay();
            if (display.isDisposed()) return;
            display.asyncExec(() -> {
                if (context == null || context.isDisposed()) return;
                IProject resolved = activeDebugProject();
                if (resolved != null && !resolved.equals(project)) displayProject(resolved);
            });
        };
        DebugUITools.getDebugContextManager().getContextService(getSite().getWorkbenchWindow())
                .addDebugContextListener(debugContextListener);
    }

    private IProject selectedProject() {
        var selection = getSite().getWorkbenchWindow().getSelectionService().getSelection();
        if (!(selection instanceof IStructuredSelection items) || items.size() != 1) return null;
        return SelectedProject.importedProject(items.getFirstElement());
    }

    /** Use the exact active debug context when it carries an imported Zephyr launch. */
    private IProject activeDebugProject() {
        var contextService = DebugUITools.getDebugContextManager()
                .getContextService(getSite().getWorkbenchWindow());
        var selection = contextService.getActiveContext();
        if (selection instanceof IStructuredSelection items && items.size() == 1) {
            IProject selected = SelectedProject.importedProject(items.getFirstElement());
            if (selected != null) return selected;
        }
        return null;
    }

    /**
     * Last resort: exactly one live project-labelled native Zephyr launch.
     * Multiple different projects are ambiguous; never take the first match.
     * This is metadata only and does not enumerate probe threads or memory.
     */
    private IProject uniqueLiveDebugProject() {
        IProject found = null;
        for (ILaunch launch : DebugPlugin.getDefault().getLaunchManager().getLaunches()) {
            if (launch.isTerminated() || launch.getLaunchConfiguration() == null) continue;
            try {
                var config = launch.getLaunchConfiguration();
                if (!ZephyrLaunchDelegate.TYPE.equals(config.getType().getIdentifier())) continue;
                String name = config.getAttribute(ZephyrLaunchDelegate.PROJECT, "");
                if (name.isBlank()) continue;
                IProject selected = SelectedProject.importedProject(
                        ResourcesPlugin.getWorkspace().getRoot().getProject(name));
                if (selected == null) continue;
                if (found != null && !found.equals(selected)) return null;
                found = selected;
            } catch (org.eclipse.core.runtime.CoreException ignored) {
                // No project identity can be proven from this launch.
            }
        }
        return found;
    }

    private Button button(Composite parent, String label, Runnable action) {
        var button = new Button(parent, SWT.PUSH);
        button.setText(label);
        button.addListener(SWT.Selection, event -> action.run());
        return button;
    }

    private void column(String title, int width) {
        var column = new TableColumn(transports, SWT.LEFT);
        column.setText(title);
        column.setWidth(width);
    }

    public void displayProject(IProject selected) {
        if (selected == null || !selected.isAccessible()) return;
        if (rttJob != null && project != selected) rttJob.cancel();
        project = selected;
        try {
            String saved = selected.getPersistentProperty(RTT_PORT);
            if (saved != null && !saved.isBlank() && rttPort != null && !rttPort.isDisposed())
                rttPort.setText(saved);
        } catch (org.eclipse.core.runtime.CoreException ignored) { /* default remains */ }
        setPartName("Debug Trace Console — " + selected.getName());
        refresh();
    }

    private void refresh() {
        if (context == null || context.isDisposed()) return;
        if (project == null || !project.isAccessible()) {
            context.setText("Select a Zephyr project before inspecting build evidence.");
            snapshot = TraceConsoleCapabilities.inspect(Map.of());
            showSnapshot();
            return;
        }
        final IProject selected = project;
        final int request = generation.incrementAndGet();
        context.setText("Inspecting generated build settings for " + selected.getName() + "...");
        new Job("Inspect trace configuration — " + selected.getName()) {
            @Override protected IStatus run(IProgressMonitor monitor) {
                Snapshot result = TraceConsoleCapabilities.inspect(Map.of());
                String status;
                try {
                    var settings = ProjectContext.settings(selected);
                    Path build = ProjectContext.buildDirectory(selected, settings);
                    result = TraceConsoleCapabilities.inspect(BuildArtifacts.configuration(build));
                    status = "Project: " + selected.getName()
                            + "  |  Board: " + settings.getProperty("board", "(unknown)")
                            + "  |  Profile: " + settings.getProperty("zew.buildConfiguration", "(unknown)")
                            + "\nEvidence: " + build.resolve("zephyr/.config")
                            + "  |  Firmware on device: NOT VERIFIED";
                } catch (Exception error) {
                    status = "No trustworthy generated build configuration: " + error.getMessage()
                            + "\nBuild the selected project, then Refresh. No probe operation was attempted.";
                }
                final Snapshot inspected = result;
                final String label = status;
                if (context != null && !context.isDisposed()) {
                    org.eclipse.swt.widgets.Display.getDefault().asyncExec(() -> {
                        if (context.isDisposed() || generation.get() != request
                                || project != selected) return;
                        snapshot = inspected;
                        context.setText(label);
                        showSnapshot();
                    });
                }
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    private void showSnapshot() {
        if (transports == null || transports.isDisposed()) return;
        Backend previous = selectedBackend();
        transports.removeAll();
        int select = 0, index = 0;
        for (Channel channel : snapshot.channels()) {
            var row = new TableItem(transports, SWT.NONE);
            row.setText(new String[] {channel.title(), switch (channel.state()) {
                case CONFIGURED -> "Configured in built firmware (probe unverified)";
                case NOT_CONFIGURED -> "Not configured in built firmware";
                case UNSUPPORTED -> "Unsupported CPU architecture";
                case UNKNOWN -> "Build evidence unavailable";
            }});
            row.setData(channel);
            if (channel.backend() == previous) select = index;
            index++;
        }
        transports.setSelection(select);
        captureRam.setEnabled(project != null && project.isAccessible()
                && snapshot.get(Backend.RAM_CTF).state() == State.CONFIGURED);
        captureUart.setEnabled(project != null && project.isAccessible());
        captureRtt.setEnabled(project != null && project.isAccessible());
        explainSelection();
    }

    private Backend selectedBackend() {
        if (transports == null || transports.isDisposed() || transports.getSelectionCount() != 1)
            return Backend.RAM_CTF;
        Object value = transports.getSelection()[0].getData();
        return value instanceof Channel channel ? channel.backend() : Backend.RAM_CTF;
    }

    private void explainSelection() {
        if (explanation == null || explanation.isDisposed() || transports.getSelectionCount() != 1) return;
        var channel = (Channel) transports.getSelection()[0].getData();
        explanation.setText(channel.explanation() + "\n\nNext: " + channel.nextStep());
    }

    private void toggleRtt() {
        if (rttJob != null) {
            rttJob.cancel();
            append(project, "RTT", "Stop requested; receiver will release TCP connection.");
            return;
        }
        final IProject selected = project;
        if (selected == null || !selected.isAccessible()) return;
        int port;
        try {
            port = Integer.parseInt(rttPort.getText().trim());
            if (port < 1024 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException invalid) {
            append(selected, "RTT", "Enter an explicitly configured localhost TCP port (1024–65535).");
            return;
        }
        try { selected.setPersistentProperty(RTT_PORT, Integer.toString(port)); }
        catch (org.eclipse.core.runtime.CoreException ignored) { /* capture still explicit */ }
        final int receiverPort = port;
        Job job = new Job("Receive Zephyr RTT localhost TCP — " + selected.getName()) {
            @Override protected IStatus run(IProgressMonitor monitor) {
                var console = new MessageConsole("Zephyr RTT localhost:" + receiverPort
                        + " — " + selected.getName(), null);
                ConsolePlugin.getDefault().getConsoleManager().addConsoles(new IConsole[] {console});
                ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
                try (var stream = console.newMessageStream()) {
                    Path folder = selected.getLocation().toFile().toPath().resolve(".zew-rtt");
                    Files.createDirectories(folder);
                    Path raw = folder.resolve("rtt-" + java.util.UUID.randomUUID() + ".bin");
                    stream.println("Passive RTT channel-0 text stream, host receive time only; "
                            + "requires an existing user-configured local TCP RTT server.");
                    stream.println("Raw byte evidence: " + raw);
                    var result = RttTcpReader.capture(receiverPort, 60, raw,
                            monitor::isCanceled, bytes -> {
                                String text = new String(bytes, StandardCharsets.UTF_8);
                                stream.print(text);
                                append(selected, "RTT", text);
                            });
                    stream.println("\nRTT receiver ended: " + result.bytes() + " raw bytes; "
                            + (result.peerClosed() ? "peer closed" : "capture duration reached")
                            + ". Not MCU timestamps.");
                    return Status.OK_STATUS;
                } catch (java.io.InterruptedIOException cancelled) {
                    return Status.CANCEL_STATUS;
                } catch (Exception error) {
                    append(selected, "RTT", "Receiver failed: " + error.getMessage());
                    return new Status(IStatus.ERROR, Activator.ID, "RTT receiver failed", error);
                } finally {
                    if (rttJob == this) rttJob = null;
                    var display = org.eclipse.swt.widgets.Display.getDefault();
                    if (!display.isDisposed()) display.asyncExec(() -> {
                        if (captureRtt != null && !captureRtt.isDisposed())
                            captureRtt.setText("Connect RTT (opt-in)");
                    });
                }
            }
        };
        rttJob = job;
        captureRtt.setText("Stop RTT capture");
        job.setUser(true);
        job.schedule();
    }

    /** Read-only progress fan-out. The original Eclipse Console remains authoritative. */
    static void append(IProject source, String transport, String message) {
        if (source == null || message == null) return;
        for (var view : OPEN) {
            if (view.project == null || !view.project.equals(source)
                    || view.output == null || view.output.isDisposed()) continue;
            org.eclipse.swt.widgets.Display.getDefault().asyncExec(() -> {
                if (view.output == null || view.output.isDisposed() || !source.equals(view.project)) return;
                var text = view.output;
                if (text.getCharCount() >= MAX_TEXT)
                    text.setText("[Earlier diagnostic lines omitted]\n");
                text.append("[" + transport + "] " + message + "\n");
                text.setSelection(text.getCharCount());
            });
        }
    }

    @Override public void setFocus() {
        if (output != null && !output.isDisposed()) output.setFocus();
    }

    @Override public void dispose() {
        if (rttJob != null) rttJob.cancel();
        if (debugContextListener != null)
            DebugUITools.getDebugContextManager().getContextService(getSite().getWorkbenchWindow())
                    .removeDebugContextListener(debugContextListener);
        generation.incrementAndGet();
        OPEN.remove(this);
        super.dispose();
    }
}
