// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Navigation and explanations for the native peripheral configuration studio.
 *
 * This class deliberately categorises *existing* Kconfig symbols only. It
 * never promises that a peripheral, DMA controller or pin is present, and it
 * never resolves Zephyr's dependency expressions itself.
 */
public final class ConfigurationStudioDomains {
    private ConfigurationStudioDomains() {}

    public record Domain(String id, String title, String description,
                         String[] prefixes, String driverSymbol, String example) {
        public Domain {
            prefixes = prefixes.clone();
        }
        @Override public String[] prefixes() { return prefixes.clone(); }
    }

    private static final String ADC_EXAMPLE = """
            /* Template: configure a valid io-channels entry in your board overlay first.
             * Then check that its gain/reference/acquisition time suit your ADC.
             * This does not select a pin or enable an unsupported device. */
            #include <errno.h>
            #include <zephyr/device.h>
            #include <zephyr/drivers/adc.h>
            #include <zephyr/devicetree.h>

            #define APP_NODE DT_PATH(zephyr_user)
            #if DT_NODE_EXISTS(APP_NODE) && DT_NODE_HAS_PROP(APP_NODE, io_channels)
            static const struct adc_dt_spec adc =
                ADC_DT_SPEC_GET_BY_IDX(APP_NODE, 0);

            int read_sensor(int16_t *sample)
            {
                if (!adc_is_ready_dt(&adc)) return -ENODEV;
                int ret = adc_channel_setup_dt(&adc);
                if (ret) return ret;
                struct adc_sequence sequence = {
                    .buffer = sample,
                    .buffer_size = sizeof(*sample),
                };
                ret = adc_sequence_init_dt(&adc, &sequence);
                return ret ? ret : adc_read_dt(&adc, &sequence);
            }
            #endif
            """;
    private static final String GPIO_EXAMPLE = """
            /* Template: provide an alias 'led0' in your board's Devicetree. */
            #include <errno.h>
            #include <zephyr/device.h>
            #include <zephyr/drivers/gpio.h>
            #include <zephyr/devicetree.h>

            #if DT_NODE_EXISTS(DT_ALIAS(led0))
            static const struct gpio_dt_spec led =
                GPIO_DT_SPEC_GET(DT_ALIAS(led0), gpios);

            int configure_led(void)
            {
                return !gpio_is_ready_dt(&led) ? -ENODEV :
                        gpio_pin_configure_dt(&led, GPIO_OUTPUT_INACTIVE);
            }
            #endif
            """;

    public static final Domain OVERVIEW = new Domain("overview", "Overview",
            "Browse all resolved configuration symbols and project overrides.",
            new String[0], "", "");
    public static final Domain ADC = new Domain("adc", "ADC",
            "Analog inputs: driver, device instances, channel binding, resolution and sampling.",
            new String[] {"ADC"}, "ADC", ADC_EXAMPLE);
    public static final Domain GPIO = new Domain("gpio", "GPIO",
            "Digital inputs and outputs, port controllers, pin interrupts and pull settings.",
            new String[] {"GPIO"}, "GPIO", GPIO_EXAMPLE);
    public static final Domain I2C = new Domain("i2c", "I²C",
            "I²C bus drivers, controller support and attached devices.",
            new String[] {"I2C", "I3C"}, "I2C", "");
    public static final Domain SPI = new Domain("spi", "SPI",
            "SPI controllers, chip-select and transfer modes.",
            new String[] {"SPI"}, "SPI", "");
    public static final Domain UART = new Domain("uart", "UART",
            "Serial drivers, console routing and asynchronous transfers.",
            new String[] {"UART", "SERIAL", "CONSOLE", "SHELL"}, "SERIAL", "");
    public static final Domain CAN = new Domain("can", "CAN",
            "CAN controller support, transceiver configuration and timing.",
            new String[] {"CAN"}, "CAN", "");
    public static final Domain PWM = new Domain("pwm", "PWM",
            "PWM drivers, channels and timer-dependent outputs.",
            new String[] {"PWM"}, "PWM", "");
    public static final Domain DEBUG = new Domain("debug", "Debug",
            "Logging, assertions, fault handling, stack inspection and tracing.",
            new String[] {"LOG", "PRINTK", "DEBUG", "TRACING", "COREDUMP", "FAULT",
                "ASSERT", "STACK_SENTINEL", "GDB", "THREAD_ANALYZER"}, "", "");
    public static final Domain RTOS = new Domain("rtos", "RTOS",
            "Kernel services, threads, scheduling, synchronization and stacks.",
            new String[] {"KERNEL", "SCHED", "THREAD", "PREEMPT", "COOP",
                "TIMESLICE", "SEMAPHORE", "MUTEX", "WORKQUEUE", "POLL", "SMP",
                "TICK", "SYSTEM_WORKQUEUE", "MAIN_STACK_SIZE"}, "", "");
    public static final Domain NETWORK = new Domain("network", "Network",
            "Ethernet, Wi-Fi, Bluetooth, USB networking and protocol stacks.",
            new String[] {"NET", "ETHERNET", "WIFI", "WLAN", "BT", "BLUETOOTH",
                "MQTT", "HTTP", "SOCKET", "IPV4", "IPV6", "LORA", "OPENTHREAD"}, "", "");
    public static final Domain MORE = new Domain("more", "More",
            "Additional features and configuration outside the common peripheral groups.",
            new String[0], "", "");

    public static final List<Domain> TABS = List.of(OVERVIEW, ADC, GPIO, I2C,
            SPI, UART, CAN, PWM, DEBUG, RTOS, NETWORK, MORE);

    public static boolean matches(Domain domain, ConfigurationStudioFiles.Option option) {
        Objects.requireNonNull(domain);
        if (option == null) return false;
        if (domain == OVERVIEW) return true;
        String symbol = option.symbol().toUpperCase(Locale.ROOT);
        if (domain == MORE)
            return TABS.stream().filter(d -> d != MORE && d != OVERVIEW)
                    .noneMatch(d -> matchesPrefix(d, symbol));
        return matchesPrefix(domain, symbol);
    }

    private static boolean matchesPrefix(Domain domain, String symbol) {
        for (String prefix : domain.prefixes)
            if (symbol.equals(prefix) || symbol.startsWith(prefix + "_")) return true;
        return false;
    }

    /** Describes what is already present; it is not a Kconfig select/imply solver. */
    public static String inspect(Domain domain, ConfigurationStudioFiles.Snapshot snapshot) {
        if (snapshot == null) return "Select a project to inspect generated configuration.";
        long found = snapshot.options().stream().filter(o -> matches(domain, o)).count();
        long requested = snapshot.options().stream().filter(o ->
                matches(domain, o) && o.overridden()).count();
        StringBuilder text = new StringBuilder(domain.description()).append('\n')
                .append(found).append(" matching settings in the last snapshot; ")
                .append(requested).append(" requested by this project.");
        if (!domain.driverSymbol().isEmpty()) {
            var driver = snapshot.options().stream()
                    .filter(o -> o.symbol().equals(domain.driverSymbol())).findFirst();
            text.append("\nDriver CONFIG_").append(domain.driverSymbol()).append(": ")
                    .append(driver.map(ConfigurationStudioPresentation::current).orElse("not resolved"));
        }
        if (domain != OVERVIEW && domain != MORE) {
            var related = snapshot.options().stream().filter(o -> matches(domain, o))
                    .sorted(java.util.Comparator.comparing(ConfigurationStudioFiles.Option::symbol))
                    .limit(8).toList();
            if (!related.isEmpty()) {
                text.append("\nRelated settings in this build:");
                for (var option : related)
                    text.append("\n  CONFIG_").append(option.symbol()).append(" = ")
                            .append(ConfigurationStudioPresentation.current(option));
            }
        }
        text.append("\nEnabling a driver changes a software request. Real controllers, pins, ")
                .append("Kconfig dependencies and optional DMA are board-specific. ")
                .append("Review the source declaration and build to verify.");
        return text.toString();
    }
}
