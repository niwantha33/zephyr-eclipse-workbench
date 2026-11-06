// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.List;
import java.util.Map;

/**
 * Read-only evidence from the effective, generated Zephyr .config.
 * A configured firmware backend does not prove probe support, wiring,
 * flash identity, or that the target is currently emitting data.
 */
public final class TraceConsoleCapabilities {
    private TraceConsoleCapabilities() {}

    public enum Backend { RAM_CTF, ITM_SWO, RTT, UART_USB, SEMIHOST }
    public enum State { CONFIGURED, NOT_CONFIGURED, UNSUPPORTED, UNKNOWN }

    public record Channel(Backend backend, String title, State state,
                          String explanation, String nextStep) {}
    public record Snapshot(List<Channel> channels, boolean buildConfigPresent) {
        public Snapshot { channels = List.copyOf(channels); }
        public Channel get(Backend backend) {
            return channels.stream().filter(item -> item.backend() == backend).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Missing trace backend: " + backend));
        }
    }

    public static Snapshot inspect(Map<String, String> effectiveConfig) {
        if (effectiveConfig == null || effectiveConfig.isEmpty()) {
            return new Snapshot(List.of(
                    unknown(Backend.RAM_CTF, "RAM / CTF"),
                    unknown(Backend.ITM_SWO, "ITM / SWO"),
                    unknown(Backend.RTT, "SEGGER RTT"),
                    unknown(Backend.UART_USB, "UART / USB serial"),
                    unknown(Backend.SEMIHOST, "Semihosting")), false);
        }
        Map<String, String> cfg = Map.copyOf(effectiveConfig);
        boolean ram = enabled(cfg, "CONFIG_TRACING", "CONFIG_TRACING_CTF",
                "CONFIG_TRACING_BACKEND_RAM");
        boolean m0 = any(cfg, "CONFIG_CPU_CORTEX_M0", "CONFIG_CPU_CORTEX_M0PLUS",
                "CONFIG_CPU_CORTEX_M0_PLUS", "CONFIG_ARMV6_M");
        boolean swo = any(cfg, "CONFIG_LOG_BACKEND_SWO", "CONFIG_SWO_CONSOLE",
                "CONFIG_ITM_CONSOLE");
        boolean rtt = any(cfg, "CONFIG_LOG_BACKEND_RTT", "CONFIG_RTT_CONSOLE",
                "CONFIG_SHELL_BACKEND_RTT");
        boolean uart = any(cfg, "CONFIG_UART_CONSOLE", "CONFIG_LOG_BACKEND_UART",
                "CONFIG_SHELL_BACKEND_SERIAL");
        boolean semihost = any(cfg, "CONFIG_SEMIHOST_CONSOLE", "CONFIG_LOG_BACKEND_SEMIHOST");
        return new Snapshot(List.of(
                new Channel(Backend.RAM_CTF, "RAM / CTF",
                        ram ? State.CONFIGURED : State.NOT_CONFIGURED,
                        ram ? "CTF event tracing to RAM is enabled in this build (binary data, not text)."
                                : "This build does not enable the complete CTF-to-RAM tracing path.",
                        "Requires CONFIG_TRACING=y, CONFIG_TRACING_CTF=y and "
                                + "CONFIG_TRACING_BACKEND_RAM=y; capture requires matching flashed firmware."),
                new Channel(Backend.ITM_SWO, "ITM / SWO",
                        m0 ? State.UNSUPPORTED : swo ? State.CONFIGURED : State.NOT_CONFIGURED,
                        m0 ? "Armv6-M/Cortex-M0(+) does not implement the ITM/SWO trace channel."
                                : swo ? "Firmware requests SWO/ITM output; actual MCU, probe, pin and clock are unverified."
                                : "No ITM/SWO console or logging backend enabled in this build.",
                        "Needs an MCU with ITM, probe SWO capability, SWO wiring, correct target clock "
                                + "and an explicitly configured capture adapter."),
                new Channel(Backend.RTT, "SEGGER RTT", rtt ? State.CONFIGURED : State.NOT_CONFIGURED,
                        rtt ? "Firmware requests an RTT output backend; probe access is unverified."
                                : "No RTT console, log or shell backend enabled in this build.",
                        "Requires the selected Zephyr RTT backend, a compatible running debug probe "
                                + "and an explicitly configured RTT receiver."),
                new Channel(Backend.UART_USB, "UART / USB serial",
                        uart ? State.CONFIGURED : State.NOT_CONFIGURED,
                        uart ? "Firmware requests serial text output; port ownership and wiring are unverified."
                                : "No UART console, logging or shell backend enabled in this build.",
                        "Use an explicitly selected free serial port and matching baud rate. "
                                + "USB CDC ACM needs a firmware-backed serial console."),
                new Channel(Backend.SEMIHOST, "Semihosting",
                        semihost ? State.CONFIGURED : State.NOT_CONFIGURED,
                        semihost ? "Firmware requests debugger-hosted text; probe session is unverified."
                                : "No semihosting console/logging backend enabled in this build.",
                        "Use a debugger/runner that supports semihosting; it may pause the target.")), true);
    }

    private static Channel unknown(Backend backend, String title) {
        return new Channel(backend, title, State.UNKNOWN,
                "No generated zephyr/.config has been inspected.",
                "Select a built Zephyr project and refresh. No target access is performed.");
    }

    private static boolean any(Map<String, String> cfg, String... keys) {
        for (String key : keys) if ("y".equals(cfg.get(key))) return true;
        return false;
    }

    private static boolean enabled(Map<String, String> cfg, String... keys) {
        for (String key : keys) if (!"y".equals(cfg.get(key))) return false;
        return true;
    }
}
