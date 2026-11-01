// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only beginner's path from the *exact generated* Zephyr Devicetree to an
 * application. Applies to every vendor/SoC. Never infer pinmux, chip capability,
 * driver support, board pins or a compilable overlay from a peripheral name.
 */
public final class PeripheralStartGuide {
    private PeripheralStartGuide() {}

    public enum State { BUILD_REQUIRED, NOT_IN_DTS, DTS_DISABLED, SETUP_REQUIRED,
                        ADC_WIRING_FOUND, INSPECT_BINDING }
    public record Guide(String category, String controllerPath, State state, String explanation,
                        String steps, String configExample, String overlayExample,
                        String cExample, String upstreamSample, int generatedLine) {}

    private static final Pattern GROUP = Pattern.compile("<([^<>]{1,1024})>");
    private static final Pattern NUMBER = Pattern.compile("(?:0[xX][0-9a-fA-F]+|[0-9]+)");
    private static final Map<String, String> SYMBOLS = Map.ofEntries(
            Map.entry("ADC", "CONFIG_ADC"), Map.entry("UART", "CONFIG_SERIAL"),
            Map.entry("I2C", "CONFIG_I2C"), Map.entry("SPI", "CONFIG_SPI"),
            Map.entry("CAN", "CONFIG_CAN"), Map.entry("GPIO", "CONFIG_GPIO"),
            Map.entry("PWM", "CONFIG_PWM"), Map.entry("DAC", "CONFIG_DAC"),
            Map.entry("I2S", "CONFIG_I2S"), Map.entry("DMA", "CONFIG_DMA"),
            Map.entry("Watchdog", "CONFIG_WATCHDOG"), Map.entry("RTC", "CONFIG_RTC"),
            Map.entry("USB", "CONFIG_USB_DEVICE_STACK"), Map.entry("Ethernet", "CONFIG_NETWORKING"));

    private static final Map<String, String> SAMPLES = Map.ofEntries(
            Map.entry("ADC", "samples/drivers/adc/adc_dt"),
            Map.entry("UART", "samples/subsys/console/echo"),
            Map.entry("CAN", "samples/drivers/can/counter"),
            Map.entry("GPIO", "samples/basic/blinky"),
            Map.entry("DAC", "samples/drivers/dac"),
            Map.entry("I2S", "samples/drivers/i2s/echo"));

    public static Guide inspect(String generatedDts, String category, String selectedPath,
                                String effectiveKconfig, boolean stale) {
        String selectedCategory = category == null || category.isBlank()
                || HardwarePeripheralIndex.ALL.equals(category) ? "ADC" : category;
        String source = generatedDts == null ? "" : generatedDts;
        if (source.isBlank()) return new Guide(selectedCategory, "", State.BUILD_REQUIRED,
                "No generated zephyr.dts exists for this exact target and build profile.",
                "1. Select Project settings and the exact board/SoC.\n"
                + "2. Build & validate to generate zephyr/.config and zephyr/zephyr.dts.\n"
                + "3. Return to Hardware and select a real controller.", "",
                "", "", sample(selectedCategory), -1);
        var devices = DevicetreeHardware.inspect(source);
        DevicetreeHardware.Device selected = null;
        String requestedAdcInput = "";
        if (selectedPath != null) {
            for (var device : devices) if (device.path().equals(selectedPath)) {
                selected = device;
                break;
            }
            if (selected != null && selected.name().startsWith("channel@")) {
                Long input = number(selected.name().substring("channel@".length()));
                requestedAdcInput = input == null ? "" : Long.toString(input);
                String parent = selected.path().substring(0, selected.path().lastIndexOf('/'));
                selected = devices.stream().filter(device -> device.path().equals(parent))
                        .findFirst().orElse(null);
            }
        }
        if (selected == null || !selectedCategory.equals(HardwarePeripheralIndex.category(selected))) {
            selected = devices.stream()
                    .filter(device -> selectedCategory.equals(HardwarePeripheralIndex.category(device)))
                    .sorted(Comparator.comparing(DevicetreeHardware.Device::path))
                    .findFirst().orElse(null);
        }
        if (selected == null) {
            return new Guide(selectedCategory, "", State.NOT_IN_DTS,
                    "No " + selectedCategory + " controller node is described in this generated Zephyr DTS. "
                    + "That is NOT evidence the silicon lacks " + selectedCategory + ".",
                    "1. Inspect the exact SoC silicon catalogue or official datasheet for hardware capabilities.\n"
                    + "2. Inspect this Zephyr version's SoC .dtsi, board .dts, binding and driver source.\n"
                    + "3. If the controller is not represented, implement upstream-compatible DTS/binding/"
                    + "driver support before attempting application configuration.\n"
                    + "4. Do NOT create an overlay for a nonexistent controller or guess pinmux addresses.",
                    config(selectedCategory, effectiveKconfig), "", "", sample(selectedCategory), -1);
        }
        String path = selected.path();
        String sym = SYMBOLS.get(selectedCategory);
        String state = effectiveKconfig == null || sym == null ? "not checked"
                : (Pattern.compile("(?m)^" + Pattern.quote(sym) + "=y\\s*$")
                        .matcher(effectiveKconfig).find() ? "y in last generated .config" : "not y in last generated .config");
        String common = "1. Generated controller: " + path + " (line " + selected.line() + ")\n"
                + "   Compatible: " + selected.compatible() + "\n"
                + "   Node status: " + (PeripheralCoverage.effectiveEnabled(selected, devices)
                    ? "okay/default" : "disabled (local or ancestor)") + "\n"
                + "2. Driver Kconfig check " + (sym == null ? "(manual binding lookup required)"
                    : sym + ": " + state) + ". A Kconfig symbol does not prove a linked driver.\n"
                + "3. Inspect this node's reg, interrupts, clocks, pinctrl and its vendor binding.\n"
                + "4. Check board schematic, package pins and channel input mapping; no pads are inferred.\n"
                + "5. Review app.overlay and prj.conf, then Build & validate the exact target.\n"
                + "6. Check generated zephyr.dts, .config and compiler/linker diagnostics.\n"
                + "7. Flash the matching image and observe the real peripheral only after validation.\n";
        String scope = "This is a read-only learning guide for the *selected generated build*. "
                + "It does not edit source or prove driver availability, live state or pin safety.";
        if (stale) return new Guide(selectedCategory, path, State.BUILD_REQUIRED,
                "BUILD REQUIRED: the generated DTS/Kconfig may be stale. Rebuild before trusting this guide.",
                common + "Refresh and rebuild before making a wiring or code decision.",
                config(selectedCategory, effectiveKconfig), "", "", sample(selectedCategory), selected.line());
        if (!PeripheralCoverage.effectiveEnabled(selected, devices)) {
            return new Guide(selectedCategory, path, State.DTS_DISABLED,
                    "Controller exists but is disabled by Devicetree status (possibly an ancestor). " + scope,
                    common + "Enable only this verified existing controller via a reviewed overlay, "
                    + "and validate its clocks, pinctrl, binding and driver with real Zephyr configure.",
                    config(selectedCategory, effectiveKconfig),
                    "// REFERENCE ONLY — controller is disabled. Review binding, clocks and package pins.\n"
                    + "// Existing controller: " + path + "\n"
                    + "// Do not assume adding status = \"okay\" is sufficient or electrically safe.\n",
                    "", sample(selectedCategory), selected.line());
        }
        if (!"ADC".equals(selectedCategory)) {
            return new Guide(selectedCategory, path, State.INSPECT_BINDING,
                    "Real " + selectedCategory + " controller in generated DTS. "
                    + "Application wiring and driver support still require validation. " + scope,
                    common + "For " + selectedCategory + ", inspect the upstream sample and its "
                    + "consumer binding. Do not paste an unverified controller-specific code template.",
                    config(selectedCategory, effectiveKconfig), "",
                    "// " + selectedCategory + " application example is intentionally gated.\n"
                    + "// Controller: " + path + "\n"
                    + "// Inspect the exact Zephyr sample and bus/client binding first:\n"
                    + "// " + sample(selectedCategory) + "\n",
                    sample(selectedCategory), selected.line());
        }
        var properties = DevicetreeParameters.inspect(source);
        var aliases = new LinkedHashMap<Long, String>();
        var duplicatePhandles = new java.util.HashSet<Long>();
        for (var p : properties) if (p.name().equals("phandle")) {
            Long parsed = number(singleCell(p.value()));
            if (parsed != null) {
                String previous = aliases.putIfAbsent(parsed, p.path());
                if (previous != null && !previous.equals(p.path())) duplicatePhandles.add(parsed);
            }
        }
        var io = properties.stream().filter(p -> p.path().equals("/zephyr,user")
                && p.name().equals("io-channels")).findFirst().orElse(null);
        boolean oneCellInput = properties.stream().anyMatch(p -> p.path().equals(path)
                && p.name().equals("#io-channel-cells") && "1".equals(singleCell(p.value())));
        int matchedIndex = -1;
        String channel = "";
        boolean ambiguous = false;
        if (io != null) {
            Matcher groups = GROUP.matcher(io.value());
            int index = 0;
            while (groups.find()) {
                String[] parts = groups.group(1).strip().split("\\s+");
                if (parts.length != 2 || !NUMBER.matcher(parts[0]).matches()
                        || !NUMBER.matcher(parts[1]).matches()) {
                    ambiguous = true;
                    break;
                }
                Long phandle = number(parts[0]);
                Long id = number(parts[1]);
                if (phandle == null || id == null) { ambiguous = true; break; }
                if (path.equals(aliases.get(phandle))) {
                    if (duplicatePhandles.contains(phandle)) { ambiguous = true; break; }
                    String input = Long.toString(id);
                    if (requestedAdcInput.isEmpty() || requestedAdcInput.equals(input)) {
                        if (matchedIndex != -1) { ambiguous = true; break; }
                        matchedIndex = index;
                        channel = input;
                    }
                }
                index++;
            }
            if (index == 0) ambiguous = true;
        }
        boolean ioMatched = oneCellInput && !ambiguous && matchedIndex >= 0;
        String adcSteps = common
                + "ADC-specific Devicetree relationships:\n"
                + "  • Controller node: " + path + "\n"
                + "  • Optional child channel@N: gain, reference, acquisition-time, "
                + "resolution and pin selection are binding-specific.\n"
                + "  • Application consumer: /zephyr,user with io-channels = <&ADC_NODE N>.\n"
                + "  • ADC_DT_SPEC_GET_BY_IDX(DT_PATH(zephyr_user), index) reads that reference.\n"
                + "  • Call adc_is_ready_dt(), adc_channel_setup_dt(), "
                + "adc_sequence_init_dt(), adc_read_dt().\n"
                + "  • Raw units are ADC codes; millivolts conversion requires a supported "
                + "reference and channel configuration.\n";
        if (!ioMatched) return new Guide("ADC", path, State.SETUP_REQUIRED,
                "ADC controller exists and DTS is okay, but no unambiguous /zephyr,user "
                + "io-channels reference to THIS controller was verified. " + scope,
                adcSteps + "Configure the exact channel in its binding and the application "
                + "io-channels consumer, then rebuild. "
                + "Do not guess ADC input, pin, gain, reference, resolution or channel count.",
                config("ADC", effectiveKconfig),
                "// REFERENCE ONLY — fill in a verified channel and exact input using the binding.\n"
                + "// Existing controller: " + path + "\n"
                + "// In app.overlay, connect zephyr,user io-channels only after "
                + "verifying the matching ADC input and pinctrl.\n"
                + "// See Zephyr sample: " + sample("ADC") + "\n",
                "", sample("ADC"), selected.line());
        // Require an actual child channel configuration for adc_channel_setup_dt.
        // Without it the sample may configure no gain/reference or simply fail.
        String channelPath = path + "/channel@" + channel;
        boolean configuredChannel = devices.stream().anyMatch(d -> d.path().equals(channelPath))
                && properties.stream().anyMatch(p -> p.path().equals(channelPath) && p.name().equals("reg")
                        && channel.equals(String.valueOf(number(singleCell(p.value())))))
                && properties.stream().anyMatch(p -> p.path().equals(channelPath) && p.name().equals("zephyr,gain"))
                && properties.stream().anyMatch(p -> p.path().equals(channelPath) && p.name().equals("zephyr,reference"))
                && properties.stream().anyMatch(p -> p.path().equals(channelPath) && p.name().equals("zephyr,acquisition-time"))
                && properties.stream().anyMatch(p -> p.path().equals(channelPath) && p.name().equals("zephyr,resolution"));
        if (!configuredChannel) return new Guide("ADC", path, State.SETUP_REQUIRED,
                "An io-channels link to this controller exists, but channel@" + channel
                + " lacks a verified complete Zephyr ADC child configuration. " + scope,
                adcSteps + "Inspect " + channelPath + " for actual gain/reference/acquisition-time/resolution "
                + "and pin mapping. The example remains withheld until the generated DTS is complete.",
                config("ADC", effectiveKconfig), "", "", sample("ADC"), selected.line());
        String cCode = adcExample(matchedIndex, channel);
        return new Guide("ADC", path, State.ADC_WIRING_FOUND,
                "Existing generated DTS connects /zephyr,user io-channels index "
                + matchedIndex + " to " + path + " input " + channel + ". "
                + "Compiler, ADC channel metadata, electrical pin assignment and driver remain UNVERIFIED. "
                + scope,
                adcSteps + "Wiring candidate: io-channels index " + matchedIndex + ", input "
                + channel + ". Review child channel@" + channel
                + ", CONFIG_ADC, and pinctrl before using the reference example. "
                + "Build and flash only after exact-board validation.",
                config("ADC", effectiveKconfig), "", cCode, sample("ADC"), selected.line());
    }

    private static String adcExample(int index, String channel) {
        // This example follows the Zephyr 4.4 adc_dt sample. It is a scaffold,
        // never a claim of compiled firmware or verified physical ADC input.
        return """
                /* REFERENCE EXAMPLE — do not overwrite working main.c.\n                 * This buffer holds a raw storage word, NOT a calibrated voltage.
                 * Generated DTS has io-channels index INDEX -> input CHANNEL.
                 * Verify gain/reference/pinctrl/channel child before building.
                 */
                #include <zephyr/kernel.h>
                #include <zephyr/device.h>
                #include <zephyr/devicetree.h>
                #include <zephyr/drivers/adc.h>
                #include <zephyr/sys/printk.h>
                #include <stdint.h>

                #if !DT_NODE_EXISTS(DT_PATH(zephyr_user)) || !DT_NODE_HAS_PROP(DT_PATH(zephyr_user), io_channels)
                #error "Missing zephyr,user io-channels: review app.overlay and rebuild"
                #endif
                static const struct adc_dt_spec adc_input =
                    ADC_DT_SPEC_GET_BY_IDX(DT_PATH(zephyr_user), INDEX);

                int main(void)
                {
                    uint32_t sample = 0;
                    struct adc_sequence sequence = {
                        .buffer = &sample,
                        .buffer_size = sizeof(sample),
                    };
                    if (!adc_is_ready_dt(&adc_input)) {
                        printk("ADC not ready\\n");
                        return -1;
                    }
                    int err = adc_channel_setup_dt(&adc_input);
                    if (err < 0) {
                        printk("ADC channel setup failed: %d\\n", err);
                        return err;
                    }
                    while (true) {
                        (void)adc_sequence_init_dt(&adc_input, &sequence);
                        err = adc_read_dt(&adc_input, &sequence);
                        if (err == 0) {
                            printk("ADC raw sample storage: 0x%x\\n", sample);
                        } else {
                            printk("ADC read failed: %d\\n", err);
                        }
                        k_sleep(K_MSEC(1000));
                    }
                }
                """.replace("INDEX", Integer.toString(index))
                    .replace("CHANNEL", channel);
    }

    private static String sample(String category) {
        String path = SAMPLES.get(category);
        return path == null ? switch (category) {
            case "I2C" -> "https://docs.zephyrproject.org/latest/hardware/peripherals/i2c.html";
            case "SPI" -> "https://docs.zephyrproject.org/latest/hardware/peripherals/spi.html";
            case "PWM" -> "https://docs.zephyrproject.org/latest/hardware/peripherals/pwm.html";
            default -> "https://docs.zephyrproject.org/latest/samples/index.html";
        } : "https://github.com/zephyrproject-rtos/zephyr/tree/v4.4.0/" + path;
    }

    private static String config(String category, String effective) {
        String sym = SYMBOLS.get(category);
        if (sym == null) return "// Inspect exact device binding, drivers and effective Zephyr Kconfig.";
        boolean enabled = effective != null && Pattern.compile("(?m)^" + Pattern.quote(sym)
                + "=y\\s*$").matcher(effective).find();
        return "# Recommended Kconfig symbol to REVIEW, not automatically apply.\n"
                + "# Actual effective setting: " + (enabled ? "y" : "not verified enabled") + "\n"
                + sym + "=y\n";
    }

    private static String singleCell(String value) {
        Matcher matcher = GROUP.matcher(value == null ? "" : value);
        if (!matcher.matches()) return "";
        String token = matcher.group(1).strip();
        return NUMBER.matcher(token).matches() ? token : "";
    }
    private static Long number(String literal) {
        if (literal == null || !NUMBER.matcher(literal).matches()) return null;
        try {
            long value = literal.startsWith("0x") || literal.startsWith("0X")
                    ? Long.parseUnsignedLong(literal.substring(2), 16)
                    : Long.parseLong(literal);
            return value > 0 && value <= 0xffff_ffffL ? value : null;
        } catch (NumberFormatException invalid) { return null; }
    }
}
