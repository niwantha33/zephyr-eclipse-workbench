// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.eclipse.cdt.core.CCorePlugin;
import org.eclipse.cdt.core.dom.ast.*;
import org.eclipse.cdt.core.index.IndexFilter;
import org.eclipse.cdt.core.model.*;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.equinox.app.*;
import io.github.niwantha33.zew.core.EditorSettings;

/** Test-only bundle: exercise the installed plug-in through real CDT APIs. */
public final class EditorSmoke implements IApplication {
    private static void check(boolean value, String name) { if (!value) throw new AssertionError(name); System.out.println("PASS CDT: " + name); }
    @Override public Object start(IApplicationContext context) throws Exception {
        String[] args = (String[]) context.getArguments().get(IApplicationContext.APPLICATION_ARGS);
        Path fixture = Path.of(args[0]);
        var workspace = ResourcesPlugin.getWorkspace();
        var wd = workspace.getDescription(); wd.setAutoBuilding(false); workspace.setDescription(wd);
        var project = workspace.getRoot().getProject("HelloZephyrEditorSmoke");
        if (!project.exists()) {
            var description = workspace.newProjectDescription(project.getName()); description.setLocationURI(fixture.toUri());
            project.create(description, null); project.open(null);
            var pd = project.getDescription(); var command = pd.newCommand(); command.setBuilderName("io.github.niwantha33.zew.ui.zephyrBuilder");
            pd.setBuildSpec(new ICommand[] {command}); project.setDescription(pd, null);
        }
        project.open(null); project.refreshLocal(IResource.DEPTH_INFINITE, null);
        var ui = Platform.getBundle("io.github.niwantha33.zew.ui");
        ui.start();
        // Test contributions from the actually installed p2 feature, not just
        // plugin.xml in the checkout.
        var registry = Platform.getExtensionRegistry();
        var studioViews = Arrays.stream(registry.getConfigurationElementsFor("org.eclipse.ui.views"));
        check(studioViews.anyMatch(e -> e.getContributor().getName().equals("io.github.niwantha33.zew.ui")
                && "io.github.niwantha33.zew.ui.configurationStudio".equals(e.getAttribute("id"))),
                "installed Configuration Studio view registered");
        var studioMenus = Arrays.stream(registry.getConfigurationElementsFor("org.eclipse.ui.menus"))
                .filter(e -> e.getContributor().getName().equals("io.github.niwantha33.zew.ui"))
                .filter(e -> "popup:org.eclipse.ui.popup.any?after=additions".equals(e.getAttribute("locationURI")))
                .flatMap(e -> Arrays.stream(e.getChildren("command")))
                .filter(e -> "io.github.niwantha33.zew.configurationStudio".equals(e.getAttribute("commandId")))
                .toList();
        check(studioMenus.size() == 1 && studioMenus.get(0).getChildren("visibleWhen").length == 1,
                "installed right-click Configuration Studio command is selection-guarded");
        check(Arrays.stream(registry.getConfigurationElementsFor("org.eclipse.ui.propertyPages"))
                .anyMatch(e -> "io.github.niwantha33.zew.projectProperties".equals(e.getAttribute("id"))),
                "Project settings action resolves the registered Zephyr property page");
        var tester = (org.eclipse.core.expressions.PropertyTester) Platform.getBundle(
                "io.github.niwantha33.zew.ui").loadClass(
                        "io.github.niwantha33.zew.ui.ZephyrProjectTester")
                        .getConstructor().newInstance();
        check(tester.test(project.getFile("prj.conf"), "isConfigurationStudioTarget",
                new Object[0], Boolean.TRUE),
                "right-click prj.conf is offered for an unconfigured Zephyr project");
        check(tester.test(project, "isConfigurationStudioTarget", new Object[0], Boolean.TRUE),
                "right-click Zephyr project is offered without SDK configuration");
        check(!tester.test(project.getFile("src/main.c"), "isConfigurationStudioTarget",
                new Object[0], Boolean.TRUE),
                "Configuration Studio action is excluded from unrelated project source files");
        check(!tester.test(workspace.getRoot().getProject("UnmanagedSmoke"), "isConfigurationStudioTarget",
                new Object[0], Boolean.TRUE),
                "Configuration Studio action is excluded from non-Zephyr projects");
        Path otherLocation = fixture.getParent().resolve("foreign-vendor-zephyr-app");
        Files.createDirectories(otherLocation);
        Files.copy(fixture.resolve("CMakeLists.txt"), otherLocation.resolve("CMakeLists.txt"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(fixture.resolve("prj.conf"), otherLocation.resolve("prj.conf"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        var external = workspace.getRoot().getProject("VendorOwnedZephyrApp");
        if (!external.exists()) {
            var description = workspace.newProjectDescription(external.getName());
            description.setLocationURI(otherLocation.toUri());
            external.create(description, null);
            external.open(null);
        }
        var foreignDescription = external.getDescription();
        var foreignBuilder = foreignDescription.newCommand();
        foreignBuilder.setBuilderName("com.vendor.otherBuilder");
        foreignDescription.setBuildSpec(new ICommand[] {foreignBuilder});
        external.setDescription(foreignDescription, null);
        external.refreshLocal(IResource.DEPTH_INFINITE, null);
        check(tester.test(external.getFile("prj.conf"), "isConfigurationStudioTarget",
                new Object[0], Boolean.TRUE),
                "right-click source configuration is available without replacing vendor build ownership");
        check(!(Boolean) ui.loadClass("io.github.niwantha33.zew.ui.ZephyrProjectLifecycle")
                .getMethod("supports", IProject.class).invoke(null, external),
                "foreign Eclipse builder is not taken over for West build");
        var popupVisibility = org.eclipse.core.expressions.ExpressionConverter.getDefault().perform(
                studioMenus.get(0).getChildren("visibleWhen")[0].getChildren()[0]);
        var popupContext = new org.eclipse.core.expressions.EvaluationContext(null, project.getFile("prj.conf"));
        popupContext.addVariable("activeMenuSelection", java.util.List.of(project.getFile("prj.conf")));
        check(popupVisibility.evaluate(popupContext) == org.eclipse.core.expressions.EvaluationResult.TRUE,
                "installed Eclipse popup expression shows Configuration Studio for prj.conf");
        popupContext.addVariable("activeMenuSelection", java.util.List.of(project.getFile("src/main.c")));
        check(popupVisibility.evaluate(popupContext) == org.eclipse.core.expressions.EvaluationResult.FALSE,
                "installed Eclipse popup expression hides Configuration Studio for unrelated C file");

        if (Boolean.getBoolean("zew.nativeFlow")) {
            org.eclipse.swt.widgets.Display.getDefault().syncExec(() -> {
                try {
                    var windows = org.eclipse.ui.PlatformUI.getWorkbench().getWorkbenchWindows();
                    check(windows.length > 0, "native Eclipse workbench window exists");
                    var view = windows[0].getActivePage().showView(
                            "io.github.niwantha33.zew.ui.configurationStudio");
                    view.getClass().getMethod("displayProject", IProject.class).invoke(view, project);
                    var snapshotField = view.getClass().getDeclaredField("snapshot");
                    snapshotField.setAccessible(true);
                    Object snapshot = snapshotField.get(view);
                    check(snapshot != null, "native Configuration Studio opened unconfigured prj.conf");
                    var entries = (java.util.List<?>) snapshot.getClass().getMethod("options").invoke(snapshot);
                    check(entries.stream().anyMatch(entry -> {
                        try {
                            return "DEBUG_INFO".equals(entry.getClass().getMethod("symbol").invoke(entry));
                        } catch (Exception error) { throw new IllegalStateException(error); }
                    }), "unconfigured Configuration Studio displays invalid source override");
                } catch (Exception error) { throw new IllegalStateException("Native Studio smoke failed", error); }
            });
        }
        var parsed = EditorSettings.parse(Files.readString(fixture.resolve(".zew-build/first/editor-settings.tsv"), StandardCharsets.UTF_8));
        OpenOcdMetadataSmoke.run(ui, fixture);
        if (args.length < 2 || !args[1].equals("verify")) ProjectImportSmoke.run(ui, fixture.getParent());
        if (Boolean.getBoolean("zew.nativeFlow") && (args.length < 2 || !args[1].equals("verify")))
            StandardProjectFlowSmoke.run(ui, fixture.getParent());
        if (Boolean.getBoolean("zew.nativeFlow")) {
            if (args.length < 2 || !args[1].equals("verify")) CoreBuildCompatibilitySmoke.run(ui);
            else CoreBuildCompatibilitySmoke.verifyRestored();
        }
        // Query native shortcuts without starting a debugger or programming hardware.
        var flashShortcut = (org.eclipse.debug.ui.ILaunchShortcut2) ui.loadClass(
                "io.github.niwantha33.zew.ui.ZephyrFlashShortcut").getConstructor().newInstance();
        var debugShortcut = (org.eclipse.debug.ui.ILaunchShortcut2) ui.loadClass(
                "io.github.niwantha33.zew.ui.ZephyrDebugShortcut").getConstructor().newInstance();
        var selection = new org.eclipse.jface.viewers.StructuredSelection(project.getFile("src/main.c"));
        var setupProperty = new QualifiedName("io.github.niwantha33.zew.ui", "setupRoot");
        String savedSetup = project.getPersistentProperty(setupProperty);
        var launchManager = org.eclipse.debug.core.DebugPlugin.getDefault().getLaunchManager();
        var type = launchManager.getLaunchConfigurationType("io.github.niwantha33.zew.ui.launchType");
        org.eclipse.debug.core.ILaunchConfiguration flashConfig = null, debugConfig = null;
        try {
            project.setPersistentProperty(setupProperty, null);
            check(flashShortcut.getLaunchableResource(selection) == null,
                    "ordinary C project is not treated as an imported Zephyr Flash target");
            project.setPersistentProperty(setupProperty, fixture.toString());
            check(Platform.getAdapterManager().hasAdapter(project, "org.eclipse.debug.ui.actions.ILaunchable"),
                    "managed project participates in native launch selection");
            check(project.equals(flashShortcut.getLaunchableResource(selection)),
                    "Flash shortcut resolves editor source selection to imported project");
            var flashCopy = type.newInstance(null, launchManager.generateLaunchConfigurationName("Smoke Flash"));
            flashCopy.setAttribute("io.github.niwantha33.zew.ui.launch.project", project.getName());
            flashCopy.setAttribute("io.github.niwantha33.zew.ui.launch.action", "flash");
            flashConfig = flashCopy.doSave();
            var debugCopy = type.newInstance(null, launchManager.generateLaunchConfigurationName("Smoke Debug"));
            debugCopy.setAttribute("io.github.niwantha33.zew.ui.launch.project", project.getName());
            debugCopy.setAttribute("io.github.niwantha33.zew.ui.launch.action", "debug");
            debugConfig = debugCopy.doSave();
            var launch = new org.eclipse.debug.core.Launch(debugConfig, "debug", null);
            var frame = (org.eclipse.debug.core.model.IDebugElement) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {org.eclipse.debug.core.model.IDebugElement.class},
                    (proxy, method, arguments) -> method.getName().equals("getLaunch") ? launch : null);
            var debugSelection = new org.eclipse.jface.viewers.StructuredSelection(frame);
            check(project.equals(debugShortcut.getLaunchableResource(debugSelection)),
                    "Debug view stack-frame context resolves the managed project without a source editor");
            check(project.equals(flashShortcut.getLaunchableResource(new org.eclipse.jface.viewers.StructuredSelection(launch))),
                    "Flash resolves the selected managed launch instead of guessing a project");
            check(Platform.getAdapterManager().hasAdapter(frame, "org.eclipse.debug.ui.actions.ILaunchable"),
                    "managed debug element is launchable by native toolbar");
            var handlers = Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.handlers");
            var nativeBuild = Arrays.stream(handlers).filter(element -> element.getContributor().getName()
                    .equals("io.github.niwantha33.zew.ui") && "org.eclipse.ui.project.buildProject".equals(
                            element.getAttribute("commandId"))).findFirst().orElseThrow();
            var expression = org.eclipse.core.expressions.ExpressionConverter.getDefault().perform(
                    nativeBuild.getChildren("activeWhen")[0].getChildren()[0]);
            var contextForBuild = new org.eclipse.core.expressions.EvaluationContext(null, frame);
            contextForBuild.addVariable("selection", java.util.List.of(frame));
            check(expression.evaluate(contextForBuild) == org.eclipse.core.expressions.EvaluationResult.TRUE,
                    "native Build handler activates for the managed Debug view selection");
            contextForBuild.addVariable("selection", java.util.List.of(workspace.getRoot().getProject("UnmanagedSmoke")));
            check(expression.evaluate(contextForBuild) == org.eclipse.core.expressions.EvaluationResult.FALSE,
                    "native Build handler does not replace other projects' normal behavior");
            var toolbarCommands = Arrays.stream(Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.menus"))
                    .filter(element -> element.getContributor().getName().equals("io.github.niwantha33.zew.ui")
                            && element.getAttribute("locationURI").startsWith("toolbar:"))
                    .flatMap(element -> Arrays.stream(element.getChildren("toolbar")))
                    .flatMap(element -> Arrays.stream(element.getChildren("command")))
                    .map(element -> element.getAttribute("commandId")).toList();
            check(toolbarCommands.contains("org.eclipse.ui.project.buildProject")
                    && toolbarCommands.contains("io.github.niwantha33.zew.flash"),
                    "installed toolbar contributes visible native Build and explicit Flash commands");
            check(!toolbarCommands.contains("io.github.niwantha33.zew.debug"),
                    "installed toolbar retains Eclipse's own Debug control without a duplicate");
            var flashes = Arrays.asList(flashShortcut.getLaunchConfigurations(selection));
            var debugs = Arrays.asList(debugShortcut.getLaunchConfigurations(selection));
            check(flashes.contains(flashConfig) && !flashes.contains(debugConfig),
                    "Run Flash discovery excludes native Debug configurations");
            check(debugs.contains(debugConfig) && !debugs.contains(flashConfig),
                    "Debug discovery excludes firmware programming configurations");
        } finally {
            if (flashConfig != null) flashConfig.delete();
            if (debugConfig != null) debugConfig.delete();
            project.setPersistentProperty(setupProperty, savedSetup);
        }
        var apply = ui.loadClass("io.github.niwantha33.zew.ui.CdtEditor").getMethod("apply", IProject.class, EditorSettings.class, IProgressMonitor.class);
        // The dedicated post-build refresh uses the workspace root rule.
        if (args.length < 2 || !args[1].equals("verify")) workspace.run(m -> {
            try { apply.invoke(null, project, parsed, m); }
            catch (Exception error) { throw new CoreException(new Status(IStatus.ERROR, "zew.editor.smoke", "CDT apply failed", error)); }
        }, workspace.getRoot(), 0, new NullProgressMonitor());
        check(project.hasNature("org.eclipse.cdt.core.cnature"), "C nature installed");
        check(project.hasNature("org.eclipse.cdt.core.ccnature"), "C++ nature installed");
        check(project.getDescription().getBuildSpec().length == 1 && project.getDescription().getBuildSpec()[0].getBuilderName().endsWith(".zephyrBuilder"), "native West builder preserved; no Make builder added");
        var info = CCorePlugin.getDefault().getScannerInfoProvider(project).getScannerInformation(project.getFile("src/main.c"));
        System.out.println("Scanner provider: " + CCorePlugin.getDefault().getScannerInfoProvider(project).getClass().getName() + "; macros=" + (info == null ? "null" : info.getDefinedSymbols().size()));
        var diagnostics = CoreModel.getDefault().getProjectDescription(project, false);
        System.out.println("Active=" + diagnostics.getActiveConfiguration().getId() + "; default=" + diagnostics.getDefaultSettingConfiguration().getId());
        for (var cfg : diagnostics.getConfigurations()) {
            System.out.println("Config=" + cfg.getId() + "; provider=" + cfg.getBuildSystemId());
            for (var lang : cfg.getRootFolderDescription().getLanguageSettings()) System.out.println("Root language=" + lang.getLanguageId() + "; macros=" + lang.getSettingEntries(org.eclipse.cdt.core.settings.model.ICSettingEntry.MACRO).length);
            var file = (org.eclipse.cdt.core.settings.model.ICFileDescription)cfg.getResourceDescription(new org.eclipse.core.runtime.Path("src/main.c"), true);
            System.out.println("File=" + file + "; language=" + (file == null ? "missing" : file.getLanguageSetting().getLanguageId()));
            var keeper = (org.eclipse.cdt.core.language.settings.providers.ILanguageSettingsProvidersKeeper)cfg;
            for (var p : keeper.getLanguageSettingProviders()) {
                var values = p.getSettingEntries(cfg, project.getFile("src/main.c"), file.getLanguageSetting().getLanguageId());
                System.out.println("LSP=" + p.getId() + "; entries=" + (values == null ? "null" : values.size()));
            }
        }
        check(info != null && "73".equals(info.getDefinedSymbols().get("CONFIG_ZEW_TEST")), "generated Kconfig macro visible");
        check(Arrays.stream(info.getIncludePaths()).anyMatch(p -> p.endsWith("/include") && p.contains("headers")), "compiler include paths visible");
        var cpp = CCorePlugin.getDefault().getScannerInfoProvider(project).getScannerInformation(project.getFile("src/helper.cpp"));
        check("201703L".equals(cpp.getDefinedSymbols().get("__cplusplus")), "C++17 compiler mode visible");
        check(!cpp.getDefinedSymbols().containsKey("__EXCEPTIONS") && !cpp.getDefinedSymbols().containsKey("__GXX_RTTI"), "C++ exception/RTTI compiler flags preserved");
        check("11".equals(cpp.getDefinedSymbols().get("CPP_ONLY")) && !info.getDefinedSymbols().containsKey("CPP_ONLY"), "per-file C/C++ macros remain separate");
        // Refresh resources after CDT Core Build transitions and preserve the
        // upstream job synchronisation before verifying persisted index entries.
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        var manager = CCorePlugin.getIndexManager();
        // Native configuration jobs may have switched the active configuration during the import tests.
        org.eclipse.core.runtime.jobs.Job.getJobManager().join(
                org.eclipse.cdt.debug.core.launch.CoreBuildLaunchBarTracker.JOB_FAMILY_CORE_BUILD_LAUNCH_BAR_TRACKER,
                new NullProgressMonitor());
        manager.reindex(CoreModel.getDefault().create(project));
        // Reindex schedules a setup job before it queues indexer work. Joining the
        // indexer alone can report idle before that job has submitted anything.
        org.eclipse.core.runtime.jobs.Job.getJobManager().join(manager, new NullProgressMonitor());
        check(manager.joinIndexer(30000, new NullProgressMonitor()), "indexer completed");
        var index = manager.getIndex(CoreModel.getDefault().create(project)); index.acquireReadLock();
        try {
            check(index.findBindings(Pattern.compile("k_sleep"), false, IndexFilter.ALL, new NullProgressMonitor()).length > 0, "header function indexed for symbol navigation");
            check(index.findBindings(Pattern.compile("cpp_user"), false, IndexFilter.ALL, new NullProgressMonitor()).length > 0, "C++ source indexed");
            var tu = (ITranslationUnit) CoreModel.getDefault().create(project.getFile("src/main.c"));
            var ast = tu.getAST(index, 0); check(ast != null, "editor AST created");
            check(Arrays.stream(ast.getIncludeDirectives()).allMatch(IASTPreprocessorIncludeStatement::isResolved), "Zephyr header resolved");
            int[] problems = {0};
            ast.accept(new ASTVisitor() {
                { shouldVisitNames = true; }
                @Override public int visit(IASTName name) { if (name.resolveBinding() instanceof IProblemBinding) { System.out.println("Problem name: " + name); problems[0]++; } return PROCESS_CONTINUE; }
            });
            check(problems[0] == 0, "source bindings resolve, including forced header types");
        } finally { index.releaseReadLock(); }
        check(Files.isRegularFile(fixture.resolve(".cproject")), "CDT settings persisted");
        workspace.save(true, new NullProgressMonitor());
        System.out.println("HEADLESS CDT EDITOR CHECKS PASSED");
        if (Boolean.getBoolean("zew.nativeFlow")) RemoteDebugSmoke.run(fixture);
        return IApplication.EXIT_OK;
    }
    @Override public void stop() {}
}
