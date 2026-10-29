// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.ViewPart;
import io.github.niwantha33.zew.core.ConfigurationStudioFiles;
import io.github.niwantha33.zew.core.ConfigurationStudioPresentation;
import io.github.niwantha33.zew.core.KconfigSourceHelp;
import io.github.niwantha33.zew.core.ConfigurationStudioFiles.Option;
import io.github.niwantha33.zew.core.ConfigurationStudioFiles.Snapshot;

/**
 * Project-scoped native Eclipse configuration explorer.
 *
 * Effective Kconfig and compiled Devicetree are read-only. All application
 * configuration edits require a review, a backup, and a subsequent Zephyr build.
 * This view deliberately does not fabricate vendor pinmux or clock solutions.
 */
public final class ConfigurationStudioView extends ViewPart {
    public static final String ID = Activator.ID + ".configurationStudio";
    private IProject project;
    private Snapshot snapshot;
    private Text search, help, tree, analysis;
    private Table options;
    private Button enableButton, disableButton, editButton, removeButton;
    private Combo category;
    private TabFolder tabs;
    private Label context, status, overview;
    private String filter = "";
    private String selectedOverlay = "app.overlay";
    private Job helpJob;
    private final java.util.concurrent.atomic.AtomicInteger helpGeneration = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.ConcurrentMap<String, String> definitionCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override public void createPartControl(Composite parent) {
        var outer = new Composite(parent, SWT.NONE);
        outer.setLayout(new GridLayout(1, false));
        var heading = new Composite(outer, SWT.NONE);
        heading.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        heading.setLayout(new GridLayout(6, false));
        context = new Label(heading, SWT.WRAP);
        context.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        context.setText("Select an imported Zephyr application to inspect its configuration.");
        action(heading, "Refresh", this::reload).setToolTipText("Reload the saved project configuration");
        action(heading, "Project settings", this::openProjectSettings)
                .setToolTipText("Select Zephyr environment, board, SoC/core and build configuration");
        action(heading, "Edit source", () -> openResource("prj.conf"))
                .setToolTipText("Open the original prj.conf in the Eclipse editor");
        action(heading, "Hardware overlay", this::openOverlay)
                .setToolTipText("Edit app.overlay without generating unsafe pin or clock values");
        action(heading, "Build & validate", this::build)
                .setToolTipText("Run Zephyr configuration and compile for the selected exact target");

        tabs = new TabFolder(outer, SWT.NONE);
        tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        Composite software = tab(tabs, "Kconfig Studio");
        software.setLayout(new GridLayout(1, false));
        overview = new Label(software, SWT.WRAP);
        overview.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        overview.setText("Select an option to inspect its effective value, override, and Kconfig definition.");
        Composite navigation = new Composite(software, SWT.NONE);
        navigation.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        navigation.setLayout(new GridLayout(2, false));
        category = new Combo(navigation, SWT.DROP_DOWN | SWT.READ_ONLY);
        category.setItems(ConfigurationStudioPresentation.FILTERS);
        category.select(0);
        category.setToolTipText("Browse by purpose; categories are approximate and do not change Kconfig dependencies");
        category.addListener(SWT.Selection, event -> refreshOptions());
        search = new Text(navigation, SWT.SEARCH | SWT.ICON_SEARCH | SWT.CANCEL | SWT.BORDER);
        search.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        search.setMessage("Search configuration options (LOG, DEBUG_THREAD_INFO, Bluetooth...)");
        search.addModifyListener(event -> { filter = search.getText(); refreshOptions(); });
        SashForm sash = new SashForm(software, SWT.HORIZONTAL);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        options = new Table(sash, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
        options.setHeaderVisible(true);
        options.setLinesVisible(true);
        for (String name : new String[] {"Option", "Effective", "Requested", "Validation"}) {
            var col = new TableColumn(options, SWT.LEFT);
            col.setText(name);
        }
        Composite detail = new Composite(sash, SWT.NONE);
        detail.setLayout(new GridLayout(1, false));
        help = new Text(detail, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        help.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        Composite actions = new Composite(detail, SWT.NONE);
        actions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        actions.setLayout(new GridLayout(2, false));
        enableButton = action(actions, "Enable", () -> setToggle("y"));
        enableButton.setToolTipText("Request CONFIG_<symbol>=y in prj.conf, with review and backup");
        disableButton = action(actions, "Disable", () -> setToggle("n"));
        disableButton.setToolTipText("Request CONFIG_<symbol>=n in prj.conf, with review and backup");
        editButton = action(actions, "Edit value...", () -> editSelected(false));
        editButton.setToolTipText("Edit numeric, text or other non-switch settings");
        removeButton = action(actions, "Remove override...", () -> editSelected(true));
        removeButton.setToolTipText("Remove the prj.conf assignment; board defaults and other fragments still apply");
        sash.setWeights(new int[] {62, 38});
        options.addListener(SWT.Selection, event -> describeSelected());

        Composite hardware = tab(tabs, "Devicetree Studio");
        hardware.setLayout(new GridLayout(1, false));
        Label warning = new Label(hardware, SWT.WRAP);
        warning.setText("Compiled Devicetree is read-only. Change hardware through an application overlay "
                + "and rebuild. Pinmux, clocks and peripheral constraints depend on the board and vendor.");
        warning.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        tree = new Text(hardware, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        tree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        action(hardware, "Create/Open app.overlay in source editor", this::openOverlay);

        Composite reports = tab(tabs, "Configuration Analysis");
        reports.setLayout(new GridLayout(1, false));
        analysis = new Text(reports, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        analysis.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        status = new Label(outer, SWT.WRAP);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        status.setText("Read-only until you select a project.");
        reload();
    }

    private static Composite tab(TabFolder folder, String name) {
        var item = new TabItem(folder, SWT.NONE);
        item.setText(name);
        var body = new Composite(folder, SWT.NONE);
        item.setControl(body);
        return body;
    }
    private static Button action(Composite parent, String label, Runnable run) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(label);
        button.addListener(SWT.Selection, event -> run.run());
        return button;
    }

    public void displayProject(IProject selected) { displayProject(selected, false, null); }

    public void displayProject(IProject selected, boolean hardware) {
        displayProject(selected, hardware, null);
    }

    public void displayProject(IProject selected, boolean hardware, String overlayPath) {
        if (selected == null || !selected.isAccessible()) return;
        boolean changedProject = project != null && !project.equals(selected);
        project = selected;
        selectedOverlay = overlayPath == null ? "app.overlay" : overlayPath;
        if (changedProject && category != null && !category.isDisposed()) {
            category.select(0);
            if (search != null && !search.isDisposed()) search.setText("");
            filter = "";
        }
        setPartName("Configuration — " + selected.getName());
        if (options != null && !options.isDisposed()) {
            reload();
            tabs.setSelection(hardware ? 1 : 0);
        }
    }

    /** Context action on prj.conf highlights only the project-owned choices. */
    public void focusProjectChanges() {
        if (category == null || category.isDisposed()) return;
        category.select(1);
        tabs.setSelection(0);
        refreshOptions();
    }

    private void reload() {
        helpGeneration.incrementAndGet();
        if (helpJob != null) helpJob.cancel();
        definitionCache.clear();
        if (options == null || options.isDisposed()) return;
        if (project == null) {
            options.removeAll();
            describeSelected();
            help.setText("Open Zephyr > Configuration Studio with an application selected in Project Explorer.");
            tree.setText("");
            analysis.setText("");
            return;
        }
        try {
            Path app = project.getLocation().toFile().toPath();
            Path build = null;
            String profile = "Source configuration";
            String board = "No target selected";
            String setupProblem = null;
            try {
                var settings = ProjectContext.settings(project);
                board = settings.getProperty("board", "No target selected");
                profile = settings.getProperty("zew.buildConfiguration", "default");
                build = ProjectContext.buildDirectory(project, settings);
            } catch (Exception notConfigured) {
                // Project configuration is still useful if tool installation or
                // board selection fails. Source repair must not require a build.
                setupProblem = notConfigured.getMessage();
            }
            snapshot = ConfigurationStudioFiles.inspect(app, build, profile);
            context.setText("CONFIGURATION STUDIO   •   " + project.getName() + "   •   "
                    + board + "   •   " + snapshot.profile());
            refreshOptions();
            tree.setText(snapshot.devicetree().isEmpty()
                    ? "No resolved Devicetree is available. Select the board in Project settings "
                            + "and build to generate zephyr.dts. Existing overlays remain editable."
                    : snapshot.devicetree());
            if (build == null) {
                long requested = snapshot.options().stream().filter(Option::overridden).count();
                analysis.setText("Project: " + project.getName()
                        + "\\nStatus: Environment or board not configured"
                        + "\\nRequested settings in prj.conf: " + requested
                        + "\\nNo effective Kconfig or hardware data is available yet."
                        + "\\n\\nProject settings → choose the Zephyr installation and exact board."
                        + "\\nBuild & validate → generate the effective Kconfig and Devicetree."
                        + "\\n\\nYou can inspect prj.conf now and remove obsolete or invalid "
                        + "requests with the normal backup and change review.");
                status.setText("Source-only mode: " + setupProblem
                        + "  •  Configure the project to enable validated setting changes.");
            } else {
                refreshAnalysis(build);
                status.setText("Build values are from the last generated output. "
                        + "Changes to prj.conf require review, backup and a new build.");
            }
        } catch (Exception error) {
            snapshot = null;
            options.removeAll();
            describeSelected();
            help.setText("Configuration cannot be loaded: " + error.getMessage());
            tree.setText("");
            analysis.setText("Open Project settings or inspect the source file for configuration errors.");
            status.setText("Configuration unavailable. No source files were changed.");
        }
    }


    private void refreshOptions() {
        if (options == null || options.isDisposed()) return;
        Option previousSelection = selected();
        String previousSymbol = previousSelection == null ? null : previousSelection.symbol();
        options.setRedraw(false);
        int shown = 0;
        int keepIndex = -1;
        int matches = 0;
        int total = snapshot == null ? 0 : snapshot.options().size();
        try {
            options.removeAll();
            if (snapshot != null) {
                String active = category == null || category.isDisposed()
                        ? ConfigurationStudioPresentation.ALL : category.getText();
                for (Option option : snapshot.options().stream()
                        .sorted(Comparator.comparing(Option::symbol)).toList()) {
                    if (!ConfigurationStudioPresentation.matches(option, active, filter)) continue;
                    matches++;
                    if (shown >= 750) continue;
                    TableItem row = new TableItem(options, SWT.NONE);
                    row.setText(new String[] {
                            ConfigurationStudioPresentation.title(option.symbol()),
                            ConfigurationStudioPresentation.current(option),
                            ConfigurationStudioPresentation.requested(option),
                            ConfigurationStudioPresentation.review(option)
                    });
                    row.setData(option);
                    if (option.symbol().equals(previousSymbol)) keepIndex = shown;
                    ++shown;
                }
            }
            for (int i = 0; i < options.getColumnCount(); i++) {
                options.getColumn(i).pack();
                int cap = i == 0 ? 260 : 150;
                if (options.getColumn(i).getWidth() > cap) options.getColumn(i).setWidth(cap);
            }
            if (options.getItemCount() > 0) options.select(keepIndex >= 0 ? keepIndex : 0);
        } finally {
            options.setRedraw(true);
        }
        if (overview != null && !overview.isDisposed()) {
            overview.setText("Showing " + shown + " of " + matches + " matching settings"
                    + "   •   " + total + " values from the selected build"
                    + (shown < matches ? "   •   Narrow your search to see more" : "")
                    + "   •   Select a feature for its explanation");
        }
        describeSelected();
    }

    private Option selected() {
        if (options == null || options.isDisposed() || options.getSelectionCount() == 0) return null;
        return (Option) options.getSelection()[0].getData();
    }

    private void describeSelected() {
        if (helpJob != null) helpJob.cancel();
        Option o = selected();
        boolean togglable = ConfigurationStudioPresentation.isToggle(o);
        if (enableButton != null && !enableButton.isDisposed()) {
            enableButton.setEnabled(togglable && !"y".equals(o.requested()));
            disableButton.setEnabled(togglable && !"n".equals(o.requested()));
            editButton.setEnabled(o != null && !o.unresolved() && !togglable);
            removeButton.setEnabled(o != null && o.requested() != null);
        }
        if (o == null) {
            help.setText("No settings match the current search.");
            return;
        }
        String explanation = switch (o.symbol()) {
            case "LOG" -> "Enables the Zephyr logging subsystem. Logging can increase firmware size and runtime work.";
            case "LOG_MODE_DEFERRED" -> "Queues log messages for deferred processing. Requires the logging subsystem.";
            case "DEBUG_THREAD_INFO" -> "Provides thread debug metadata for supported debugging integrations.";
            case "THREAD_NAME" -> "Enables human-readable Zephyr thread names; has memory overhead.";
            case "DEBUG_OPTIMIZATIONS" -> "Requests debug-friendly compiler optimization (-Og). Not equivalent to enabling every debug feature.";
            case "TRACING" -> "Enables Zephyr tracing infrastructure. Select a compatible tracing backend separately.";
            case "TRACING_CTF" -> "Selects the Common Trace Format encoder when supported and its dependencies are satisfied.";
            default -> "No curated explanation for this option yet. Refer to its definition in the "
                    + "selected Zephyr checkout and upstream Kconfig help; the effective value is authoritative.";
        };
        String base = ConfigurationStudioPresentation.title(o.symbol())
                + "\n" + ConfigurationStudioPresentation.current(o)
                + "   •   " + ConfigurationStudioPresentation.source(o)
                + "\nTechnical option: CONFIG_" + o.symbol()
                + "\nRequested in prj.conf: " + ConfigurationStudioPresentation.requested(o)
                + (o.requestedLine() > 0 ? " (line " + o.requestedLine() + ")" : "")
                + "\n\nWhat this setting does\n" + explanation
                + (o.unresolved() ? "\n\nThis request has no corresponding entry in the last generated "
                        + ".config. It may be undefined or the generated configuration may be stale."
                        + " Examine Zephyr's Kconfig diagnostics before changing it." : "")
                + "\n\nEnable / Disable writes an explicit prj.conf request. The effective "
                + "value is determined by Zephyr during the next configuration/build.";
        help.setText(base);
        // Source text is a supplementary explanation, not a Kconfig dependency
        // evaluation. Run filesystem searches off the SWT UI thread.
        if (project == null) return;
        final int generation = helpGeneration.incrementAndGet();
        final org.eclipse.swt.widgets.Display display = help.getDisplay();
        try {
            Path workspace = Path.of(ProjectContext.settings(project).getProperty("workspace"));
            String key = workspace + "#" + o.symbol();
            String cached = definitionCache.get(key);
            if (cached != null) { help.setText(base + cached); return; }
            Job lookup = new Job("Explain CONFIG_" + o.symbol()) {
                @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    String result;
                    try {
                        var definition = KconfigSourceHelp.firstDefinition(workspace, o.symbol(), monitor::isCanceled);
                        result = definition == null
                                ? "\n\nNo source definition found in the bounded scan. "
                                  + "This does not prove the symbol is undefined in the active build."
                                : "\n\nSource declaration (raw text, not resolved dependency state):\n"
                                  + definition.file() + ":" + definition.line() + "\n"
                                  + definition.sourceExcerpt();
                    } catch (Exception error) {
                        result = "\n\nSource explanation unavailable: " + error.getMessage();
                    }
                    definitionCache.putIfAbsent(key, result);
                    String sourceText = result;
                    if (!display.isDisposed()) display.asyncExec(() -> {
                        if (!help.isDisposed() && generation == helpGeneration.get()
                                && selected() != null && o.symbol().equals(selected().symbol()))
                            help.setText(base + sourceText);
                    });
                    return Status.OK_STATUS;
                }
            };
            lookup.setSystem(true);
            helpJob = lookup;
            lookup.schedule();
        } catch (Exception unavailable) {
            // Basic effective/requested values remain usable without source indexing.
        }
    }

    /** Direct Enable/Disable controls stay visible; only resolved switches are actionable. */
    private void setToggle(String value) {
        Option current = selected();
        if (!ConfigurationStudioPresentation.isToggle(current)) {
            MessageDialog.openWarning(getSite().getShell(), "Cannot change as a switch",
                    "This option is not a resolved on/off setting. "
                    + "Inspect its Kconfig definition or use Edit value for non-switch types.");
            return;
        }
        saveSelected(current, value);
    }

    private void editSelected(boolean remove) {
        Option current = selected();
        if (project == null || current == null) return;
        if (remove) {
            if (current.requested() == null) return;
            saveSelected(current, null);
            return;
        }
        if (current.unresolved()) {
            MessageDialog.openWarning(getSite().getShell(), "Unresolved setting",
                    "This setting has no resolved value in the last .config. "
                    + "Remove the invalid override or fix Kconfig dependencies and build first.");
            return;
        }
        if (ConfigurationStudioPresentation.isToggle(current)) {
            MessageDialog.openInformation(getSite().getShell(), "On/off setting",
                    "Use the Enable or Disable controls for this setting.");
            return;
        }
        var dialog = new InputDialog(getSite().getShell(),
                "Edit " + ConfigurationStudioPresentation.title(current.symbol()),
                "Enter a value for CONFIG_" + current.symbol()
                + ".\nZephyr validates it during the next build.",
                current.requested() != null ? current.requested() : current.effective(),
                value -> value == null || value.isBlank() || value.indexOf('\n') >= 0
                        || value.indexOf('\r') >= 0 ? "Enter one nonempty line." : null);
        if (dialog.open() == Window.OK) saveSelected(current, dialog.getValue());
    }

    /** Every GUI action shares review, optimistic locking and backed-up atomic save. */
    private void saveSelected(Option current, String value) {
        if (project == null || current == null) return;
        try {
            Path app = project.getLocation().toFile().toPath();
            Path build = null;
            try {
                build = ProjectContext.buildDirectory(project, ProjectContext.settings(project));
            } catch (Exception notConfigured) {
                if (current.requested() == null)
                    throw new java.io.IOException("Select an environment and build the exact board before "
                            + "adding a new symbol.", notConfigured);
                // Existing prj.conf assignments can be repaired without a build.
            }
            var preview = ConfigurationStudioFiles.plan(app, build, current.symbol(), value);
            if (!preview.changed()) {
                status.setText("No change: CONFIG_" + current.symbol() + " already has that request.");
                return;
            }
            String before = preview.before() == null ? "(no prj.conf assignment)"
                    : "CONFIG_" + current.symbol() + "=" + preview.before();
            String after = value == null ? "(remove assignment)"
                    : "CONFIG_" + current.symbol() + "=" + value;
            if (!MessageDialog.openConfirm(getSite().getShell(), "Review Kconfig change",
                    "Project: " + project.getName()
                    + "\nFile: " + preview.file()
                    + "\n\nBefore: " + before + "\nAfter:  " + after
                    + "\n\nThis edits the request in prj.conf, not Zephyr's generated .config."
                    + "\nA timestamped backup will be saved. Effective settings and dependencies "
                    + "are verified only by the next Zephyr build."
                    + "\n\nApply this change?")) return;
            Path backup = ConfigurationStudioFiles.commit(app, preview);
            project.refreshLocal(IResource.DEPTH_INFINITE, null);
            reload();
            status.setText("Saved CONFIG_" + current.symbol()
                    + " to prj.conf; build to validate. Backup: " + backup);
        } catch (Exception error) {
            MessageDialog.openError(getSite().getShell(), "Configuration not saved", error.getMessage());
        }
    }

    private void openProjectSettings() {
        if (project == null) return;
        if (!ZephyrProjectLifecycle.supports(project)) {
            MessageDialog.openInformation(getSite().getShell(), "Existing Eclipse project",
                    "This Zephyr application uses another Eclipse build system. "
                    + "Configuration Studio can inspect prj.conf without taking over that builder. "
                    + "For native West build and debug, use File > Import > Zephyr > "
                    + "Existing Zephyr Application and complete the target setup.");
            return;
        }
        var dialog = org.eclipse.ui.dialogs.PreferencesUtil.createPropertyDialogOn(
                getSite().getShell(), project, "io.github.niwantha33.zew.projectProperties", null, null);
        if (dialog != null && dialog.open() == Window.OK) reload();
    }

    private void openResource(String path) {
        if (project == null) return;
        try {
            var file = project.getFile(path);
            if (!file.exists()) throw new java.io.IOException("File not found: " + path);
            IDE.openEditor(getSite().getPage(), file, true);
        } catch (Exception error) {
            MessageDialog.openError(getSite().getShell(), "Open source", error.getMessage());
        }
    }

    private void openOverlay() {
        if (project == null) return;
        try {
            Path app = project.getLocation().toFile().toPath().toRealPath();
            Path overlay = app.resolve(selectedOverlay).normalize();
            if (!overlay.startsWith(app)) throw new java.io.IOException("Overlay is outside the application.");
            if (!Files.exists(overlay) && !"app.overlay".equals(selectedOverlay))
                throw new java.io.IOException("The selected overlay no longer exists: " + selectedOverlay);
            if (!Files.exists(overlay) && MessageDialog.openConfirm(getSite().getShell(),
                    "Create application overlay", "Create an empty app.overlay in this project?\n"
                            + "The file will be recognised as an application hardware overlay on the next "
                            + "Zephyr configure. No pins, clocks or peripheral nodes will be changed automatically."))
                Files.writeString(overlay, "/* Application hardware overlay. Validate against this board's bindings. */\n",
                        java.nio.charset.StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            project.refreshLocal(IResource.DEPTH_INFINITE, null);
            if (Files.isRegularFile(overlay)) openResource(selectedOverlay);
        } catch (Exception error) {
            MessageDialog.openError(getSite().getShell(), "Devicetree overlay", error.getMessage());
        }
    }

    private void refreshAnalysis(Path build) throws java.io.IOException {
        if (snapshot == null) return;
        long requested = snapshot.options().stream().filter(Option::overridden).count();
        long unresolved = snapshot.options().stream().filter(Option::unresolved).count();
        Path elf = build.resolve("zephyr/zephyr.elf");
        Path bin = build.resolve("zephyr/zephyr.bin");
        String report = "Project: " + project.getName() + "\nProfile: " + snapshot.profile()
                + "\nGenerated Kconfig settings: " + snapshot.options().size()
                + "\nExplicit prj.conf settings: " + requested
                + "\nRequests absent from last generated .config: " + unresolved
                + "\nCompiled Devicetree: " + (snapshot.devicetree().isEmpty() ? "not available" : "available")
                + "\nELF file: " + (Files.isRegularFile(elf) ? Files.size(elf) + " bytes on disk" : "not built")
                + "\nBIN file: " + (Files.isRegularFile(bin) ? Files.size(bin) + " bytes on disk" : "not generated")
                + "\n\nThe ELF file size is not flash or RAM utilisation. Use Zephyr's "
                + "memory region report/map data for target memory usage."
                + "\n\nChanges to configuration require a new build before comparing firmware impact."
                + "\n\nRTOS analysis prerequisite checklist:\n"
                + "CONFIG_DEBUG_THREAD_INFO, CONFIG_THREAD_NAME, CONFIG_TRACING, "
                + "CONFIG_TRACING_CTF (subject to target/backend availability)."
                + "\n\nGenerated output may be stale after edits or an unsuccessful configure.";
        analysis.setText(report);
    }

    private void build() {
        if (project == null) return;
        if (!ZephyrProjectLifecycle.supports(project)) {
            openProjectSettings();
            return;
        }
        try {
            ProjectContext.settings(project);
        } catch (Exception notReady) {
            MessageDialog.openInformation(getSite().getShell(), "Configure Zephyr project",
                    "Select a ready Zephyr environment, exact target and build profile."
                            + "\n\n" + notReady.getMessage());
            openProjectSettings();
            return;
        }
        IProject target = project;
        org.eclipse.swt.widgets.Display display = options.getDisplay();
        Job job = new Job("Validate and build Zephyr configuration — " + target.getName()) {
            @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                try {
                    target.build(IncrementalProjectBuilder.FULL_BUILD, Activator.ID + ".zephyrBuilder",
                            java.util.Map.of(), monitor);
                    if (!monitor.isCanceled() && !display.isDisposed())
                        display.asyncExec(() -> {
                            if (options != null && !options.isDisposed() && project == target) reload();
                        });
                    return monitor.isCanceled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
                } catch (org.eclipse.core.runtime.CoreException error) { return error.getStatus(); }
            }
        };
        job.setUser(true);
        job.schedule();
    }

    @Override public void dispose() {
        helpGeneration.incrementAndGet();
        if (helpJob != null) helpJob.cancel();
        super.dispose();
    }

    @Override public void setFocus() {
        if (search != null && !search.isDisposed()) search.setFocus();
    }
}
