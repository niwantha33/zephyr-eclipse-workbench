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
        check(nordic.registers().stream().anyMatch(r -> r.fields().stream()
                .anyMatch(field -> !field.enumeratedValues().isEmpty())),
                "Authentic Nordic field enums survive trimmed parser fixture");
        System.out.println("VendorSvdFixtureTests PASS");
    }
}
