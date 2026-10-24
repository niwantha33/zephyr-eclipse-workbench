// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.cdt.core.CCorePlugin;
import org.eclipse.cdt.core.CProjectNature;
import org.eclipse.cdt.core.CCProjectNature;
import org.eclipse.cdt.core.dom.IPDOMManager;
import org.eclipse.cdt.core.model.CoreModel;
import org.eclipse.cdt.core.language.settings.providers.ILanguageSettingsProvidersKeeper;
import org.eclipse.cdt.core.language.settings.providers.LanguageSettingsManager;
import org.eclipse.cdt.core.settings.model.*;
import org.eclipse.cdt.core.settings.model.extension.impl.CDataFactory;
import org.eclipse.cdt.core.settings.model.util.CDataUtil;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import io.github.niwantha33.zew.core.EditorSettings;

/** No CDT build commands: West remains the project's sole firmware builder. */
public final class CdtEditor {
    public static final String CONFIG = "io.github.niwantha33.zew.ui.editor";
    public static void ensureProject(IProject project, IProgressMonitor monitor) throws Exception {
        CProjectNature.addCNature(project, monitor);
        CCProjectNature.addCCNature(project, monitor);
    }
    public static void apply(IProject project, EditorSettings settings, IProgressMonitor monitor) throws Exception {
        for (String file : settings.sources().keySet())
            if (!project.getFile(file).exists()) throw new java.io.IOException("Editor source is not in the project: " + file);
        CProjectNature.addCNature(project, monitor);
        CCProjectNature.addCCNature(project, monitor);
        var model = CoreModel.getDefault();
        var description = model.getProjectDescription(project, true);
        if (description == null) description = model.createProjectDescription(project, false);
        var config = description.getConfigurationById(CONFIG);
        boolean first = config == null;
        if (first) config = description.createConfiguration("io.github.niwantha33.zew.ui.editorData",
                CDataUtil.createEmptyData(CONFIG, "Zephyr build editor", new CDataFactory(), true));
        var root = config.getRootFolderDescription();
        // Clear only resource settings in our owned configuration, so removed
        // source files and board switches cannot leave stale definitions.
        for (var resource : config.getResourceDescriptions())
            if (!resource.getPath().isEmpty()) config.removeResourceDescription(resource);
        for (var language : root.getLanguageSettings()) {
            String wanted = "org.eclipse.cdt.core.g++".equals(language.getLanguageId()) ? "c++" : "c";
            var source = settings.sources().values().stream().filter(s -> s.language().equals(wanted)).findFirst()
                    .orElse(settings.sources().values().iterator().next());
            apply(language, source);
        }
        for (var entry : settings.sources().entrySet()) {
            var file = config.createFileDescription(new Path(entry.getKey()), root);
            apply(file.getLanguageSetting(), entry.getValue());
        }
        ((ILanguageSettingsProvidersKeeper) config).setDefaultLanguageSettingsProvidersIds(new String[] {ZephyrLanguageSettingsProvider.ID});
        ((ILanguageSettingsProvidersKeeper) config).setLanguageSettingProviders(List.of(LanguageSettingsManager.getWorkspaceProvider(ZephyrLanguageSettingsProvider.ID)));
        config.setSourceEntries(new ICSourceEntry[] {new CSourceEntry("", new org.eclipse.core.runtime.IPath[] {new Path(".zew-build/")}, ICSettingEntry.VALUE_WORKSPACE_PATH)});
        if (first) { description.setActiveConfiguration(config); description.setDefaultSettingConfiguration(config); }
        description.setCdtProjectCreated();
        model.setProjectDescription(project, description, false, monitor);
        var cproject = model.create(project);
        CCorePlugin.getIndexManager().setIndexerId(cproject, IPDOMManager.ID_FAST_INDEXER);
        CCorePlugin.getIndexManager().reindex(cproject);
    }
    private static void apply(ICLanguageSetting language, EditorSettings.Source source) {
        var includes = new ArrayList<ICLanguageSettingEntry>(); var defines = new ArrayList<ICLanguageSettingEntry>();
        var forced = new ArrayList<ICLanguageSettingEntry>(); var macros = new ArrayList<ICLanguageSettingEntry>();
        for (var entry : source.entries()) switch (entry.kind()) {
            case 'I' -> includes.add(new CIncludePathEntry(entry.name(), ICSettingEntry.RESOLVED));
            case 'D' -> defines.add(new CMacroEntry(entry.name(), entry.value(), ICSettingEntry.RESOLVED));
            case 'H' -> forced.add(new CIncludeFileEntry(entry.name(), ICSettingEntry.RESOLVED));
            case 'A' -> macros.add(new CMacroFileEntry(entry.name(), ICSettingEntry.RESOLVED));
            default -> throw new IllegalArgumentException("Unknown editor entry");
        }
        language.setSettingEntries(ICSettingEntry.INCLUDE_PATH, includes);
        language.setSettingEntries(ICSettingEntry.MACRO, defines);
        language.setSettingEntries(ICSettingEntry.INCLUDE_FILE, forced);
        language.setSettingEntries(ICSettingEntry.MACRO_FILE, macros);
    }
}
