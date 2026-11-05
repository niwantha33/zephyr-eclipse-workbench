// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Path;
import java.util.*;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.text.*;
import org.eclipse.jface.text.contentassist.*;
import org.eclipse.jface.text.presentation.*;
import org.eclipse.jface.text.rules.*;
import org.eclipse.jface.text.source.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
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
                        if (!monitor.isCanceled()) snapshot = result;
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
        if (indexJob != null) indexJob.cancel();
        super.dispose();
    }
    private DevicetreeIndex.Snapshot index() { return snapshot; }

    private static final class Configuration extends SourceViewerConfiguration {
        private final DevicetreeEditor editor;
        Configuration(DevicetreeEditor editor) { this.editor = editor; }
        @Override public IContentAssistant getContentAssistant(ISourceViewer sourceViewer) {
            var assistant = new ContentAssistant();
            assistant.setContentAssistProcessor(new Processor(editor), IDocument.DEFAULT_CONTENT_TYPE);
            assistant.enableAutoActivation(true);
            assistant.setAutoActivationDelay(350);
            assistant.setProposalPopupOrientation(IContentAssistant.PROPOSAL_OVERLAY);
            assistant.setContextInformationPopupOrientation(IContentAssistant.CONTEXT_INFO_ABOVE);
            return assistant;
        }
        @Override public IPresentationReconciler getPresentationReconciler(ISourceViewer sourceViewer) {
            Color comment = Display.getDefault().getSystemColor(SWT.COLOR_DARK_GREEN);
            Color literal = Display.getDefault().getSystemColor(SWT.COLOR_DARK_BLUE);
            Color directive = Display.getDefault().getSystemColor(SWT.COLOR_DARK_MAGENTA);
            var scanner = new RuleBasedScanner();
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
            scanner.setRules(new IRule[] {
                    new MultiLineRule("/*", "*/", new Token(new TextAttribute(comment))),
                    new EndOfLineRule("//", new Token(new TextAttribute(comment))),
                    new SingleLineRule("\"", "\"", new Token(new TextAttribute(literal)), '\\'),
                    words
            });
            var repairer = new DefaultDamagerRepairer(scanner);
            var reconciler = new PresentationReconciler();
            reconciler.setDamager(repairer, IDocument.DEFAULT_CONTENT_TYPE);
            reconciler.setRepairer(repairer, IDocument.DEFAULT_CONTENT_TYPE);
            return reconciler;
        }
        @Override public ITextHover getTextHover(ISourceViewer sourceViewer, String contentType) {
            return new ITextHover() {
                @Override public IRegion getHoverRegion(ITextViewer viewer, int offset) {
                    IDocument doc = viewer.getDocument();
                    try {
                        int left = offset, right = offset;
                        while (left > 0 && word(doc.getChar(left - 1))) left--;
                        while (right < doc.getLength() && word(doc.getChar(right))) right++;
                        return new Region(left, right - left);
                    } catch (BadLocationException error) {
                        return new Region(offset, 0);
                    }
                }
                @Override public String getHoverInfo(ITextViewer viewer, IRegion region) {
                    try {
                        String word = viewer.getDocument().get(region.getOffset(), region.getLength());
                        String document = viewer.getDocument().get();
                        var index = editor.index();
                        if (region.getOffset() > 0 && document.charAt(region.getOffset() - 1) == '&') {
                            var node = index.label(word);
                            if (node != null) return node.path() + "\nGenerated status: " + node.status()
                                    + "\nCompatible: " + node.compatible()
                                    + "\nFrom the selected build, not hardware runtime evidence.";
                        }
                        if ("status".equals(word))
                            return "Controls the Devicetree status of a node. status = \"okay\" does not prove driver initialisation or runtime use.";
                        var header = java.util.regex.Pattern.compile("&([A-Za-z_][A-Za-z0-9_]*)\\s*\\{")
                                .matcher(document.substring(0, region.getOffset()));
                        String path = null;
                        while (header.find()) {
                            var node = index.label(header.group(1));
                            if (node != null) path = node.path();
                        }
                        for (var property : index.properties(path)) if (property.name().equals(word))
                            return property.description() + "\nType: " + property.type() +
                                    (property.required() ? " (required)" : " (optional)") +
                                    "\nSource: " + property.source() +
                                    "\nBuilt snapshot; not validation of unsaved edits.";
                        return null;
                    } catch (BadLocationException error) { return null; }
                }
            };
        }
        private static boolean word(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == ',' || c == '#' || c == '.';
        }
    }
    private static final class Processor implements IContentAssistProcessor {
        private final DevicetreeEditor editor;
        Processor(DevicetreeEditor editor) { this.editor = editor; }
        @Override public ICompletionProposal[] computeCompletionProposals(ITextViewer viewer, int offset) {
            var result = DevicetreeAssist.suggest(viewer.getDocument().get(), offset, editor.index());
            var proposals = new ArrayList<ICompletionProposal>();
            for (var entry : result.suggestions()) {
                proposals.add(new CompletionProposal(entry.replacement(), result.start(),
                        result.length(), entry.replacement().length(), null,
                        entry.label(), null, entry.help()));
            }
            return proposals.toArray(ICompletionProposal[]::new);
        }
        @Override public IContextInformation[] computeContextInformation(ITextViewer viewer, int offset) {
            return new IContextInformation[0];
        }
        @Override public char[] getCompletionProposalAutoActivationCharacters() {
            return new char[]{'&', '=', '{'};
        }
        @Override public char[] getContextInformationAutoActivationCharacters() { return null; }
        @Override public String getErrorMessage() {
            return editor.index().built() ? null :
                    editor.index().explanation() + " — syntax assistance only until an exact build is available.";
        }
        @Override public IContextInformationValidator getContextInformationValidator() { return null; }
    }
}
