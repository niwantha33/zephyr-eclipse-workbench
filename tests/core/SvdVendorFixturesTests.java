// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.SvdDevice;
import java.nio.file.Files;
import java.nio.file.Path;

/** Trimmed original vendor XML, including its original licensing text. */
public final class SvdVendorFixturesTests {
    private static void check(boolean yes, String message) {
        if (!yes) throw new AssertionError(message);
    }
    private static SvdDevice read(String filename) throws Exception {
        Path path = Path.of("tests", "fixtures", "svd-vendors", filename);
        check(Files.isRegularFile(path) && Files.size(path) < 32_768,
                "bounded original vendor fixture present: " + filename);
        try (var input = Files.newInputStream(path)) {
            return SvdDevice.read(input);
        }
    }
    public static void main(String[] args) throws Exception {
        var nordic = read("nrf52840_trimmed.svd");
        check("nrf52840".equalsIgnoreCase(nordic.name()), "genuine Nordic nRF52840 identity");
        check(nordic.registers().stream().anyMatch(r -> r.peripheral().equals("APPROTECT")),
                "Nordic register block survives vendor XML");
        check(nordic.registers().stream().anyMatch(r -> r.peripheral().equals("SWI1")),
                "Nordic derived peripheral resolves to registers");
        var rp = read("rp2040_trimmed.svd");
        check("rp2040".equalsIgnoreCase(rp.name()), "genuine Raspberry Pi RP2040 identity");
        check(rp.registers().stream().anyMatch(r -> r.peripheral().equals("PLL_USB")),
                "Raspberry Pi derived PLL register block survives");
        check(rp.registers().stream().anyMatch(r -> r.peripheral().equals("SYSINFO")),
                "Raspberry Pi metadata register block survives");
        check(Files.readString(Path.of("tests/fixtures/svd-vendors/nrf52840_trimmed.svd"))
                .contains("SPDX-License-Identifier: BSD-3-Clause")
                && Files.readString(Path.of("tests/fixtures/svd-vendors/rp2040_trimmed.svd"))
                .contains("SPDX-License-Identifier: BSD-3-Clause"),
                "original vendor license text retained");
        System.out.println("SvdVendorFixturesTests PASS");
    }
}
