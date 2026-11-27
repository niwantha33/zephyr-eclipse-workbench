// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * DTS Topology: offline, modern Eclipse-embedded read-only view of
 * generated Devicetree. It never contacts GDB, probes, vendor CDNs or targets.
 * The legacy SWT Graph Studio remains available until separately qualified.
 */
public final class HardwareStudioView extends ViewPart {
    public static final String ID = Activator.ID + ".hardwareStudio";
    public static final String PHYSICAL_ID = Activator.ID + ".physicalHardwareStudio";
    private boolean physical;
    private String selectionOrigin() { return physical ? "physical-hardware-studio" : "hardware-studio"; }
    private final AtomicInteger generation = new AtomicInteger();
    private Browser browser;
    private Label fallback;
    private Button fallbackAction;
    private Button fallbackOverlay;
    private String template;
    private AutoCloseable contextSubscription;
    private Job job;
    private IProject project;
    private Path build;
    private String focus = "";
    private String theme = "dark";
    private String boardName = "";
    private String profileName = "";
    private DtsGraphModel.Snapshot snapshot;
    private boolean staleSnapshot = true;

    @Override
    public void createPartControl(Composite parent) {
        physical = PHYSICAL_ID.equals(getViewSite().getId());
        parent.setLayout(new GridLayout(1, false));
        try {
            var entry = Activator.getDefault().getBundle().getEntry(physical ? "hardware-studio/package.html" : "hardware-studio/index.html");
            if (entry == null) throw new IOException("Packaged DTS Topology interface not found");
            try (var stream = entry.openStream()) {
                byte[] bytes = stream.readNBytes(180_001);
                if (bytes.length > 180_000) throw new IOException("DTS Topology interface too large");
                template = new String(bytes, StandardCharsets.UTF_8);
            }
            browser = new Browser(parent, SWT.NONE);
            browser.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            browser.addLocationListener(new org.eclipse.swt.browser.LocationAdapter() {
                @Override public void changing(org.eclipse.swt.browser.LocationEvent event) {
                    // The UI is inline and offline. Never navigate to remote or
                    // local-file pages that could acquire the privileged bridge.
                    if (event.location != null && !event.location.isBlank()
                            && !"about:blank".equalsIgnoreCase(event.location))
                        event.doit = false;
                }
            });
            // Exact, minimal command channel; there is no general file/URL/JS executor.
            new BrowserFunction(browser, "zewHardwareAction") {
                @Override public Object function(Object[] arguments) {
                    if (arguments == null || arguments.length != 2
                            || !(arguments[0] instanceof String action)
                            || !(arguments[1] instanceof String path))
                        return "DENIED";
                    if (!java.util.Set.of("refresh", "focus", "source", "graph", "theme", "topology", "overlay").contains(action)
                            || ("refresh".equals(action) && !path.isEmpty())
                            || ("overlay".equals(action) && (physical || !path.isEmpty() || project == null
                                    || !project.isAccessible()))
                            || ("theme".equals(action) && !"dark".equals(path) && !"light".equals(path))
                            || (!java.util.Set.of("theme", "refresh", "overlay").contains(action)
                                    && verifiedNode(path) == null))
                        return "DENIED";
                    IProject actionProject = project;
                    browser.getDisplay().asyncExec(() -> {
                        if (browser == null || browser.isDisposed()) return;
                        switch (action) {
                            case "refresh" -> refreshAfterBuild(project);
                            case "focus" -> focus(path);
                            case "source" -> openSource(path);
                            case "graph" -> openClassicGraph(path);
                            case "topology" -> openTopology(path);
                            case "overlay" -> {
                                if (java.util.Objects.equals(project, actionProject)) openApplicationOverlay();
                            }
                            case "theme" -> theme = path;
                            default -> { /* guarded above */ }
                        }
                    });
                    return "QUEUED";
                }
            };
            render(DtsGraphModel.unavailable(
                    "Select a Zephyr project in Project Explorer to open DTS sources and build evidence."), "");
        } catch (SWTError | IOException | RuntimeException unavailable) {
            if (browser != null && !browser.isDisposed()) browser.dispose();
            browser = null;
            fallback = new Label(parent, SWT.WRAP);
            fallback.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            fallback.setText("DTS Topology's embedded browser is unavailable. "
                    + "The existing native DTS Graph Studio still works. "
                    + "On Windows check WebView2; on Linux check WebKitGTK. "
                    + unavailable.getClass().getSimpleName());
            fallbackAction = new Button(parent, SWT.PUSH);
            fallbackAction.setText("Open classic Devicetree Graph Studio");
            fallbackAction.addListener(SWT.Selection, event -> openClassicGraph(focus));
            fallbackAction.setEnabled(false);
            if (!physical) {
                fallbackOverlay = new Button(parent, SWT.PUSH);
                fallbackOverlay.setText("Edit app.overlay");
                fallbackOverlay.setEnabled(false);
                fallbackOverlay.addListener(SWT.Selection, event -> openApplicationOverlay());
            }
        }
        contextSubscription = ZephyrViewContextHub.forWindow(getSite().getWorkbenchWindow())
                .subscribe(this::onSelection);
    }

    /** Resolve the current profile while retaining source access for unconfigured projects. */
    void displayCurrentProject(IProject selected) {
        try {
            Path current = selected == null ? null
                    : ProjectContext.buildDirectory(selected, ProjectContext.settings(selected)).toAbsolutePath().normalize();
            displayProject(selected, current, selected != null && selected.equals(project)
                    && current != null && current.equals(build) ? focus : null);
        } catch (Exception unconfigured) {
            displayProject(selected, null, null);
        }
    }

    void refreshAfterBuild(IProject built) {
        if (built != null && built.equals(project)) displayCurrentProject(built);
    }

    /** Explicit exact-build handoff from Hardware Studio. */
    public void displayProject(IProject selected, Path selectedBuild, String preferredNode) {
        if (selected == null || !selected.isAccessible() || selectedBuild == null) {
            generation.incrementAndGet();
            if (job != null) job.cancel();
            project = selected != null && selected.isAccessible() ? selected : null;
            build = null;
            boardName = "";
            profileName = "";
            snapshot = null;
            staleSnapshot = true;
            focus = "";
            render(DtsGraphModel.unavailable(project == null ? "Select a Zephyr project in Project Explorer."
                    : "Select a board and build profile to generate the Devicetree topology."), "");
            return;
        }
        project = selected;
        build = selectedBuild.toAbsolutePath().normalize();
        focus = preferredNode == null ? "" : preferredNode;
        load();
    }

    private void onSelection(ZephyrViewContextHub.Selection choice) {
        if (choice == null || choice.project() == null || !choice.project().isAccessible()) return;
        boolean changed = project != choice.project() || build == null
                || !build.equals(choice.identity().buildDirectory());
        if (changed) {
            displayProject(choice.project(), choice.identity().buildDirectory(),
                    choice.identity().nodePath());
        } else if (!choice.identity().nodePath().isBlank()
                && snapshot != null && snapshot.observed()
                && snapshot.nodes().stream().anyMatch(n -> n.path().equals(choice.identity().nodePath()))
                && !selectionOrigin().equals(choice.origin())) {
            focus = choice.identity().nodePath();
            render(snapshot, focus);
        }
    }

    private void load() {
        if (project == null || !project.isAccessible() || build == null) return;
        int token = generation.incrementAndGet();
        if (job != null) job.cancel();
        final IProject selected = project;
        final Path selectedBuild = build;
        final org.eclipse.swt.widgets.Display uiDisplay = getSite().getShell().getDisplay();
        final String selectedFocus = focus;
        snapshot = null;
        staleSnapshot = true;
        boardName = "";
        profileName = "";
        render(DtsGraphModel.unavailable("Inspecting selected generated Devicetree…"), "");
        job = new Job("DTS Topology · inspect selected Devicetree") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                DtsGraphModel.Snapshot observed;
                String foundBoard = "";
                String foundProfile = "";
                try {
                    Properties settings = ProjectContext.settings(selected);
                    Path expected = ProjectContext.buildDirectory(selected, settings)
                            .toAbsolutePath().normalize();
                    if (!expected.equals(selectedBuild))
                        throw new IOException("Selected build does not match current board/profile");
                    foundBoard = settings.getProperty("board", "");
                    foundProfile = ProjectContext.profileIdentity(settings);
                    String workspace = settings.getProperty("workspace");
                    String python = settings.getProperty("python");
                    var entry = Activator.getDefault().getBundle().getEntry("scripts/dts_graph_observe.py");
                    if (workspace == null || python == null || entry == null)
                        throw new IOException("Missing managed Zephyr checkout, Python or bundled observer");
                    Path script = Path.of(FileLocator.toFileURL(entry).toURI());
                    observed = DtsGraphLoader.load(Path.of(python), script,
                            Path.of(workspace, "zephyr"),
                            selected.getLocation().toFile().toPath(), selectedBuild,
                            monitor::isCanceled);
                } catch (Exception unavailable) {
                    observed = DtsGraphModel.unavailable(
                            "Devicetree inspection unavailable: " + unavailable.getClass().getSimpleName());
                }
                if (monitor.isCanceled() || generation.get() != token) return Status.CANCEL_STATUS;
                final var result = observed;
                final boolean resultStale = isStale(result, selected, selectedBuild);
                final String resultBoard = foundBoard;
                final String resultProfile = foundProfile;
                uiDisplay.asyncExec(() -> {
                    if (generation.get() != token || project != selected
                            || !selectedBuild.equals(build) || getSite().getShell().isDisposed())
                        return;
                    snapshot = result;
                    staleSnapshot = resultStale;
                    boardName = resultBoard;
                    profileName = resultProfile;
                    render(result, selectedFocus);
                });
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.schedule();
    }

    /** Deterministic real UI smoke entry: snapshot is synthetic only in tests. */
    public void displayObservation(DtsGraphModel.Snapshot observation, String selectedFocus) {
        generation.incrementAndGet();
        if (job != null) job.cancel();
        snapshot = observation;
        staleSnapshot = true; // synthetic installed-smoke evidence is never a fresh real build.
        focus = selectedFocus == null ? "" : selectedFocus;
        render(snapshot, focus);
    }

    private void render(DtsGraphModel.Snapshot evidence, String selectedFocus) {
        if (fallbackAction != null && !fallbackAction.isDisposed())
            fallbackAction.setEnabled(project != null && build != null);
        if (fallbackOverlay != null && !fallbackOverlay.isDisposed())
            fallbackOverlay.setEnabled(project != null && project.isAccessible());
        if (browser == null || browser.isDisposed() || template == null) return;
        String projectName = project == null ? "" : project.getName();
        String buildName = build == null ? "" : build.toString();
        try {
            String document = HardwareStudioDocument.render(template, evidence, projectName,
                    buildName, selectedFocus,
                    staleSnapshot && evidence != null && evidence.observed(),
                    theme, boardName, profileName, physical);
            browser.setText(document);
        } catch (RuntimeException invalid) {
            browser.setText("<html><body><h2>DTS Topology unavailable</h2>"
                    + "<p>Invalid evidence or HTML model. Existing Graph Studio remains available.</p>"
                    + "</body></html>");
        }
    }

    private static boolean isStale(DtsGraphModel.Snapshot evidence,
                                   IProject selectedProject, Path selectedBuild) {
        if (evidence == null || !evidence.observed()) return false;
        if (selectedProject == null || selectedBuild == null || evidence.generated() == null)
            return true;
        try {
            Path root = selectedProject.getLocation().toFile().toPath().toRealPath();
            Path buildRoot = selectedBuild.toRealPath();
            Path generated = evidence.generated().toRealPath();
            if (!generated.equals(buildRoot.resolve("zephyr/zephyr.dts"))
                    || !Files.isRegularFile(generated) || Files.size(generated) > 4_194_304)
                return true;
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(generated)));
            if (!actual.equals(evidence.sha256())) return true;
            var builtAt = Files.getLastModifiedTime(generated);
            Path config = root.resolve("prj.conf");
            if (Files.isRegularFile(config)
                    && Files.getLastModifiedTime(config).compareTo(builtAt) > 0) return true;
            int count = 0;
            try (var overlays = Files.newDirectoryStream(root, "*.overlay")) {
                for (Path overlay : overlays) {
                    if (++count > 500 || Files.isSymbolicLink(overlay)
                            || Files.getLastModifiedTime(overlay).compareTo(builtAt) > 0)
                        return true;
                }
            }
            return false;
        } catch (Exception unverified) {
            // Unknown freshness is never silently promoted to verified evidence.
            return true;
        }
    }

    private DtsGraphModel.Node verifiedNode(String path) {
        if (snapshot == null || !snapshot.observed() || path == null) return null;
        return snapshot.nodes().stream().filter(node -> node.path().equals(path))
                .findFirst().orElse(null);
    }

    private void focus(String path) {
        if (verifiedNode(path) == null) return;
        focus = path;
        render(snapshot, path);
        if (project != null && build != null)
            ZephyrViewContextHub.forWindow(getSite().getWorkbenchWindow())
                    .publish(project, build, path, selectionOrigin());
    }

    private void openTopology(String path) {
        if (project == null || build == null || verifiedNode(path) == null) return;
        focus(path);
        try {
            var part = getSite().getPage().showView(ID);
            if (part instanceof HardwareStudioView view)
                view.displayProject(project, build, path);
        } catch (PartInitException ignored) { /* optional topology view */ }
    }

    private void openApplicationOverlay() {
        if (project != null && project.isAccessible())
            ConfigurationStudioView.openOverlay(project, "app.overlay", getSite().getPage(), getSite().getShell());
    }

    private void openClassicGraph(String path) {
        if (project == null || build == null) return;
        try {
            var part = getSite().getPage().showView(DtsGraphView.ID);
            if (part instanceof DtsGraphView view)
                view.displayProject(project, build, verifiedNode(path) == null ? null : path);
        } catch (PartInitException ignored) {
            // Classic Graph Studio remains independently installable and optional.
        }
    }

    /**
     * Generated source only: re-check project containment, read bounds, digest,
     * and stale overlay before navigating. Never edit or guess package wiring.
     */
    private void openSource(String path) {
        DtsGraphModel.Node node = verifiedNode(path);
        if (node == null || project == null || build == null || snapshot.generated() == null)
            return;
        try {
            Path root = project.getLocation().toFile().toPath().toRealPath();
            Path selectedBuild = build.toRealPath();
            Path source = snapshot.generated().toRealPath();
            if (!selectedBuild.startsWith(root)
                    || !source.equals(selectedBuild.resolve("zephyr/zephyr.dts"))
                    || !Files.isRegularFile(source) || Files.size(source) > 4_194_304
                    || isStale(snapshot, project, build)) return;
            byte[] content = Files.readAllBytes(source);
            if (content.length > 4_194_304) return;
            String fingerprint = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
            if (!fingerprint.equals(snapshot.sha256())) return;
            String relative = root.relativize(source).toString().replace('\\', '/');
            IFile file = project.getFile(relative);
            if (!file.exists()) project.refreshLocal(IResource.DEPTH_INFINITE, null);
            if (!file.exists()) return;
            var editor = IDE.openEditor(getSite().getPage(), file);
            if (editor instanceof ITextEditor textEditor) {
                try {
                    int start = textEditor.getDocumentProvider().getDocument(
                            editor.getEditorInput()).getLineOffset(node.line() - 1);
                    textEditor.selectAndReveal(start, 0);
                } catch (BadLocationException changed) {
                    // Open editor but never select a guessed line.
                }
            }
        } catch (Exception unavailable) {
            // Read-only navigation must never interrupt Eclipse.
        }
    }

    @Override public void setFocus() {
        if (browser != null && !browser.isDisposed()) browser.setFocus();
        else if (fallbackAction != null && !fallbackAction.isDisposed()) fallbackAction.setFocus();
    }

    @Override public void dispose() {
        generation.incrementAndGet();
        if (job != null) job.cancel();
        if (contextSubscription != null) {
            try { contextSubscription.close(); }
            catch (Exception ignored) { /* UI-thread subscription only */ }
        }
        super.dispose();
    }
}
