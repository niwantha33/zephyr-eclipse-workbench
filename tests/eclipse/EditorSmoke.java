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
        var parsed = EditorSettings.parse(Files.readString(fixture.resolve(".zew-build/first/editor-settings.tsv"), StandardCharsets.UTF_8));
        var ui = Platform.getBundle("io.github.niwantha33.zew.ui");
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
        var manager = CCorePlugin.getIndexManager();
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
        return IApplication.EXIT_OK;
    }
    @Override public void stop() {}
}
