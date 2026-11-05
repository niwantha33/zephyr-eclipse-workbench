// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.ViewPart;
import io.github.niwantha33.zew.core.ConfigurationStudioFiles;
import io.github.niwantha33.zew.core.DevicetreeHardware;
import io.github.niwantha33.zew.core.DevicetreeParameters;
import io.github.niwantha33.zew.core.FocusedConfigurationModel;
import io.github.niwantha33.zew.core.HardwarePeripheralIndex;
import io.github.niwantha33.zew.core.PeripheralStartGuide;

/** Three-section configuration entrypoint, backed by existing evidence engines. */
public final class ConfigurationHomeView extends ViewPart {
    public static final String ID = Activator.ID + ".configurationHome";
    private record Evidence(String board, String profile, Path build, String dts,
                            String config, Properties setup, boolean stale, String warning,
                            FocusedConfigurationModel.Overview overview) {}
    private static final String[] ADC_STEPS =
            {"Controller", "Pin & channel", "Settings", "Review"};
    private static final String[] BUS_STEPS =
            {"Controller", "Connections", "Driver & settings", "Review"};
    private static final int OVERVIEW = 0, HARDWARE = 1, BUILD = 2;
    private final AtomicInteger generation = new AtomicInteger();
    private IProject project;
    private Evidence evidence;
    private Job refreshJob;
    private TabFolder primary;
    private Label header, freshness, capacityFlash, capacityRam, overviewInfo, issueInfo;
    private Table peripheralRows, issueRows;
    private Combo categories, controllers;
    private String category = "ADC", controllerPath = "";
    private Composite stepHost;
    private StackLayout stepLayout;
    private Composite[] pages;
    private int step = 0;
    private Label breadcrumb, guideState;
    private Text[] pageText;
    private Button previous, next, validateApply, example;
    private Text buildDetails;

    @Override public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));
        var heading = bar(parent, 3);
        var title = new Composite(heading, SWT.NONE);
        title.setLayoutData(fillWidth());
        title.setLayout(new GridLayout(1, false));
        new Label(title, SWT.NONE).setText("ZEPHYR  /  CONFIGURATION STUDIO");
        header = new Label(title, SWT.WRAP);
        header.setLayoutData(fillWidth());
        header.setText("Select a Zephyr project  •  Board unknown  •  Profile unknown");
        freshness = new Label(heading, SWT.WRAP);
        freshness.setText("Build evidence: unknown");
        button(heading, "Refresh", this::refresh);

        primary = new TabFolder(parent, SWT.NONE);
        primary.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        overview(tab("Overview"));
        hardware(tab("Hardware"));
        build(tab("Build & System"));
        primary.setSelection(OVERVIEW);
        clear("Select a Zephyr project from Project Explorer.");
    }

    private Composite tab(String text) {
        var tab = new TabItem(primary, SWT.NONE);
        tab.setText(text);
        var content = new Composite(primary, SWT.NONE);
        content.setLayout(new GridLayout(1, false));
        tab.setControl(content);
        return content;
    }

    private void overview(Composite root) {
        new Label(root, SWT.NONE).setText("Your board at a glance");
        overviewInfo = new Label(root, SWT.WRAP);
        overviewInfo.setLayoutData(fillWidth());
        var sizes = bar(root, 3);
        var f = new Composite(sizes, SWT.NONE);
        f.setLayout(new GridLayout(1, false)); f.setLayoutData(fillWidth());
        new Label(f, SWT.NONE).setText("FLASH CAPACITY");
        capacityFlash = new Label(f, SWT.NONE);
        var m = new Composite(sizes, SWT.NONE);
        m.setLayout(new GridLayout(1, false)); m.setLayoutData(fillWidth());
        new Label(m, SWT.NONE).setText("RAM CAPACITY");
        capacityRam = new Label(m, SWT.NONE);
        button(sizes, "View address map", this::memoryMap);
        new Label(root, SWT.NONE).setText("Peripherals described by the selected generated build");
        peripheralRows = table(root, new String[]{"Peripheral", "Status", "Next action"},
                new int[]{135, 165, 570}, true);
        peripheralRows.addListener(SWT.DefaultSelection, e -> choosePeripheral());
        button(root, "Configure selected peripheral", this::choosePeripheral);
        new Label(root, SWT.NONE).setText("Issues requiring attention");
        issueRows = table(root, new String[]{"Issue", "Next action"},
                new int[]{240, 630}, false);
        issueInfo = new Label(root, SWT.WRAP);
        issueInfo.setLayoutData(fillWidth());
        issueRows.addListener(SWT.Selection, e -> {
            if (issueRows.getSelectionCount() == 1)
                issueInfo.setText(((FocusedConfigurationModel.Issue)
                        issueRows.getSelection()[0].getData()).detail());
        });
        button(root, "Review issue", this::reviewIssue);
        overviewInfo.setText("No generated build data. No chip/pin inference.");
        capacityFlash.setText("Unknown"); capacityRam.setText("Unknown");
        issueInfo.setText("Choose an issue for its evidence and next action.");
    }

    private void hardware(Composite root) {
        var selection = bar(root, 4);
        new Label(selection, SWT.NONE).setText("Peripheral");
        categories = new Combo(selection, SWT.READ_ONLY | SWT.DROP_DOWN);
        categories.setItems(FocusedConfigurationModel.COMMON.toArray(String[]::new));
        categories.select(1);
        categories.addListener(SWT.Selection, e -> {
            category = categories.getText(); controllerPath = ""; step = 0;
            fillControllers(); renderGuide();
        });
        new Label(selection, SWT.NONE).setText("Built controller");
        controllers = new Combo(selection, SWT.READ_ONLY | SWT.DROP_DOWN);
        controllers.setLayoutData(fillWidth());
        controllers.addListener(SWT.Selection, e -> {
            controllerPath = (String) controllers.getData(
                    Integer.toString(controllers.getSelectionIndex()));
            step = 0; renderGuide();
        });
        breadcrumb = new Label(root, SWT.WRAP);
        breadcrumb.setLayoutData(fillWidth());
        guideState = new Label(root, SWT.WRAP);
        guideState.setLayoutData(fillWidth());
        stepHost = new Composite(root, SWT.NONE);
        stepHost.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        stepLayout = new StackLayout(); stepHost.setLayout(stepLayout);
        pages = new Composite[4]; pageText = new Text[4];
        for (int i = 0; i < 4; i++) {
            pages[i] = new Composite(stepHost, SWT.NONE);
            pages[i].setLayout(new GridLayout(1, false));
            pageText[i] = new Text(pages[i], SWT.BORDER | SWT.READ_ONLY | SWT.MULTI
                    | SWT.WRAP | SWT.V_SCROLL);
            pageText[i].setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        }
        var nav = bar(root, 3);
        previous = button(nav, "Back", () -> { step = Math.max(0, step - 1); renderGuide(); });
        next = button(nav, "Next", () -> { step = Math.min(3, step + 1); renderGuide(); });
        validateApply = button(nav, "Preview / validate / apply…", () -> advanced("review"));
        var tools = bar(root, 5);
        button(tools, "Chip & pins", () -> advanced("pins"));
        button(tools, "Memory map", this::memoryMap);
        button(tools, "Devicetree sources", () -> advanced("sources"));
        button(tools, "Binding & evidence", () -> advanced("guide"));
        example = button(tools, "Verified C example", () -> advanced("guide"));
        renderGuide();
    }

    private void build(Composite root) {
        new Label(root, SWT.NONE).setText("Build profile, toolchain & reproducibility");
        buildDetails = new Text(root, SWT.BORDER | SWT.READ_ONLY | SWT.MULTI
                | SWT.WRAP | SWT.V_SCROLL);
        buildDetails.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        var actions = bar(root, 3);
        button(actions, "Build & validate", () -> advanced("build"));
        button(actions, "Project settings & profiles", () -> advanced("settings"));
        button(actions, "Inspect build evidence", () -> advanced("evidence"));
        new Label(root, SWT.NONE).setText("Advanced system settings");
        var lower = bar(root, 3);
        button(lower, "Kconfig & prj.conf", () -> advanced("software"));
        button(lower, "RTOS & tracing", () -> advanced("rtos"));
        button(lower, "Devicetree & bindings", () -> advanced("sources"));
        buildDetails.setText("Select a project to inspect its exact build and Zephyr environment.");
    }

    private static GridData fillWidth() { return new GridData(SWT.FILL, SWT.CENTER, true, false); }
    private static Composite bar(Composite parent, int columns) {
        var child = new Composite(parent, SWT.NONE);
        child.setLayoutData(fillWidth()); child.setLayout(new GridLayout(columns, false));
        return child;
    }
    private static Button button(Composite parent, String title, Runnable action) {
        Button result = new Button(parent, SWT.PUSH);
        result.setText(title);
        result.addListener(SWT.Selection, e -> action.run());
        return result;
    }
    private static Table table(Composite parent, String[] headings, int[] widths, boolean grow) {
        Table t = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL);
        var gd = new GridData(SWT.FILL, SWT.FILL, true, grow);
        if (!grow) gd.heightHint = 100;
        t.setLayoutData(gd);
        t.setHeaderVisible(true); t.setLinesVisible(true);
        for (int i = 0; i < headings.length; i++) {
            var col = new TableColumn(t, SWT.LEFT);
            col.setText(headings[i]); col.setWidth(widths[i]);
        }
        return t;
    }

    public void displayProject(IProject selected) {
        if (selected == null || !selected.isAccessible()) return;
        project = selected;
        setPartName("Configuration — " + selected.getName());
        primary.setSelection(OVERVIEW);
        refresh();
    }

    public void openSection(String section) {
        if (primary == null || primary.isDisposed()) return;
        primary.setSelection("hardware".equals(section) ? HARDWARE
                : "system".equals(section) ? BUILD : OVERVIEW);
    }

    private static boolean newer(Path source, Path target) throws Exception {
        return source != null && Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                && (target == null || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.getLastModifiedTime(source).compareTo(Files.getLastModifiedTime(target)) > 0);
    }

    /** All file/project IO is off the SWT event thread. */
    private void refresh() {
        if (header == null || header.isDisposed()) return;
        int ticket = generation.incrementAndGet();
        if (refreshJob != null) refreshJob.cancel();
        IProject selected = project;
        if (selected == null || !selected.isAccessible() || selected.getLocation() == null) {
            clear("Select an accessible Zephyr project in Project Explorer.");
            return;
        }
        header.setText(selected.getName() + "  •  Reading build profile…");
        freshness.setText("Reading selected build…");
        var display = getSite().getShell().getDisplay();
        refreshJob = new Job("Read selected Zephyr Configuration Studio build") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                Evidence observed;
                try {
                    Path app = selected.getLocation().toFile().toPath();
                    Properties settings = new Properties();
                    String board = "Not selected", profile = "default", warning = "";
                    Path build = null;
                    try {
                        settings = ProjectContext.settings(selected);
                        board = settings.getProperty("board", "Not selected");
                        profile = settings.getProperty("zew.buildConfiguration", "default");
                        build = ProjectContext.buildDirectory(selected, settings);
                    } catch (Exception unavailable) {
                        warning = "Project environment: " + unavailable.getMessage();
                    }
                    var snapshot = ConfigurationStudioFiles.inspect(app, build, profile);
                    Path effective = build == null ? null : build.resolve("zephyr/.config");
                    Path generated = build == null ? null : build.resolve("zephyr/zephyr.dts");
                    String config = effective != null && Files.isRegularFile(effective)
                            && Files.size(effective) <= 2_000_000 ? Files.readString(effective) : "";
                    boolean stale = generated == null || !Files.isRegularFile(generated)
                            || newer(app.resolve("prj.conf"), effective)
                            || newer(app.resolve("app.overlay"), generated);
                    var summary = FocusedConfigurationModel.inspect(
                            board, profile, snapshot.devicetree(), config, stale);
                    observed = new Evidence(board, profile, build, snapshot.devicetree(), config,
                            settings, stale, warning, summary);
                } catch (Exception problem) {
                    observed = new Evidence("Unknown", "Unknown", null, "", "",
                            new Properties(), true, problem.getMessage(),
                            FocusedConfigurationModel.inspect("Unknown", "Unknown", "", "", true));
                }
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                final Evidence result = observed;
                display.asyncExec(() -> {
                    if (ticket == generation.get() && project == selected
                            && header != null && !header.isDisposed()) {
                        evidence = result; render();
                    }
                });
                return Status.OK_STATUS;
            }
        };
        refreshJob.setSystem(true); refreshJob.schedule();
    }

    private void clear(String description) {
        header.setText(description); freshness.setText("Build evidence: missing");
        overviewInfo.setText(description);
        peripheralRows.removeAll(); issueRows.removeAll();
        capacityFlash.setText("Unknown"); capacityRam.setText("Unknown");
        buildDetails.setText(description);
        guideState.setText(description);
    }

    private void render() {
        Evidence found = evidence;
        if (found == null) return;
        var summary = found.overview();
        header.setText(project.getName() + "  •  " + found.board() + "  •  " + found.profile()
                + "  •  Target qualifier: " + qualifier(found.board()));
        freshness.setText(switch (summary.freshness()) {
            case NO_BUILD -> "Build data MISSING";
            case STALE_SNAPSHOT -> "Build data STALE";
            case CURRENT_SNAPSHOT -> "Last generated build available";
        });
        overviewInfo.setText("States describe generated configuration, NOT runtime use."
                + " Capacities are not memory usage."
                + (found.warning().isBlank() ? "" : "\n" + found.warning()));
        capacityFlash.setText(summary.flash().value());
        capacityFlash.setToolTipText(summary.flash().evidence());
        capacityRam.setText(summary.ram().value());
        capacityRam.setToolTipText(summary.ram().evidence());
        peripheralRows.removeAll();
        for (var row : summary.peripherals()) {
            var item = new TableItem(peripheralRows, SWT.NONE);
            item.setText(new String[]{row.category(),
                    FocusedConfigurationModel.display(row.state()), row.nextAction()});
            item.setData(row);
        }
        issueRows.removeAll();
        for (var issue : summary.issues()) {
            var item = new TableItem(issueRows, SWT.NONE);
            item.setText(new String[]{issue.title(), issue.nextAction()});
            item.setData(issue);
        }
        issueInfo.setText(summary.issues().isEmpty()
                ? "No deterministic issue identified; hardware still requires verification."
                : "Select an issue and choose Review issue.");
        controllersForCategory();
        renderGuide();
        buildDetails.setText("SELECTED PROJECT\n\nBoard: " + found.board()
                + "\nSoC/target qualifier: " + qualifier(found.board())
                + "\nProfile: " + found.profile()
                + "\nBuild folder: " + (found.build() == null ? "unavailable" : found.build())
                + "\nGenerated DTS: " + (found.dts().isBlank() ? "missing" : "present")
                + "\nGenerated Kconfig: " + (found.config().isBlank() ? "missing" : "present")
                + "\nBuild freshness: " + summary.freshness()
                + "\nToolchain/SDK: " + found.setup().getProperty("tools.sdk", "not configured")
                + "\nZephyr checkout: " + (found.setup().getProperty("workspace", "").isBlank()
                        ? "not configured" : Path.of(found.setup().getProperty("workspace"), "zephyr"))
                + "\nZephyr revision: not proven by this snapshot; inspect locked West manifest"
                + "\n\nBuild profiles and source revisions are selected independently."
                + "\nAdvanced Kconfig and RTOS options remain accessible below.");
        primary.getParent().layout(true, true);
    }

    private static String qualifier(String board) {
        return board != null && board.contains("/")
                ? board.substring(board.lastIndexOf('/') + 1) : "not specified";
    }

    private void choosePeripheral() {
        if (peripheralRows.getSelectionCount() != 1) {
            MessageDialog.openInformation(getSite().getShell(), "Choose a peripheral",
                    "Select one row in the Overview first.");
            return;
        }
        var row = (FocusedConfigurationModel.Row) peripheralRows.getSelection()[0].getData();
        selectCategory(row.category());
    }

    private void reviewIssue() {
        if (issueRows.getSelectionCount() != 1) {
            MessageDialog.openInformation(getSite().getShell(), "Select an issue",
                    "Select an issue in the Overview before reviewing it.");
            return;
        }
        var row = (FocusedConfigurationModel.Issue) issueRows.getSelection()[0].getData();
        issueInfo.setText(row.detail() + "\nNext: " + row.nextAction());
        if (row.category().isBlank()) primary.setSelection(BUILD);
        else selectCategory(row.category());
    }

    private void selectCategory(String type) {
        int index = categories.indexOf(type);
        if (index < 0) return;
        categories.select(index); category = type; controllerPath = ""; step = 0;
        controllersForCategory();
        primary.setSelection(HARDWARE); renderGuide();
    }

    private void controllersForCategory() {
        controllers.removeAll();
        var nodes = evidence == null ? List.<DevicetreeHardware.Device>of()
                : DevicetreeHardware.inspect(evidence.dts());
        int n = 0;
        for (var node : nodes) {
            if (!HardwarePeripheralIndex.category(node).equals(category)) continue;
            controllers.add(node.name() + "  •  " + node.path());
            controllers.setData(Integer.toString(n++), node.path());
        }
        if (n > 0) {
            int selected = 0;
            for (int i = 0; i < n; i++) if (controllerPath.equals(
                    controllers.getData(Integer.toString(i)))) { selected = i; break; }
            controllers.select(selected);
            controllerPath = (String) controllers.getData(Integer.toString(selected));
        } else controllerPath = "";
    }

    private PeripheralStartGuide.Guide guide() {
        var d = evidence;
        return PeripheralStartGuide.inspect(d == null ? "" : d.dts(), category,
                controllerPath, d == null ? "" : d.config(), d == null || d.stale());
    }

    private String detailsForChannel() {
        if (evidence == null || evidence.dts().isBlank() || controllerPath.isBlank())
            return "Channel and pin evidence unavailable.";
        var out = new StringBuilder();
        for (var p : DevicetreeParameters.inspect(evidence.dts()))
            if ((p.path().startsWith(controllerPath + "/channel@")
                    || p.path().equals("/zephyr,user"))
                    && List.of("reg","io-channels","zephyr,gain","zephyr,reference",
                            "zephyr,resolution","zephyr,acquisition-time").contains(p.name()))
                out.append(p.path()).append("  •  ").append(p.name())
                        .append(" = ").append(p.value()).append('\n');
        return out.isEmpty() ? "No verified ADC channel or consumer link." : out.toString();
    }

    private String detailsForSettings() {
        if (evidence == null || controllerPath.isBlank()) return "Build first.";
        var out = new StringBuilder();
        for (var p : DevicetreeParameters.inspect(evidence.dts()))
            if ((p.path().equals(controllerPath)
                    || p.path().startsWith(controllerPath + "/channel@"))
                    && List.of("status","pinctrl-0","clocks","interrupts",
                            "zephyr,gain","zephyr,reference","zephyr,resolution",
                            "zephyr,acquisition-time").contains(p.name()))
                out.append(p.name()).append(" = ").append(p.value()).append('\n');
        return out.isEmpty() ? "No verified configurable values found." : out.toString();
    }

    private void renderGuide() {
        if (stepHost == null || stepHost.isDisposed()) return;
        var d = evidence; var g = guide();
        var labels = category.equals("ADC") ? ADC_STEPS : BUS_STEPS;
        breadcrumb.setText("Hardware  /  " + category + "  /  " + labels[step]
                + "  •  " + (step + 1) + " of 4");
        guideState.setText(g.state() + "  •  " + g.explanation());
        pageText[0].setText("CONTROLLER\n\n" + g.steps()
                + "\n\nSelected generated node: "
                + (controllerPath.isBlank() ? "(none)" : controllerPath));
        pageText[1].setText(category.equals("ADC")
                ? "PIN & CHANNEL\n\n" + FocusedConfigurationModel.pinEvidence(g)
                + "\n\n" + detailsForChannel()
                : connectionsHelp(category) + "\n\n" + g.explanation());
        pageText[2].setText("SETTINGS\n\n" + detailsForSettings()
                + "\n\nDriver/Kconfig evidence:\n" + g.configExample()
                + "\n\nValues can only be edited through an exact binding-guided request.");
        pageText[3].setText("REVIEW PROPOSED CHANGES\n\n"
                + "Current controller: " + (controllerPath.isBlank() ? "(none)" : controllerPath)
                + "\n\nNew source changes: NONE proposed yet.\n\n"
                + "Use the existing verified change editor to preview the exact overlay diff, "
                + "run scratch-only Zephyr configure, and explicitly save with backup."
                + "\nPhysical pins and resource conflicts are NOT verified here; no "
                + "automatic pinmux or channel assignment will be invented."
                + "\nAfter a source save: rebuild the selected profile; generated values "
                + "stay stale until that build completes.");
        stepLayout.topControl = pages[step];
        stepHost.layout(true, true);
        previous.setEnabled(step > 0);
        next.setEnabled(step < 3);
        validateApply.setVisible(step == 3);
        validateApply.setEnabled(step == 3 && d != null && !d.stale()
                && !controllerPath.isBlank());
        example.setEnabled(FocusedConfigurationModel.canOfferAdcCode(g,
                d == null || d.stale(), d == null ? "" : d.config()));
    }

    private static String connectionsHelp(String type) {
        return switch (type) {
            case "UART" -> "TX/RX: verify pinctrl, voltage and wiring; then driver and baud.";
            case "I2C" -> "SDA/SCL: verify pull-ups, pinctrl and client bus address (not CPU address).";
            case "SPI" -> "SCK/MOSI/MISO/CS: verify pinctrl, ownership, clock and CS mapping.";
            case "CAN" -> "Verify transceiver, termination, clocks, pinctrl and bitrate.";
            case "GPIO" -> "Logical GPIO lines are NOT physical package pads. Verify pin metadata.";
            default -> "Inspect verified binding and board wiring for this peripheral.";
        };
    }

    private void memoryMap() {
        if (project == null || evidence == null) return;
        try {
            var part = getSite().getPage().showView(SocMemoryMapView.ID);
            if (part instanceof SocMemoryMapView map)
                map.displayProject(project, evidence.dts(), evidence.build(), evidence.stale());
        } catch (PartInitException e) {
            MessageDialog.openError(getSite().getShell(), "Memory map", e.getMessage());
        }
    }

    /** Dedicated advanced tools retain existing preview, real CMake and backup gates. */
    private void advanced(String destination) {
        if (project == null || !project.isAccessible()) {
            MessageDialog.openInformation(getSite().getShell(), "Select a project",
                    "Choose a Zephyr project to use this tool.");
            return;
        }
        try {
            var part = getSite().getPage().showView(ConfigurationStudioView.ID);
            if (part instanceof ConfigurationStudioView old) {
                old.displayProject(project,
                        List.of("pins","sources","guide","review","rtos").contains(destination),
                        null);
                old.openFocusedTool(destination, category, controllerPath);
            }
        } catch (PartInitException e) {
            MessageDialog.openError(getSite().getShell(), "Advanced tools", e.getMessage());
        }
    }

    @Override public void setFocus() {
        if (primary != null && !primary.isDisposed()) primary.setFocus();
    }
    @Override public void dispose() {
        generation.incrementAndGet();
        if (refreshJob != null) refreshJob.cancel();
        super.dispose();
    }
}
