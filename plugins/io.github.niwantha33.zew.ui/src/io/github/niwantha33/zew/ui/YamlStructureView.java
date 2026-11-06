// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.Viewer;
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
 * Step 5: native, read-only structure tree of an imported YAML observation.
 *
 * Intentionally accepts only the JSON produced by Step 3. Selecting and
 * parsing original project YAML files, source navigation and refresh on build
 * changes are the separately approval-gated Step 6. Never presents a parsed
 * YAML document as a validated Zephyr binding, board or test schema.
 */
public final class YamlStructureView extends ViewPart {
    public static final String ID = Activator.ID + ".yamlStructure";
    private static final long MAX_JSON_BYTES = 4 * 1024 * 1024;

    private final AtomicInteger generation = new AtomicInteger();
    private TreeViewer tree;
    private Label status;
    private Job reader;

    @Override
    public void createPartControl(Composite parent) {
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 10;
        layout.marginHeight = 10;
        parent.setLayout(layout);

        var header = new Label(parent, SWT.WRAP);
        header.setText("ZEPHYR YAML STRUCTURE  /  SAVED OBSERVATION  /  READ-ONLY");
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        var button = new Button(parent, SWT.PUSH);
        button.setText("Open YAML observation JSON…");
        button.setToolTipText("Import an existing zew.yaml-observation/1 report. Source YAML selection is not wired yet.");
        button.addListener(SWT.Selection, event -> chooseReport());

        status = new Label(parent, SWT.WRAP);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        status.setText("Import a saved YAML observation report to inspect its structure. "
                + "Open-file integration with .yaml/.yml is planned for the next step.");

        tree = new TreeViewer(parent, SWT.BORDER | SWT.FULL_SELECTION
                | SWT.H_SCROLL | SWT.V_SCROLL | SWT.SINGLE);
        tree.getTree().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        tree.getTree().setHeaderVisible(true);
        tree.getTree().setLinesVisible(true);
        tree.setContentProvider(new ITreeContentProvider() {
            @Override public Object[] getElements(Object input) {
                return input instanceof List<?> items ? items.toArray() : new Object[0];
            }
            @Override public Object[] getChildren(Object parent) {
                return parent instanceof YamlTreeProjection.Row row
                        ? row.children().toArray() : new Object[0];
            }
            @Override public Object getParent(Object element) { return null; }
            @Override public boolean hasChildren(Object element) {
                return element instanceof YamlTreeProjection.Row row
                        && !row.children().isEmpty();
            }
            @Override public void dispose() {}
            @Override public void inputChanged(Viewer viewer, Object previous, Object next) {}
        });
        column("YAML key / index", 270, 0);
        column("Type", 115, 1);
        column("Value preview", 380, 2);
        column("Line:column", 100, 3);
        tree.setInput(List.of());
    }

    private void column(String name, int width, int kind) {
        var column = new TreeViewerColumn(tree, SWT.LEFT);
        column.getColumn().setText(name);
        column.getColumn().setWidth(width);
        column.setLabelProvider(new ColumnLabelProvider() {
            @Override public String getText(Object element) {
                if (!(element instanceof YamlTreeProjection.Row row)) return "";
                return switch (kind) {
                    case 0 -> row.label();
                    case 1 -> row.kind();
                    case 2 -> row.preview();
                    case 3 -> row.location();
                    default -> "";
                };
            }
        });
    }

    private void chooseReport() {
        var dialog = new FileDialog(getSite().getShell(), SWT.OPEN);
        dialog.setText("Open a saved Zephyr YAML observation");
        dialog.setFilterNames(new String[] {"YAML observation JSON (*.json)", "All files (*.*)"});
        dialog.setFilterExtensions(new String[] {"*.json", "*.*"});
        String chosen = dialog.open();
        if (chosen == null) return;

        // Any previous import is stale immediately; discard its delayed UI result.
        int request = generation.incrementAndGet();
        if (reader != null) reader.cancel();
        tree.setInput(List.of());
        status.setText("Loading saved YAML observation…");
        Path report;
        try {
            report = Path.of(chosen);
        } catch (RuntimeException error) {
            status.setText("Cannot open the selected observation path.");
            return;
        }
        reader = new Job("Import saved YAML observation") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                YamlDocumentModel.Snapshot snapshot = null;
                String error = null;
                try {
                    if (!Files.isRegularFile(report) || Files.size(report) > MAX_JSON_BYTES)
                        throw new IOException("Missing or oversized report");
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    snapshot = YamlDocumentModel.parse(Files.readString(report, StandardCharsets.UTF_8));
                } catch (IOException | IllegalArgumentException exception) {
                    // Avoid displaying private YAML text or arbitrary parser/file paths.
                    error = "The selected report is inaccessible, oversized or not a valid YAML observation v1.";
                }
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                YamlDocumentModel.Snapshot loaded = snapshot;
                String failure = error;
                Display.getDefault().asyncExec(() -> {
                    if (request != generation.get() || tree == null
                            || tree.getTree().isDisposed()) return;
                    if (failure != null) {
                        tree.setInput(List.of());
                        status.setText(failure);
                    } else {
                        displayObservation(loaded);
                    }
                });
                return Status.OK_STATUS;
            }
        };
        reader.setSystem(true);
        reader.schedule();
    }

    /**
     * Accept a previously parsed and independently verified observation.
     * Must be called on the SWT UI thread, never on a background Job.
     */
    public void displayObservation(YamlDocumentModel.Snapshot snapshot) {
        if (Display.getCurrent() == null)
            throw new IllegalStateException("YAML view updates require the UI thread");
        if (tree == null || tree.getTree().isDisposed()) return;
        tree.setInput(YamlTreeProjection.rows(snapshot));
        if (snapshot == null) {
            status.setText("No YAML observation has been selected.");
            return;
        }
        String source = snapshot.relativePath() == null ? "(path not verified)"
                : YamlTreeProjection.preview(snapshot.relativePath());
        status.setText("Recorded " + snapshot.status() + " — " + snapshot.kind()
                + " — " + snapshot.reason() + " — " + source
                + ". Syntax observation only; Zephyr schema validity is unverified.");
        if (snapshot.parsed() && snapshot.root() != null)
            tree.expandToLevel(2);
    }

    @Override public void setFocus() {
        if (tree != null && !tree.getTree().isDisposed())
            tree.getTree().setFocus();
    }

    @Override public void dispose() {
        generation.incrementAndGet();
        if (reader != null) reader.cancel();
        super.dispose();
    }
}
