// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.SvdTargetEvidence;
import java.util.Map;

public final class SvdTargetEvidenceTests {
    private static int checks;
    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
        checks++;
    }
    public static void main(String[] ignored) {
        var explicit = SvdTargetEvidence.select("lp_mspm0g3507/mspm0g3507",
                Map.of("CONFIG_BOARD", "\"lp_mspm0g3507\"",
                        "CONFIG_SOC", "\"mspm0g3507\"",
                        "CONFIG_BOARD_QUALIFIERS", "\"mspm0g3507\""));
        check(explicit.state() == SvdTargetEvidence.State.EXACT_QUALIFIER && explicit.usable(),
                "exact selected qualifier corroborated by generated build");
        var simple = SvdTargetEvidence.select("thingy52",
                Map.of("CONFIG_BOARD", "\"thingy52\"", "CONFIG_SOC", "\"nrf52832\"",
                        "CONFIG_BOARD_QUALIFIERS", "\"nrf52832\""));
        check(simple.state() == SvdTargetEvidence.State.VERIFIED_BUILD_SOC
                && simple.target().equals("thingy52/nrf52832"),
                "board-only target obtains exact SoC from build, never board string");
        check(SvdTargetEvidence.select("thingy52", Map.of()).state()
                == SvdTargetEvidence.State.MISSING, "no build does not justify guessing");
        check(SvdTargetEvidence.select("thingy52",
                Map.of("CONFIG_SOC", "\"nrf52832\"")).state() == SvdTargetEvidence.State.MISSING,
                "unbound SoC value does not match selected board");
        check(SvdTargetEvidence.select("thingy52",
                Map.of("CONFIG_BOARD", "\"other_board\"", "CONFIG_SOC", "\"nrf52832\"")).state()
                == SvdTargetEvidence.State.CONFLICT, "stale other board fails closed");
        check(SvdTargetEvidence.select("lp_mspm0g3507/mspm0g3507",
                Map.of("CONFIG_BOARD", "\"lp_mspm0g3507\"",
                        "CONFIG_SOC", "\"mspm0g3506\"")).state() == SvdTargetEvidence.State.CONFLICT,
                "conflicting SoC on explicit qualifier fails closed");
        check(SvdTargetEvidence.select("qemu_x86/atom", Map.of()).state()
                == SvdTargetEvidence.State.MISSING, "virtual core not treated as SoC");
        check(SvdTargetEvidence.select("board", Map.of("CONFIG_BOARD", "\"board\"",
                "CONFIG_SOC", "\"cortexm4\"")).state() == SvdTargetEvidence.State.MISSING,
                "CPU architecture not exact silicon");
        check(SvdTargetEvidence.select("board", Map.of("CONFIG_BOARD", "\"board\"",
                "CONFIG_SOC", "\"nrf52840\"",
                "CONFIG_BOARD_QUALIFIERS", "\"nrf52833\"")).state() == SvdTargetEvidence.State.MISSING,
                "mismatching generated qualifiers disallow selection");
        System.out.println("SvdTargetEvidenceTests PASS: " + checks + " checks");
    }
}
