// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.viewers.IStructuredSelection;
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
 * PR1 / Step 4: evidence-labelled, read-only SWT memory map.
 * Auto-follows verified builds from the existing native CDT/West builder.
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
    private IProject followedProject;
    private Path followedBuild;
    private boolean manualImport;

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
        controls.setLayout(new GridLayout(3, false));
        button(controls, "Follow selected project builds", this::followSelectedProject);
        button(controls, "Open memory report JSON…", this::chooseReport);
        button(controls, "Reload selected report", this::reload);

        status = new Label(parent, SWT.WRAP);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        status.setText("Select a Zephyr project to follow successful builds, or import a saved JSON report.");

        var legend = new Label(parent, SWT.WRAP);
        legend.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        legend.setText("Blue: Flash hint · Green: RAM hint · Cyan: section VMA · Yellow: explicit section LMA · "
                + "Purple: imported MPU range · Red: overlapping MPU ranges (not necessarily a fault; captured data only)");

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
        var selected = getSite().getWorkbenchWindow().getSelectionService().getSelection();
        if (selected instanceof IStructuredSelection items && items.size() == 1) {
            IProject p = SelectedProject.importedProject(items.getFirstElement());
            if (p != null) followProject(p);
        }
    }

    /** Refresh only this view's selected project; manual imports never auto-switch. */
    public void displayBuiltReport(IProject project, Path build, Path report) {
        if (manualImport || project == null || !project.isAccessible()
                || (followedProject != null && !followedProject.equals(project)))
            return;
        followedProject = project;
        followedBuild = build.toAbsolutePath().normalize();
        selectedReport = report.toAbsolutePath().normalize();
        setPartName("Memory Profiler — " + project.getName());
        reload();
    }

    private void followSelectedProject() {
        var selected = getSite().getWorkbenchWindow().getSelectionService().getSelection();
        IProject project = selected instanceof IStructuredSelection items && items.size() == 1
                ? SelectedProject.importedProject(items.getFirstElement()) : null;
        if (project == null) {
            status.setText("Select an imported Zephyr project in Project Explorer, then click Follow selected project builds.");
            return;
        }
        followProject(project);
    }

    private void followProject(IProject project) {
        try {
            var settings = ProjectContext.settings(project);
            Path build = ProjectContext.buildDirectory(project, settings);
            manualImport = false;
            followedProject = project;
            followedBuild = build.toAbsolutePath().normalize();
            selectedReport = MemoryProfilerReportGuard.output(build);
            setPartName("Memory Profiler — " + project.getName());
            reload();
        } catch (Exception ex) {
            status.setText("Cannot follow selected project: " + ex.getMessage());
        }
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
            manualImport = true;
            followedProject = null;
            followedBuild = null;
            selectedReport = Path.of(chosen);
            setPartName("Memory Profiler — imported report");
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
        Path build = manualImport ? null : followedBuild;
        String projectName = followedProject == null ? null : followedProject.getName();
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
                    if (build != null) {
                        Path map = MemoryProfilerReportGuard.map(build);
                        String mapSha = MemoryProfilerReportGuard.hashMap(map);
                        MemoryProfilerReportGuard.verifyReport(json, mapSha);
                    }
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    String mpuNote = diagram.hasMpuSnapshot()
                            ? "Imported debugger snapshot (not independently authenticated; not live)."
                            : "MPU unknown: no hardware readback in linker map.";
                    String identity = build == null
                            ? "Explicit imported report; build freshness unverified."
                            : "Project " + projectName + " | build " + build
                              + " | checked against current map SHA-256 (subsequent changes not monitored).";
                    show(ticket, diagram, "Loaded " + diagram.regions().size() + " linker regions, "
                            + diagram.sections().size() + " section headers. "
                            + identity + " " + mpuNote);
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
            detail.setText(diagram == null ? "No valid layout available." :
                    "Select a region for exact addresses. MPU status: " + diagram.mpuStatus()
                    + "\nSaved linker-map evidence; no target or ELF validation performed.");
            status.getParent().layout(true, true);
        });
    }

    private void showDetails(Region region) {
        if (displayed == null || detail == null || detail.isDisposed()) return;
        int selectedRow = canvas.selectedIndex();
        if (selectedRow >= 0) {
            int top = MemoryProfilerCanvas.HEADER_HEIGHT + selectedRow * MemoryProfilerCanvas.ROW_HEIGHT;
            int bottom = top + MemoryProfilerCanvas.ROW_HEIGHT;
            var origin = scroll.getOrigin();
            int height = scroll.getClientArea().height;
            if (top < origin.y) scroll.setOrigin(origin.x, top);
            else if (height > 0 && bottom > origin.y + height)
                scroll.setOrigin(origin.x, bottom - height);
        }
        int vma = 0, lma = 0, capturedMpu = 0;
        for (var section : displayed.sections()) {
            if (region.address().intersect(section.vma()) != null) vma++;
            if (section.lma() != null && region.address().intersect(section.lma()) != null) lma++;
        }
        for (var mpu : displayed.mpu())
            if (region.address().intersect(mpu.address()) != null) capturedMpu++;
        int overlaps = displayed.overlaps(region).size();
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
