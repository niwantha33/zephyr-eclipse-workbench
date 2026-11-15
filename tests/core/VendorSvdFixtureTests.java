// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.SvdDevice;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigInteger;

/** License-preserving minimal extracts of actual named upstream vendor SVDs. */
public final class VendorSvdFixtureTests {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static SvdDevice load(String filename) throws Exception {
        Path path = Path.of("tests/fixtures/vendor-svd", filename + ".svd");
        try (var input = Files.newInputStream(path)) { return SvdDevice.read(input); }
    }
    public static void main(String[] args) throws Exception {
        var nordic = load("nrf52840");
        check(nordic.name().equalsIgnoreCase("nrf52840"), "Nordic exact name retained");
        check(nordic.registers().stream().anyMatch(r ->
                r.peripheral().equals("FICR") && r.name().equals("CODEPAGESIZE")
                        && r.address().equals(new BigInteger("10000010", 16))),
                "Nordic original FICR address and register");
        check(nordic.registers().stream().anyMatch(r ->
                r.peripheral().equals("P1") && r.name().equals("OUT")
                        && r.address().equals(new BigInteger("50000804", 16))),
                "Nordic P1 inherits P0 register and address offset from authentic derivedFrom");
        var rp = load("rp2040");
        check(rp.name().equalsIgnoreCase("RP2040"), "Raspberry Pi exact name retained");
        check(rp.registers().stream().anyMatch(r ->
                r.peripheral().equals("ADC") && r.name().equals("CS")
                        && r.address().equals(new BigInteger("4004c000", 16))),
                "Raspberry Pi original ADC register address");
        var stm = load("stm32f103");
        check(stm.name().equalsIgnoreCase("STM32F103"), "STM32 exact chip name retained");
        check(stm.registers().stream().anyMatch(r ->
                r.peripheral().equals("FSMC") && r.name().equals("BCR1")
                        && r.address().equals(new BigInteger("a0000000", 16))),
                "STM32 original FSMC register and vendor fields");
        var samd = load("atsamd21g18a");
        check(samd.name().equalsIgnoreCase("ATSAMD21G18A"), "Microchip exact part name preserved");
        check(samd.registers().stream().anyMatch(r ->
                r.peripheral().equals("AC") && r.name().equals("CTRLA")
                        && r.address().equals(new BigInteger("42004400", 16))),
                "Microchip authentic comparator register and offset");
        Path packRoot = Files.createTempDirectory("zew-stm32-pdsc-");
        try {
            Path svdDir = Files.createDirectories(packRoot.resolve("SVD"));
            Files.copy(Path.of("tests/fixtures/vendor-svd/stm32f103.svd"),
                    svdDir.resolve("STM32F103.svd"));
            // Synthetic PDSC route with an unmodified authentic STM32 SVD
            // excerpt; this tests selection, not an upstream DFP revision.
            Files.writeString(packRoot.resolve("ST.DFP.pdsc"),
                    "<package><devices><family Dfamily='STM32F1'>"
                    + "<device Dname='STM32F103'><debug svd='SVD/STM32F103.svd'/>"
                    + "</device></family></devices></package>");
            var mapped = io.github.niwantha33.zew.core.SvdAutoDiscovery.resolve(
                    "nucleo_f103rb/stm32f103", java.util.List.of(packRoot),
                    packRoot.resolve("cache"));
            check(mapped.state() == io.github.niwantha33.zew.core.SvdAutoDiscovery.State.MATCHED
                    && mapped.mappedByPack(), "exact STM32F103 synthetic PDSC selects vendor register XML");
        } finally {
            try (var walk = Files.walk(packRoot)) {
                for (var file : walk.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(file);
            }
        }
        check(nordic.registers().stream().anyMatch(r -> r.fields().stream()
                .anyMatch(field -> !field.enumeratedValues().isEmpty())),
                "Authentic Nordic field enums survive trimmed parser fixture");
        System.out.println("VendorSvdFixtureTests PASS");
    }
}
