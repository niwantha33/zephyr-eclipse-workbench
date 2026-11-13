// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import java.lang.reflect.Proxy;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.ui.ILaunchShortcut2;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.FileEditorInput;
import org.osgi.framework.Bundle;

/** Installed Eclipse regression: resolve launch context without programming a target. */
public final class DebugLaunchSelectionSmoke {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        System.out.println("PASS: " + message);
    }

    public static void run(Bundle ui, IProject project) throws Exception {
        var resolver = ui.loadClass("io.github.niwantha33.zew.ui.SelectedProject")
                .getDeclaredMethod("forLaunch", ISelection.class, IEditorPart.class);
        resolver.setAccessible(true);
        var input = new FileEditorInput(project.getFile("src/main.c"));
        var editor = (IEditorPart) Proxy.newProxyInstance(DebugLaunchSelectionSmoke.class.getClassLoader(),
                new Class<?>[] {IEditorPart.class},
                (proxy, method, args) -> method.getName().equals("getEditorInput") ? input : null);
        check(project.equals(resolver.invoke(null, new TextSelection(0, 0), editor)),
                "editor text selection resolves its current file's imported Zephyr project");
        check(project.equals(resolver.invoke(null, StructuredSelection.EMPTY, editor)),
                "empty selection falls back to the current editor");
        check(project.equals(resolver.invoke(null, null, editor)),
                "missing selection falls back to the current editor");
        check(resolver.invoke(null, StructuredSelection.EMPTY, null) == null,
                "no context never guesses a workspace project or previous launch");
        var externalInput = (org.eclipse.ui.IEditorInput) Proxy.newProxyInstance(
                DebugLaunchSelectionSmoke.class.getClassLoader(), new Class<?>[] {org.eclipse.ui.IEditorInput.class},
                (proxy, method, args) -> null);
        var externalEditor = (IEditorPart) Proxy.newProxyInstance(DebugLaunchSelectionSmoke.class.getClassLoader(),
                new Class<?>[] {IEditorPart.class},
                (proxy, method, args) -> method.getName().equals("getEditorInput") ? externalInput : null);
        check(resolver.invoke(null, new TextSelection(0, 0), externalEditor) == null,
                "unmapped external editor never guesses a Zephyr project");
        var adaptedInput = (org.eclipse.ui.IEditorInput) Proxy.newProxyInstance(
                DebugLaunchSelectionSmoke.class.getClassLoader(), new Class<?>[] {org.eclipse.ui.IEditorInput.class},
                (proxy, method, args) -> method.getName().equals("getAdapter")
                        && args[0] == org.eclipse.core.resources.IFile.class ? project.getFile("src/main.c") : null);
        var adaptedEditor = (IEditorPart) Proxy.newProxyInstance(DebugLaunchSelectionSmoke.class.getClassLoader(),
                new Class<?>[] {IEditorPart.class},
                (proxy, method, args) -> method.getName().equals("getEditorInput") ? adaptedInput : null);
        check(project.equals(resolver.invoke(null, new TextSelection(0, 0), adaptedEditor)),
                "editor inputs adapting only to IFile resolve their imported project");
        check(resolver.invoke(null, new StructuredSelection(new Object[] {project, project.getFile("src/main.c")}), editor) == null,
                "multiple explicit targets remain ambiguous despite an open editor");
        var ordinary = ResourcesPlugin.getWorkspace().getRoot().getProject("UnmanagedLaunchSelection");
        check(resolver.invoke(null, new StructuredSelection(ordinary), editor) == null,
                "explicit unmanaged target never falls back to another editor's project");
        var other = ResourcesPlugin.getWorkspace().getRoot().getProject("OtherLaunchSelection");
        check(!other.exists(), "second launch-selection test project starts absent");
        other.create(null);
        try {
            other.open(null);
            other.setPersistentProperty(new QualifiedName("io.github.niwantha33.zew.ui", "setupRoot"), "test-only");
            check(other.equals(resolver.invoke(null, new StructuredSelection(other.getFile("other.c")), editor)),
                    "explicit project B source wins over project A's active editor");
            other.close(null);
            check(resolver.invoke(null, new StructuredSelection(other), editor) == null,
                    "closed explicit project never redirects to the editor's project");
            other.open(null);
            if (Boolean.getBoolean("zew.nativeFlow")) {
                var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                org.eclipse.swt.widgets.Display.getDefault().syncExec(() -> {
                    try { nativeEditor(ui, project, other); }
                    catch (Throwable error) { failure.set(error); }
                });
                if (failure.get() != null) throw new IllegalStateException("Native editor launch selection failed", failure.get());
            }
        } finally { other.delete(true, null); }
    }

    private static void nativeEditor(Bundle ui, IProject project, IProject other) throws Exception {
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        var previous = page.getActivePart();
        var editor = org.eclipse.ui.ide.IDE.openEditor(page, project.getFile("src/main.c"), true);
        try {
            page.activate(editor);
            var provider = editor.getSite().getSelectionProvider();
            provider.setSelection(new TextSelection(0, 0));
            var shortcut = (ILaunchShortcut2) ui.loadClass("io.github.niwantha33.zew.ui.ZephyrDebugShortcut")
                    .getConstructor().newInstance();
            check(project.equals(shortcut.getLaunchableResource(provider.getSelection())),
                    "installed Debug As resolves a real active editor's text selection");
            check(project.equals(shortcut.getLaunchableResource(editor)),
                    "installed Debug As editor overload resolves the same project");
            check(other.equals(shortcut.getLaunchableResource(new StructuredSelection(other))),
                    "installed shortcut preserves explicit selection over the active editor");
            check(shortcut.getLaunchableResource(new StructuredSelection(new Object[] {project, other})) == null,
                    "installed shortcut refuses multiple selected projects");
            check(shortcut.getLaunchConfigurations(provider.getSelection()).length == 0,
                    "editor Debug As cannot reuse an old attach-only launch");
            var manager = DebugPlugin.getDefault().getLaunchManager();
            var type = manager.getLaunchConfigurationType("io.github.niwantha33.zew.ui.launchType");
            var copy = type.newInstance(null, manager.generateLaunchConfigurationName("Unsaved editor defaults smoke"));
            var tab = (org.eclipse.debug.ui.AbstractLaunchConfigurationTab) ui.loadClass(
                    "io.github.niwantha33.zew.ui.ZephyrLaunchTabs$MainTab")
                    .getConstructor(boolean.class).newInstance(true);
            try {
                tab.setDefaults(copy);
                check(project.getName().equals(copy.getAttribute("io.github.niwantha33.zew.ui.launch.project", "")),
                        "new Debug configuration inherits the current editor's Zephyr project");
                check(java.util.Arrays.equals(copy.getMappedResources(), new org.eclipse.core.resources.IResource[] {project}),
                        "new Debug configuration maps the selected project for native launch association");
                copy.setAttribute("io.github.niwantha33.zew.ui.launch.project", other.getName());
                var host = new org.eclipse.swt.widgets.Shell(page.getWorkbenchWindow().getShell());
                try {
                    tab.createControl(host);
                    tab.initializeFrom(copy);
                    var field = tab.getClass().getDeclaredField("project");
                    field.setAccessible(true);
                    check(other.getName().equals(((org.eclipse.swt.widgets.Text) field.get(tab)).getText()),
                            "existing configuration retains its saved project when a different file is active");
                } finally { host.dispose(); }
            } finally { tab.dispose(); }
        } finally {
            if (previous != null) page.activate(previous);
        }
    }
}
