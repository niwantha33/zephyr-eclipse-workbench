// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;
import io.github.niwantha33.zew.ui.MemoryProfilerLayout.Diagram;
import io.github.niwantha33.zew.ui.MemoryProfilerLayout.Region;

/**
 * PR1 / Step 3: evidence-labelled, read-only SWT linker memory diagram.
 * Import is explicit: no project binding, build listener, or runtime debug calls.
 */
public final class MemoryProfilerView extends ViewPart {
    public static final String ID = Activator.ID + ".memoryProfiler";
    private final AtomicInteger generation = new AtomicInteger();
    private ScrolledComposite scroll;
    private MemoryProfilerCanvas canvas;
    private Label status;
    private Text detail;
    private Job loadJob;
    private Path selectedReport;
    private Diagram displayed;

    @Override public void createPartControl(Composite parent) {
        var layout = new GridLayout(1, false);
        layout.marginWidth = 12;
        layout.marginHeight = 12;
        parent.setLayout(layout);

        var heading = new Label(parent, SWT.WRAP);
        heading.setText("LINKER MEMORY / FLASH & RAM / IMPORTED MPU EVIDENCE");
        heading.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        var controls = new Composite(parent, SWT.NONE);
        controls.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        controls.setLayout(new GridLayout(2, false));
        button(controls, "Open memory report JSON…", this::chooseReport);
        button(controls, "Reload selected report", this::reload);

        status = new Label(parent, SWT.WRAP);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        status.setText("No report selected. Generate a saved JSON report using scripts/memory_profiler.py first.");

        var legend = new Label(parent, SWT.WRAP);
        legend.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        legend.setText("Blue: Flash hint · Green: RAM hint · Cyan: section VMA · Yellow: explicit section LMA · "
                + "Purple: imported MPU range · Red: overlapping MPU ranges (only when captured)");

        scroll = new ScrolledComposite(parent, SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL);
        scroll.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        scroll.setExpandHorizontal(false);
        scroll.setExpandVertical(false);
        canvas = new MemoryProfilerCanvas(scroll);
        scroll.setContent(canvas);
        canvas.onSelection(this::showDetails);
        canvas.setDiagram(null);
        scroll.setMinSize(canvas.getSize());

        detail = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        GridData details = new GridData(SWT.FILL, SWT.BOTTOM, true, false);
        details.heightHint = 115;
        detail.setLayoutData(details);
        detail.setText("Select a region using the mouse or arrow keys to inspect exact half-open addresses.\n"
                + "Section placements are linker observations; this is not a utilisation measurement.");
    }

    private static Button button(Composite parent, String caption, Runnable action) {
        var button = new Button(parent, SWT.PUSH);
        button.setText(caption);
        button.addListener(SWT.Selection, event -> action.run());
        return button;
    }

    private void chooseReport() {
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.OPEN);
        dialog.setText("Open Zephyr memory-profiler JSON");
        dialog.setFilterExtensions(new String[] {"*.json", "*.*"});
        String chosen = dialog.open();
        if (chosen != null) {
            selectedReport = Path.of(chosen);
            reload();
        }
    }

    private void reload() {
        if (selectedReport == null) {
            status.setText("Choose a JSON report first.");
            return;
        }
        int ticket = generation.incrementAndGet();
        if (loadJob != null) loadJob.cancel();
        canvas.setDiagram(null);
        displayed = null;
        scroll.setMinSize(canvas.getSize());
        status.setText("Reading saved linker-map evidence; this is not the active build or live device.");
        detail.setText("No memory range selected.");
        Path path = selectedReport;
        loadJob = new Job("Read Zephyr memory profiler JSON") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                try {
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            || Files.size(path) > MemoryProfilerJson.MAX_CHARACTERS)
                        throw new IOException("Expected a regular JSON report of 8 MiB or less");
                    String json = Files.readString(path);
                    List<MemoryProfilerJson.Row> fields = MemoryProfilerJson.parse(json);
                    Diagram diagram = MemoryProfilerLayout.fromRows(fields);
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    String mpuNote = diagram.hasMpuSnapshot()
                            ? "Imported debugger snapshot (not independently authenticated; not live)."
                            : "MPU unknown: no hardware readback in linker map.";
                    show(ticket, diagram, "Imported " + diagram.regions().size() + " linker memory regions, "
                            + diagram.sections().size() + " section headers from " + path.getFileName()
                            + ". " + mpuNote + " Build freshness unverified.");
                } catch (IOException | IllegalArgumentException ex) {
                    show(ticket, null, "Cannot load memory report: " + ex.getMessage());
                }
                return Status.OK_STATUS;
            }
        };
        loadJob.setSystem(true);
        loadJob.schedule();
    }

    private void show(int ticket, Diagram diagram, String message) {
        Display.getDefault().asyncExec(() -> {
            if (generation.get() != ticket || canvas == null || canvas.isDisposed()) return;
            displayed = diagram;
            canvas.setDiagram(diagram);
            scroll.setMinSize(canvas.getSize());
            status.setText(message);
            detail.setText(diagram == null ? "No verified layout available." :
                    "Select a region for exact addresses. MPU status: " + diagram.mpuStatus()
                    + "\nSaved linker-map evidence; no target or ELF validation performed.");
            status.getParent().layout(true, true);
        });
    }

    private void showDetails(Region region) {
        if (displayed == null || detail == null || detail.isDisposed()) return;
        int vma = 0, lma = 0, capturedMpu = 0;
        for (var section : displayed.sections()) {
            if (region.address().intersect(section.vma()) != null) vma++;
            if (section.lma() != null && region.address().intersect(section.lma()) != null) lma++;
        }
        for (var mpu : displayed.mpu())
            if (region.address().intersect(mpu.address()) != null) capturedMpu++;
        int overlaps = displayed.conflicts(region).size();
        String note = displayed.hasMpuSnapshot()
                ? "Imported MPU capture intersects this region: " + capturedMpu
                    + "; pairwise overlap intervals: " + overlaps
                    + ". Capture provenance is not hardware authentication."
                : "MPU unknown: linker map has no programmed MPU registers.";
        detail.setText(region.name() + "  [" + MemoryProfilerLayout.hex(region.address().start()) + ", "
                + MemoryProfilerLayout.hex(region.address().end()) + ") — " + region.size() + " bytes\n"
                + "Kind: " + region.kind() + " (" + region.basis() + "); linker attributes: "
                + region.attributes() + " (NOT MPU permissions)\n"
                + "Intersecting sections: " + vma + " VMA, " + lma + " explicit LMA. " + note);
    }

    @Override public void setFocus() {
        if (canvas != null && !canvas.isDisposed()) canvas.setFocus();
    }

    @Override public void dispose() {
        generation.incrementAndGet();
        if (loadJob != null) loadJob.cancel();
        super.dispose();
    }
}
