// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Search order hints ONLY; all SVD matches still require exact chip/core evidence. */
public final class SvdVendorRoots {
    private SvdVendorRoots() {}

    public static List<Path> prioritized(Path workspace, String part) {
        if (workspace == null) return List.of();
        String soc = part == null ? "" : part.toLowerCase(Locale.ROOT);
        String first = soc.startsWith("stm32") ? "stm32"
                : soc.startsWith("nrf") ? "nordic"
                : soc.startsWith("esp32") ? "espressif"
                : soc.startsWith("rp2") ? "raspberrypi"
                : soc.startsWith("samd") || soc.startsWith("same") ? "atmel"
                : soc.startsWith("ra6") || soc.startsWith("ra4") ? "renesas"
                : soc.startsWith("efr32") ? "silabs"
                : soc.startsWith("mspm0") ? "ti" : "";
        var names = new ArrayList<>(List.of("espressif", "stm32", "nordic", "ti",
                "raspberrypi", "atmel", "microchip", "renesas", "silabs"));
        if (!first.isEmpty()) {
            names.remove(first);
            names.add(0, first);
        }
        var result = new ArrayList<Path>();
        for (String vendor : names) result.add(workspace.resolve("modules/hal/" + vendor));
        return List.copyOf(result);
    }
}
