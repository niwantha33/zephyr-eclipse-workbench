// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.regex.Pattern;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.jface.text.contentassist.*;
import org.eclipse.jface.text.hyperlink.*;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import io.github.niwantha33.zew.core.DevicetreeOverlaySupport;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.text.*;
import org.eclipse.jface.text.presentation.*;
import org.eclipse.jface.text.rules.*;
import org.eclipse.jface.text.source.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.*;
import org.eclipse.ui.editors.text.TextEditor;

/**
 * The normal editor for .overlay/.dts/.dtsi. CDT remains the C/C++ editor.
 * Cursor text comes from the unsaved IDocument; hardware facts are from the
 * selected edtlib index, never a guessed SoC, runtime state or unrelated SDK.
 */
public final class DevicetreeEditor extends TextEditor {
    private volatile DevicetreeIndex.Snapshot snapshot =
            DevicetreeIndex.empty("loading", "Indexing the selected Zephyr project…");
    private Job indexJob;
    private volatile DevicetreeOverlaySupport.Context overlayContext;
    private Job diagnosticJob;
    private final AtomicLong diagnosticGeneration = new AtomicLong();
    private final Map<Annotation, Position> shownDiagnostics = new LinkedHashMap<>();
    private IDocument observedDocument;
    private volatile boolean disposed;

    private final IDocumentListener diagnosticListener = new IDocumentListener() {
        @Override public void documentAboutToBeChanged(DocumentEvent event) { }
        @Override public void documentChanged(DocumentEvent event) { queueDiagnostics(); }
    };

    @Override public void createPartControl(Composite parent) {
        super.createPartControl(parent);
        observedDocument = getDocumentProvider().getDocument(getEditorInput());
        if (observedDocument != null) observedDocument.addDocumentListener(diagnosticListener);
        queueDiagnostics();
    }

    private void queueDiagnostics() {
        if (disposed || observedDocument == null) return;
        // Snapshot unsaved document text on the caller's UI thread; analyze
        // the actual immutable text in a debounced background Job.
        final String text = observedDocument.get();
        final DevicetreeIndex.Snapshot evidence = snapshot;
        final boolean overlay = getEditorInput() instanceof IFileEditorInput input
                && input.getFile().getName().toLowerCase(Locale.ROOT).endsWith(".overlay");
        final long ticket = diagnosticGeneration.incrementAndGet();
        if (diagnosticJob != null) diagnosticJob.cancel();
        diagnosticJob = new Job("Inspect unsaved Devicetree syntax") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                var findings = new ArrayList<>(DevicetreeEditorDiagnostics.inspect(text, evidence, overlay));
                var context = overlayContext;
                if (overlay && context != null && context.verified()
                        && getEditorInput() instanceof IFileEditorInput input
                        && !context.active(input.getFile().getLocation().toFile().toPath())) {
                    findings.add(new DevicetreeEditorDiagnostics.Problem(0, Math.min(1, text.length()),
                            "warning", context.explanation(input.getFile().getLocation().toFile().toPath())));
                }
                if (monitor.isCanceled() || ticket != diagnosticGeneration.get())
                    return Status.CANCEL_STATUS;
                Display.getDefault().asyncExec(() -> {
                    if (!disposed && ticket == diagnosticGeneration.get())
                        showDiagnostics(findings);
                });
                return Status.OK_STATUS;
            }
        };
        diagnosticJob.setSystem(true);
        diagnosticJob.setPriority(Job.DECORATE);
        diagnosticJob.schedule(280);
    }

    private void showDiagnostics(List<DevicetreeEditorDiagnostics.Problem> findings) {
        if (disposed) return;
        IAnnotationModel model = getDocumentProvider().getAnnotationModel(getEditorInput());
        if (model == null) return;
        Map<Annotation, Position> next = new LinkedHashMap<>();
        // Silence advisory hints. Keep actual errors visible as annotations,
        // but do not create completions or show text/annotation hover popups.
        for (var problem : findings) {
            if (!"error".equals(problem.severity()) && !"warning".equals(problem.severity())) continue;
            String type = "error".equals(problem.severity())
                    ? "org.eclipse.ui.workbench.texteditor.error"
                    : "org.eclipse.ui.workbench.texteditor.warning";
            Annotation annotation = new Annotation(type, false, problem.message());
            next.put(annotation, new Position(problem.offset(), problem.length()));
        }
        if (model instanceof IAnnotationModelExtension extension) {
            extension.replaceAnnotations(shownDiagnostics.keySet().toArray(Annotation[]::new), next);
        } else {
            for (Annotation old : shownDiagnostics.keySet()) model.removeAnnotation(old);
            next.forEach(model::addAnnotation);
        }
        shownDiagnostics.clear();
        shownDiagnostics.putAll(next);
        // The annotation is the only visual diagnostic: do not overwrite the
        // workbench status line with live guidance or hover-style messages.
        var status = getEditorSite().getActionBars().getStatusLineManager();
        status.setErrorMessage(null);
        status.setMessage(null);
    }


    public DevicetreeEditor() {
        setSourceViewerConfiguration(new Configuration(this));
    }
    @Override public void init(IEditorSite site, IEditorInput input) throws PartInitException {
        super.init(site, input);
        if (input instanceof IFileEditorInput fileInput) {
            IFile file = fileInput.getFile();
            indexJob = new Job("Devicetree bindings — " + file.getProject().getName()) {
                @Override protected IStatus run(IProgressMonitor monitor) {
                    try {
                        var settings = ProjectContext.settings(file.getProject());
                        if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                        Path zephyr = Path.of(settings.getProperty("workspace"), "zephyr");
                        Path application = file.getProject().getLocation().toFile().toPath();
                        Path build = ProjectContext.buildDirectory(file.getProject(), settings);
                        Path script = Path.of(FileLocator.toFileURL(
                                Activator.getDefault().getBundle().getEntry("scripts/dt_editor_index.py")).toURI());
                        var result = DevicetreeIndex.load(Path.of(settings.getProperty("python")),
                                script, zephyr, application, build);
                        var context = DevicetreeOverlaySupport.inspect(application, build, zephyr);
                        if (!monitor.isCanceled()) {
                            snapshot = result;
                            overlayContext = context;
                            Display.getDefault().asyncExec(() -> {
                                if (!disposed) queueDiagnostics();
                            });
                        }
                        return Status.OK_STATUS;
                    } catch (Exception error) {
                        if (!monitor.isCanceled())
                            snapshot = DevicetreeIndex.empty("unavailable",
                                    "Choose a ready environment and board in Project Properties > Zephyr");
                        return Status.OK_STATUS;
                    }
                }
            };
            indexJob.setSystem(true);
            indexJob.setPriority(Job.DECORATE);
            indexJob.schedule();
        } else snapshot = DevicetreeIndex.empty("unavailable", "Select a workspace project and board");
    }
    @Override public void dispose() {
        disposed = true;
        diagnosticGeneration.incrementAndGet();
        if (indexJob != null) indexJob.cancel();
        if (diagnosticJob != null) diagnosticJob.cancel();
        if (observedDocument != null) observedDocument.removeDocumentListener(diagnosticListener);
        shownDiagnostics.clear();
        super.dispose();
    }
    private DevicetreeIndex.Snapshot index() { return snapshot; }

    private static final class Configuration extends SourceViewerConfiguration {
        private final DevicetreeEditor editor;
        Configuration(DevicetreeEditor editor) { this.editor = editor; }
        // No auto completion, Ctrl+Space proposals, context information or
        // pop-up hints. Typing and syntax colouring stay fully functional.
        @Override public IContentAssistant getContentAssistant(ISourceViewer sourceViewer) {
            if (!editor.preference("completion")) return null;
            var assistant = new ContentAssistant();
            assistant.setContentAssistProcessor(new Processor(editor), IDocument.DEFAULT_CONTENT_TYPE);
            // Completion is explicitly requested with Ctrl+Space; typing does
            // not cause pop-ups even if a project opts into completion.
            assistant.enableAutoActivation(false);
            return assistant;
        }
        @Override public ITextHover getTextHover(ISourceViewer sourceViewer, String contentType) {
            if (!editor.preference("hover")) return null;
            return new ITextHover() {
                @Override public IRegion getHoverRegion(ITextViewer viewer, int offset) {
                    try {
                        var text = viewer.getDocument();
                        int start = offset;
                        while (start > 0 && word(text.getChar(start - 1))) start--;
                        int end = offset;
                        while (end < text.getLength() && word(text.getChar(end))) end++;
                        return new Region(start, end - start);
                    } catch (BadLocationException invalid) { return new Region(offset, 0); }
                }
                @Override public String getHoverInfo(ITextViewer viewer, IRegion region) {
                    try {
                        String name = viewer.getDocument().get(region.getOffset(), region.getLength());
                        if (region.getOffset() > 0 && viewer.getDocument().getChar(region.getOffset() - 1) == '&') {
                            var node = editor.snapshot.label(name);
                            if (node != null) return "Generated node: " + node.path()
                                    + "\nStatus: " + node.status() + "\nCompatible: " + node.compatible()
                                    + "\nLast selected build; not a runtime hardware state.";
                        }
                        return null;
                    } catch (BadLocationException invalid) { return null; }
                }
            };
        }
        @Override public IAnnotationHover getAnnotationHover(ISourceViewer sourceViewer) {
            return null; // No unsolicited annotation hints or hover pop-ups.
        }
        @Override public IHyperlinkDetector[] getHyperlinkDetectors(ISourceViewer sourceViewer) {
            return new IHyperlinkDetector[] { new DefinitionLinks(editor) };
        }
        private static boolean word(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == ',';
        }
        @Override public IPresentationReconciler getPresentationReconciler(ISourceViewer sourceViewer) {
            Color comment = Display.getDefault().getSystemColor(SWT.COLOR_DARK_GREEN);
            Color literal = Display.getDefault().getSystemColor(SWT.COLOR_DARK_BLUE);
            Color directive = Display.getDefault().getSystemColor(SWT.COLOR_DARK_MAGENTA);
            var scanner = new RainbowBracketScanner();
            var keyword = new Token(new TextAttribute(directive, null, SWT.BOLD));
            var words = new WordRule(new IWordDetector() {
                @Override public boolean isWordStart(char c) { return Character.isLetter(c) || c == '#' || c == '/'; }
                @Override public boolean isWordPart(char c) {
                    return Character.isLetterOrDigit(c) || c == '-' || c == ',' || c == '#' || c == '/';
                }
            });
            for (String value : List.of("status", "compatible", "reg", "pinctrl-0",
                    "/dts-v1/", "/delete-node/", "/delete-property/", "#include"))
                words.addWord(value, keyword);
            // Use SWT system colours so no resource cleanup is required.
            int[] colours = {SWT.COLOR_DARK_MAGENTA, SWT.COLOR_DARK_CYAN,
                    SWT.COLOR_DARK_BLUE, SWT.COLOR_DARK_GREEN,
                    SWT.COLOR_DARK_RED, SWT.COLOR_DARK_YELLOW};
            IToken[] bracketTokens = new IToken[colours.length];
            for (int i = 0; i < colours.length; i++)
                bracketTokens[i] = new Token(new TextAttribute(
                        Display.getDefault().getSystemColor(colours[i]), null, SWT.BOLD));
            IRule bracketRule = input -> {
                int depth = scanner.bracketLevel(scanner.getTokenOffset());
                if (depth < 0) return Token.UNDEFINED;
                if (input.read() == ICharacterScanner.EOF) return Token.UNDEFINED;
                return bracketTokens[depth % bracketTokens.length];
            };
            scanner.setRules(new IRule[] {
                    new MultiLineRule("/*", "*/", new Token(new TextAttribute(comment))),
                    new EndOfLineRule("//", new Token(new TextAttribute(comment))),
                    new SingleLineRule("\"", "\"", new Token(new TextAttribute(literal)), '\\'),
                    bracketRule,
                    words
            });
            var repairer = new DefaultDamagerRepairer(scanner);
            var reconciler = new PresentationReconciler();
            reconciler.setDamager(repairer, IDocument.DEFAULT_CONTENT_TYPE);
            reconciler.setRepairer(repairer, IDocument.DEFAULT_CONTENT_TYPE);
            return reconciler;
        }

    }

    // Project-owned, explicit editor settings. Existing projects remain
    // silent: both switches default to false and no global settings change.
    private boolean preference(String name) {
        if (!(getEditorInput() instanceof IFileEditorInput input)) return false;
        Path prefs = input.getFile().getProject().getLocation().toFile().toPath()
                .resolve(".zew/devicetree-editor.properties");
        if (!Files.isRegularFile(prefs) || Files.isSymbolicLink(prefs)) return false;
        try {
            var properties = new Properties();
            try (var reader = Files.newBufferedReader(prefs, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            return "true".equals(properties.getProperty(name, "false").strip());
        } catch (Exception invalid) { return false; }
    }

    private static final class Processor implements IContentAssistProcessor {
        private final DevicetreeEditor editor;
        Processor(DevicetreeEditor editor) { this.editor = editor; }
        @Override public ICompletionProposal[] computeCompletionProposals(ITextViewer viewer, int offset) {
            var result = DevicetreeAssist.suggest(viewer.getDocument().get(), offset, editor.snapshot);
            var proposals = new ArrayList<ICompletionProposal>();
            for (var entry : result.suggestions())
                proposals.add(new CompletionProposal(entry.replacement(), result.start(),
                        result.length(), entry.replacement().length(), null,
                        entry.label(), null, entry.help()));
            return proposals.toArray(ICompletionProposal[]::new);
        }
        @Override public IContextInformation[] computeContextInformation(ITextViewer viewer, int offset) {
            return new IContextInformation[0];
        }
        @Override public char[] getCompletionProposalAutoActivationCharacters() { return null; }
        @Override public char[] getContextInformationAutoActivationCharacters() { return null; }
        @Override public String getErrorMessage() { return null; }
        @Override public IContextInformationValidator getContextInformationValidator() { return null; }
    }

    /** Ctrl+click from &label to its verified base/include declaration. */
    private static final class DefinitionLinks implements IHyperlinkDetector {
        private final DevicetreeEditor editor;
        DefinitionLinks(DevicetreeEditor editor) { this.editor = editor; }
        @Override public IHyperlink[] detectHyperlinks(ITextViewer viewer, IRegion region,
                boolean canShowMultipleHyperlinks) {
            var snapshot = editor.snapshot;
            if (!snapshot.built()) return null;
            try {
                IDocument text = viewer.getDocument();
                int offset = Math.max(0, Math.min(region.getOffset(), text.getLength()));
                int start = offset;
                while (start > 0 && Character.isLetterOrDigit(text.getChar(start - 1))
                        || start > 0 && text.getChar(start - 1) == '_') start--;
                int end = offset;
                while (end < text.getLength() && (Character.isLetterOrDigit(text.getChar(end))
                        || text.getChar(end) == '_')) end++;
                if (start == 0 || start >= end || text.getChar(start - 1) != '&') return null;
                String label = text.get(start, end - start);
                var node = snapshot.label(label);
                if (node == null) return null;
                IRegion selected = new Region(start - 1, end - start + 1);
                return new IHyperlink[] { new IHyperlink() {
                    @Override public IRegion getHyperlinkRegion() { return selected; }
                    @Override public String getTypeLabel() { return "Zephyr Devicetree"; }
                    @Override public String getHyperlinkText() {
                        return "Open built node declaration: " + node.path();
                    }
                    @Override public void open() { editor.openDefinition(label, node); }
                }};
            } catch (BadLocationException invalid) { return null; }
        }
    }

    private void openDefinition(String label, DevicetreeIndex.Node node) {
        try {
            Path source = snapshot.dts();
            int line = Math.max(1, node.line());
            var context = overlayContext;
            if (context != null && context.verified()) {
                var definition = Pattern.compile("(?m)^\\s*"
                        + Pattern.quote(label) + "\\s*:\\s*[^\\s{]+\\s*\\{");
                for (Path candidate : context.sourceFiles()) {
                    if (!Files.isRegularFile(candidate) || Files.isSymbolicLink(candidate)
                            || Files.size(candidate) > 2_000_000) continue;
                    String sourceText = Files.readString(candidate, StandardCharsets.UTF_8);
                    var match = definition.matcher(sourceText);
                    if (!match.find()) continue;
                    source = candidate;
                    line = 1 + (int) sourceText.substring(0, match.start()).chars()
                            .filter(ch -> ch == '\n').count();
                    break;
                }
            }
            if (source == null || !Files.isRegularFile(source)) return;
            var part = IDE.openEditorOnFileStore(getSite().getPage(),
                    EFS.getLocalFileSystem().getStore(source.toUri()));
            if (part instanceof ITextEditor textEditor) {
                var document = textEditor.getDocumentProvider()
                        .getDocument(textEditor.getEditorInput());
                if (document != null && document.getNumberOfLines() > 0)
                    textEditor.selectAndReveal(document.getLineOffset(
                            Math.min(Math.max(0, line - 1), document.getNumberOfLines() - 1)), 0);
            }
        } catch (Exception failure) {
            // Broken/stale source data must never fabricate a destination.
        }
    }

    /**
     * Bracket colours come from balanced nesting in the actual document, not
     * a guessed board/binding. A full lexical pass is necessary so repainting
     * just one line never changes the bracket-pair colours.
     */
    private static final class RainbowBracketScanner extends RuleBasedScanner {
        private byte[] levels = new byte[0];

        @Override public void setRange(IDocument document, int offset, int length) {
            levels = bracketLevels(document.get());
            super.setRange(document, offset, length);
        }
        int bracketLevel(int offset) {
            return offset >= 0 && offset < levels.length ? levels[offset] : -1;
        }
    }

    /**
     * Return nesting depths for punctuation in Devicetree source. -1 means
     * ordinary text, including all quoted strings and comments. A matching
     * opener and closer receive exactly the same palette index. No edits.
     */
    private static byte[] bracketLevels(String source) {
        if (source == null || source.length() > 2_000_000) return new byte[0];
        byte[] result = new byte[source.length()];
        Arrays.fill(result, (byte) -1);
        var opens = new ArrayDeque<Integer>();
        boolean blockComment = false, lineComment = false, quoted = false;
        boolean character = false, escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (lineComment) {
                if (ch == '\n') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (ch == '*' && next == '/') { blockComment = false; i++; }
                continue;
            }
            if (quoted || character) {
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (quoted && ch == '"' || character && ch == '\'') {
                    quoted = false;
                    character = false;
                }
                continue;
            }
            if (ch == '/' && next == '*') { blockComment = true; i++; continue; }
            if (ch == '/' && next == '/') { lineComment = true; i++; continue; }
            if (ch == '"') { quoted = true; continue; }
            if (ch == '\'') { character = true; continue; }
            if (ch == '{' || ch == '(' || ch == '[' || ch == '<') {
                result[i] = (byte) (opens.size() % 6);
                opens.push(i);
            } else if (ch == '}' || ch == ')' || ch == ']' || ch == '>') {
                if (!opens.isEmpty()) {
                    int opening = opens.peek();
                    char partner = source.charAt(opening);
                    if (partner == '{' && ch == '}' || partner == '(' && ch == ')'
                            || partner == '[' && ch == ']' || partner == '<' && ch == '>') {
                        result[i] = result[opens.pop()];
                    }
                }
            }
        }
        return result;
    }
}
