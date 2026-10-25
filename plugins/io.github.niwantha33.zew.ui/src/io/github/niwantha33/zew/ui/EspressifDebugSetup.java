// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Path;
import java.util.Optional;
import io.github.niwantha33.zew.core.EspressifOpenOcd;

/** Resolve existing Espressif tools without modifying the user's global PATH. */
final class EspressifDebugSetup {
    private EspressifDebugSetup() {}

    static Optional<EspressifOpenOcd.Installation> find(String board) {
        if (!EspressifOpenOcd.appliesTo(board)) return Optional.empty();
        Path tools = null;
        String location = System.getenv("IDF_TOOLS_PATH");
        if (location != null && !location.isBlank()) {
            try { tools = Path.of(location); } catch (RuntimeException ignored) {}
        }
        Path home = null;
        String homeText = System.getProperty("user.home", "");
        if (!homeText.isBlank()) {
            try { home = Path.of(homeText); } catch (RuntimeException ignored) {}
        }
        return EspressifOpenOcd.discover(home, tools);
    }
}
