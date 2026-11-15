// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.SvdDiscoveryRoots;
import java.nio.file.Path;
import java.util.List;

public final class SvdDiscoveryRootsTests {
    private static void check(boolean yes, String message) {
        if (!yes) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        var paths = List.of(Path.of("/workspace/modules/hal/stm32"),
                Path.of("/workspace/modules/hal/espressif"),
                Path.of("/workspace/modules/hal/nordic"),
                Path.of("/workspace/modules/hal/ti"));
        check(SvdDiscoveryRoots.prioritize("nrf5340dk/nrf5340/cpuapp", paths).get(0)
                .toString().endsWith("nordic"), "nRF HAL searched first");
        check(SvdDiscoveryRoots.prioritize("board/stm32f103xb", paths).get(0)
                .toString().endsWith("stm32"), "STM32 HAL searched first");
        check(SvdDiscoveryRoots.prioritize("board/esp32c3", paths).get(0)
                .toString().endsWith("espressif"), "Espressif HAL searched first");
        check(SvdDiscoveryRoots.prioritize("board/unknown_chip", paths).equals(paths),
                "unknown vendor preserves original ordering without guessing");
        check(SvdDiscoveryRoots.halFor("board/rp2040").equals("raspberrypi")
                && SvdDiscoveryRoots.halFor("board/samd21g18a").equals("atmel")
                && SvdDiscoveryRoots.halFor("board/ra6m3").equals("renesas")
                && SvdDiscoveryRoots.halFor("board/efr32mg24").equals("silabs"),
                "non-identity vendor HAL prefixes are supported");
        System.out.println("SvdDiscoveryRootsTests PASS");
    }
}
