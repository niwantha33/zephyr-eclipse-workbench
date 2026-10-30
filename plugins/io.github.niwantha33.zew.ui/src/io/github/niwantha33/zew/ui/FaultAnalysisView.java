// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.ViewPart;
import io.github.niwantha33.zew.core.ArmElfSymbols;
import io.github.niwantha33.zew.core.FaultLogAnalysis;

/** Offline fatal log analysis; deliberately independent of a selected project or connected board. */
public final class FaultAnalysisView extends ViewPart {
    public static final String ID = Activator.ID + ".faultAnalysis";
    private final AtomicInteger generation = new AtomicInteger();
    private org.eclipse.swt.widgets.Display ui;
    private Combo architecture;
    private Text reportText;
    private Label status;
    private Table locations;
    private FaultLogAnalysis.Report report;
    private String importedLog;
    private String provenance = "";
    private Job job;

    @Override public void createPartControl(Composite parent) {
        ui = parent.getDisplay();
        parent.setLayout(new GridLayout(1, false));
        var controls = new Composite(parent, SWT.NONE);
        controls.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        controls.setLayout(new GridLayout(4, false));
        button(controls, "Open fatal log…", this::openLog);
        architecture = new Combo(controls, SWT.READ_ONLY);
        for (var value : FaultLogAnalysis.Architecture.values()) architecture.add(value.label());
        architecture.select(0);
        architecture.setToolTipText("Choose the architecture from the board/SoC documentation. No automatic architecture inference.");
        architecture.addListener(SWT.Selection, event -> { if (importedLog != null) analyse(importedLog, provenance); });
        button(controls, "Resolve PC / LR…", this::resolve);
        button(controls, "Export report…", this::exportReport);
        status = new Label(parent, SWT.WRAP);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        status.setText("Open a UTF-8 log containing one failure. Select Cortex-M architecture to enable decoding. No hardware is accessed.");
        var split = new SashForm(parent, SWT.VERTICAL);
        split.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        reportText = new Text(split, SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        reportText.setFont(JFaceResources.getTextFont());
        reportText.setText("Import a Zephyr fatal log to inspect captured registers and supported fault status bits.\n\n"
                + "This view accepts text logs, not binary core dumps. Core-dump memory/thread investigation still uses Zephyr's GDB server.\n"
                + "Address lookup requires an explicitly confirmed matching ARM ELF and your trusted addr2line executable.");
        locations = new Table(split, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.H_SCROLL | SWT.V_SCROLL);
        locations.setHeaderVisible(true);
        locations.setLinesVisible(true);
        for (String title : new String[] {"Register", "Function", "Source location"}) {
            var column = new TableColumn(locations, SWT.NONE);
            column.setText(title);
            column.setWidth(title.equals("Register") ? 90 : title.equals("Function") ? 230 : 480);
        }
        locations.setToolTipText("Double-click a resolved location to open an existing local source file. No source path is guessed.");
        locations.addListener(SWT.DefaultSelection, event -> revealSource());
        split.setWeights(80, 20);
    }
    private void button(Composite parent, String title, Runnable action) {
        var button = new Button(parent, SWT.PUSH);
        button.setText(title);
        button.addListener(SWT.Selection, event -> action.run());
    }
    private FaultLogAnalysis.Architecture selectedArchitecture() {
        return FaultLogAnalysis.Architecture.values()[architecture.getSelectionIndex()];
    }
    private void openLog() {
        var dialog = new FileDialog(getSite().getShell(), SWT.OPEN);
        dialog.setText("Open one Zephyr fatal-log excerpt (UTF-8, up to 4 MiB)");
        dialog.setFilterExtensions(new String[] {"*.log;*.txt", "*"});
        String chosen = dialog.open();
        if (chosen == null) return;
        int token = begin("Reading fatal log…");
        var arch = selectedArchitecture();
        job = new Job("Import Zephyr fatal log") {
            @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                try {
                    byte[] bytes;
                    try (var in = Files.newInputStream(Path.of(chosen))) { bytes = in.readNBytes(FaultLogAnalysis.MAX_LOG_CHARS + 1); }
                    if (bytes.length > FaultLogAnalysis.MAX_LOG_CHARS) throw new java.io.IOException("Select a log excerpt no larger than 4 MiB.");
                    String log = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                    if (log.indexOf('\0') >= 0) throw new java.io.IOException("Binary data is not a supported fatal text log.");
                    String identity = "Imported log: " + Path.of(chosen).toAbsolutePath() + "\nLog SHA-256: "
                            + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) + "\n";
                    var result = FaultLogAnalysis.inspect(log, arch);
                    complete(token, () -> { importedLog = log; provenance = identity; show(result); });
                } catch (Exception error) { fail(token, error); }
                return monitor.isCanceled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }
    private void analyse(String log, String identity) {
        int token = begin("Analysing captured evidence…");
        var arch = selectedArchitecture();
        job = new Job("Analyse Cortex-M fatal log") {
            @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                try {
                    var result = FaultLogAnalysis.inspect(log, arch);
                    complete(token, () -> { provenance = identity; show(result); });
                } catch (Exception error) { fail(token, error); }
                return monitor.isCanceled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
            }
        };
        job.schedule();
    }
    private void show(FaultLogAnalysis.Report result) {
        report = result;
        locations.removeAll();
        reportText.setText(provenance + "\n" + result.display());
        status.setText("Offline analysis complete. Captured status is evidence, not a guaranteed root cause.");
    }
    private void resolve() {
        if (report == null || report.architecture() == FaultLogAnalysis.Architecture.UNSPECIFIED) {
            MessageDialog.openInformation(getSite().getShell(), "Address lookup", "Import one failure and select its Cortex-M architecture first.");
            return;
        }
        var addresses = report.registers().values().stream().filter(r -> r.name().equals("PC") || r.name().equals("LR")).toList();
        if (addresses.isEmpty()) {
            MessageDialog.openInformation(getSite().getShell(), "Address lookup", "No unambiguous PC or LR was captured.");
            return;
        }
        var elfDialog = new FileDialog(getSite().getShell(), SWT.OPEN);
        elfDialog.setText("Select the exact ARM ELF built for this captured failure");
        elfDialog.setFilterExtensions(new String[] {"*.elf;*.axf", "*"});
        String elf = elfDialog.open();
        if (elf == null) return;
        if (!MessageDialog.openQuestion(getSite().getShell(), "Confirm firmware provenance",
                "Have you independently checked that this exact ELF belongs to the captured failure?\n\n" + elf
                + "\n\nThe log parser cannot establish this match. A different ELF can produce convincing but incorrect source locations."
                + "\nSelecting Yes records user confirmation, not automatic verification.")) return;
        var toolDialog = new FileDialog(getSite().getShell(), SWT.OPEN);
        toolDialog.setText("Select trusted toolchain addr2line (for example arm-zephyr-eabi-addr2line)");
        String tool = toolDialog.open();
        if (tool == null) return;
        int token = begin("Resolving captured addresses in user-confirmed ELF…");
        var captured = report;
        String base = provenance + "\n" + captured.display();
        job = new Job("Resolve captured Cortex-M addresses") {
            @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                try {
                    var image = ArmElfSymbols.inspect(Path.of(elf));
                    var resolved = new ArrayList<ArmElfSymbols.Location>();
                    var text = new StringBuilder(base).append("\nELF MATCH: USER-CONFIRMED; not independently verified from capture.\nELF: ")
                            .append(image.path()).append("\nELF SHA-256: ").append(image.sha256()).append("\nLookup tool: ").append(tool).append('\n');
                    for (var register : addresses) {
                        if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                        try {
                            var location = ArmElfSymbols.resolve(image, Path.of(tool), register, monitor::isCanceled);
                            resolved.add(location);
                            text.append('\n').append(location.display()).append('\n');
                        } catch (java.io.IOException error) {
                            text.append('\n').append(register.name()).append(" lookup unavailable: ").append(error.getMessage()).append('\n');
                        }
                    }
                    if (!monitor.isCanceled()) complete(token, () -> {
                        reportText.setText(text.toString());
                        locations.removeAll();
                        for (var location : resolved) {
                            var item = new TableItem(locations, SWT.NONE);
                            item.setText(new String[] {location.register(), location.function(), location.raw()});
                            item.setData(location);
                        }
                        status.setText("Address lookup complete; ELF identity is user-confirmed. Double-click a local source location to open it.");
                    });
                } catch (Exception error) { fail(token, error); }
                return monitor.isCanceled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }
    private void revealSource() {
        if (locations.getSelectionCount() == 0) return;
        var location = (ArmElfSymbols.Location) locations.getSelection()[0].getData();
        try {
            if (location.source().isBlank() || location.line() <= 0) throw new java.io.IOException("No source line was resolved. The ELF may lack debug information.");
            Path source = Path.of(location.source());
            if (!source.isAbsolute() || !Files.isRegularFile(source)) throw new java.io.IOException(
                    "The recorded source path is not an existing absolute local file:\n" + location.raw() + "\nNo alternative path was guessed.");
            var files = ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(source.toUri());
            if (files.length > 0 && files[0].isAccessible()) {
                IMarker marker = files[0].createMarker(IMarker.TEXT);
                try {
                    marker.setAttribute(IMarker.LINE_NUMBER, location.line());
                    IDE.openEditor(getSite().getPage(), marker);
                } finally { marker.delete(); }
            } else {
                IDE.openEditor(getSite().getPage(), source.toUri(), "org.eclipse.ui.DefaultTextEditor", true);
                status.setText("Opened external source; recorded line " + location.line() + ". Source content has not been matched to the captured build.");
            }
        } catch (Exception error) { MessageDialog.openInformation(getSite().getShell(), "Open captured source", error.getMessage()); }
    }
    private void exportReport() {
        if (report == null) { MessageDialog.openInformation(getSite().getShell(), "Export investigation", "Import and analyse a fatal log first."); return; }
        var dialog = new FileDialog(getSite().getShell(), SWT.SAVE);
        dialog.setText("Export fatal-log investigation report");
        dialog.setFileName("zephyr-fault-investigation.txt");
        dialog.setOverwrite(true);
        String file = dialog.open();
        if (file == null) return;
        try {
            Files.writeString(Path.of(file), reportText.getText(), StandardCharsets.UTF_8);
            status.setText("Report exported to " + file + ". Review paths and captured values before sharing.");
        } catch (Exception error) { MessageDialog.openError(getSite().getShell(), "Export investigation", error.getMessage()); }
    }
    private int begin(String message) {
        if (job != null) job.cancel();
        int token = generation.incrementAndGet();
        status.setText(message);
        return token;
    }
    private void complete(int token, Runnable action) {
        if (ui.isDisposed()) return;
        ui.asyncExec(() -> { if (!reportText.isDisposed() && generation.get() == token) action.run(); });
    }
    private void fail(int token, Exception error) {
        complete(token, () -> status.setText("Investigation could not finish: " + error.getMessage()));
    }
    @Override public void setFocus() { reportText.setFocus(); }
    @Override public void dispose() { generation.incrementAndGet(); if (job != null) job.cancel(); super.dispose(); }
}
