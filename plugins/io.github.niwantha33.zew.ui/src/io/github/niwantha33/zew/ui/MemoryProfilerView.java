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
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.part.ViewPart;

/**
 * PR1 / Step2: the raw, read-only JSON tabular view.
 *
 * No build triggering, map parsing in Java, MPU measurements, or graphical
 * memory diagram. Users explicitly open a Step-1 JSON report; this view does
 * not claim it matches the active Eclipse project or a flashed target.
 */
public final class MemoryProfilerView extends ViewPart {
    public static final String ID = Activator.ID + ".memoryProfiler";
    private final AtomicInteger generation = new AtomicInteger();
    private TableViewer table;
    private Label status;
    private Job loadJob;
    private Path selectedReport;

    @Override
    public void createPartControl(Composite parent) {
        var layout = new GridLayout(1, false);
        layout.marginWidth = 12;
        layout.marginHeight = 12;
        parent.setLayout(layout);

        var heading = new Label(parent, SWT.WRAP);
        heading.setText("LINKER MAP  /  SAVED JSON EVIDENCE (NOT LIVE MEMORY)");
        heading.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        var controls = new Composite(parent, SWT.NONE);
        controls.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        controls.setLayout(new GridLayout(2, false));
        button(controls, "Open memory report JSON…", this::chooseReport);
        button(controls, "Reload selected report", this::reload);

        status = new Label(parent, SWT.WRAP);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        status.setText("No report selected. Run scripts/memory_profiler.py --map ... --output ... first.");

        table = new TableViewer(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.H_SCROLL
                | SWT.V_SCROLL | SWT.VIRTUAL);
        table.getTable().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        table.getTable().setHeaderVisible(true);
        table.getTable().setLinesVisible(true);
        table.setContentProvider(ArrayContentProvider.getInstance());
        column("JSON field", 350, true);
        column("Raw JSON value", 480, false);
        table.setInput(List.of());
    }

    private void column(String name, int width, boolean key) {
        var col = new TableViewerColumn(table, SWT.LEFT);
        col.getColumn().setText(name);
        col.getColumn().setWidth(width);
        col.setLabelProvider(new ColumnLabelProvider() {
            @Override public String getText(Object element) {
                if (!(element instanceof MemoryProfilerJson.Row row)) return "";
                return key ? row.field() : row.rawValue();
            }
        });
    }

    private static Button button(Composite parent, String label, Runnable action) {
        var result = new Button(parent, SWT.PUSH);
        result.setText(label);
        result.addListener(SWT.Selection, event -> action.run());
        return result;
    }

    private void chooseReport() {
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.OPEN);
        dialog.setText("Open Zephyr memory-profiler JSON");
        dialog.setFilterExtensions(new String[] {"*.json", "*.*"});
        String selected = dialog.open();
        if (selected != null) {
            selectedReport = Path.of(selected);
            reload();
        }
    }

    private void reload() {
        if (selectedReport == null) {
            status.setText("Select a JSON report before reloading.");
            return;
        }
        int ticket = generation.incrementAndGet();
        if (loadJob != null) loadJob.cancel();
        table.setInput(List.of());
        status.setText("Reading a saved report; its build freshness has not been verified.");
        Path path = selectedReport;
        loadJob = new Job("Read Zephyr memory report JSON") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                try {
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            || Files.size(path) > MemoryProfilerJson.MAX_CHARACTERS)
                        throw new IOException("Expected a regular JSON file of 8 MiB or less");
                    String json = Files.readString(path);
                    List<MemoryProfilerJson.Row> rows = MemoryProfilerJson.parse(json);
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    show(ticket, rows, "Loaded " + rows.size() + " JSON values from "
                            + path.getFileName()
                            + ". Saved linker-map evidence; MPU state and build freshness unverified.");
                } catch (IOException | IllegalArgumentException ex) {
                    show(ticket, List.of(), "Cannot load memory report: " + ex.getMessage());
                }
                return Status.OK_STATUS;
            }
        };
        loadJob.setSystem(true);
        loadJob.schedule();
    }

    private void show(int ticket, List<MemoryProfilerJson.Row> rows, String message) {
        Display.getDefault().asyncExec(() -> {
            if (generation.get() != ticket || table == null || table.getControl().isDisposed())
                return;
            table.setInput(rows);
            status.setText(message);
            status.getParent().layout(true, true);
        });
    }

    @Override public void setFocus() {
        if (table != null && !table.getControl().isDisposed()) table.getControl().setFocus();
    }

    @Override public void dispose() {
        generation.incrementAndGet();
        if (loadJob != null) loadJob.cancel();
        super.dispose();
    }
}
