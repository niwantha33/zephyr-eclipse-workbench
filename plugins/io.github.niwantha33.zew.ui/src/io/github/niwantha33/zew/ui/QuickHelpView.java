// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;
import io.github.niwantha33.zew.core.QuickHelpCatalog;
import io.github.niwantha33.zew.core.ZephyrReferenceCatalog;

/** Native, theme-adaptive Zephyr learning panel. Offline and read-only by design. */
public final class QuickHelpView extends ViewPart {
    public static final String ID = Activator.ID + ".quickHelp";
    private static final String[][] STARTERS = {
        {"Create thread", "create_task"}, {"Blink LED", "blink led"},
        {"Start timer", "periodic timer"}, {"Semaphore", "wait for semaphore"},
        {"Protect data", "protect shared variable"}, {"Send message", "message queue"}
    };
    private static final Pattern C_TOKENS = Pattern.compile(
            "//[^\\n]*|/\\*(?s:.*?)\\*/|\"(?:\\\\.|[^\"\\\\])*\"|"
            + "'(?:\\\\.|[^'\\\\])*'|\\b(?:void|int|static|const|return|if|else|while|for|"
            + "struct|sizeof|uint32_t|size_t|bool|true|false|NULL|K_NO_WAIT|K_FOREVER|"
            + "K_MSEC|K_SECONDS|K_THREAD_STACK_DEFINE|K_TIMER_DEFINE|K_MSGQ_DEFINE|"
            + "K_SEM_DEFINE|K_MUTEX_DEFINE|CONFIG_[A-Z0-9_]+)\\b");

    private Text search;
    private Label projectLabel;
    private Label resultsLabel;
    private Label titleLabel;
    private Label apiLabel;
    private Label statusLabel;
    private Label summaryLabel;
    private Label matchLabel;
    private Label learnExplanation;
    private Label learnRequirements;
    private Label learnMistakes;
    private Label referenceParameters;
    private Label referenceRelated;
    private Label referenceSource;
    private Label codeHint;
    private Table results;
    private TableColumn apiColumn;
    private TableColumn taskColumn;
    private TabFolder detailTabs;
    private StyledText example;
    private StyledText signature;
    private Button copyButton;
    private Button docsButton;
    private SashForm panes;
    private ScrolledComposite learnScroll;
    private ScrolledComposite referenceScroll;
    private Composite learnContent;
    private Composite referenceContent;
    private Display display;
    private volatile QuickHelpCatalog catalogue;
    private QuickHelpCatalog.Hit selected;
    private IProject project;
    private String projectVersion = "";
    private long requestNumber;
    private Job loadingJob;
    private Job searchJob;
    private AutoCloseable contextSubscription;
    private final List<Color> ownedColors = new ArrayList<>();
    private final List<Font> ownedFonts = new ArrayList<>();
    private final List<Label> bodyLabels = new ArrayList<>();
    private final List<Label> headingLabels = new ArrayList<>();
    private final List<Label> titleLabels = new ArrayList<>();
    private Font headingFont;
    private Font titleFont;
    private Font bodyFont;
    private Font codeFont;
    private Color accent;
    private Color muted;
    private Color caution;
    private Color positive;
    private Color codeBackground;
    private Color syntaxString;
    private Color syntaxComment;
    private Color syntaxKeyword;
    private int fontScale;

    @Override public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        boolean dark = darkTheme(parent);
        accent = color(dark ? 130 : 35, dark ? 191 : 105, dark ? 255 : 185);
        muted = color(dark ? 186 : 87, dark ? 196 : 101, dark ? 211 : 120);
        caution = color(dark ? 246 : 150, dark ? 194 : 93, dark ? 112 : 24);
        positive = color(dark ? 148 : 39, dark ? 219 : 124, dark ? 172 : 91);
        codeBackground = color(dark ? 32 : 246, dark ? 37 : 248, dark ? 44 : 252);
        syntaxString = color(dark ? 158 : 37, dark ? 211 : 121, dark ? 151 : 75);
        syntaxComment = color(dark ? 154 : 106, dark ? 165 : 114, dark ? 173 : 126);
        syntaxKeyword = accent;
        setReadableFonts();

        GridLayout rootLayout = new GridLayout(1, false);
        rootLayout.marginWidth = 12;
        rootLayout.marginHeight = 10;
        rootLayout.verticalSpacing = 10;
        parent.setLayout(rootLayout);
        Composite header = new Composite(parent, SWT.NONE);
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout headerLayout = new GridLayout(2, false);
        headerLayout.marginWidth = 0;
        headerLayout.marginHeight = 0;
        header.setLayout(headerLayout);
        Label headline = new Label(header, SWT.NONE);
        headline.setText("Zephyr Quick Help");
        headline.setFont(headingFont);
        headingLabels.add(headline);
        headline.setForeground(accent);
        headline.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Label offline = new Label(header, SWT.NONE);
        offline.setText("OFFLINE  •  NO ACCOUNT");
        offline.setForeground(muted);
        Label subtitle = body(header, "Describe a task. Find the real Zephyr API and a useful example.");
        subtitle.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        Label searchCaption = new Label(parent, SWT.NONE);
        searchCaption.setText("SEARCH BY TASK OR API NAME");
        searchCaption.setForeground(muted);
        search = new Text(parent, SWT.SEARCH | SWT.ICON_SEARCH | SWT.CANCEL | SWT.BORDER);
        search.setMessage("e.g. create_task, blink LED, wait for semaphore");
        search.setToolTipText("Search locally; type a plain-English task or an API name");
        search.setFont(bodyFont);
        search.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        search.addModifyListener(event -> requestSearch());

        Composite starterGrid = new Composite(parent, SWT.NONE);
        GridLayout starterLayout = new GridLayout(3, true);
        starterLayout.marginWidth = 0;
        starterLayout.marginHeight = 0;
        starterLayout.horizontalSpacing = 6;
        starterLayout.verticalSpacing = 5;
        starterGrid.setLayout(starterLayout);
        starterGrid.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        for (String[] starter : STARTERS) {
            Button button = new Button(starterGrid, SWT.PUSH);
            button.setText(starter[0]);
            button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            button.setToolTipText("Find: " + starter[1]);
            button.addListener(SWT.Selection, event -> {
                search.setText(starter[1]);
                search.setFocus();
            });
        }

        Composite projectRow = new Composite(parent, SWT.NONE);
        projectRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout projectLayout = new GridLayout(2, false);
        projectLayout.marginWidth = 0;
        projectLayout.marginHeight = 0;
        projectRow.setLayout(projectLayout);
        projectLabel = new Label(projectRow, SWT.WRAP);
        projectLabel.setForeground(muted);
        projectLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button refresh = new Button(projectRow, SWT.PUSH);
        refresh.setText("Refresh");
        refresh.setToolTipText("Refresh the active project's Zephyr version");
        refresh.addListener(SWT.Selection, event -> detectActiveProject());

        panes = new SashForm(parent, SWT.HORIZONTAL | SWT.SMOOTH);
        panes.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        makeResultsPane(panes);
        makeLearningPane(panes);
        panes.setWeights(new int[] {33, 67});
        parent.addControlListener(new ControlAdapter() {
            @Override public void controlResized(ControlEvent event) {
                if (panes.isDisposed()) return;
                boolean narrow = parent.getClientArea().width < 730;
                int newOrientation = narrow ? SWT.VERTICAL : SWT.HORIZONTAL;
                if (panes.getOrientation() != newOrientation) {
                    panes.setOrientation(newOrientation);
                    panes.setWeights(narrow ? new int[] {28, 72} : new int[] {33, 67});
                }
            }
        });
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
                    if (!display.isDisposed()) display.asyncExec(() -> {
                        if (results.isDisposed()) return;
                        catalogue = loaded;
                        requestSearch();
                    });
                } catch (Exception error) {
                    if (!display.isDisposed()) display.asyncExec(() -> {
                        if (results.isDisposed()) return;
                        resultsLabel.setText("Offline catalogue unavailable");
                        summaryLabel.setText("The bundled documentation could not be read: "
                                + error.getMessage());
                    });
                }
                return Status.OK_STATUS;
            }
        };
        loadingJob.setSystem(true);
        loadingJob.schedule();
    }

    private void makeResultsPane(Composite parent) {
        Composite left = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 6;
        layout.marginHeight = 6;
        left.setLayout(layout);
        Label label = new Label(left, SWT.NONE);
        label.setText("DISCOVER APIs");
        label.setFont(titleFont);
        titleLabels.add(label);
        resultsLabel = new Label(left, SWT.WRAP);
        resultsLabel.setText("Loading offline reference...");
        resultsLabel.setForeground(muted);
        resultsLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        results = new Table(left, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
        results.setHeaderVisible(true);
        results.setLinesVisible(false);
        results.setFont(bodyFont);
        results.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        apiColumn = new TableColumn(results, SWT.LEFT);
        apiColumn.setText("Zephyr API");
        taskColumn = new TableColumn(results, SWT.LEFT);
        taskColumn.setText("What it does");
        results.addListener(SWT.Selection, event -> updateSelection());
        results.addControlListener(new ControlAdapter() {
            @Override public void controlResized(ControlEvent event) {
                if (results.isDisposed()) return;
                int width = results.getClientArea().width;
                if (width <= 0) return;
                apiColumn.setWidth(Math.max(135, width * 48 / 100));
                taskColumn.setWidth(Math.max(160, width - apiColumn.getWidth() - 8));
            }
        });
        Label tip = body(left, "Tip: select a result to view a short lesson and complete C reference example.");
        tip.setForeground(muted);
        tip.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    private void makeLearningPane(Composite parent) {
        Composite right = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 8;
        layout.marginHeight = 6;
        layout.verticalSpacing = 8;
        right.setLayout(layout);
        titleLabel = new Label(right, SWT.WRAP);
        titleLabel.setText("Pick an API to start learning");
        titleLabel.setFont(headingFont);
        headingLabels.add(titleLabel);
        titleLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Composite identity = new Composite(right, SWT.NONE);
        identity.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout identityLayout = new GridLayout(2, false);
        identityLayout.marginWidth = 0;
        identityLayout.marginHeight = 0;
        identity.setLayout(identityLayout);
        apiLabel = new Label(identity, SWT.NONE);
        apiLabel.setFont(titleFont);
        titleLabels.add(apiLabel);
        apiLabel.setForeground(accent);
        apiLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        statusLabel = new Label(identity, SWT.NONE);
        statusLabel.setForeground(muted);
        summaryLabel = body(right, "Select one of the quick-start topics or type an API name.");
        summaryLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        matchLabel = new Label(right, SWT.WRAP);
        matchLabel.setForeground(muted);
        matchLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Composite actions = new Composite(right, SWT.NONE);
        GridLayout actionsLayout = new GridLayout(4, false);
        actionsLayout.marginWidth = 0;
        actionsLayout.marginHeight = 0;
        actions.setLayout(actionsLayout);
        actions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        copyButton = new Button(actions, SWT.PUSH);
        copyButton.setText("Copy C example");
        copyButton.setEnabled(false);
        copyButton.addListener(SWT.Selection, event -> copyExample());
        docsButton = new Button(actions, SWT.PUSH);
        docsButton.setText("Official docs");
        docsButton.setEnabled(false);
        docsButton.addListener(SWT.Selection, event -> openDocumentation());
        Button smaller = new Button(actions, SWT.PUSH);
        smaller.setText("A-");
        smaller.setToolTipText("Decrease reading size");
        smaller.addListener(SWT.Selection, event -> resizeText(-1));
        Button larger = new Button(actions, SWT.PUSH);
        larger.setText("A+");
        larger.setToolTipText("Increase reading size");
        larger.addListener(SWT.Selection, event -> resizeText(1));
        detailTabs = new TabFolder(right, SWT.NONE);
        detailTabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        createLearnTab();
        createCodeTab();
        createReferenceTab();
        detailTabs.setSelection(0);
    }

    private void createLearnTab() {
        TabItem tab = new TabItem(detailTabs, SWT.NONE);
        tab.setText("Overview");
        learnScroll = new ScrolledComposite(detailTabs, SWT.V_SCROLL | SWT.H_SCROLL);
        learnScroll.setExpandHorizontal(true);
        learnContent = new Composite(learnScroll, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 16;
        layout.marginHeight = 16;
        layout.verticalSpacing = 12;
        learnContent.setLayout(layout);
        section(learnContent, "WHAT IT DOES", false);
        learnExplanation = body(learnContent, "Select an API to start.");
        section(learnContent, "BEFORE YOU USE IT", false);
        learnRequirements = body(learnContent, "Board and Kconfig requirements will appear here.");
        section(learnContent, "WATCH OUT FOR", true);
        learnMistakes = body(learnContent, "Thread-safety and ISR warnings will appear here.");
        learnScroll.setContent(learnContent);
        attachScrollSizing(learnScroll, learnContent);
        tab.setControl(learnScroll);
    }

    private void createCodeTab() {
        TabItem tab = new TabItem(detailTabs, SWT.NONE);
        tab.setText("C example");
        Composite panel = new Composite(detailTabs, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 12;
        layout.marginHeight = 12;
        panel.setLayout(layout);
        Label file = new Label(panel, SWT.NONE);
        file.setText("main.c   •   complete example");
        file.setFont(titleFont);
        titleLabels.add(file);
        codeHint = new Label(panel, SWT.WRAP);
        codeHint.setText("Code examples are for the pinned reference; review board requirements before building.");
        codeHint.setForeground(muted);
        codeHint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        example = new StyledText(panel, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        example.setFont(codeFont);
        example.setBackground(codeBackground);
        example.setTabs(4);
        example.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        example.setText("// Choose an API to view its complete C example.");
        tab.setControl(panel);
    }

    private void createReferenceTab() {
        TabItem tab = new TabItem(detailTabs, SWT.NONE);
        tab.setText("API reference");
        referenceScroll = new ScrolledComposite(detailTabs, SWT.V_SCROLL | SWT.H_SCROLL);
        referenceScroll.setExpandHorizontal(true);
        referenceContent = new Composite(referenceScroll, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 16;
        layout.marginHeight = 16;
        layout.verticalSpacing = 12;
        referenceContent.setLayout(layout);
        section(referenceContent, "FUNCTION SIGNATURE", false);
        signature = new StyledText(referenceContent, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.READ_ONLY);
        GridData signatureLayout = new GridData(SWT.FILL, SWT.TOP, true, false);
        signatureLayout.heightHint = 78;
        signature.setLayoutData(signatureLayout);
        signature.setFont(codeFont);
        signature.setBackground(codeBackground);
        section(referenceContent, "PARAMETERS", false);
        referenceParameters = body(referenceContent, "Select an API to see parameters.");
        section(referenceContent, "RELATED APIs", false);
        referenceRelated = body(referenceContent, "No API selected.");
        section(referenceContent, "PINNED OFFICIAL SOURCE", false);
        referenceSource = body(referenceContent, "Use Official docs to open the upstream reference.");
        referenceSource.setForeground(muted);
        referenceScroll.setContent(referenceContent);
        attachScrollSizing(referenceScroll, referenceContent);
        tab.setControl(referenceScroll);
    }

    private void attachScrollSizing(ScrolledComposite scroll, Composite content) {
        scroll.addControlListener(new ControlAdapter() {
            @Override public void controlResized(ControlEvent event) { updateScrollSize(scroll, content); }
        });
        updateScrollSize(scroll, content);
    }

    private void updateScrollSize(ScrolledComposite scroll, Composite content) {
        if (scroll.isDisposed() || content.isDisposed()) return;
        int width = Math.max(180, scroll.getClientArea().width - 14);
        // Explicit width hints let SWT.WRAP labels reflow on narrow/docked views.
        for (Control child : content.getChildren()) {
            if (child instanceof Label && child.getLayoutData() instanceof GridData data)
                data.widthHint = Math.max(140, width - 46);
        }
        scroll.setMinSize(content.computeSize(width, SWT.DEFAULT));
    }

    private Label section(Composite parent, String text, boolean warning) {
        Label label = new Label(parent, SWT.WRAP);
        label.setText(text);
        label.setFont(titleFont);
        titleLabels.add(label);
        label.setForeground(warning ? caution : accent);
        label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return label;
    }

    private Label body(Composite parent, String text) {
        Label label = new Label(parent, SWT.WRAP);
        label.setText(text);
        label.setFont(bodyFont);
        label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        bodyLabels.add(label);
        return label;
    }

    private boolean darkTheme(Composite parent) {
        var rgb = parent.getBackground().getRGB();
        return (rgb.red * 299 + rgb.green * 587 + rgb.blue * 114) / 1000 < 125;
    }

    private Color color(int red, int green, int blue) {
        Color value = new Color(display, red, green, blue);
        ownedColors.add(value);
        return value;
    }

    private Font font(int size, int style, boolean monospaced) {
        FontData data = monospaced
                ? new FontData(System.getProperty("os.name", "").startsWith("Windows")
                        ? "Consolas" : "Monospace", size, style)
                : display.getSystemFont().getFontData()[0];
        data.setHeight(size);
        data.setStyle(style);
        Font value = new Font(display, data);
        ownedFonts.add(value);
        return value;
    }

    private void setReadableFonts() {
        int base = Math.max(12, display.getSystemFont().getFontData()[0].getHeight());
        bodyFont = font(base + fontScale, SWT.NORMAL, false);
        titleFont = font(base + fontScale + 1, SWT.BOLD, false);
        headingFont = font(base + fontScale + 5, SWT.BOLD, false);
        codeFont = font(base + fontScale, SWT.NORMAL, true);
    }

    private void resizeText(int delta) {
        int next = Math.max(-1, Math.min(6, fontScale + delta));
        if (next == fontScale) return;
        fontScale = next;
        List<Font> previous = new ArrayList<>(ownedFonts);
        ownedFonts.clear();
        setReadableFonts();
        if (!results.isDisposed()) results.setFont(bodyFont);
        if (!search.isDisposed()) search.setFont(bodyFont);
        for (Label label : headingLabels) if (!label.isDisposed()) label.setFont(headingFont);
        for (Label label : titleLabels) if (!label.isDisposed()) label.setFont(titleFont);
        for (Label label : bodyLabels) if (!label.isDisposed()) label.setFont(bodyFont);
        if (!example.isDisposed()) example.setFont(codeFont);
        if (!signature.isDisposed()) signature.setFont(codeFont);
        for (Font old : previous) old.dispose();
        updateScrollSize(learnScroll, learnContent);
        updateScrollSize(referenceScroll, referenceContent);
        titleLabel.getParent().layout(true, true);
    }

    private void detectActiveProject() {
        var page = getSite().getWorkbenchWindow().getActivePage();
        IProject current = page == null || page.getActiveEditor() == null ? null
                : SelectedProject.importedProject(page.getActiveEditor().getEditorInput());
        displayProject(current);
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
                // An inaccessible project/environment must remain UNKNOWN.
            }
        }
        if (projectLabel != null && !projectLabel.isDisposed()) {
            projectLabel.setText(project == null
                    ? "No project selected  •  Zephyr version unknown"
                    : "Project: " + project.getName() + "  •  Zephyr "
                    + (projectVersion.isEmpty() ? "version unknown" : projectVersion));
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
        if (search == null || search.isDisposed() || results == null || results.isDisposed()) return;
        long serial = ++requestNumber;
        if (searchJob != null) searchJob.cancel();
        QuickHelpCatalog current = catalogue;
        if (current == null) return;
        String term = search.getText();
        if (term.length() > 128) {
            resultsLabel.setText("Please shorten your search to 128 characters.");
            return;
        }
        searchJob = new Job("Search offline Zephyr APIs") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                List<QuickHelpCatalog.Hit> hits = current.search(term, 40);
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                if (!display.isDisposed()) display.asyncExec(() -> {
                    if (!results.isDisposed() && serial == requestNumber)
                        displayResults(term, hits);
                });
                return Status.OK_STATUS;
            }
        };
        searchJob.setSystem(true);
        searchJob.schedule(120L);
    }

    private void displayResults(String term, List<QuickHelpCatalog.Hit> hits) {
        results.setRedraw(false);
        try {
            results.removeAll();
            for (QuickHelpCatalog.Hit hit : hits) {
                TableItem row = new TableItem(results, SWT.NONE);
                row.setText(new String[] {hit.entry().id(), hit.entry().title()});
                row.setData(hit);
            }
            if (!hits.isEmpty()) results.setSelection(0);
        } finally {
            results.setRedraw(true);
        }
        resultsLabel.setText(term.isBlank() ? "Explore six starter topics"
                : hits.isEmpty() ? "No local match — try a shorter phrase."
                : hits.size() + " matching API" + (hits.size() == 1 ? "" : "s"));
        updateSelection();
    }

    private static String firstSentence(String text) {
        int end = text.indexOf(". ");
        return end > 0 ? text.substring(0, end + 1) : text;
    }

    private void updateSelection() {
        selected = null;
        if (results == null || results.isDisposed()) return;
        if (results.getSelectionCount() == 1 && results.getSelection()[0].getData()
                instanceof QuickHelpCatalog.Hit hit) selected = hit;
        if (copyButton != null && !copyButton.isDisposed()) copyButton.setEnabled(selected != null);
        if (docsButton != null && !docsButton.isDisposed()) docsButton.setEnabled(selected != null);
        if (titleLabel == null || titleLabel.isDisposed()) return;
        if (selected == null) {
            titleLabel.setText("Pick an API to start learning");
            apiLabel.setText("");
            statusLabel.setText("");
            summaryLabel.setText("Try one of the six quick-start topics or search a task in plain English.");
            matchLabel.setText("");
            learnExplanation.setText("Select an API to start.");
            learnRequirements.setText("Board and configuration requirements will appear here.");
            learnMistakes.setText("Thread-safety and ISR warnings will appear here.");
            example.setText("// Choose an API to view its complete C example.");
            signature.setText("");
            referenceParameters.setText("");
            referenceRelated.setText("");
            referenceSource.setText("");
        } else {
            QuickHelpCatalog.Entry entry = selected.entry();
            titleLabel.setText(entry.title());
            apiLabel.setText(entry.id() + "()");
            summaryLabel.setText(firstSentence(entry.explanation()));
            matchLabel.setText(selected.intention()
                    ? "Your phrase: \"" + selected.matchedTerm()
                            + "\"  →  real API: " + entry.id()
                    : "Verified API name from the local reference: " + entry.id());
            if (selected.spellingSuggestion()) matchLabel.setText(
                    matchLabel.getText() + "  •  possible spelling match");
            QuickHelpCatalog.VersionState state = QuickHelpCatalog.versionState(entry, projectVersion);
            statusLabel.setToolTipText("Reference-version match only; example build and board compatibility are unverified");
            switch (state) {
                case MATCH -> {
                    statusLabel.setText("REFERENCE MATCH");
                    statusLabel.setForeground(positive);
                }
                case MISMATCH -> {
                    statusLabel.setText("VERSION MISMATCH");
                    statusLabel.setForeground(caution);
                }
                case UNKNOWN -> {
                    statusLabel.setText("VERSION UNKNOWN");
                    statusLabel.setForeground(caution);
                }
            }
            learnExplanation.setText(entry.explanation());
            learnRequirements.setText(entry.requirements());
            learnMistakes.setText(entry.mistakes());
            example.setText(entry.example());
            styleCode(entry.example());
            signature.setText(entry.signature());
            referenceParameters.setText(entry.parameters());
            referenceRelated.setText(String.join("   •   ", entry.related()));
            referenceSource.setText("Zephyr " + entry.version() + "\n" + entry.source()
                    + "\n\nExamples are educational, not verified for this board."
                    + (state == QuickHelpCatalog.VersionState.MISMATCH
                            ? "\nYour project uses " + projectVersion + "; check its headers before use." : "")
                    + "\n\nZephyr Project: Apache-2.0 headers; original Workbench example.");
        }
        updateScrollSize(learnScroll, learnContent);
        updateScrollSize(referenceScroll, referenceContent);
        titleLabel.getParent().layout(true, true);
    }

    private void styleCode(String text) {
        List<StyleRange> ranges = new ArrayList<>();
        Matcher matcher = C_TOKENS.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            Color color = token.startsWith("//") || token.startsWith("/*") ? syntaxComment
                    : token.startsWith("\"") || token.startsWith("'") ? syntaxString
                    : syntaxKeyword;
            ranges.add(new StyleRange(matcher.start(), matcher.end() - matcher.start(),
                    color, null, color == syntaxKeyword ? SWT.BOLD : SWT.NORMAL));
        }
        example.setStyleRanges(ranges.toArray(new StyleRange[0]));
        example.setTopIndex(0);
    }

    private void copyExample() {
        if (selected == null) return;
        Clipboard clipboard = new Clipboard(display);
        try {
            clipboard.setContents(new Object[] {selected.entry().example()},
                    new Transfer[] {TextTransfer.getInstance()});
            codeHint.setText("Copied! Review the requirements before building for your board.");
        } finally {
            clipboard.dispose();
        }
    }

    private void openDocumentation() {
        if (selected != null && !Program.launch(selected.entry().source()))
            codeHint.setText("Browser unavailable. The source URL is under API reference.");
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
        for (Font font : ownedFonts) if (!font.isDisposed()) font.dispose();
        for (Color color : ownedColors) if (!color.isDisposed()) color.dispose();
    }
}
