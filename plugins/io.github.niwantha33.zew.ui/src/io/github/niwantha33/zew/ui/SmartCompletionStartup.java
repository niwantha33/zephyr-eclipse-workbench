// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.Locale;
import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Opens CDT's existing Content Assist from the first typed identifier letter.
 *
 * Never substitutes a static keyword catalogue for the CDT index. Only an
 * active, editable CDT C/C++ source editor in a Zephyr project is affected.
 * The short delay coalesces quick typing; a changed focus/selection/token
 * cancels the pending request without editing source text.
 */
public final class SmartCompletionStartup implements IStartup {
    private static final int DELAY_MS = 120;
    private static final int MAX_IDENTIFIER_LENGTH = 96;
    private static final String CDT_EDITOR_ID = "org.eclipse.cdt.ui.editor.CEditor";
    private static Listener keyFilter;
    private static Display hookedDisplay;

    @Override public void earlyStartup() {
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.asyncExec(() -> {
            if (display.isDisposed() || keyFilter != null) return;
            keyFilter = event -> onKeyUp(display, event);
            hookedDisplay = display;
            display.addFilter(SWT.KeyUp, keyFilter);
        });
    }

    /** Remove the global SWT hook when this bundle is stopped or upgraded. */
    static void stop() {
        Display display = hookedDisplay;
        Listener listener = keyFilter;
        if (display == null || listener == null || display.isDisposed()) return;
        Runnable remove = () -> {
            if (!display.isDisposed()) display.removeFilter(SWT.KeyUp, listener);
            if (keyFilter == listener) {
                keyFilter = null;
                hookedDisplay = null;
            }
        };
        if (Display.getCurrent() == display) remove.run();
        else display.asyncExec(remove);
    }

    private static void onKeyUp(Display display, Event event) {
        if (!(event.widget instanceof StyledText text) || text.isDisposed()) return;
        if ((event.stateMask & (SWT.CTRL | SWT.ALT | SWT.COMMAND)) != 0) return;
        if (!enabled()) return;

        ITextEditor editor = activeZephyrEditor();
        if (editor == null) return;
        int caret = text.getCaretOffset();
        if (caret < 1 || text.getSelectionCount() != 0) return;
        char inserted = text.getTextRange(caret - 1, 1).charAt(0);
        char preceding = caret > 1 ? text.getTextRange(caret - 2, 1).charAt(0) : '\0';
        if (!SmartCompletionTrigger.shouldOpen(event.character, inserted, preceding)) return;

        final int wordStart = caret - 1;
        // Do not open before the typed text is committed. This also batches
        // rapidly entered characters into one CDT completion request.
        display.timerExec(DELAY_MS, () -> {
            if (display.isDisposed() || text.isDisposed() || keyFilter == null
                    || display.getFocusControl() != text || !enabled()
                    || activeZephyrEditor() != editor || !editor.isEditable()
                    || text.getSelectionCount() != 0) return;
            int end = text.getCaretOffset();
            if (end <= wordStart || end - wordStart > MAX_IDENTIFIER_LENGTH) return;
            if (!SmartCompletionTrigger.isCurrentIdentifier(text.getTextRange(wordStart, end - wordStart))) return;
            ITextOperationTarget target = editor.getAdapter(ITextOperationTarget.class);
            if (target != null && target.canDoOperation(ISourceViewer.CONTENTASSIST_PROPOSALS))
                target.doOperation(ISourceViewer.CONTENTASSIST_PROPOSALS);
        });
    }

    private static boolean enabled() {
        Activator plugin = Activator.getDefault();
        return plugin != null
                && plugin.getPreferenceStore().getBoolean(WorkbenchPreferences.SMART_COMPLETION_ENABLED);
    }

    private static ITextEditor activeZephyrEditor() {
        var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null) return null;
        IWorkbenchPage page = window.getActivePage();
        if (page == null) return null;
        IEditorPart part = page.getActiveEditor();
        if (part == null || page.getActivePart() != part
                || !CDT_EDITOR_ID.equals(part.getEditorSite().getId())
                || !(part instanceof ITextEditor editor)
                || !(part.getEditorInput() instanceof IFileEditorInput input)) return null;
        IFile file = input.getFile();
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".c") || name.endsWith(".h") || name.endsWith(".cc")
                || name.endsWith(".cpp") || name.endsWith(".cxx")
                || name.endsWith(".hh") || name.endsWith(".hpp") || name.endsWith(".hxx"))) return null;
        try {
            return file.getProject().isOpen() && file.getProject().hasNature(ZephyrProjectNature.ID)
                    && editor.isEditable() ? editor : null;
        } catch (org.eclipse.core.runtime.CoreException ignored) {
            return null;
        }
    }
}
