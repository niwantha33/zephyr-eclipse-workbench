// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;
import io.github.niwantha33.zew.core.ZephyrReferenceCatalog;

/** Read-only links to official upstream resources, with exact-project context. */
public final class ZephyrDocumentationView extends ViewPart {
    public static final String ID = Activator.ID + ".documentation";
    private Label context;
    private Label details;
    private Table table;
    private Text filter;
    private Button open;
    private IProject project;
    private List<ZephyrReferenceCatalog.Link> links = List.of();
    private AutoCloseable contextSubscription;

    @Override public void createPartControl(Composite parent) {
        var layout = new GridLayout(1, false);
        layout.marginWidth = 12;
        layout.marginHeight = 10;
        parent.setLayout(layout);

        var heading = new Label(parent, SWT.NONE);
        heading.setText("ZEPHYR / DOCUMENTATION & REFERENCES");
        context = new Label(parent, SWT.WRAP);
        context.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        var description = new Label(parent, SWT.WRAP);
        description.setText("Official external references only. A link does not prove hardware support, "
                + "compatibility, active configuration, or register correctness.");
        description.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        var controls = new Composite(parent, SWT.NONE);
        controls.setLayout(new GridLayout(2, false));
        controls.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        filter = new Text(controls, SWT.SEARCH | SWT.ICON_SEARCH | SWT.CANCEL);
        filter.setMessage("Filter Zephyr APIs, TRMs, boards, Devicetree...");
        filter.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        filter.addModifyListener(event -> refreshRows());
        var refresh = new Button(controls, SWT.PUSH);
        refresh.setText("Use selected project");
        refresh.addListener(SWT.Selection, event -> {
            IProject selected = selectedProject();
            if (selected != null) displayProject(selected);
            else showNoProject();
        });

        table = new Table(parent, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION
                | SWT.V_SCROLL | SWT.H_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        String[] titles = {"Reference", "Source", "Applicability"};
        int[] widths = {330, 110, 420};
        for (int i = 0; i < titles.length; i++) {
            var column = new TableColumn(table, SWT.LEFT);
            column.setText(titles[i]);
            column.setWidth(widths[i]);
        }
        table.addListener(SWT.Selection, event -> updateSelection());
        table.addListener(SWT.DefaultSelection, event -> openSelected());

        details = new Label(parent, SWT.WRAP);
        details.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        var actions = new Composite(parent, SWT.NONE);
        actions.setLayout(new GridLayout(1, false));
        actions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        open = new Button(actions, SWT.PUSH);
        open.setText("Open selected official link in browser");
        open.setEnabled(false);
        open.addListener(SWT.Selection, event -> openSelected());

        showNoProject();
        contextSubscription = ZephyrViewContextHub.forWindow(getSite().getWorkbenchWindow())
                .subscribe(selection -> {
                    if (selection.project() != project) displayProject(selection.project());
                });
    }

    public void displayProject(IProject selected) {
        if (!ZephyrProjectLifecycle.canInspect(selected)) {
            showNoProject();
            return;
        }
        project = selected;
        try {
            var settings = ProjectContext.settings(selected);
            String board = settings.getProperty("board", "");
            String version = ZephyrReferenceCatalog.releaseFromWorkspace(
                    Path.of(settings.getProperty("workspace")));
            links = ZephyrReferenceCatalog.forTarget(board, version);
            context.setText("Selected project: " + selected.getName() + " | Target: " + board
                    + " | Zephyr documentation: " + (version.isEmpty()
                            ? "latest development version (checkout release unconfirmed)"
                            : version + " (inferred from local VERSION file)"));
        } catch (Exception unavailable) {
            links = ZephyrReferenceCatalog.forTarget("", "");
            context.setText("Project: " + selected.getName()
                    + " | Exact target / checkout unavailable: " + unavailable.getMessage()
                    + ". Showing generic latest references only.");
        }
        refreshRows();
        context.getParent().layout(true, true);
    }

    private IProject selectedProject() {
        var window = getSite().getWorkbenchWindow();
        if (window != null && window.getSelectionService().getSelection() instanceof IStructuredSelection selection
                && selection.size() == 1) {
            var candidate = SelectedProject.importedProject(selection.getFirstElement());
            if (candidate != null) return candidate;
        }
        return project;
    }

    private void showNoProject() {
        project = null;
        if (context == null || context.isDisposed()) return;
        context.setText("No exact Zephyr project selected. Showing latest development documentation only. "
                + "Select an imported project for board-specific references.");
        links = ZephyrReferenceCatalog.forTarget("", "");
        refreshRows();
    }

    private void refreshRows() {
        if (table == null || table.isDisposed()) return;
        String query = filter == null || filter.isDisposed() ? "" :
                filter.getText().strip().toLowerCase(Locale.ROOT);
        table.setRedraw(false);
        try {
            table.removeAll();
            for (var link : links) {
                if (!query.isEmpty() && !(link.title() + " " + link.category() + " "
                        + link.qualification()).toLowerCase(Locale.ROOT).contains(query)) continue;
                var row = new TableItem(table, SWT.NONE);
                row.setText(new String[] {link.title(), link.category(), link.qualification()});
                row.setData(link);
            }
            if (table.getItemCount() > 0) table.setSelection(0);
            updateSelection();
        } finally {
            table.setRedraw(true);
        }
    }

    private void updateSelection() {
        var link = selectedLink();
        if (details != null && !details.isDisposed())
            details.setText(link == null ? "No matching references." : link.url());
        if (open != null && !open.isDisposed()) open.setEnabled(link != null);
    }

    private ZephyrReferenceCatalog.Link selectedLink() {
        if (table == null || table.isDisposed() || table.getSelectionCount() != 1) return null;
        Object data = table.getSelection()[0].getData();
        return data instanceof ZephyrReferenceCatalog.Link link ? link : null;
    }

    private void openSelected() {
        var link = selectedLink();
        if (link == null) return;
        if (!Program.launch(link.url()))
            details.setText("Browser could not open the official link. Copy URL: " + link.url());
    }

    @Override public void setFocus() {
        if (filter != null && !filter.isDisposed()) filter.setFocus();
    }

    @Override public void dispose() {
        if (contextSubscription != null) {
            try { contextSubscription.close(); } catch (Exception ignored) {}
            contextSubscription = null;
        }
        super.dispose();
    }
}
