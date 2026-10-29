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
    private Combo category;
    private TabFolder tabs;
    private Label context, status, overview;
    private String filter = "";
    private Job helpJob;
    private final java.util.concurrent.atomic.AtomicInteger helpGeneration = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.ConcurrentMap<String, String> definitionCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override public void createPartControl(Composite parent) {
        var outer = new Composite(parent, SWT.NONE);
        outer.setLayout(new GridLayout(1, false));
        var heading = new Composite(outer, SWT.NONE);
        heading.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        heading.setLayout(new GridLayout(5, false));
        context = new Label(heading, SWT.WRAP);
        context.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        context.setText("Select an imported Zephyr application to inspect its configuration.");
        action(heading, "Refresh", this::reload).setToolTipText("Reload the saved project configuration");
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
        overview.setText("Choose a feature to understand it. Changes are reviewed before saving.");
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
        search.setMessage("Find a feature: logging, debug, Bluetooth, CONFIG_...");
        search.addModifyListener(event -> { filter = search.getText(); refreshOptions(); });
        SashForm sash = new SashForm(software, SWT.HORIZONTAL);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        options = new Table(sash, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
        options.setHeaderVisible(true);
        options.setLinesVisible(true);
        for (String name : new String[] {"Feature", "Current value", "Configured by"}) {
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
        action(actions, "Change this setting...", () -> editSelected(false))
                .setToolTipText("For switches, choose On or Off; other values are entered as text");
        action(actions, "Use Zephyr default...", () -> editSelected(true))
                .setToolTipText("Remove this setting from prj.conf after reviewing the change");
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

    public void displayProject(IProject selected) { displayProject(selected, false); }

    public void displayProject(IProject selected, boolean hardware) {
        if (selected == null || !selected.isAccessible()) return;
        project = selected;
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
            help.setText("Open Zephyr > Configuration Studio with an application selected in Project Explorer.");
            tree.setText("");
            analysis.setText("");
            return;
        }
        try {
            var settings = ProjectContext.settings(project);
            Path build = ProjectContext.buildDirectory(project, settings);
            snapshot = ConfigurationStudioFiles.inspect(project.getLocation().toFile().toPath(),
                    build, settings.getProperty("zew.buildConfiguration", "default"));
            context.setText("CONFIGURATION STUDIO   •   " + project.getName() + "   •   "
                    + settings.getProperty("board") + "   •   " + snapshot.profile());
            refreshOptions();
            tree.setText(snapshot.devicetree().isEmpty()
                    ? "No generated zephyr.dts yet. Build the project to inspect the resolved hardware tree."
                    : snapshot.devicetree());
            refreshAnalysis(build);
            status.setText("Read-only build outputs. Changes to prj.conf require review and explicit confirmation.");
        } catch (Exception error) {
            snapshot = null;
            options.removeAll();
            help.setText("Configuration cannot be loaded: " + error.getMessage());
            tree.setText("");
            analysis.setText("Check Project Properties > Zephyr, and select the exact board and environment.");
            status.setText("Configuration unavailable. No project files were changed.");
        }
    }

    private void refreshOptions() {
        if (options == null || options.isDisposed()) return;
        options.setRedraw(false);
        int shown = 0;
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
                            ConfigurationStudioPresentation.source(option)
                    });
                    row.setData(option);
                    ++shown;
                }
            }
            for (int i = 0; i < options.getColumnCount(); i++) {
                options.getColumn(i).pack();
                int cap = i == 0 ? 280 : 175;
                if (options.getColumn(i).getWidth() > cap) options.getColumn(i).setWidth(cap);
            }
            if (options.getItemCount() > 0) options.select(0);
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
                + "\nValue in prj.conf: " + (o.requested() == null ? "not explicitly set" : o.requested())
                + (o.requestedLine() > 0 ? " (line " + o.requestedLine() + ")" : "")
                + "\n\nWhat this setting does\n" + explanation
                + (o.unresolved() ? "\n\nThis request has no corresponding entry in the last generated "
                        + ".config. It may be undefined or the generated configuration may be stale."
                        + " Examine Zephyr's Kconfig diagnostics before changing it." : "")
                + "\n\nConfiguration changes take effect only after the selected board is configured and rebuilt.";
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

    private void editSelected(boolean remove) {
        Option current = selected();
        if (project == null || current == null) return;
        if (remove && current.requested() == null) {
            MessageDialog.openInformation(getSite().getShell(), "No application override",
                    "There is no assignment for this symbol in prj.conf to remove.");
            return;
        }
        String value = null;
        if (!remove) {
            if (current.unresolved()) {
                MessageDialog.openWarning(getSite().getShell(), "Unresolved setting",
                        "This symbol is absent from the last generated .config. "
                                + "Remove the invalid override or resolve Kconfig prerequisites before editing it.");
                return;
            }
            if ("y".equals(current.effective()) || "n".equals(current.effective())) {
                var dialog = new MessageDialog(getSite().getShell(),
                        "Configure " + ConfigurationStudioPresentation.title(current.symbol()),
                        null, "Choose what you want.\n\n"
                                + "Zephyr will check whether this choice is supported by the board.",
                        MessageDialog.QUESTION, new String[] {"Turn on", "Turn off", "Cancel"}, 2);
                int selected = dialog.open();
                if (selected == 2 || selected < 0) return;
                value = selected == 0 ? "y" : "n";
            } else {
                var dialog = new InputDialog(getSite().getShell(),
                        "Configure " + ConfigurationStudioPresentation.title(current.symbol()),
                        "Enter a value for CONFIG_" + current.symbol()
                                + ".\nZephyr will check it during the next build.",
                        current.requested() != null ? current.requested() : current.effective(),
                        text -> text == null || text.isBlank() || text.indexOf('\n') >= 0
                                || text.indexOf('\r') >= 0 ? "Enter one nonempty line." : null);
                if (dialog.open() != Window.OK) return;
                value = dialog.getValue();
            }
        }
        try {
            Path app = project.getLocation().toFile().toPath();
            Path build = ProjectContext.buildDirectory(project, ProjectContext.settings(project));
            var preview = ConfigurationStudioFiles.plan(app, build, current.symbol(), value);
            if (!preview.changed()) {
                MessageDialog.openInformation(getSite().getShell(), "No changes", "prj.conf already contains that setting.");
                return;
            }
            String before = preview.before() == null ? "(not set)" : "CONFIG_" + current.symbol() + "=" + preview.before();
            String after = remove ? "(remove explicit assignment)" : "CONFIG_" + current.symbol() + "=" + value;
            if (!MessageDialog.openConfirm(getSite().getShell(), "Review configuration edit",
                    "File: " + preview.file() + "\n\nBefore: " + before + "\nAfter: " + after
                    + "\n\nExisting comments and unrelated configuration are preserved. "
                    + "A dated backup will be created. Zephyr must validate the result on the next build.\n\nApply this change?"))
                return;
            Path backup = ConfigurationStudioFiles.commit(app, preview);
            project.refreshLocal(IResource.DEPTH_INFINITE, null);
            reload();
            MessageDialog.openInformation(getSite().getShell(), "Configuration saved",
                    "prj.conf updated atomically.\nBackup: " + backup
                            + "\n\nBuild the project to validate Kconfig dependencies and regenerate .config.");
        } catch (Exception error) {
            MessageDialog.openError(getSite().getShell(), "Configuration not saved", error.getMessage());
        }
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
            Path overlay = app.resolve("app.overlay");
            if (!Files.exists(overlay) && MessageDialog.openConfirm(getSite().getShell(),
                    "Create application overlay", "Create an empty app.overlay in this project?\n"
                            + "The file will be recognised as an application hardware overlay on the next "
                            + "Zephyr configure. No pins, clocks or peripheral nodes will be changed automatically."))
                Files.writeString(overlay, "/* Application hardware overlay. Validate against this board's bindings. */\n",
                        java.nio.charset.StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            project.refreshLocal(IResource.DEPTH_INFINITE, null);
            if (Files.isRegularFile(overlay)) openResource("app.overlay");
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
