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
            return new Selection(State.CONFLICT, "", "Too many board qualifier segments.");
        String qualifier = segments.length > 1 ? segments[1] : "";
        String cluster = segments.length == 3 ? segments[2] : "";
        String source = config == null ? "" : scalar(config.get("CONFIG_BOARD"));
        String soc = config == null ? "" : scalar(config.get("CONFIG_SOC"));
        String configQualifiers = config == null ? "" : scalar(config.get("CONFIG_BOARD_QUALIFIERS"));
        if (!source.isEmpty() && !source.equals(board))
            return new Selection(State.CONFLICT, "", "Generated .config names board " + source
                    + " but the selected project names " + board + ". Rebuild this profile before SVD discovery.");
        if (segments.length == 3) {
            if (!part(qualifier) || !Set.of("cpuapp", "cpunet", "cpu0", "cpu1", "m7", "m4", "appcpu", "netcore")
                    .contains(cluster))
                return new Selection(State.AMBIGUOUS, "",
                        "Unrecognised multicore cluster; no SVD core is selected.");
            String qualified = qualifier + "/" + cluster;
            String effective = configQualifiers.replaceFirst("^/", "");
            if (configQualifiers.isEmpty() || !effective.equals(qualified))
                return new Selection(State.AMBIGUOUS, "",
                        "Multicore build requires exact CONFIG_BOARD_QUALIFIERS=" + qualified);
            if (part(soc) && !soc.equals(qualifier))
                return new Selection(State.CONFLICT, "",
                        "Selected SoC conflicts with CONFIG_SOC; no SVD selected.");
            if (!SvdPartCompatibility.hasQualifier(qualifier, cluster))
                return new Selection(State.AMBIGUOUS, "",
                        "No documented SVD mapping for core " + qualified);
            return new Selection(State.EXACT_QUALIFIER, selectedBoard,
                    "Exact multicore SoC and cluster verified from generated qualifiers: " + qualified);
        }
        if (part(qualifier) && SvdPartCompatibility.requiresQualifier(qualifier))
            return new Selection(State.AMBIGUOUS, "",
                    "Dual-core SoC requires the exact CPU cluster and CONFIG_BOARD_QUALIFIERS.");
        if (part(qualifier)) {
            if (part(soc) && !soc.equals(qualifier))
                return new Selection(State.CONFLICT, "", "Selected board qualifier " + qualifier
                        + " conflicts with generated CONFIG_SOC=" + soc + "; no automatic SVD.");
            if (!configQualifiers.isEmpty() && !configQualifiers.replaceFirst("^/", "").startsWith(qualifier))
                return new Selection(State.CONFLICT, "", "Selected qualifiers conflict with generated CONFIG_BOARD_QUALIFIERS="
                        + configQualifiers + "; rebuild the selected board.");
            return new Selection(State.EXACT_QUALIFIER, selectedBoard,
                    "Exact selected board qualifier " + qualifier
                    + (source.isEmpty() ? " (no matching build metadata observed)." : " (board identity matches .config).")
                    + " Vendor SVD identity still requires validation.");
        }
        // A board-only target is valid Zephyr syntax. Accept a SoC only from
        // its generated effective config, never by guessing from the board name.
        if (!source.equals(board) || !part(soc) || !configQualifiers.isEmpty()
                && !configQualifiers.replaceFirst("^/", "").startsWith(soc))
            return new Selection(State.MISSING, "", "Board-only target needs generated .config"
                    + " with matching CONFIG_BOARD, exact CONFIG_SOC and compatible qualifiers."
                    + " Rebuild or choose an exact vendor SVD manually.");
        if (SvdPartCompatibility.requiresQualifier(soc))
            return new Selection(State.AMBIGUOUS, "",
                    "Generated SoC has multiple SVD core maps; select explicit core qualifiers.");
        return new Selection(State.VERIFIED_BUILD_SOC, selectedBoard + "/" + soc,
                "Effective generated CONFIG_SOC=" + soc + " for board " + board
                + "; SVD file/model still requires exact verification.");
    }
}
