// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.List;
import java.util.Objects;

/**
 * Task-first explanations of observed generated-DTS evidence.
 *
 * A child/beginner should start with "read a voltage", not CONFIG_ADC.
 * The result NEVER equates a described controller to bonded package pins,
 * enabled driver, wiring, a usable channel, or runtime readiness.
 */
public final class BeginnerHardwareGuide {
    private BeginnerHardwareGuide() {}

    public record Goal(String title, String category, String plainEnglish) {}
    public record Guidance(Goal goal, String headline, String nextAction,
                           String steps, String evidence, int described, int markedOkay) {}

    public static final List<Goal> GOALS = List.of(
            new Goal("Blink a light", "GPIO", "Turn an output on and off."),
            new Goal("Read a voltage", "ADC", "Measure an analogue signal."),
            new Goal("Send text to a computer", "UART", "Send and receive serial data."),
            new Goal("Connect an I2C sensor", "I2C", "Talk to a sensor using two signal lines."),
            new Goal("Connect an SPI device", "SPI", "Exchange data with an SPI device."),
            new Goal("Send a CAN message", "CAN", "Communicate on a CAN bus."));

    public static Guidance inspect(String generated, boolean stale, Goal requested) {
        Goal goal = Objects.requireNonNull(requested, "goal");
        if (!GOALS.contains(goal)) throw new IllegalArgumentException("Unknown learning goal");
        String steps = "1. Find the " + goal.category() + " controller described by this board's build.\n"
                + "2. Check whether its node is enabled and the required pins/channels are described.\n"
                + "3. Use the setup guide to check its binding and driver configuration.\n"
                + "4. Build again, then use a verified Zephyr C example and test on hardware.";
        String warning = "A node in DTS describes hardware; it does NOT prove an available package pin,"
                + " a ready driver, a connected sensor or successful firmware operation.";
        if (generated == null || generated.isBlank())
            return new Guidance(goal, "First, build your selected board.",
                    "Click Build & validate to discover what the selected board describes.",
                    steps, "No generated zephyr.dts for the selected build. "
                    + "No controller or pin has been inferred. " + warning, 0, 0);

        var matching = DevicetreeHardware.inspect(generated).stream()
                .filter(device -> HardwarePeripheralIndex.category(device).equals(goal.category()))
                .toList();
        int marked = (int) matching.stream().filter(device ->
                device.status().isBlank() || device.status().equals("okay")
                        || device.status().equals("ok")).count();
        String paths = matching.stream().limit(3).map(DevicetreeHardware.Device::path)
                .reduce((a, b) -> a + ", " + b).orElse("(none found)");
        String evidence = "Source of these observations: the last generated zephyr.dts.\n"
                + "Matching controller node paths: " + paths + "\n"
                + marked + " of " + matching.size() + " described nodes have a local"
                + " 'okay' or default status. Parent dependencies, bindings, pinctrl,"
                + " drivers and electrical connections have not been verified.\n" + warning;
        if (stale)
            return new Guidance(goal, "Your board settings changed since the last build.",
                    "Click Build & validate before relying on any listed controller or example.",
                    steps, evidence + "\nPrevious snapshot is outdated.", matching.size(), marked);
        if (matching.isEmpty())
            return new Guidance(goal, "No " + goal.category()
                    + " controller was found in this generated board description.",
                    "Open the hardware guide to check the board's upstream DTS/DTSI and driver support. "
                    + "This is NOT proof the chip lacks that peripheral.",
                    steps, evidence, 0, 0);
        return new Guidance(goal,
                "I found " + matching.size() + " " + goal.category()
                        + " controller description" + (matching.size() == 1 ? "" : "s")
                        + " in this board's generated files.",
                marked == 0
                        ? "The matching nodes are not marked enabled locally. Inspect a real controller, "
                            + "then use the setup guide before proposing an overlay."
                        : "Choose a controller in Hardware, then follow the setup guide. "
                            + "A node marked 'okay' is NOT a tested hardware connection.",
                steps, evidence, matching.size(), marked);
    }
}
