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
 * Hardware Studio 0.1: offline, modern Eclipse-embedded read-only view of
 * generated Devicetree. It never contacts GDB, probes, vendor CDNs or targets.
 * The legacy SWT Graph Studio remains available until separately qualified.
 */
public final class HardwareStudioView extends ViewPart {
    public static final String ID = Activator.ID + ".hardwareStudio";
    private final AtomicInteger generation = new AtomicInteger();
    private Browser browser;
    private Label fallback;
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

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));
        try {
            var entry = Activator.getDefault().getBundle().getEntry("hardware-studio/index.html");
            if (entry == null) throw new IOException("Packaged Hardware Studio interface not found");
            try (var stream = entry.openStream()) {
                byte[] bytes = stream.readNBytes(180_001);
                if (bytes.length > 180_000) throw new IOException("Hardware Studio interface too large");
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
                    if (!java.util.Set.of("refresh", "focus", "source", "graph", "theme").contains(action)
                            || ("refresh".equals(action) && !path.isEmpty())
                            || ("theme".equals(action) && !"dark".equals(path) && !"light".equals(path))
                            || (!"theme".equals(action) && !"refresh".equals(action)
                                    && verifiedNode(path) == null))
                        return "DENIED";
                    browser.getDisplay().asyncExec(() -> {
                        if (browser == null || browser.isDisposed()) return;
                        switch (action) {
                            case "refresh" -> load();
                            case "focus" -> focus(path);
                            case "source" -> openSource(path);
                            case "graph" -> openClassicGraph(path);
                            case "theme" -> theme = path;
                            default -> { /* guarded above */ }
                        }
                    });
                    return "QUEUED";
                }
            };
            render(DtsGraphModel.unavailable(
                    "Open Configuration Studio → Hardware, choose a project/board/profile and build first."), "");
        } catch (SWTError | IOException | RuntimeException unavailable) {
            if (browser != null && !browser.isDisposed()) browser.dispose();
            browser = null;
            fallback = new Label(parent, SWT.WRAP);
            fallback.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            fallback.setText("Hardware Studio's embedded browser is unavailable. "
                    + "The existing native DTS Graph Studio still works. "
                    + "On Windows check WebView2; on Linux check WebKitGTK. "
                    + unavailable.getClass().getSimpleName());
            Button classic = new Button(parent, SWT.PUSH);
            classic.setText("Open classic Devicetree Graph Studio");
            classic.addListener(SWT.Selection, event -> openClassicGraph(focus));
        }
        contextSubscription = ZephyrViewContextHub.forWindow(getSite().getWorkbenchWindow())
                .subscribe(this::onSelection);
    }

    /** Explicit exact-build handoff from Configuration Studio; no guessed global build. */
    public void displayProject(IProject selected, Path selectedBuild, String preferredNode) {
        if (selected == null || !selected.isAccessible() || selectedBuild == null) {
            generation.incrementAndGet();
            if (job != null) job.cancel();
            project = null;
            build = null;
            boardName = "";
            profileName = "";
            snapshot = null;
            focus = "";
            render(DtsGraphModel.unavailable("Choose an open Zephyr project and configured build."), "");
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
                && !"hardware-studio".equals(choice.origin())) {
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
        final String selectedFocus = focus;
        snapshot = null;
        boardName = "";
        profileName = "";
        render(DtsGraphModel.unavailable("Inspecting selected generated Devicetree…"), "");
        job = new Job("Hardware Studio · inspect selected Devicetree") {
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
                            selected.getLocation().toFile().toPath(), selectedBuild);
                } catch (Exception unavailable) {
                    observed = DtsGraphModel.unavailable(
                            "Devicetree inspection unavailable: " + unavailable.getClass().getSimpleName());
                }
                if (monitor.isCanceled() || generation.get() != token) return Status.CANCEL_STATUS;
                final var result = observed;
                final String resultBoard = foundBoard;
                final String resultProfile = foundProfile;
                getSite().getShell().getDisplay().asyncExec(() -> {
                    if (generation.get() != token || project != selected
                            || !selectedBuild.equals(build) || getSite().getShell().isDisposed())
                        return;
                    snapshot = result;
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
        focus = selectedFocus == null ? "" : selectedFocus;
        render(snapshot, focus);
    }

    private void render(DtsGraphModel.Snapshot evidence, String selectedFocus) {
        if (browser == null || browser.isDisposed() || template == null) return;
        String projectName = project == null ? "" : project.getName();
        String buildName = build == null ? "" : build.toString();
        try {
            String document = HardwareStudioDocument.render(template, evidence, projectName,
                    buildName, selectedFocus, isStale(evidence), theme, boardName, profileName);
            browser.setText(document);
        } catch (RuntimeException invalid) {
            browser.setText("<html><body><h2>Hardware Studio unavailable</h2>"
                    + "<p>Invalid evidence or HTML model. Existing Graph Studio remains available.</p>"
                    + "</body></html>");
        }
    }

    private boolean isStale(DtsGraphModel.Snapshot evidence) {
        if (evidence == null || !evidence.observed()) return false;
        if (project == null || build == null || evidence.generated() == null) return true;
        try {
            Path root = project.getLocation().toFile().toPath().toRealPath();
            Path buildRoot = build.toRealPath();
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
                    .publish(project, build, path, "hardware-studio");
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
                    || !Files.isRegularFile(source) || Files.size(source) > 2_000_000
                    || isStale(snapshot)) return;
            byte[] content = Files.readAllBytes(source);
            if (content.length > 2_000_000) return;
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
        else if (fallback != null && !fallback.isDisposed()) fallback.setFocus();
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
