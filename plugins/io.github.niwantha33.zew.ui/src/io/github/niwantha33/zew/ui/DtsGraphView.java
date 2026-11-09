// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * DTS Graph Studio: selected-build topology, not hardware truth or live usage.
 * The observer runs on a Job, sourced exclusively from the current managed
 * build and checkout. All UI updates are back on SWT's display thread.
 */
public final class DtsGraphView extends ViewPart {
    public static final String ID = Activator.ID + ".dtsGraph";
    private DtsGraphCanvas graph;
    private Label status;
    private Text query, inspector;
    private Button openSource, openRegisters, openMemory;
    private IProject project;
    private Path build;
    private DtsGraphModel.Snapshot snapshot;
    private DtsGraphModel.Node inspected;
    private String desiredFocus;
    private Job job;
    private AutoCloseable contextSubscription;
    private Button followSelection, pinSelection;
    private boolean receivingSelection;
    private final AtomicInteger generation = new AtomicInteger();

    @Override public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));
        var title = new Label(parent, SWT.NONE);
        title.setText("ZEPHYR DEVICETREE GRAPH STUDIO  /  EXACT GENERATED DTS  /  READ-ONLY");
        title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        var toolbar = new Composite(parent, SWT.NONE);
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        toolbar.setLayout(new GridLayout(10, false));
        Button refresh = button(toolbar, "Refresh selected build", () -> load());
        refresh.setToolTipText("Observe only the exact board/profile used by the selected project.");
        query = new Text(toolbar, SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH);
        query.setMessage("Exact node path or unique text");
        GridData searchSize = new GridData(SWT.FILL, SWT.CENTER, true, false);
        searchSize.widthHint = 260;
        query.setLayoutData(searchSize);
        query.addListener(SWT.DefaultSelection, event -> focusQuery());
        button(toolbar, "Focus", this::focusQuery);
        button(toolbar, "−", () -> graph.zoom(graph.zoomPercent() - 10));
        button(toolbar, "100%", () -> graph.zoom(100));
        button(toolbar, "+", () -> graph.zoom(graph.zoomPercent() + 10));
        openSource = button(toolbar, "Open DTS source", this::openSelectedSource);
        openSource.setEnabled(false);
        followSelection = new Button(toolbar, SWT.CHECK);
        followSelection.setText("Follow Selection");
        followSelection.setSelection(true);
        pinSelection = new Button(toolbar, SWT.CHECK);
        pinSelection.setText("Pin Selection");
        pinSelection.setToolTipText("Keep this graph on the current node, even when another view selects one.");
        button(toolbar, "?", () -> status.setText(
                "Solid = parent-child in generated DTS. Dashed = upstream-resolved reference. "
                + "Neither proves physical wiring or runtime use."));

        var linkedTools = new Composite(parent, SWT.NONE);
        linkedTools.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        linkedTools.setLayout(new GridLayout(3, false));
        openRegisters = button(linkedTools, "Open Hardware Registers", this::openRegisterExplorer);
        openRegisters.setToolTipText("Navigate to the exact project's SVD explorer; "
                + "no register address is inferred and no target is read.");
        openMemory = button(linkedTools, "Open SoC Memory Map", this::openMemoryMap);
        openMemory.setToolTipText("Show build-only memory regions from the SHA-matched generated DTS.");
        openRegisters.setEnabled(false);
        openMemory.setEnabled(false);
        var note = new Label(linkedTools, SWT.WRAP);
        note.setText("Selection is build evidence, not runtime state.");
        note.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        status = new Label(parent, SWT.WRAP);
        status.setText("Select a Zephyr application in Configuration Studio → Hardware, "
                + "then choose DTS Graph.");
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        SashForm sash = new SashForm(parent, SWT.VERTICAL);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        graph = new DtsGraphCanvas(sash, SWT.NONE);
        graph.onInspect(this::inspectNode);
        graph.onOpen(this::openNodeSource);
        inspector = new Text(sash, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        inspector.setText("Click a block to inspect its exact generated DTS path, "
                + "compatible/status, and typed references. Double-click to jump to the declaration.");
        sash.setWeights(new int[] {75, 25});
        contextSubscription = ZephyrViewContextHub.forWindow(getSite().getWorkbenchWindow())
                .subscribe(this::followContext);
    }

    private static Button button(Composite parent, String label, Runnable action) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(label);
        button.addListener(SWT.Selection, event -> action.run());
        return button;
    }

    public void displayProject(IProject target, Path selectedBuild, String focus) {
        if (target == null || !target.isAccessible() || selectedBuild == null) {
            status.setText("No managed application or build selected.");
            graph.message("Select a real Zephyr project and board/profile.");
            return;
        }
        project = target;
        build = selectedBuild;
        desiredFocus = focus;
        ZephyrViewContextHub.forWindow(getSite().getWorkbenchWindow())
                .publish(target, selectedBuild, "", "dts-graph-open");
        load();
    }

    private void load() {
        if (project == null || !project.isAccessible() || build == null) {
            status.setText("Open DTS Graph from Configuration Studio → Hardware.");
            return;
        }
        final IProject selectedProject = project;
        final Path selectedBuild = build;
        final String focus = desiredFocus;
        final int token = generation.incrementAndGet();
        if (job != null) job.cancel();
        status.setText("Reading exact selected-build zephyr.dts with Zephyr dtlib/edtlib…");
        graph.message("Inspecting selected generated Devicetree…");
        openSource.setEnabled(false);
        openRegisters.setEnabled(false);
        openMemory.setEnabled(false);
        job = new Job("Inspect selected Zephyr Devicetree graph") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                DtsGraphModel.Snapshot result;
                try {
                    Properties settings = ProjectContext.settings(selectedProject);
                    Path zephyr = Path.of(settings.getProperty("workspace"), "zephyr");
                    var entry = Activator.getDefault().getBundle()
                            .getEntry("scripts/dts_graph_observe.py");
                    if (entry == null) throw new java.io.IOException("DTS graph observer is not packaged.");
                    Path script = Path.of(FileLocator.toFileURL(entry).toURI());
                    result = DtsGraphLoader.load(Path.of(settings.getProperty("python")),
                            script, zephyr, selectedProject.getLocation().toFile().toPath(), selectedBuild);
                } catch (Exception unavailable) {
                    result = DtsGraphModel.unavailable("Graph inspection is unavailable: "
                            + unavailable.getClass().getSimpleName());
                }
                if (monitor.isCanceled() || generation.get() != token)
                    return Status.CANCEL_STATUS;
                final DtsGraphModel.Snapshot resultToShow = result;
                graph.getDisplay().asyncExec(() -> {
                    if (graph.isDisposed() || generation.get() != token
                            || project != selectedProject || !selectedBuild.equals(build)) return;
                    apply(resultToShow, focus);
                });
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.schedule();
    }

    /** Public for deterministic installed-Eclipse smoke; does not authenticate editable source. */
    public void displayObservation(DtsGraphModel.Snapshot observation, String focus) {
        apply(observation, focus);
    }

    private void apply(DtsGraphModel.Snapshot result, String focus) {
        snapshot = result;
        inspected = null;
        openSource.setEnabled(result != null && result.observed() && project != null);
        openRegisters.setEnabled(false);
        openMemory.setEnabled(false);
        graph.show(result, focus);
        if (result == null || !result.observed()) {
            status.setText("DTS graph unavailable: " + (result == null
                    ? "No observation" : result.explanation()));
            inspector.setText("No verified generated DTS topology is available.");
            return;
        }
        var layout = graph.layoutSnapshot();
        query.setText(layout.focus());
        inspector.setText("Select a block to see exact evidence. Focused neighborhood only; "
                + "dashed links require resolved upstream phandles.");
        boolean stale = false;
        try {
            if (project != null) {
                Path overlay = project.getLocation().toFile().toPath().resolve("app.overlay");
                Path generated = result.generated();
                stale = Files.isRegularFile(overlay) && Files.isRegularFile(generated)
                        && Files.getLastModifiedTime(overlay).compareTo(Files.getLastModifiedTime(generated)) > 0;
            }
        } catch (Exception ignored) {
            stale = true;
        }
        status.setText("DTS " + result.state() + " | "
                + result.nodes().size() + " observed nodes, "
                + result.edges().size() + " verified typed references | Focus: "
                + layout.focus()
                + (stale ? " | BUILD MAY BE STALE: application overlay newer than DTS" : "")
                + " | Runtime wiring/use UNKNOWN. " + layout.message());
    }

    private void focusQuery() {
        if (snapshot == null || !snapshot.observed()) return;
        String term = query.getText().trim();
        DtsGraphModel.Node match = null;
        for (DtsGraphModel.Node node : snapshot.nodes()) {
            if (node.path().equals(term)) {
                match = node;
                break;
            }
            if (!term.isBlank() && (node.path().contains(term) || node.labels().contains(term))) {
                if (match != null) {
                    status.setText("Multiple nodes match. Enter an exact DTS path to focus.");
                    return;
                }
                match = node;
            }
        }
        if (match == null) {
            status.setText("No observed node matches this path/search. Nothing was changed.");
            return;
        }
        desiredFocus = match.path();
        graph.focus(desiredFocus);
        query.setText(desiredFocus);
        inspectNode(match);
    }

    private void inspectNode(DtsGraphModel.Node node) {
        inspected = node;
        boolean available = project != null && build != null && snapshot != null
                && snapshot.observed();
        openRegisters.setEnabled(available);
        openMemory.setEnabled(available);
        StringBuilder text = new StringBuilder("Generated DTS node: ").append(node.path())
                .append("\nDTS status: ").append(node.status())
                .append(" (not a runtime reading)")
                .append("\nCompatible: ").append(node.compatible().isBlank() ? "unspecified" : node.compatible())
                .append("\nNode labels: ").append(node.labels().isBlank() ? "none" : node.labels())
                .append("\nGenerated DTS line: ").append(node.line())
                .append("\n\nExact resolved references originating here:");
        int count = 0;
        if (snapshot != null) for (DtsGraphModel.Edge edge : snapshot.edges()) {
            if (!edge.source().equals(node.path())) continue;
            text.append("\n  ").append(edge.kind()).append("  ")
                    .append(edge.property()).append(" → ").append(edge.target())
                    .append(edge.detail().isBlank() ? "" : " " + edge.detail());
            count++;
        }
        if (count == 0) text.append("\n  None verified (not proof of no hardware links).");
        text.append("\n\nRead-only observed configuration; actual wiring, pinmux and driver state unverified.");
        inspector.setText(text.toString());
        if (!receivingSelection && project != null && build != null
                && snapshot != null && snapshot.observed())
            ZephyrViewContextHub.forWindow(getSite().getWorkbenchWindow())
                    .publish(project, build, node.path(), "dts-graph");
    }

    private void followContext(ZephyrViewContextHub.Selection selected) {
        if (graph == null || graph.isDisposed() || !followSelection.getSelection()
                || pinSelection.getSelection() || project == null || build == null
                || snapshot == null || !snapshot.observed()
                || !new io.github.niwantha33.zew.core.ZephyrSelectionIdentity(
                        project.getName(), build, "").sameBuild(selected.identity())
                || selected.identity().nodePath().isBlank()
                || "dts-graph".equals(selected.origin())) return;
        for (DtsGraphModel.Node node : snapshot.nodes()) {
            if (!node.path().equals(selected.identity().nodePath())) continue;
            desiredFocus = node.path();
            graph.focus(desiredFocus);
            query.setText(desiredFocus);
            receivingSelection = true;
            try { inspectNode(node); }
            finally { receivingSelection = false; }
            return;
        }
    }

    /** Explicit navigation, never a guessed DTS/SVD physical address match. */
    private void openRegisterExplorer() {
        if (inspected == null || project == null || build == null || snapshot == null
                || !snapshot.observed()) return;
        try {
            var view = getSite().getPage().showView(SocRegistersHandler.VIEW_ID,
                    null, org.eclipse.ui.IWorkbenchPage.VIEW_ACTIVATE);
            if (view instanceof SocRegistersView registers)
                registers.inspectDtsSelection(project, build, inspected.path());
        } catch (org.eclipse.ui.PartInitException error) {
            status.setText("Cannot open Hardware Register Explorer: " + error.getMessage());
        }
    }

    /**
     * Only hand build evidence to the memory map if the source is still byte-for-byte
     * identical to the graph observation. Does not access MCU physical memory.
     */
    private void openMemoryMap() {
        if (inspected == null || project == null || build == null || snapshot == null
                || !snapshot.observed() || snapshot.generated() == null) return;
        try {
            Path root = project.getLocation().toFile().toPath().toRealPath();
            Path built = build.toRealPath();
            Path generated = snapshot.generated().toRealPath();
            if (!built.startsWith(root) || !generated.startsWith(built.resolve("zephyr"))
                    || !Files.isRegularFile(generated) || Files.size(generated) > 2_000_000)
                throw new java.io.IOException("Generated DTS is outside the exact selected build or too large");
            byte[] content = Files.readAllBytes(generated);
            if (content.length > 2_000_000) throw new java.io.IOException("Generated DTS exceeds 2 MB");
            String sha256 = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
            if (!sha256.equals(snapshot.sha256())) {
                status.setText("Generated DTS changed since the graph was observed. Refresh selected build first.");
                return;
            }
            Path overlay = root.resolve("app.overlay");
            boolean stale = Files.isRegularFile(overlay)
                    && Files.getLastModifiedTime(overlay).compareTo(
                            Files.getLastModifiedTime(generated)) > 0;
            var view = getSite().getPage().showView(SocMemoryMapView.ID,
                    null, org.eclipse.ui.IWorkbenchPage.VIEW_ACTIVATE);
            if (view instanceof SocMemoryMapView memory)
                memory.displayProject(project, new String(content, StandardCharsets.UTF_8),
                        build, stale);
        } catch (Exception error) {
            status.setText("SoC Memory Map unavailable: " + error.getMessage());
        }
    }

    private void openSelectedSource() {
        if (inspected != null) openNodeSource(inspected);
        else if (snapshot != null && snapshot.observed()) {
            String focus = graph.layoutSnapshot().focus();
            snapshot.nodes().stream().filter(n -> n.path().equals(focus))
                    .findFirst().ifPresent(this::openNodeSource);
        }
    }

    private void openNodeSource(DtsGraphModel.Node node) {
        if (snapshot == null || !snapshot.observed() || project == null
                || snapshot.generated() == null) return;
        try {
            Path root = project.getLocation().toFile().toPath().toAbsolutePath().normalize();
            Path source = snapshot.generated().toAbsolutePath().normalize();
            if (!source.startsWith(root) || !Files.isRegularFile(source)) {
                status.setText("Generated source not inside this project; no file was opened.");
                return;
            }
            String projectRelative = root.relativize(source).toString().replace('\\', '/');
            IFile file = project.getFile(projectRelative);
            if (!file.exists()) {
                project.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE, null);
            }
            if (!file.exists()) {
                status.setText("Generated source has not been refreshed into Eclipse resources.");
                return;
            }
            var editor = IDE.openEditor(getSite().getPage(), file);
            if (editor instanceof ITextEditor textEditor) {
                try {
                    int line = textEditor.getDocumentProvider().getDocument(editor.getEditorInput())
                            .getLineOffset(node.line() - 1);
                    textEditor.selectAndReveal(line, 0);
                } catch (BadLocationException ignored) {
                    status.setText("Node source opened; saved source line has moved.");
                }
            }
        } catch (Exception error) {
            status.setText("Cannot open generated source: " + error.getClass().getSimpleName());
        }
    }

    @Override public void setFocus() {
        if (graph != null && !graph.isDisposed()) graph.setFocus();
    }
    @Override public void dispose() {
        generation.incrementAndGet();
        if (job != null) job.cancel();
        if (contextSubscription != null) {
            try { contextSubscription.close(); }
            catch (Exception ignored) { /* UI-thread subscription has no I/O. */ }
        }
        super.dispose();
    }
}
