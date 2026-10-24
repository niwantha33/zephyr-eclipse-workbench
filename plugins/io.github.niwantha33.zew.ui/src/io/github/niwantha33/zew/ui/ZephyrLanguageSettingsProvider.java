// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.eclipse.cdt.core.language.settings.providers.LanguageSettingsBaseProvider;
import org.eclipse.cdt.core.settings.model.*;
import org.eclipse.core.resources.IResource;

/** Expose the persisted owned configuration to CDT's scanner, without a second settings store. */
public final class ZephyrLanguageSettingsProvider extends LanguageSettingsBaseProvider {
    public static final String ID = "io.github.niwantha33.zew.ui.editor.compiler";
    @Override public List<ICLanguageSettingEntry> getSettingEntries(ICConfigurationDescription config, IResource resource, String languageId) {
        if (config == null || !CdtEditor.CONFIG.equals(config.getId())) return null;
        ICResourceDescription description = resource == null ? config.getRootFolderDescription()
                : config.getResourceDescription(resource.getProjectRelativePath(), false);
        ICLanguageSetting language = null;
        if (description instanceof ICFileDescription file) language = file.getLanguageSetting();
        else if (description instanceof ICFolderDescription folder)
            language = Arrays.stream(folder.getLanguageSettings()).filter(l -> java.util.Objects.equals(l.getLanguageId(), languageId)).findFirst().orElse(null);
        if (language == null || !java.util.Objects.equals(language.getLanguageId(), languageId)) return null;
        var entries = new ArrayList<ICLanguageSettingEntry>();
        for (int kind : new int[] {ICSettingEntry.INCLUDE_PATH, ICSettingEntry.MACRO, ICSettingEntry.INCLUDE_FILE, ICSettingEntry.MACRO_FILE})
            entries.addAll(List.of(language.getSettingEntries(kind)));
        return List.copyOf(entries);
    }
}
