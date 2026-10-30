// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.List;
import java.util.Locale;

/**
 * Navigation-only grouping of nodes from the exact generated Devicetree.
 * It does not infer pin availability, package geometry, driver enablement
 * or supported functions from a node name.
 */
public final class HardwarePeripheralIndex {
    private HardwarePeripheralIndex() {}

    public static final String ALL = "All peripherals";
    public static final List<String> FILTERS = List.of(ALL, "ADC", "GPIO", "UART", "I2C",
            "SPI", "PWM", "Timers", "DMA", "CAN", "Pinctrl", "Other");

    /** Classification is an aid to navigation, not hardware capability evidence. */
    public static String category(DevicetreeHardware.Device device) {
        String name = device.name().toLowerCase(Locale.ROOT);
        String compatible = device.compatible().toLowerCase(Locale.ROOT);
        if (name.equals("/") || name.equals("soc") || name.equals("aliases")
                || name.equals("chosen") || name.equals("cpus") || name.startsWith("channel@"))
            return "Other";
        // gpio-leds and gpio-keys are consumers, not GPIO controllers.
        if (compatible.contains("gpio-leds") || compatible.contains("gpio-keys"))
            return "Other";
        if (named(name, "adc") || compatible.contains("-adc")) return "ADC";
        if (named(name, "gpio") || compatible.contains("-gpio")) return "GPIO";
        if (named(name, "uart") || named(name, "usart") || named(name, "serial")
                || compatible.contains("-uart") || compatible.contains("-usart")) return "UART";
        if (named(name, "i2c") || named(name, "iic") || compatible.contains("-i2c")) return "I2C";
        if (named(name, "spi") || compatible.contains("-spi")) return "SPI";
        if (named(name, "pwm") || compatible.contains("-pwm")) return "PWM";
        if (named(name, "timer") || named(name, "tim") || compatible.contains("-timer")) return "Timers";
        if (named(name, "dma") || compatible.contains("-dma")) return "DMA";
        if (named(name, "can") || compatible.contains("-can")) return "CAN";
        if (named(name, "pin-controller") || named(name, "pinctrl")
                || compatible.contains("-pinctrl") || compatible.contains("-pinmux"))
            return "Pinctrl";
        return "Other";
    }

    public static boolean matches(DevicetreeHardware.Device device, String filter) {
        return filter == null || ALL.equals(filter) || filter.isBlank()
                || category(device).equals(filter);
    }

    private static boolean named(String value, String prefix) {
        return value.equals(prefix) || value.startsWith(prefix + "@")
                || value.startsWith(prefix + "-");
    }
}
