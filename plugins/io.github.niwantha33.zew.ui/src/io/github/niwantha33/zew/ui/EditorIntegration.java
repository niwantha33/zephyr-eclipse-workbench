// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.*;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.MessageConsole;
import io.github.niwantha33.zew.core.EditorSettings;
import io.github.niwantha33.zew.core.ProcessRunner;

public final class EditorIntegration {
    public static final Object FAMILY = new Object();
    private EditorIntegration() {}
    public static void refresh(IProject project, Properties settings, Map<String, String> environment,
            IProgressMonitor monitor, Consumer<String> output) throws Exception {
        if (Platform.getBundle("org.eclipse.cdt.core") == null) {
            output.accept("C/C++ indexing requires Eclipse CDT. Use Eclipse IDE for Embedded C/C++ Developers, then refresh editor settings."); return;
        }
        Path application = project.getLocation().toFile().toPath();
        Path build = ProjectContext.buildDirectory(project, settings);
        if (!Files.isRegularFile(build.resolve("compile_commands.json"))) {
            output.accept("Editor settings need a compilation database. Build this project once with the updated workbench."); return;
        }
        var helper = Path.of(FileLocator.toFileURL(Activator.getDefault().getBundle().getEntry("tools/editor_settings.py")).toURI());
        Path result = build.resolve("editor-settings.tsv");
        new ProcessRunner().run(List.of(settings.getProperty("python"), helper.toString(), "--project", application.toString(),
                "--build", build.toString(), "--sdk", settings.getProperty("tools.sdk"), "--output", result.toString()),
                application, Duration.ofMinutes(3), monitor::isCanceled, output, environment).requireSuccess();
        if (Files.size(result) > EditorSettings.MAX_BYTES) throw new java.io.IOException("Editor settings exceed size limit");
        var parsed = EditorSettings.parse(Files.readString(result, StandardCharsets.UTF_8));
        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
        CdtEditor.apply(project, parsed, monitor);
        output.accept("C/C++ editor settings refreshed from " + parsed.sources().size() + " application source file(s). CDT indexing scheduled.");
    }
    public static void schedule(IProject project) {
        var job = new WorkspaceJob("Refresh Zephyr C/C++ editor — " + project.getName()) {
            @Override public boolean belongsTo(Object family) { return family == FAMILY; }
            @Override public IStatus runInWorkspace(IProgressMonitor monitor) {
                var console = new MessageConsole("Zephyr Editor — " + project.getName(), null);
                ConsolePlugin.getDefault().getConsoleManager().addConsoles(new org.eclipse.ui.console.IConsole[] {console});
                ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
                try (var stream = console.newMessageStream()) {
                    var settings = ProjectContext.settings(project);
                    refresh(project, settings, ProjectContext.environment(settings), monitor, stream::println);
                    return Status.OK_STATUS;
                } catch (java.io.InterruptedIOException error) { return Status.CANCEL_STATUS; }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); return Status.CANCEL_STATUS; }
                catch (Exception | LinkageError error) { return new Status(IStatus.ERROR, Activator.ID, "Could not refresh C/C++ editor: " + error.getMessage(), error); }
            }
        };
        // CDT nature/configuration changes require the workspace root rule.
        // Queue separately so a builder never tries to expand its project rule.
        job.setRule(org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot()); job.setUser(true); job.schedule();
    }
}
