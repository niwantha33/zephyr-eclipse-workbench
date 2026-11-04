// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.ScrolledComposite;
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
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.part.ViewPart;
import io.github.niwantha33.zew.core.DevicetreeMemoryMap;
import io.github.niwantha33.zew.core.DevicetreeHardware;
import io.github.niwantha33.zew.core.DevicetreeSourceExplorer;
import io.github.niwantha33.zew.core.DevicetreeSourceTrace;

/**
 * Genuine native Eclipse, project-scoped read-only SoC address map. Memory and
 * peripheral ranges are independently derived from the selected generated DTS;
 * no silicon, SVD, probe, firmware write or speculative pin data is involved.
 */
public final class SocMemoryMapView extends ViewPart {
    public static final String ID = Activator.ID + ".socMemoryMap";
    private IProject project;
    private String generated = "";
    private Path build;
    private boolean stale;
    private DevicetreeMemoryMap.Report report = DevicetreeMemoryMap.inspect("");
    private List<DevicetreeMemoryMap.Region> shown = List.of();
    private DevicetreeMemoryMap.Region selected;
    private DevicetreeHardware.Device selectedNode;
    private Label summary;
    private Combo filter;
    private ScrolledComposite scroll;
    private SocMemoryMapCanvas diagram;
    private Tree topology;
    private Table regions;
    private Text detail, compiled, origin;
    private TabFolder details;
    private Button revealButton, traceButton;

    @Override public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));
        var heading = new Label(parent, SWT.NONE);
        heading.setText("SOC MEMORY MAP  /  BUILT DEVICETREE EVIDENCE");
        var toolbar = new Composite(parent, SWT.NONE);
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        toolbar.setLayout(new GridLayout(4, false));
        filter = new Combo(toolbar, SWT.DROP_DOWN | SWT.READ_ONLY);
        filter.setItems("All regions", "RAM", "Flash", "Peripheral registers",
                "Flash partitions", "Reserved memory", "Other", "Unresolved bus addresses");
        filter.select(0);
        filter.addListener(SWT.Selection, e -> applyFilter());
        revealButton = new Button(toolbar, SWT.PUSH);
        revealButton.setText("Show generated DTS line");
        revealButton.addListener(SWT.Selection, e -> revealCompiled());
        traceButton = new Button(toolbar, SWT.PUSH);
        traceButton.setText("Find original DTS/DTSI reg");
        traceButton.addListener(SWT.Selection, e -> traceOriginal());
        var refresh = new Button(toolbar, SWT.PUSH);
        refresh.setText("Refresh from selected snapshot");
        refresh.addListener(SWT.Selection, e -> render());
        summary = new Label(parent, SWT.WRAP);
        summary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        summary.setText("Select a configured Zephyr project; no silicon address has been assumed.");

        var split = new SashForm(parent, SWT.HORIZONTAL);
        split.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        var visualTabs = new TabFolder(split, SWT.NONE);
        var addressTab = new TabItem(visualTabs, SWT.NONE);
        addressTab.setText("Address map");
        scroll = new ScrolledComposite(visualTabs, SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
        addressTab.setControl(scroll);
        scroll.setExpandHorizontal(true);
        scroll.setExpandVertical(false);
        diagram = new SocMemoryMapCanvas(scroll);
        scroll.setContent(diagram);
        scroll.setMinSize(SocMemoryMapCanvas.WIDTH, diagram.getSize().y);
        diagram.onSelection(this::selectRegion);

        var treeTab = new TabItem(visualTabs, SWT.NONE);
        treeTab.setText("Devicetree hierarchy");
        topology = new Tree(visualTabs, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION
                | SWT.H_SCROLL | SWT.V_SCROLL);
        topology.setHeaderVisible(true);
        topology.setLinesVisible(true);
        for (var headingText : new String[] {"Board / bus / device", "DTS status", "CPU address or bus address"}) {
            var column = new TreeColumn(topology, SWT.LEFT);
            column.setText(headingText);
        }
        topology.getColumn(0).setWidth(300);
        topology.getColumn(1).setWidth(110);
        topology.getColumn(2).setWidth(240);
        topology.addListener(SWT.Selection, event -> {
            if (topology.getSelectionCount() > 0)
                selectNode((DevicetreeHardware.Device) topology.getSelection()[0].getData());
        });
        treeTab.setControl(topology);

        var right = new SashForm(split, SWT.VERTICAL);
        regions = new Table(right, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION
                | SWT.V_SCROLL | SWT.H_SCROLL);
        regions.setHeaderVisible(true);
        regions.setLinesVisible(true);
        for (String caption : new String[] {
                "Kind", "CPU start / bus-relative", "Size", "Node and block"}) {
            var column = new TableColumn(regions, SWT.LEFT);
            column.setText(caption);
        }
        regions.getColumn(0).setWidth(115);
        regions.getColumn(1).setWidth(175);
        regions.getColumn(2).setWidth(100);
        regions.getColumn(3).setWidth(250);
        regions.addListener(SWT.Selection, event -> {
            if (regions.getSelectionCount() != 0)
                selectRegion((DevicetreeMemoryMap.Region) regions.getSelection()[0].getData());
        });
        details = new TabFolder(right, SWT.NONE);
        detail = outputTab(details, "Exact range + status");
        compiled = outputTab(details, "Generated zephyr.dts");
        origin = outputTab(details, "Possible source declarations");
        right.setWeights(new int[] {54, 46});
        split.setWeights(new int[] {49, 51});
        render();
    }

    private static Text outputTab(TabFolder folder, String title) {
        var tab = new TabItem(folder, SWT.NONE);
        tab.setText(title);
        var text = new Text(folder, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY
                | SWT.H_SCROLL | SWT.V_SCROLL);
        tab.setControl(text);
        return text;
    }

    /**
     * Called by Configuration Studio after the user opens this view.
     * Never reads an arbitrary board or stale global "last build".
     */
    public void displayProject(IProject selectedProject, String builtDts, Path selectedBuild,
                               boolean isStale) {
        project = selectedProject;
        generated = builtDts == null ? "" : builtDts;
        build = selectedBuild;
        stale = isStale;
        if (project != null)
            setPartName("SoC Memory Map — " + project.getName());
        render();
    }

    private void render() {
        if (regions == null || regions.isDisposed()) return;
        report = DevicetreeMemoryMap.inspect(generated);
        populateTopology();
        compiled.setText(generated.isBlank()
                ? "No generated zephyr.dts. Build the selected board first."
                : generated);
        filter.select(0);
        applyFilter();
    }

    private void applyFilter() {
        if (regions == null || regions.isDisposed()) return;
        String selection = filter.getText();
        shown = report.regions().stream().filter(r -> switch (selection) {
            case "RAM" -> r.kind() == DevicetreeMemoryMap.Kind.RAM;
            case "Flash" -> r.kind() == DevicetreeMemoryMap.Kind.FLASH;
            case "Peripheral registers" -> r.kind() == DevicetreeMemoryMap.Kind.PERIPHERAL;
            case "Flash partitions" -> r.kind() == DevicetreeMemoryMap.Kind.PARTITION;
            case "Reserved memory" -> r.kind() == DevicetreeMemoryMap.Kind.RESERVED;
            case "Other" -> r.kind() == DevicetreeMemoryMap.Kind.OTHER;
            case "Unresolved bus addresses" -> !r.cpuMapped();
            default -> true;
        }).toList();
        regions.setRedraw(false);
        try {
            regions.removeAll();
            for (var mapped : shown) {
                var item = new TableItem(regions, SWT.NONE);
                item.setText(new String[] {mapped.kind().name(),
                        mapped.cpuMapped() ? mapped.cpuHex() : "BUS " + mapped.localHex(),
                        mapped.sizeLabel(), mapped.path()
                                + (mapped.registerName().isBlank() ? "" : " / " + mapped.registerName())});
                item.setData(mapped);
            }
        } finally { regions.setRedraw(true); }
        diagram.setRegions(shown);
        scroll.setMinSize(SocMemoryMapCanvas.WIDTH, diagram.getSize().y);
        selected = null;
        if (shown.isEmpty()) {
            detail.setText("No decoded regions for this selection. Missing DTS nodes do NOT "
                    + "prove missing silicon hardware.");
            origin.setText("Select a register range and trace its build input to find candidate declarations.");
            revealButton.setEnabled(false);
            traceButton.setEnabled(false);
        } else {
            regions.setSelection(0);
            selectRegion(shown.get(0));
        }
        summary.setText(report.regions().size() + " address region(s) from the selected "
                + "generated DTS | " + report.unmappable() + " bus-relative or unresolved"
                + " | " + report.omitted() + " not safely decoded"
                + (stale ? " | STALE BUILD — rebuild before relying on this map." : "")
                + "\nAddress map: ordered, not to scale. Hierarchy: all parsed DTS nodes, "
                + "including buses and devices without a CPU address. This is not a complete vendor silicon map.");
        summary.getParent().layout(true, true);
    }

    private void populateTopology() {
        topology.setRedraw(false);
        try {
            topology.removeAll();
            if (generated.isBlank()) return;
            Map<String, TreeItem> byPath = new HashMap<>();
            Map<String, DevicetreeMemoryMap.Region> byNode = new HashMap<>();
            for (var region : report.regions())
                byNode.putIfAbsent(region.path(), region);
            for (var node : DevicetreeHardware.inspect(generated)) {
                String path = node.path();
                int slash = path.lastIndexOf('/');
                TreeItem parent = byPath.get(slash <= 0 ? "/" : path.substring(0, slash));
                TreeItem item = parent == null ? new TreeItem(topology, SWT.NONE)
                        : new TreeItem(parent, SWT.NONE);
                var address = byNode.get(path);
                String name = "/".equals(path) ? "Board / root" : node.name();
                if (!node.label().isBlank()) name += "  ·  " + node.label();
                item.setText(new String[] {name,
                        node.status().isBlank() ? "default" : node.status(),
                        address == null ? (node.reg().isBlank() ? "—" : "reg: see details")
                                : address.cpuMapped() ? address.cpuHex() + "  ·  " + address.sizeLabel()
                                        : "bus " + address.localHex() + "  ·  " + address.sizeLabel()});
                item.setData(node);
                byPath.put(path, item);
            }
            for (TreeItem root : topology.getItems()) root.setExpanded(true);
        } finally { topology.setRedraw(true); }
    }

    private void selectNode(DevicetreeHardware.Device node) {
        if (node == null) return;
        selectedNode = node;
        var mapped = report.regions().stream().filter(r -> r.path().equals(node.path()))
                .findFirst().orElse(null);
        if (mapped != null) {
            selectRegion(mapped);
            return;
        }
        selected = null;
        diagram.selectRegion(null);
        traceButton.setEnabled(false);
        detail.setText("DEVICETREE NODE FROM GENERATED BUILD\nPath: " + node.path()
                + "\nStatus: " + (node.status().isBlank() ? "default" : node.status())
                + "\nCompatible: " + (node.compatible().isBlank() ? "not specified" : node.compatible())
                + "\nBuilt DTS declaration line: " + node.line()
                + "\n\nNo safely decoded CPU address exists for this node. A bus, pin, or device"
                + " can be present without a CPU memory range. This is not a full silicon inventory.");
        revealButton.setEnabled(true);
        origin.setText("Select an address range to trace its original reg declaration.");
    }

    private void selectRegion(DevicetreeMemoryMap.Region r) {
        if (r == null || detail == null || detail.isDisposed()) return;
        selected = r;
        selectedNode = null;
        diagram.selectRegion(r);
        for (TableItem row : regions.getItems()) if (row.getData() == r) {
            regions.setSelection(row);
            regions.showSelection();
            break;
        }
        revealButton.setEnabled(true);
        traceButton.setEnabled(project != null && project.getLocation() != null);
        detail.setText("EXACT REG RANGE FROM GENERATED DEVICETREE"
                + "\nType: " + r.kind()
                + "\nNode path: " + r.path()
                + "\nRegister block: " + (r.registerName().isBlank() ? "(unnamed)" : r.registerName())
                + "\nLocal reg address: " + r.localHex()
                + "\nMapped CPU start: " + r.cpuHex()
                + "\nMapped CPU end (exclusive): " + r.endHex()
                + "\nLength: " + r.sizeLabel() + " (" + r.size() + " bytes)"
                + "\nAddress-space origin: " + r.addressSpace()
                + "\nCompatible: " + (r.compatible().isBlank() ? "(not stated)" : r.compatible())
                + "\nNode status: " + r.status()
                + "\nBuilt DTS declaration line: " + r.line()
                + "\nEvidence: " + r.evidence()
                + (stale ? "\nWARNING: application settings changed since this build." : "")
                + "\n\nA reg address is NOT proof of a working driver, live register value,"
                + " allocated code/data, available package pin or full silicon memory capacity.");
        origin.setText("Press Find original DTS/DTSI reg to see candidate source declarations "
                + "for " + r.path() + ". They may not be the winning source assignment.");
    }

    private void revealCompiled() {
        if (selected == null && selectedNode == null) return;
        details.setSelection(1);
        int offset = 0;
        String text = compiled.getText();
        int line = selected != null ? selected.line() : selectedNode.line();
        for (int i = 1; i < line; i++) {
            int next = text.indexOf('\n', offset);
            if (next < 0) return;
            offset = next + 1;
        }
        int end = text.indexOf('\n', offset);
        compiled.setSelection(offset, end < 0 ? text.length() : end);
        compiled.showSelection();
    }

    private void traceOriginal() {
        if (selected == null || project == null || project.getLocation() == null) return;
        try {
            var sources = DevicetreeSourceExplorer.inspect(
                    project.getLocation().toFile().toPath(), build);
            var trace = DevicetreeSourceTrace.inspect(generated, sources, selected.path(), "reg");
            var text = new StringBuilder("POSSIBLE ORIGINAL reg DECLARATIONS — READ ONLY\n\n");
            for (var hit : trace.hits())
                text.append(hit.source().path()).append(":").append(hit.line())
                        .append("\n  Value: ").append(hit.sourceValue())
                        .append("\n  ").append(hit.evidence()).append("\n\n");
            if (trace.hits().isEmpty())
                text.append("No safe source declaration was found. Includes or macros "
                        + "may prevent matching. No address was guessed.\n\n");
            origin.setText(text.append(trace.notice()).toString());
            details.setSelection(2);
        } catch (Exception unavailable) {
            origin.setText("Cannot read project source dependencies: " + unavailable.getMessage()
                    + "\nThe generated DTS remains the only effective build evidence.");
            details.setSelection(2);
        }
    }

    @Override public void setFocus() {
        if (regions != null && !regions.isDisposed()) regions.setFocus();
    }
}
