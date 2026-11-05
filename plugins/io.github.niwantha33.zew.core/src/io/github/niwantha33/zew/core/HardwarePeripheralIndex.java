// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.List;
import java.util.Locale;

/**
 * Classifies real generated Devicetree nodes for navigation, not silicon features.
 * Classification does not establish driver enablement, pad bonding or runtime use.
 * Peripheral-like pinctrl state names and gpio-leds consumers are excluded.
 */
public final class HardwarePeripheralIndex {
    private HardwarePeripheralIndex() {}

    public static final String ALL = "All peripherals";
    public static final List<String> FILTERS = List.of(ALL, "ADC", "DAC", "GPIO", "UART",
            "I2C", "SPI", "I2S", "PWM", "Timers", "DMA", "CAN", "USB", "RTC",
            "Watchdog", "Ethernet", "Pinctrl", "Flash / Storage", "Other");

    public static String category(DevicetreeHardware.Device device) {
        String name = device.name().toLowerCase(Locale.ROOT);
        String compatible = device.compatible().toLowerCase(Locale.ROOT);
        String path = device.path().toLowerCase(Locale.ROOT);

        // Check storage bindings BEFORE name-derived UART/SPI classifications.
        // For example "serial-flash@0" with "jedec,spi-nor" describes SPI
        // flash, not a UART controller or an SPI bus controller.
        if (storageDevice(name, compatible)) return "Flash / Storage";

        // Some MSPM0 versions place real GPIO controller nodes beneath pinctrl.
        // Only a compatible-bearing gpio controller is exempted from that rule.
        if (named(name, "gpio") && token(compatible, "gpio"))
            return "GPIO";
        if (path.contains("/pinctrl/") || path.contains("/pin-controller@")
                || path.contains("/pin-controller/")) return "Pinctrl";
        if (name.equals("/") || name.equals("soc") || name.equals("aliases")
                || name.equals("chosen") || name.equals("cpus") || name.startsWith("channel@"))
            return "Other";
        if (compatible.contains("gpio-leds") || compatible.contains("gpio-keys")
                || compatible.contains("pwm-leds") || compatible.contains("adc-keys"))
            return "Other";

        if (named(name, "adc") || named(name, "adc12") || token(compatible, "adc")
                || token(compatible, "adc12")) return "ADC";
        if (named(name, "dac") || token(compatible, "dac")) return "DAC";
        if (named(name, "gpio") || token(compatible, "gpio")) return "GPIO";
        if (named(name, "uart") || named(name, "usart") || named(name, "lpuart")
                || serialControllerName(name) || token(compatible, "uart")
                || token(compatible, "usart") || token(compatible, "lpuart")) return "UART";
        if (named(name, "i2c") || named(name, "iic") || named(name, "twi")
                || token(compatible, "i2c") || token(compatible, "iic")) return "I2C";
        if (named(name, "spi") || named(name, "qspi") || named(name, "ospi")
                || token(compatible, "spi") || token(compatible, "qspi")) return "SPI";
        if (named(name, "i2s") || token(compatible, "i2s")) return "I2S";
        // PWM child compatible "ti,mspm0-timer-pwm" must not be buried in Timers.
        if (named(name, "pwm") || token(compatible, "pwm")) return "PWM";
        if (named(name, "timer") || named(name, "tim") || named(name, "timg")
                || named(name, "tima") || token(compatible, "timer")
                || token(compatible, "counter")) return "Timers";
        if (named(name, "dma") || named(name, "bdma") || token(compatible, "dma")) return "DMA";
        if (named(name, "can") || named(name, "canfd") || named(name, "fdcan")
                || named(name, "mcan") || token(compatible, "can")
                || token(compatible, "canfd") || token(compatible, "mcan")
                || token(compatible, "fdcan")) return "CAN";
        if (named(name, "usb") || token(compatible, "usb")) return "USB";
        if (named(name, "rtc") || token(compatible, "rtc")) return "RTC";
        if (named(name, "watchdog") || named(name, "wdt") || token(compatible, "watchdog")
                || token(compatible, "wdt")) return "Watchdog";
        if (named(name, "ethernet") || named(name, "eth") || token(compatible, "ethernet"))
            return "Ethernet";
        if (named(name, "pin-controller") || named(name, "pinctrl")
                || token(compatible, "pinctrl") || token(compatible, "pinmux"))
            return "Pinctrl";
        return "Other";
    }

    public static boolean matches(DevicetreeHardware.Device device, String filter) {
        return filter == null || ALL.equals(filter) || filter.isBlank()
                || category(device).equals(filter);
    }

    private static boolean serialControllerName(String name) {
        // Only an ordinary serial@... controller, never serial-flash,
        // serial-eeprom or serial-console wrapper nodes.
        return name.equals("serial") || name.startsWith("serial@")
                || name.matches("serial[0-9]+(?:@.*)?");
    }

    private static boolean storageDevice(String name, String compatible) {
        if (compatible.contains("jedec,spi-nor") || compatible.contains("jedec,spi-nand")
                || compatible.contains("cfi-flash") || compatible.contains("soc-nv-flash")
                || compatible.contains("zephyr,flash-disk")
                || compatible.contains("spi-nor") || compatible.contains("spi-nand")
                || compatible.contains("nand-controller")
                || compatible.contains("sdhc") || compatible.contains("sdmmc")
                || compatible.contains("mmc-controller") || compatible.contains("nvme"))
            return true;
        // Exact node names are only provisional when binding metadata is absent.
        // Exclude partitions, which are layout within a parent Flash device.
        return (named(name, "flash") || named(name, "nor-flash")
                || named(name, "nand") || named(name, "sdhc")
                || named(name, "sdmmc"))
                && !name.startsWith("flash-partition");
    }

    private static boolean named(String value, String prefix) {
        return value.equals(prefix) || value.startsWith(prefix + "@")
                || value.startsWith(prefix + "-")
                || value.matches(java.util.regex.Pattern.quote(prefix) + "[0-9]+(?:@.*)?");
    }

    private static boolean token(String value, String suffix) {
        // Tokens in comma-separated compatible strings: e.g. "ti,mspm0-canfd".
        // Avoid matching "scan", "can-controller" or generic consumer names.
        return value.matches(".*(?:[-,])" + java.util.regex.Pattern.quote(suffix)
                + "(?:[0-9]+)?(?:[,\\s-].*)?$");
    }
}
