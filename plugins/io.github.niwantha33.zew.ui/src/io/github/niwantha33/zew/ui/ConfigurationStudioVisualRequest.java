// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import io.github.niwantha33.zew.core.ConfigurationStudioFiles;
import io.github.niwantha33.zew.core.ConfigurationStudioPresentation;
import io.github.niwantha33.zew.core.FocusedConfigurationModel;
import java.util.regex.Pattern;

/**
 * The browser does not decide which values are editable. This bounded pure-JDK
 * request gate accepts ONLY a toggle already present in the exact selected
 * source/build snapshot, or removal of an existing application request.
 */
public final class ConfigurationStudioVisualRequest {
    private static final Pattern REQUEST =
            Pattern.compile("^CONFIG_([A-Z0-9_]{1,160})=(y|n|remove)$");

    private ConfigurationStudioVisualRequest() {}

    public record Reviewed(String symbol, String value, ConfigurationStudioFiles.Option expected) {}

    public static Reviewed parse(String input, ConfigurationStudioFiles.Snapshot source,
                                 FocusedConfigurationModel.Freshness freshness,
                                 boolean unsavedEditors) {
        if (unsavedEditors || input == null || input.length() > 180 || source == null)
            throw new IllegalArgumentException("Save or discard source edits and refresh first.");
        var syntax = REQUEST.matcher(input);
        if (!syntax.matches()) throw new IllegalArgumentException("Not a supported configuration request.");
        String symbol = syntax.group(1);
        String target = syntax.group(2);
        var selected = source.options().stream()
                .filter(option -> option.symbol().equals(symbol))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "Requested symbol absent from exact selected configuration."));
        if ("remove".equals(target)) {
            if (selected.requested() == null)
                throw new IllegalArgumentException("No explicit project override to remove.");
            return new Reviewed(symbol, null, selected);
        }
        if (freshness != FocusedConfigurationModel.Freshness.CURRENT_SNAPSHOT
                || !ConfigurationStudioPresentation.isToggle(selected))
            throw new IllegalArgumentException(
                    "Resolved on/off options require current verified build evidence.");
        if (target.equals(selected.requested()))
            throw new IllegalArgumentException("The project already requests this value.");
        return new Reviewed(symbol, target, selected);
    }
}
