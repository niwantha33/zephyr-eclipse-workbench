// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.SvdVendorRoots;
import java.nio.file.Path;

public final class SvdVendorRootsTests {
    private static void check(String soc, String vendor) {
        var paths = SvdVendorRoots.prioritized(Path.of("workspace"), soc);
        if (paths.isEmpty() || !paths.get(0).endsWith(Path.of("modules/hal/" + vendor)))
            throw new AssertionError("Wrong search priority for " + soc + ": " + paths);
        if (paths.size() != paths.stream().distinct().count())
            throw new AssertionError("Duplicate search roots: " + soc);
    }
    public static void main(String[] args) {
        check("stm32f103", "stm32");
        check("nrf52840", "nordic");
        check("esp32s3", "espressif");
        check("rp2040", "raspberrypi");
        check("samd21g18a", "atmel");
        check("ra6m5", "renesas");
        check("efr32mg24", "silabs");
        check("mspm0g3507", "ti");
        System.out.println("SvdVendorRootsTests PASS");
    }
}
