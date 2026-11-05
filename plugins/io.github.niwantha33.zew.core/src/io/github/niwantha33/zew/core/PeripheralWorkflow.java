// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cross-vendor, task-focused help for EVERY category in the actual Zephyr
 * hardware index. These are semantic checks, NOT pin selections, support
 * promises, or proposed values. All actual controller/property rows still
 * originate from the selected generated Devicetree and its bindings.
 */
public final class PeripheralWorkflow {
    private PeripheralWorkflow() {}

    public record Plan(String category, String connectionTitle, String connectionHelp,
                       String settingsHelp, String reviewHelp, List<String> properties) {
        public Plan { properties = List.copyOf(properties); }
        public List<String> steps() {
            return List.of("Controller", connectionTitle, "Settings", "Review");
        }
    }

    private static final List<String> SHARED = List.of(
            "status", "compatible", "reg", "interrupts", "interrupt-parent",
            "clocks", "clock-names", "resets", "pinctrl-0", "pinctrl-1",
            "pinctrl-names", "dmas", "dma-names", "power-domains");

    private static Plan plan(String kind, String title, String connections,
                             String settings, String review, String... keys) {
        var props = new LinkedHashSet<>(SHARED);
        props.addAll(List.of(keys));
        return new Plan(kind, title, connections, settings, review, List.copyOf(props));
    }

    private static final Map<String, Plan> PLANS;
    static {
        var list = new LinkedHashMap<String, Plan>();
        for (Plan p : List.of(
                plan("GPIO", "Port & line",
                        "Choose a real GPIO controller and logical line from generated DTS. "
                        + "Check the #gpio-cells binding, polarity, input/output mode and pull/interrupt options. "
                        + "A logical line is NOT a physical package pad.",
                        "Review gpio-controller, gpio-ranges, #gpio-cells and consumer *-gpios "
                        + "phandles. Check the requested CONFIG_GPIO against the effective build.",
                        "Check line ownership, consumer conflicts and electrical limits; never invent a pin.",
                        "gpio-controller", "gpio-ranges", "#gpio-cells", "gpios", "gpio-hog",
                        "input-enable", "output-enable", "bias-pull-up", "bias-pull-down"),
                plan("ADC", "Pin & channel",
                        "Select a real ADC controller and exact io-channels input. "
                        + "Verify channel@N metadata, phandle cells and the /zephyr,user consumer. "
                        + "Do not equate an input number with a physical pad.",
                        "Review gain, reference, acquisition time, resolution and oversampling "
                        + "only where supported by this controller's exact binding.",
                        "Check the complete channel, pinmux and driver; require scratch CMake "
                        + "verification before accepting a source change.",
                        "#io-channel-cells", "io-channels", "zephyr,gain",
                        "zephyr,reference", "zephyr,acquisition-time",
                        "zephyr,resolution", "zephyr,oversampling", "zephyr,vref-mv"),
                plan("DAC", "Output & channel",
                        "Inspect DAC channel child nodes and output routing. "
                        + "Verify reference voltage, analog output pin and electrical loading in exact metadata.",
                        "Inspect #io-channel-cells, channel resolution and reference "
                        + "from the vendor binding; a DAC channel is not a pin number.",
                        "Validate output-channel wiring and driver configuration, including shared analog resources.",
                        "#io-channel-cells", "io-channels", "resolution", "vref-mv",
                        "zephyr,resolution", "zephyr,vref-mv"),
                plan("UART", "TX/RX & flow control",
                        "Inspect TX/RX pinctrl, optional CTS/RTS signals and routing through "
                        + "chosen/aliases. Confirm the board electrical interface and signal ownership.",
                        "Inspect current-speed, parity, stop-bits and hw-flow-control "
                        + "only when provided by this binding; the serial driver must also be configured.",
                        "Check console/shell conflicts, pinctrl and peer settings before a serial test.",
                        "current-speed", "parity", "stop-bits", "data-bits", "hw-flow-control",
                        "rx-pin", "tx-pin"),
                plan("I2C", "SDA/SCL & clients",
                        "Inspect SDA/SCL pinctrl, external pull-ups, bus voltage and child "
                        + "devices. A child reg is usually a bus address, not a CPU address.",
                        "Review clock-frequency and client compatible/reg only if defined by "
                        + "the selected controller/client bindings.",
                        "Check duplicate client addresses, bus ownership and pinmux before configuring.",
                        "clock-frequency", "sda-gpios", "scl-gpios", "reg", "address-cells"),
                plan("SPI", "Bus & chip select",
                        "Verify SCK/MOSI/MISO pinctrl and chip-select assignments. "
                        + "A child reg is a chip-select index, not a physical package pin.",
                        "Review spi-max-frequency, mode polarity/phase and cs-gpios "
                        + "against each client's binding and the controller capabilities.",
                        "Check competing CS lines and pins, voltage and DMA requirements.",
                        "spi-max-frequency", "spi-cpol", "spi-cpha", "cs-gpios",
                        "duplex", "frame-format", "word-size"),
                plan("I2S", "Audio signals",
                        "Verify BCLK, frame-sync, data pins and master/slave clock ownership. "
                        + "Do not borrow a SPI or I2C pinout.",
                        "Review codec and clock configuration, channel count, sample width "
                        + "and sample-rate constraints in actual board/binding metadata.",
                        "Check audio-clock conflicts, codec wiring and DMA suitability.",
                        "clock-frequency", "mclk-frequency", "dai-format", "dai-tdm-slot-num",
                        "dai-tdm-slot-width"),
                plan("PWM", "Timer & output",
                        "Locate the real PWM provider and its #pwm-cells. "
                        + "Check output timer channel, pinctrl and the consumer pwms phandle.",
                        "Inspect periods and flags from the consumer, but do not infer "
                        + "an allowed frequency or package pad from a channel number.",
                        "Check channel/timer sharing, polarity and pin resource conflicts.",
                        "#pwm-cells", "pwms", "pwm-names", "prescaler", "clock-frequency"),
                plan("Timers", "Counter & clock",
                        "Inspect clock source, counter block, capture/compare channels and "
                        + "interrupt routing. Do not treat an available timer as unclaimed.",
                        "Review clocks, prescalers and timer properties in the vendor "
                        + "binding. Actual runtime frequency is not inferred from DTS alone.",
                        "Check shared channels, RTOS timebase and interrupt conflicts.",
                        "clock-frequency", "prescaler", "counter-width", "timer-frequency"),
                plan("DMA", "Requests & streams",
                        "Inspect the DMA controller, request-cell binding, stream/channel "
                        + "allocation and each device consumer's dmas phandles.",
                        "Check #dma-cells, dma-names, directions and constraints using "
                        + "the binding's real cell names; never assume a channel index format.",
                        "Check request/stream conflicts, memory visibility and cache/coherency constraints.",
                        "#dma-cells", "dma-channels", "dma-requests", "dmas", "dma-names"),
                plan("CAN", "TX/RX & transceiver",
                        "Inspect CAN TX/RX pinctrl plus external transceiver, voltage, "
                        + "termination and oscillator routing. DTS controller alone is not a functioning bus.",
                        "Review bus speed/FD timing and transceiver phandles where defined. "
                        + "Check CONFIG_CAN and clock support.",
                        "Check transceiver ownership, bitrate/FD compatibility and shared pins.",
                        "bus-speed", "sample-point", "sjw", "phys", "transceiver",
                        "max-bitrate"),
                plan("USB", "Role & data lines",
                        "Check USB controller role, D+/D-/PHY or high-speed pins, "
                        + "VBUS sensing/power and board connector wiring.",
                        "Inspect dr_mode, phys, phy-names, maximum-speed and regulator "
                        + "dependencies where supported by the selected binding.",
                        "Check host/device role, power and pin conflicts. Build support is not USB enumeration.",
                        "dr_mode", "phys", "phy-names", "maximum-speed",
                        "vbus-supply", "vbus-gpios"),
                plan("RTC", "Clock & wakeup",
                        "Inspect RTC source clock/oscillator, backup domain and wakeup "
                        + "interrupt. Battery-backed capability is not assumed.",
                        "Review alarm and clock configuration from real bindings and "
                        + "effective Kconfig; do not promise time accuracy.",
                        "Check clock selection, power modes and shared wakeup interrupts.",
                        "clock-frequency", "alarms-count", "wakeup-source"),
                plan("Watchdog", "Timeout & reset",
                        "Check watchdog clock source, reset connection and any "
                        + "hardware window. An enabled watchdog node is not proof it is running.",
                        "Review timeout/window/reset-mode support in the driver/binding; "
                        + "do not invent a timeout register value.",
                        "Ensure application feeds and reset behavior are safe for the exact hardware.",
                        "timeout-sec", "window-min", "window-max", "reset-mode"),
                plan("Ethernet", "MAC & PHY",
                        "Inspect PHY phandle, interface mode (RMII/MII/RGMII etc.), "
                        + "reference clock, MDIO, reset and package pins.",
                        "Review phy-mode, phy-handle, MAC address sources and clock "
                        + "requirements; board wiring must match the selected PHY.",
                        "Check PHY address collisions, shared clocks, pins and power rails.",
                        "phy-mode", "phy-handle", "local-mac-address",
                        "mac-address", "mdio", "reset-gpios"),
                plan("Pinctrl", "States & pads",
                        "Trace controller and state nodes to exact pinmux cells. "
                        + "Logical signal groups are not proof of bonded physical pads.",
                        "Inspect pinmux, bias, drive strength and input/output options "
                        + "only from the exact vendor pinctrl binding.",
                        "Cross-check assigned pads for conflicts across all active consumers.",
                        "pinmux", "bias-pull-up", "bias-pull-down",
                        "drive-strength", "input-enable", "output-enable"),
                plan("Other", "Binding-specific connections",
                        "Unclassified compatible-bearing nodes from the exact generated DTS. "
                        + "Inspect their real #*-cells, phandles, parent bus and binding; "
                        + "no generic pin/channel recipe is safe.",
                        "Inspect properties and Zephyr YAML for the selected compatible. "
                        + "Unknown vendors or bindings must remain unsupported for automatic edits.",
                        "Only binding-qualified source changes can be proposed; do not assume this is a GPIO or ADC.",
                        "compatible", "reg", "#address-cells", "#size-cells", "ranges")
        )) {
            if (list.put(p.category(), p) != null)
                throw new IllegalStateException("Duplicate peripheral plan " + p.category());
        }
        PLANS = Map.copyOf(list);
        var expected = HardwarePeripheralIndex.FILTERS.stream()
                .filter(kind -> !HardwarePeripheralIndex.ALL.equals(kind)).toList();
        if (!PLANS.keySet().containsAll(expected))
            throw new IllegalStateException("Every hardware category requires a focused plan: " + expected);
    }

    /** One category for each Zephyr index family, including vendor-specific Other. */
    public static List<String> categories() {
        return HardwarePeripheralIndex.FILTERS.stream()
                .filter(kind -> !HardwarePeripheralIndex.ALL.equals(kind)).toList();
    }

    public static Plan forCategory(String category) {
        Plan found = PLANS.get(category);
        if (found == null) throw new IllegalArgumentException("Unknown peripheral category: " + category);
        return found;
    }

    /** Structural DTS nodes are not unclassified user-configurable controllers. */
    public static boolean selectable(DevicetreeHardware.Device node, String category) {
        if (!HardwarePeripheralIndex.category(node).equals(category)) return false;
        if (!"Other".equals(category)) return true;
        if (node.compatible().isBlank()) return false;
        // "Other" is for additional compatible-bearing devices, not RAM,
        // flash, partitions or structural application containers.
        String name = node.name().toLowerCase(java.util.Locale.ROOT);
        String compatible = node.compatible().toLowerCase(java.util.Locale.ROOT);
        return !name.matches("(?:memory|sram|flash|partition|cpu|cpus|chosen|aliases)(?:@.*|[-_].*)?")
                && !name.equals("zephyr,user")
                && !compatible.contains("mmio-sram")
                && !compatible.contains("soc-nv-flash")
                && !compatible.contains("fixed-partition");
    }

    /** Compact overview: six common entries even with no build, plus ALL
     * additional categories that the selected built DTS actually describes. */
    public static List<String> overviewCategories(List<DevicetreeHardware.Device> devices) {
        var shown = new LinkedHashSet<>(FocusedConfigurationModel.COMMON);
        for (String kind : categories())
            if (!shown.contains(kind) && devices.stream().anyMatch(d -> selectable(d, kind)))
                shown.add(kind);
        return List.copyOf(shown);
    }

    /** Values displayed here are evidence, not editable suggestions. */
    public static String describeProperties(String builtDts, String path, String category) {
        if (builtDts == null || builtDts.isBlank() || path == null || path.isBlank())
            return "No generated controller properties. Build this exact board/profile first.";
        var allowed = Set.copyOf(forCategory(category).properties());
        var output = new StringBuilder();
        int count = 0;
        for (DevicetreeParameters.Property p : DevicetreeParameters.inspect(builtDts)) {
            if (!p.path().equals(path) && !p.path().startsWith(path + "/")) continue;
            // Show only the actual values decoded from the build, never
            // placeholder values or a made-up register/pin assignment.
            if (!allowed.contains(p.name())) continue;
            if (count++ >= 48) {
                output.append("\nMore properties are available in the advanced source browser.");
                break;
            }
            output.append(p.path()).append("  •  ").append(p.name())
                    .append(" = ").append(p.value()).append('\n');
        }
        return count == 0
                ? "No matching generated properties. Inspect the exact binding and source."
                : output.toString();
    }
}
