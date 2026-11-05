// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Path;
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
                var findings = DevicetreeEditorDiagnostics.inspect(text, evidence, overlay);
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
            if (!"error".equals(problem.severity())) continue;
            Annotation annotation = new Annotation(
                    "org.eclipse.ui.workbench.texteditor.error", false, problem.message());
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
                        if (!monitor.isCanceled()) {
                            snapshot = result;
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
        @Override public org.eclipse.jface.text.contentassist.IContentAssistant getContentAssistant(
                ISourceViewer sourceViewer) { return null; }
        @Override public ITextHover getTextHover(ISourceViewer sourceViewer, String contentType) {
            return null;
        }
        @Override public IAnnotationHover getAnnotationHover(ISourceViewer sourceViewer) {
            return null;
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
