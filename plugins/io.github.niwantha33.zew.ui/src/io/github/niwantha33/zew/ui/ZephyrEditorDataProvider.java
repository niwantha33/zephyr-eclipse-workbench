// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

/** A separate owned CDT configuration; never replace an existing build system. */
public final class ZephyrEditorDataProvider extends org.eclipse.cdt.core.settings.model.extension.impl.CDefaultConfigurationDataProvider {
    private void configure(org.eclipse.cdt.core.settings.model.ICConfigurationDescription config) {
        var keeper = (org.eclipse.cdt.core.language.settings.providers.ILanguageSettingsProvidersKeeper) config;
        keeper.setDefaultLanguageSettingsProvidersIds(new String[] {ZephyrLanguageSettingsProvider.ID});
        keeper.setLanguageSettingProviders(java.util.List.of(org.eclipse.cdt.core.language.settings.providers.LanguageSettingsManager.getWorkspaceProvider(ZephyrLanguageSettingsProvider.ID)));
    }
    @Override public org.eclipse.cdt.core.settings.model.extension.CConfigurationData applyConfiguration(
            org.eclipse.cdt.core.settings.model.ICConfigurationDescription config,
            org.eclipse.cdt.core.settings.model.ICConfigurationDescription base,
            org.eclipse.cdt.core.settings.model.extension.CConfigurationData data,
            org.eclipse.core.runtime.IProgressMonitor monitor) throws org.eclipse.core.runtime.CoreException {
        configure(config);
        return super.applyConfiguration(config, base, data, monitor);
    }
    @Override public org.eclipse.cdt.core.settings.model.extension.CConfigurationData loadConfiguration(
            org.eclipse.cdt.core.settings.model.ICConfigurationDescription config,
            org.eclipse.core.runtime.IProgressMonitor monitor) throws org.eclipse.core.runtime.CoreException {
        configure(config);
        return super.loadConfiguration(config, monitor);
    }
}
