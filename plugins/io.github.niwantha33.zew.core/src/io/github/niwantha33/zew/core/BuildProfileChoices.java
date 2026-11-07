// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Exact selection model for native Build/Flash quick actions.
 * Built-in modes retain their original IDs; named profiles keep stable IDs
 * independent of display names and optimisation settings.
 */
public final class BuildProfileChoices {
    public record Choice(String key, String label, String builtInId, String namedId) {
        public Choice {
            if (key == null || key.isBlank() || label == null || label.isBlank()
                    || builtInId == null || builtInId.isBlank())
                throw new IllegalArgumentException("Invalid build profile choice.");
        }
        public boolean named() { return namedId != null; }
        public String identity() { return named() ? "profile-" + namedId : builtInId; }
    }

    private BuildProfileChoices() { }

    public static List<Choice> available(Path application) throws IOException {
        var choices = new ArrayList<Choice>();
        for (BuildConfiguration option : BuildConfiguration.values())
            choices.add(new Choice("builtin:" + option.id(), option.label(), option.id(), null));
        for (BuildProfile profile : BuildProfile.list(application))
            choices.add(new Choice("profile:" + profile.id(), profile.label(),
                    profile.optimization().id(), profile.id()));
        return List.copyOf(choices);
    }

    public static Choice selected(List<Choice> choices, String builtInId, String namedId)
            throws IOException {
        if (namedId != null && !namedId.isBlank())
            return choose(choices, "profile:" + namedId);
        // Unknown built-in values are not replaced by a guessed default.
        if (builtInId == null || builtInId.isBlank()) builtInId = "default";
        return choose(choices, "builtin:" + builtInId);
    }

    public static Choice choose(List<Choice> choices, String key) throws IOException {
        if (key == null) throw new IOException("No build profile was selected.");
        for (Choice choice : choices)
            if (choice.key().equals(key)) return choice;
        throw new IOException("Build profile '" + key + "' is missing or unsupported. "
                + "Reopen Project Properties > Zephyr before building or flashing.");
    }
}
