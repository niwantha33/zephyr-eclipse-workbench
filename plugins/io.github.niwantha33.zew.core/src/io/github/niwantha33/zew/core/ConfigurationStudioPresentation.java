// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.Locale;

/**
 * Presentation only. Categories are approximate; neither names nor status
 * labels are a substitute for the selected build's Kconfig dependency graph.
 */
public final class ConfigurationStudioPresentation {
    private ConfigurationStudioPresentation() {}

    public static final String ALL = "All settings";
    public static final String MINE = "Changed in my project";
    public static final String REVIEW = "Needs review";
    public static final String DEBUG = "Debugging & tracing";
    public static final String KERNEL = "Kernel & threads";
    public static final String DRIVERS = "Drivers & peripherals";
    public static final String CONNECT = "Communication";
    public static final String MEMORY = "Memory & power";
    public static final String SECURITY = "Security";
    public static final String OTHER = "Other options";
    public static final String[] FILTERS = {ALL, MINE, REVIEW, DEBUG, KERNEL,
            DRIVERS, CONNECT, MEMORY, SECURITY, OTHER};

    public static String category(String symbol) {
        String s = symbol.toUpperCase(Locale.ROOT);
        if (begins(s, "LOG", "DEBUG", "TRACING", "THREAD_ANALYZER", "PRINTK",
                "ASSERT", "STACK_SENTINEL", "EXCEPTION", "FAULT", "COREDUMP",
                "GDB", "GDBSTUB")) return DEBUG;
        if (begins(s, "KERNEL", "SCHED", "THREAD", "PREEMPT", "COOP", "TIMESLICE",
                "SEMAPHORE", "MUTEX", "WORKQUEUE", "POLL", "SMP", "TICK",
                "NUM_IRQ", "SYSTEM_WORKQUEUE", "ISR")) return KERNEL;
        if (begins(s, "GPIO", "I2C", "SPI", "UART", "SERIAL", "ADC", "DAC", "PWM",
                "PINCTRL", "DEVICE", "SENSOR", "I3C", "CAN", "RTC", "DMA",
                "WATCHDOG", "WDT", "FLASH", "DISPLAY")) return DRIVERS;
        if (begins(s, "NET", "ETHERNET", "WIFI", "WLAN", "BT", "BLUETOOTH",
                "USB", "MQTT", "HTTP", "SOCKET", "IPV4", "IPV6", "LORA",
                "OPENTHREAD", "ZIGBEE")) return CONNECT;
        if (begins(s, "HEAP", "MEMORY", "STACK", "PM", "POWER", "LOW_POWER",
                "SLEEP", "CLOCK", "SYS_HEAP", "MMU", "MPU")) return MEMORY;
        if (begins(s, "SECURE", "SECURITY", "CRYPTO", "MBEDTLS", "PSA",
                "TLS", "TRUSTED", "MCUBOOT", "BOOTLOADER")) return SECURITY;
        return OTHER;
    }
    private static boolean begins(String value, String... prefixes) {
        for (String prefix : prefixes)
            if (value.equals(prefix) || value.startsWith(prefix + "_")) return true;
        return false;
    }

    public static String title(String symbol) {
        return switch (symbol) {
            case "LOG" -> "System logging";
            case "LOG_MODE_DEFERRED" -> "Deferred logging";
            case "DEBUG_THREAD_INFO" -> "Thread debugging information";
            case "DEBUG_OPTIMIZATIONS" -> "Debug-friendly optimization";
            case "THREAD_NAME" -> "Thread names";
            case "TRACING" -> "Runtime tracing";
            case "TRACING_CTF" -> "CTF runtime traces";
            default -> {
                String[] words = symbol.toLowerCase(Locale.ROOT).split("_");
                StringBuilder result = new StringBuilder();
                for (String word : words) {
                    if (word.isEmpty()) continue;
                    if (!result.isEmpty()) result.append(' ');
                    result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
                }
                yield result.toString();
            }
        };
    }

    /** Only resolved on/off values get direct controls in the current editor. */
    public static boolean isToggle(ConfigurationStudioFiles.Option option) {
        return option != null && !option.unresolved()
                && ("y".equals(option.effective()) || "n".equals(option.effective()));
    }

    /** Show requests separately from the last effective build value. */
    public static String requested(ConfigurationStudioFiles.Option option) {
        if (option.requested() == null) return "Inherited";
        return switch (option.requested()) {
            case "y" -> "Enable";
            case "n" -> "Disable";
            default -> option.requested();
        };
    }

    public static String review(ConfigurationStudioFiles.Option option) {
        if (option.unresolved()) return "Unresolved";
        if (option.requested() != null && !option.requested().equals(option.effective()))
            return "Verify with build";
        return "";
    }

    /** Compact at-a-glance request state, edited via SWT controls in the same table cell. */
    public static String inlineValue(ConfigurationStudioFiles.Option option) {
        if (!isToggle(option)) return current(option);
        String displayed = option.requested() != null ? option.requested() : option.effective();
        String controls = "y".equals(displayed) ? "● Enable  |  ○ Disable"
                : "○ Enable  |  ● Disable";
        return controls + (option.requested() != null
                && !option.requested().equals(option.effective()) ? "  (pending build)" : "");
    }

    public static String current(ConfigurationStudioFiles.Option option) {
        if (option.unresolved()) return "Not resolved";
        return switch (option.effective()) {
            case "y" -> "Enabled";
            case "n" -> "Disabled";
            case "m" -> "Module";
            default -> option.effective();
        };
    }

    public static String source(ConfigurationStudioFiles.Option option) {
        if (option.unresolved()) return "Review";
        if (option.overridden()) return "In prj.conf";
        return "Inherited";
    }

    public static boolean matches(ConfigurationStudioFiles.Option option, String filter, String term) {
        String search = term == null ? "" : term.strip().toUpperCase(Locale.ROOT);
        if (search.startsWith("CONFIG_")) search = search.substring(7);
        if (!search.isEmpty() && !(option.symbol().contains(search)
                || title(option.symbol()).toUpperCase(Locale.ROOT).contains(search)
                || option.effective() != null && option.effective().toUpperCase(Locale.ROOT).contains(search)
                || option.requested() != null && option.requested().toUpperCase(Locale.ROOT).contains(search))) return false;
        if (MINE.equals(filter)) return option.overridden();
        if (REVIEW.equals(filter)) return !review(option).isBlank();
        if (filter == null || ALL.equals(filter)) return true;
        return filter.equals(category(option.symbol()));
    }
}
