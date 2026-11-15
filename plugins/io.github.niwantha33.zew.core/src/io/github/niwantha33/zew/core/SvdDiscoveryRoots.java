// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Search-order hints only; never evidence that an SVD belongs to a given chip. */
public final class SvdDiscoveryRoots {
    private SvdDiscoveryRoots() {}
    public static String halFor(String target) {
        String part = SvdAutoDiscovery.partFromBoard(target);
        if (part.startsWith("stm32")) return "stm32";
        if (part.startsWith("nrf")) return "nordic";
        if (part.startsWith("esp32")) return "espressif";
        if (part.startsWith("rp2")) return "raspberrypi";
        if (part.startsWith("samd") || part.startsWith("same") || part.startsWith("samc"))
            return "atmel";
        if (part.startsWith("ra6") || part.startsWith("ra4") || part.startsWith("ra2"))
            return "renesas";
        if (part.startsWith("efr32")) return "silabs";
        if (part.startsWith("msp")) return "ti";
        return "";
    }
    public static List<Path> prioritize(String target, List<Path> roots) {
        if (roots == null) return List.of();
        String vendor = halFor(target);
        if (vendor.isEmpty()) return List.copyOf(roots);
        var ordered = new ArrayList<>(roots);
        // Keep every root in the input. The HAL folder name cannot validate a chip.
        ordered.sort(Comparator.comparingInt(path -> {
            if (path == null) return 2;
            String s = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
            return s.endsWith("/modules/hal/" + vendor) ? 0 : 1;
        }));
        return List.copyOf(ordered);
    }
}
