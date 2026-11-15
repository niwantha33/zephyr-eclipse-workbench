// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Opt-in configuration fragments for the EXACT pinned Zephyr 4.4.0 checkout.
 * These are configuration requests, not proof of a working trace transport.
 *
 * Zephyr 684c9e8f32e4373a21098559f748f06915f950c9 does not have
 * CONFIG_TRACING_BACKEND_RTT. Its UART backend polls each output byte and its
 * RAM backend is a finite, non-wrapping snapshot. None is certified live.
 */
public final class TraceKconfigProfile {
    public static final String ZEPHYR_COMMIT = "684c9e8f32e4373a21098559f748f06915f950c9";
    public static final String OUTPUT_NAME = "trace-live.conf";
    public enum Transport { RAM_SNAPSHOT, UART_BINARY, RTT_BINARY }

    public record Profile(Transport transport, Map<String, String> expected,
                          boolean overlayAvailable, boolean liveReady, String limitation) {
        public Profile {
            Objects.requireNonNull(transport);
            expected = Collections.unmodifiableMap(new LinkedHashMap<>(expected));
            Objects.requireNonNull(limitation);
            if (liveReady) throw new IllegalArgumentException(
                    "No CTF live transport is approved by the pinned Zephyr design.");
        }

        /** Text only: callers must explicitly choose whether to save/build the overlay. */
        public String overlay() {
            if (!overlayAvailable) throw new IllegalStateException(limitation);
            var out = new StringBuilder("# SPDX-License-Identifier: Apache-2.0\n");
            out.append("# Zephyr 4.4.0 (").append(ZEPHYR_COMMIT).append(")\n");
            out.append("# Experimental CTF configuration; NOT evidence of live streaming.\n");
            for (var entry : expected.entrySet())
                out.append("CONFIG_").append(entry.getKey()).append('=')
                        .append(entry.getValue()).append('\n');
            return out.toString();
        }
    }

    private TraceKconfigProfile() {}

    /**
     * singleCoreProven must come from selected build evidence, never a vendor
     * name: DEBUG_THREAD_INFO has 'depends on !SMP' in the pinned Kconfig.
     */
    public static Profile select(Transport transport, boolean singleCoreProven) {
        Objects.requireNonNull(transport);
        if (transport == Transport.RTT_BINARY)
            return new Profile(transport, Map.of(), false, false,
                    "Pinned Zephyr 4.4.0 has no TRACING_BACKEND_RTT and no "
                    + "tracing_backend_rtt.c or established CTF RTT channel. "
                    + "Do not substitute SEGGER_SYSTEMVIEW or RTT_CONSOLE for CTF.");

        var options = new LinkedHashMap<String, String>();
        options.put("TRACING", "y");
        options.put("TRACING_CTF", "y");
        options.put("THREAD_NAME", "y");
        options.put("THREAD_MONITOR", "y");
        options.put("THREAD_STACK_INFO", "y");
        options.put("INIT_STACKS", "y");
        options.put("THREAD_RUNTIME_STATS", "y");
        options.put("SCHED_THREAD_USAGE", "y");
        options.put("TRACING_OBJECT_TRACKING", "y");
        if (singleCoreProven) options.put("DEBUG_THREAD_INFO", "y");
        if (transport == Transport.RAM_SNAPSHOT) {
            options.put("TRACING_SYNC", "y");
            options.put("TRACING_BACKEND_RAM", "y");
            options.put("RAM_TRACING_BUFFER_SIZE", "8192");
            return new Profile(transport, options, true, false,
                    "RAM backend is a finite saved snapshot: it stops after buffer full; "
                    + "no non-intrusive running-target read or wrap counter is established.");
        }
        options.put("TRACING_ASYNC", "y");
        options.put("TRACING_BACKEND_UART", "y");
        options.put("TRACING_BUFFER_SIZE", "8192");
        return new Profile(transport, options, true, false,
                "UART tracing depends on UART_CONSOLE and a verified "
                + "zephyr,tracing-uart chosen node. The pinned backend uses "
                + "uart_poll_out and has no transport framing/sequence/loss receipt.");
    }
}
