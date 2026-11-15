// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.SvdPartCompatibility;

public final class SvdPartCompatibilityTests {
    private static void check(boolean result, String reason) {
        if (!result) throw new AssertionError(reason);
    }
    public static void main(String[] args) throws Exception {
        check(SvdPartCompatibility.matches("mspm0g3507", "MSPM0G350X"), "real TI family");
        check(SvdPartCompatibility.matches("mspm0g3507", "MSPM0G3507"), "exact TI");
        check(!SvdPartCompatibility.matches("mspm0g3506", "MSPM0G350X"), "wrong TI chip");
        check(!SvdPartCompatibility.matches("esp32s3", "MSPM0G350X"), "wrong vendor");
        check(!SvdPartCompatibility.matches("", "MSPM0G350X"), "missing device");
        check(!SvdPartCompatibility.matches("mspm0g3507", ""), "missing description");
        var mappings = SvdPartCompatibility.parse(new java.io.ByteArrayInputStream(
                ("[{\"part\":\"stm32u585\",\"svdModel\":\"stm32u585xx\"}]")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        check("stm32u585xx".equals(mappings.get("stm32u585")),
                "exact additional part mapping loads from JSON without code changes");
        try {
            SvdPartCompatibility.parse(new java.io.ByteArrayInputStream(
                    "[{\"part\":\"stm32u585\",\"svdModel\":\"stm32u585xx\"},"
                    .concat("{\"part\":\"stm32u585\",\"svdModel\":\"other\"}]")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            throw new AssertionError("duplicate mapping must be rejected");
        } catch (IllegalArgumentException expected) { /* fail closed */ }
        check(SvdPartCompatibility.modelsFor("MSPM0G3507")
                .containsAll(java.util.Set.of("mspm0g3507", "mspm0g350x")),
                "filename discovery uses the bundled alias table");
        check(SvdPartCompatibility.modelsFor("nrf5340")
                .containsAll(java.util.Set.of("nrf5340", "nrf5340application", "nrf5340network")),
                "both vendor SVD core models are listed for filename discovery");
        check(SvdPartCompatibility.modelsFor("nrf5340", "cpuapp")
                .equals(java.util.Set.of("nrf5340application")),
                "application core never searches the network SVD");
        check(!SvdPartCompatibility.matches("nrf5340", "nrf5340_application")
                && SvdPartCompatibility.matches("nrf5340", "cpuapp", "nrf5340_application")
                && !SvdPartCompatibility.matches("nrf5340", "cpuapp", "nrf5340_network"),
                "dual-core identity is qualified and fail closed");
        var qualified = SvdPartCompatibility.parseMappings(new java.io.ByteArrayInputStream(
                "[{\"part\":\"nrf5340\",\"svdModel\":\"nrf5340_network\",\"qualifier\":\"cpunet\"}]"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        check(qualified.size() == 1 && "cpunet".equals(qualified.get(0).qualifier()),
                "optional qualifier parsed from JSON");
        System.out.println("SvdPartCompatibilityTests PASS");
    }
}
