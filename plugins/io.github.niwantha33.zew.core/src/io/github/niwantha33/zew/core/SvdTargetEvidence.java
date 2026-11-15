// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Correlates a selected Zephyr board target with the effective generated
 * build configuration. A family, CPU or vendor alone never identifies an SVD.
 * Does not parse arbitrary YAML, access a probe or install any vendor files.
 */
public final class SvdTargetEvidence {
    private SvdTargetEvidence() {}
    public enum State { EXACT_QUALIFIER, VERIFIED_BUILD_SOC, MISSING, CONFLICT, AMBIGUOUS }
    public record Selection(State state, String target, String explanation) {
        public boolean usable() {
            return state == State.EXACT_QUALIFIER || state == State.VERIFIED_BUILD_SOC;
        }
    }
    private static final Set<String> GENERIC = Set.of(
            "soc", "cpu0", "appcpu", "netcore", "atom", "cortexm0", "cortexm4", "cortexm33");
    private static String scalar(String input) {
        if (input == null) return "";
        String clean = input.trim();
        if (clean.length() >= 2 && clean.startsWith("\"") && clean.endsWith("\""))
            clean = clean.substring(1, clean.length() - 1);
        return clean.toLowerCase(Locale.ROOT);
    }
    private static boolean part(String text) {
        return text.matches("[a-z0-9_+-]{5,72}") && !GENERIC.contains(text);
    }
    public static Selection select(String selectedBoard, Map<String, String> config) {
        if (selectedBoard == null || selectedBoard.isBlank())
            return new Selection(State.MISSING, "", "No selected Zephyr board; choose an exact project.");
        String[] segments = selectedBoard.toLowerCase(Locale.ROOT).trim().split("/", -1);
        String board = segments[0].split("@", 2)[0];
        if (!board.matches("[a-z0-9_+-]{2,100}"))
            return new Selection(State.MISSING, "", "Unrecognised board target; manual exact-device SVD required.");
        if (segments.length > 3)
            return new Selection(State.CONFLICT, "", "Unexpected SoC/core path segments; no automatic SVD.");
        String qualifier = segments.length > 1 ? segments[1] : "";
        String core = segments.length == 3 ? segments[2] : "";
        if (!core.isEmpty() && !Set.of("cpuapp", "cpunet", "cpu0", "cpu1",
                "m7", "m4", "appcpu", "netcore").contains(core))
            return new Selection(State.CONFLICT, "", "Unverified core-cluster suffix: " + core);
        String source = config == null ? "" : scalar(config.get("CONFIG_BOARD"));
        String soc = config == null ? "" : scalar(config.get("CONFIG_SOC"));
        String configQualifiers = config == null ? "" : scalar(config.get("CONFIG_BOARD_QUALIFIERS"));
        if (!source.isEmpty() && !source.equals(board))
            return new Selection(State.CONFLICT, "", "Generated .config names board " + source
                    + " but the selected project names " + board + ". Rebuild this profile before SVD discovery.");
        if (part(qualifier)) {
            if (SvdPartCompatibility.requiresQualifier(qualifier)
                    && !SvdPartCompatibility.knownQualifier(qualifier, core))
                return new Selection(State.AMBIGUOUS, "",
                        "Multiple official SVD cores exist for " + qualifier
                        + "; select an explicit matching cpuapp/cpunet board target.");
            if (SvdPartCompatibility.requiresQualifier(qualifier) && configQualifiers.isEmpty())
                return new Selection(State.AMBIGUOUS, "",
                        "Generated CONFIG_BOARD_QUALIFIERS is required to verify the "
                        + qualifier + "/" + core + " core; rebuild before selecting an SVD.");
            if (part(soc) && !soc.equals(qualifier)
                    && !(SvdPartCompatibility.knownQualifier(qualifier, core)
                        && soc.equals(qualifier + "_" + core)))
                return new Selection(State.CONFLICT, "", "Selected board qualifier " + qualifier
                        + " conflicts with generated CONFIG_SOC=" + soc + "; no automatic SVD.");
            String expected = qualifier + (core.isEmpty() ? "" : "/" + core);
            if (!configQualifiers.isEmpty()
                    && !configQualifiers.replaceFirst("^/", "").equals(expected))
                return new Selection(State.CONFLICT, "",
                        "Selected core/SoC " + expected
                        + " conflicts with CONFIG_BOARD_QUALIFIERS=" + configQualifiers
                        + "; rebuild the exact selected profile.");
            return new Selection(State.EXACT_QUALIFIER, selectedBoard,
                    "Exact selected board qualifier " + qualifier
                    + (source.isEmpty() ? " (no matching build metadata observed)." : " (board identity matches .config).")
                    + " Vendor SVD identity still requires validation.");
        }
        // A board-only target is valid Zephyr syntax. Accept a SoC only from
        // its generated effective config, never by guessing from the board name.
        if (SvdPartCompatibility.requiresQualifier(soc))
            return new Selection(State.AMBIGUOUS, "",
                    "Generated SoC " + soc + " is multi-core; select the exact application/network core.");
        if (!source.equals(board) || !part(soc) || !configQualifiers.isEmpty()
                && !configQualifiers.replaceFirst("^/", "").equals(soc))
            return new Selection(State.MISSING, "", "Board-only target needs generated .config"
                    + " with matching CONFIG_BOARD, exact CONFIG_SOC and compatible qualifiers."
                    + " Rebuild or choose an exact vendor SVD manually.");
        return new Selection(State.VERIFIED_BUILD_SOC, selectedBoard + "/" + soc,
                "Effective generated CONFIG_SOC=" + soc + " for board " + board
                + "; SVD file/model still requires exact verification.");
    }
}
