// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read-only, project/build-scoped presentation of *existing* Zephyr evidence.
 *
 * No vendor capability is inferred from an absent node, no physical pin is
 * manufactured from a logical channel number, and no described controller is
 * called active at runtime. Keep the UI small and the state labels precise.
 */
public final class FocusedConfigurationModel {
    private FocusedConfigurationModel() {}

    public enum State { CONFIGURED, DISABLED, SETUP_INCOMPLETE, UNSUPPORTED, UNKNOWN }
    public enum Freshness { CURRENT_SNAPSHOT, STALE_SNAPSHOT, NO_BUILD }
    public record Row(String category, State state, String controller, String explanation,
                      String nextAction) {}
    public record Issue(String title, String detail, String nextAction, String category) {}
    public record Capacity(String label, String value, String evidence) {}
    public record Overview(Freshness freshness, String board, String profile,
                           Capacity flash, Capacity ram, List<Row> peripherals,
                           List<Issue> issues) {
        public Overview {
            peripherals = List.copyOf(peripherals);
            issues = List.copyOf(issues);
        }
    }
    public static final List<String> COMMON = List.of("GPIO", "ADC", "UART", "I2C", "SPI", "CAN");
    private static final Map<String, String> DRIVER = Map.of(
            "GPIO", "GPIO", "ADC", "ADC", "UART", "SERIAL",
            "I2C", "I2C", "SPI", "SPI", "CAN", "CAN");

    public static Overview inspect(String board, String profile, String builtDts,
                                   String generatedConfig, boolean stale) {
        String dts = builtDts == null ? "" : builtDts;
        String config = generatedConfig == null ? "" : generatedConfig;
        Freshness freshness = dts.isBlank() ? Freshness.NO_BUILD
                : stale ? Freshness.STALE_SNAPSHOT : Freshness.CURRENT_SNAPSHOT;
        List<DevicetreeHardware.Device> devices = DevicetreeHardware.inspect(dts);
        List<Row> rows = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();
        for (String category : COMMON) {
            var matches = devices.stream()
                    .filter(d -> category.equals(HardwarePeripheralIndex.category(d))).toList();
            if (matches.isEmpty()) {
                String why = dts.isBlank() ? "No generated board description yet."
                        : "Not described by the selected built Devicetree; silicon support is unknown.";
                rows.add(new Row(category, State.UNKNOWN, "", why,
                        "Build this target or inspect its board/SoC source and bindings."));
                continue;
            }
            var enabled = matches.stream()
                    .filter(d -> PeripheralCoverage.effectiveEnabled(d, devices)).toList();
            if (freshness == Freshness.STALE_SNAPSHOT) {
                rows.add(new Row(category, State.UNKNOWN, matches.get(0).path(),
                        "Outdated build evidence; previous status is not current.",
                        "Rebuild the selected board and profile."));
                continue;
            }
            if (enabled.isEmpty()) {
                var d = matches.get(0);
                rows.add(new Row(category, State.DISABLED, d.path(),
                        "Disabled by this node or an ancestor in generated DTS.",
                        "Review the parent status, binding, clocks and pinctrl before proposing an overlay."));
                continue;
            }
            var selected = enabled.get(0);
            boolean driverRequested = config.lines().anyMatch(
                    line -> line.equals("CONFIG_" + DRIVER.get(category) + "=y"));
            var guide = PeripheralStartGuide.inspect(dts, category, selected.path(), config, stale);
            if (category.equals("ADC") && driverRequested
                    && guide.state() == PeripheralStartGuide.State.ADC_WIRING_FOUND) {
                rows.add(new Row(category, State.CONFIGURED, selected.path(),
                        "Generated DTS channel link and ADC Kconfig observed. Physical pins and runtime not verified.",
                        "Review the exact channel, pinmux and source evidence; test on hardware separately."));
            } else {
                rows.add(new Row(category, State.SETUP_INCOMPLETE, selected.path(),
                        category.equals("ADC") ? guide.explanation()
                                : "Controller described and enabled in DTS. Driver, consumer and physical wiring not fully verified.",
                        category.equals("ADC")
                                ? "Open the ADC controller → pin/channel → settings → review guide."
                                : "Open the matching peripheral guide and inspect its binding and board wiring."));
            }
        }
        if (freshness == Freshness.NO_BUILD) {
            issues.add(new Issue("Build data missing",
                    "No generated zephyr.dts for the selected board/profile.",
                    "Build & validate to discover real configuration evidence.", ""));
        } else if (stale) {
            issues.add(new Issue("Build data is stale",
                    "Project source or overlay changed after the last generated output.",
                    "Rebuild before treating last-build values as current.", ""));
        }
        for (Row row : rows) if (row.state() == State.SETUP_INCOMPLETE
                || row.state() == State.DISABLED)
            issues.add(new Issue(row.category() + ": " + display(row.state()),
                    row.explanation(), row.nextAction(), row.category()));
        var memory = DevicetreeMemoryMap.inspect(dts).regions();
        return new Overview(freshness, board == null ? "Not selected" : board,
                profile == null ? "default" : profile,
                capacity("Flash", DevicetreeMemoryMap.Kind.FLASH, memory, stale),
                capacity("RAM", DevicetreeMemoryMap.Kind.RAM, memory, stale), rows, issues);
    }

    public static String display(State s) {
        return switch (s) {
            case CONFIGURED -> "Configured (build)";
            case DISABLED -> "Disabled";
            case SETUP_INCOMPLETE -> "Setup incomplete";
            case UNSUPPORTED -> "Unsupported";
            case UNKNOWN -> "Unknown";
        };
    }

    /**
     * Only an unambiguous, CPU-mapped memory region is a capacity in this
     * compact summary. Multiple banks/aliases require the full address view.
     */
    private static Capacity capacity(String kind, DevicetreeMemoryMap.Kind type,
                                     List<DevicetreeMemoryMap.Region> regions, boolean stale) {
        var candidates = regions.stream().filter(r -> r.kind() == type
                && r.cpuMapped() && r.size().compareTo(BigInteger.ZERO) > 0).toList();
        if (candidates.size() == 1) {
            var c = candidates.get(0);
            return new Capacity(kind, c.sizeLabel() + (stale ? " (stale)" : ""),
                    "Last generated Devicetree " + c.path() + ", " + c.cpuHex()
                    + " to " + c.endHex() + " (end exclusive). This is capacity, not usage.");
        }
        return new Capacity(kind, "Unknown",
                candidates.isEmpty()
                    ? "No verified, CPU-mapped " + kind + " range in this generated Devicetree."
                    : "Multiple mapped " + kind + " ranges/aliases: inspect the memory map. No sum assumed.");
    }

    public static boolean canOfferAdcCode(PeripheralStartGuide.Guide guide, boolean stale,
                                          String config) {
        return !stale && guide != null
                && guide.state() == PeripheralStartGuide.State.ADC_WIRING_FOUND
                && !guide.cExample().isBlank()
                && config != null && config.lines().anyMatch("CONFIG_ADC=y"::equals);
    }

    /**
     * A logical ADC input is not a physical package pad. Never offer automatic
     * assignment without a validated exact-package mapping and conflict check.
     */
    public static String pinEvidence(PeripheralStartGuide.Guide guide) {
        if (guide == null || guide.state() != PeripheralStartGuide.State.ADC_WIRING_FOUND)
            return "Unknown: no unambiguous controller/channel consumer was verified. "
                    + "Do not assign a package pad or create a guessed io-channels link.";
        return "Logical ADC channel reference found in generated DTS. Physical pad, "
                + "package bonding, pinctrl and resource conflicts remain unverified. "
                + "Check the exact board schematic and Zephyr pin metadata before applying.";
    }
}
