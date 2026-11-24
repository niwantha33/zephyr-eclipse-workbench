// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Path;
import java.util.List;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;
import io.github.niwantha33.zew.core.QuickHelpCatalog;
import io.github.niwantha33.zew.core.ZephyrReferenceCatalog;

/** Read-only offline Quick Help: local docs only; browser opens on explicit click. */
public final class QuickHelpView extends ViewPart {
    public static final String ID = Activator.ID + ".quickHelp";

    private Text search;
    private Text details;
    private Label projectLabel;
    private Label resultsLabel;
    private Table list;
    private Button copy;
    private Button documentation;
    private volatile QuickHelpCatalog catalogue;
    private Display uiDisplay;
    private QuickHelpCatalog.Hit selected;
    private IProject project;
    private String projectVersion = "";
    private long requestNumber;
    private Job loadingJob;
    private Job searchJob;
    private AutoCloseable contextSubscription;

    @Override public void createPartControl(Composite parent) {
        uiDisplay = parent.getDisplay();
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 10;
        layout.marginHeight = 8;
        parent.setLayout(layout);

        Label heading = new Label(parent, SWT.NONE);
        heading.setText("QUICK HELP  /  OFFLINE ZEPHYR API GUIDE");
        search = new Text(parent, SWT.SEARCH | SWT.ICON_SEARCH | SWT.CANCEL);
        search.setMessage("Try: create_task, blink LED, wait for semaphore, k_msgq_put...");
        search.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        search.addModifyListener(event -> requestSearch());

        Composite contextRow = new Composite(parent, SWT.NONE);
        contextRow.setLayout(new GridLayout(2, false));
        contextRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        projectLabel = new Label(contextRow, SWT.WRAP);
        projectLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button refresh = new Button(contextRow, SWT.PUSH);
        refresh.setText("Refresh project");
        refresh.addListener(SWT.Selection, event -> detectActiveProject());

        SashForm sash = new SashForm(parent, SWT.HORIZONTAL | SWT.SMOOTH);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        Composite left = new Composite(sash, SWT.NONE);
        left.setLayout(new GridLayout(1, false));
        resultsLabel = new Label(left, SWT.NONE);
        resultsLabel.setText("Loading bundled catalogue...");
        list = new Table(left, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
        list.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        list.addListener(SWT.Selection, event -> updateSelection());

        Composite right = new Composite(sash, SWT.NONE);
        right.setLayout(new GridLayout(1, false));
        details = new Text(right, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
        details.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        details.setText("Search for an intention or a real Zephyr API.\n"
                + "No internet, account or subscription is needed.");
        Composite actions = new Composite(right, SWT.NONE);
        actions.setLayout(new GridLayout(2, false));
        actions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        copy = new Button(actions, SWT.PUSH);
        copy.setText("Copy Example");
        copy.setEnabled(false);
        copy.addListener(SWT.Selection, event -> copyExample());
        documentation = new Button(actions, SWT.PUSH);
        documentation.setText("Open Documentation");
        documentation.setEnabled(false);
        documentation.addListener(SWT.Selection, event -> openDocumentation());
        sash.setWeights(new int[] {35, 65});

        detectActiveProject();
        contextSubscription = ZephyrViewContextHub.forWindow(getSite().getWorkbenchWindow())
                .subscribe(context -> {
                    if (project != context.project()) displayProject(context.project());
                });
        loadingJob = new Job("Load Zephyr Quick Help catalogue") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                try {
                    QuickHelpCatalog loaded = QuickHelpCatalog.load();
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    if (!uiDisplay.isDisposed()) uiDisplay.asyncExec(() -> {
                        if (list.isDisposed()) return;
                        catalogue = loaded;
                        requestSearch();
                    });
                } catch (Exception error) {
                    if (!uiDisplay.isDisposed()) uiDisplay.asyncExec(() -> {
                        if (!list.isDisposed()) {
                            resultsLabel.setText("Catalogue unavailable");
                            details.setText("Quick Help cannot load its offline documentation: "
                                    + error.getMessage() + "\nCheck installed bundle resources.");
                        }
                    });
                }
                return Status.OK_STATUS;
            }
        };
        loadingJob.setSystem(true);
        loadingJob.schedule();
    }

    private void detectActiveProject() {
        var page = getSite().getWorkbenchWindow().getActivePage();
        IProject selectedProject = page == null || page.getActiveEditor() == null ? null
                : SelectedProject.importedProject(page.getActiveEditor().getEditorInput());
        displayProject(selectedProject);
    }

    public void displayProject(IProject candidate) {
        project = candidate != null && candidate.isAccessible() ? candidate : null;
        projectVersion = "";
        if (project != null) {
            try {
                String workspace = ProjectContext.settings(project).getProperty("workspace", "");
                if (!workspace.isBlank())
                    projectVersion = ZephyrReferenceCatalog.releaseFromWorkspace(Path.of(workspace));
            } catch (Exception ignored) {
                // An inaccessible project/environment must remain UNKNOWN, never silently assumed.
            }
        }
        if (projectLabel != null && !projectLabel.isDisposed()) {
            projectLabel.setText(project == null
                    ? "Project: not selected | Zephyr version: UNKNOWN"
                    : "Project: " + project.getName() + " | Zephyr version: "
                    + (projectVersion.isEmpty() ? "UNKNOWN" : projectVersion));
            projectLabel.getParent().layout(true, true);
            updateSelection();
        }
    }

    public void searchFor(String term, IProject selectedProject) {
        if (selectedProject != null) displayProject(selectedProject);
        if (search != null && !search.isDisposed()) {
            search.setText(term == null ? "" : term.strip());
            search.setFocus();
        }
    }

    private void requestSearch() {
        if (search == null || search.isDisposed() || list == null || list.isDisposed()) return;
        long serial = ++requestNumber;
        if (searchJob != null) searchJob.cancel();
        QuickHelpCatalog current = catalogue;
        if (current == null) return;
        String term = search.getText();
        if (term.length() > 128) {
            resultsLabel.setText("Search must be 128 characters or less");
            return;
        }
        searchJob = new Job("Search offline Zephyr APIs") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                List<QuickHelpCatalog.Hit> hits = current.search(term, 40);
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                if (!uiDisplay.isDisposed()) uiDisplay.asyncExec(() -> {
                    if (!list.isDisposed() && serial == requestNumber)
                        displayResults(term, hits);
                });
                return Status.OK_STATUS;
            }
        };
        searchJob.setSystem(true);
        searchJob.schedule(120L); // debounce typing and avoid indexing on UI thread
    }

    private void displayResults(String term, List<QuickHelpCatalog.Hit> hits) {
        list.setRedraw(false);
        try {
            list.removeAll();
            for (QuickHelpCatalog.Hit hit : hits) {
                TableItem row = new TableItem(list, SWT.NONE);
                row.setText(hit.entry().id() + "   |   " + hit.entry().category());
                row.setData(hit);
            }
            if (!hits.isEmpty()) list.setSelection(0);
        } finally {
            list.setRedraw(true);
        }
        resultsLabel.setText(term.isBlank() ? "Search an API or describe what you want to do"
                : hits.isEmpty() ? "No offline match. Try another phrase or a shorter API name."
                : hits.size() + " offline result(s)  •  Zephyr 4.4.0 snapshot");
        updateSelection();
    }

    private void updateSelection() {
        selected = null;
        if (list == null || list.isDisposed()) return;
        if (list.getSelectionCount() == 1 && list.getSelection()[0].getData()
                instanceof QuickHelpCatalog.Hit hit) selected = hit;
        if (copy != null && !copy.isDisposed()) copy.setEnabled(selected != null);
        if (documentation != null && !documentation.isDisposed()) documentation.setEnabled(selected != null);
        if (details == null || details.isDisposed()) return;
        if (selected == null) {
            if (catalogue != null) details.setText("Search for an intention (such as create_task) "
                    + "or an API (such as k_thread_create).\n\n"
                    + "No matching record? The offline catalogue is intentionally limited. "
                    + "Use Documentation & References for broader navigation.");
            return;
        }
        QuickHelpCatalog.Entry entry = selected.entry();
        String applicability = switch (QuickHelpCatalog.versionState(entry, projectVersion)) {
            case MATCH -> "MATCH: project release matches the pinned reference version.";
            case MISMATCH -> "VERSION MISMATCH: project " + projectVersion
                    + ", reference " + entry.version() + ". Verify against this project's headers before use.";
            case UNKNOWN -> "UNKNOWN PROJECT VERSION: compatibility has NOT been established.";
        };
        String match = selected.intention()
                ? "Search intention: \"" + selected.matchedTerm() + "\" (NOT a Zephyr API name)\n"
                : "Matched actual Zephyr API name: " + entry.id() + "\n";
        String note = selected.spellingSuggestion() ? "Possible spelling/wording match; review carefully.\n" : "";
        details.setText(entry.title() + "\n\n" + match + note
                + "ACTUAL ZEPHYR API: " + entry.id() + "\n"
                + "Upstream reference: Zephyr " + entry.version() + "\n"
                + applicability + "\n"
                + "Example status: authored for this reference; build/board NOT independently verified.\n\n"
                + "WHAT IT DOES\n" + entry.explanation() + "\n\n"
                + "SIGNATURE\n" + entry.signature() + "\n\n"
                + "PARAMETERS\n" + entry.parameters() + "\n\n"
                + "COMPLETE EXAMPLE (main.c)\n" + entry.example() + "\n\n"
                + "REQUIREMENTS & ASSUMPTIONS\n" + entry.requirements() + "\n\n"
                + "COMMON MISTAKES / ISR RULES\n" + entry.mistakes() + "\n\n"
                + "RELATED APIS\n" + String.join(", ", entry.related()) + "\n\n"
                + "OFFICIAL SOURCE\n" + entry.source() + "\n"
                + "Attribution: Zephyr Project, Apache-2.0 upstream headers. "
                + "Descriptions and examples are original Workbench content.");
        details.setSelection(0);
    }

    private void copyExample() {
        if (selected == null) return;
        Clipboard clipboard = new Clipboard(uiDisplay);
        try {
            clipboard.setContents(new Object[] {selected.entry().example()},
                    new Transfer[] {TextTransfer.getInstance()});
            resultsLabel.setText("Example copied. Review prerequisites before building.");
        } finally {
            clipboard.dispose();
        }
    }

    private void openDocumentation() {
        if (selected != null && !Program.launch(selected.entry().source()))
            resultsLabel.setText("Unable to open browser. Source URL is shown in the details panel.");
    }

    @Override public void setFocus() {
        if (search != null && !search.isDisposed()) search.setFocus();
    }

    @Override public void dispose() {
        ++requestNumber;
        if (loadingJob != null) loadingJob.cancel();
        if (searchJob != null) searchJob.cancel();
        if (contextSubscription != null) {
            try { contextSubscription.close(); } catch (Exception ignored) {}
            contextSubscription = null;
        }
        super.dispose();
    }
}
