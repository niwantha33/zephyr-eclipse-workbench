// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Path;
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
    private Button captureRam, captureUart;

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
        controls.setLayout(new GridLayout(3, false));
        button(controls, "Use selected project", () -> {
            var selection = getSite().getWorkbenchWindow().getSelectionService().getSelection();
            IProject selected = selection instanceof IStructuredSelection items && items.size() == 1
                    ? SelectedProject.importedProject(items.getFirstElement()) : null;
            if (selected != null) displayProject(selected);
            else context.setText("Select an imported Zephyr project in Project Explorer first.");
        });
        button(controls, "Refresh build evidence", this::refresh);
        button(controls, "Clear displayed output", () -> output.setText(""));

        context = new Label(parent, SWT.WRAP);
        context.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        context.setText("Select an imported Zephyr project. No device operation is performed by inspection.");

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
        actions.setLayout(new GridLayout(2, false));
        captureRam = button(actions, "Capture RAM CTF (halts/resets target)...", () -> {
            if (project != null && snapshot.get(Backend.RAM_CTF).state() == State.CONFIGURED)
                ZephyrTraceHandler.capture(project, getSite().getShell());
        });
        captureUart = button(actions, "Capture UART/USB text (no probe)...", () -> {
            if (project != null) ApplicationConsoleHandler.capture(project, getSite().getShell());
        });

        var note = new Label(parent, SWT.WRAP);
        note.setText("RTT and ITM/SWO require a probe-specific receiver (not started by this version). "
                + "RAM CTF must be decoded as a trace, never printed as text. "
                + "Captured evidence is also kept in the normal Eclipse Console and project output folders.");
        note.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        output = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL);
        output.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        output.setText("Capture diagnostics appear here when this view is open. "
                + "Timestamps displayed by UART capture are host receive timestamps.\n");
        OPEN.add(this);
        showSnapshot();
        var selection = getSite().getWorkbenchWindow().getSelectionService().getSelection();
        if (selection instanceof IStructuredSelection items && items.size() == 1) {
            var selected = SelectedProject.importedProject(items.getFirstElement());
            if (selected != null) displayProject(selected);
        }
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
        project = selected;
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
                    context.getDisplay().asyncExec(() -> {
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

    /** Read-only progress fan-out. The original Eclipse Console remains authoritative. */
    static void append(IProject source, String transport, String message) {
        if (source == null || message == null) return;
        for (var view : OPEN) {
            if (view.project == null || !view.project.equals(source)
                    || view.output == null || view.output.isDisposed()) continue;
            view.output.getDisplay().asyncExec(() -> {
                if (view.output == null || view.output.isDisposed() || !source.equals(view.project)) return;
                var text = view.output;
                if (text.getCharCount() >= MAX_TEXT)
                    text.replaceTextRange(0, text.getCharCount() / 2, "[Earlier diagnostic lines omitted]\n");
                text.append("[" + transport + "] " + message + "\n");
                text.setSelection(text.getCharCount());
            });
        }
    }

    @Override public void setFocus() {
        if (output != null && !output.isDisposed()) output.setFocus();
    }

    @Override public void dispose() {
        generation.incrementAndGet();
        OPEN.remove(this);
        super.dispose();
    }
}
