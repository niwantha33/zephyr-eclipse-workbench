// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.TraceConsoleCapabilities;
import io.github.niwantha33.zew.core.TraceConsoleCapabilities.Backend;
import io.github.niwantha33.zew.core.TraceConsoleCapabilities.State;
import java.util.Map;

public final class TraceConsoleCapabilitiesTests {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        var missing = TraceConsoleCapabilities.inspect(Map.of());
        check(!missing.buildConfigPresent(), "Unbuilt target must not be presented as disabled");
        for (var channel : missing.channels())
            check(channel.state() == State.UNKNOWN, "No fabricated backend detection");

        var ram = TraceConsoleCapabilities.inspect(Map.of(
                "CONFIG_TRACING", "y", "CONFIG_TRACING_CTF", "y",
                "CONFIG_TRACING_BACKEND_RAM", "y", "CONFIG_RAM_TRACING_BUFFER_SIZE", "4096"));
        check(ram.get(Backend.RAM_CTF).state() == State.CONFIGURED, "RAM CTF configured");
        check(ram.get(Backend.ITM_SWO).state() == State.NOT_CONFIGURED, "RAM CTF is not SWO");
        check(ram.get(Backend.RAM_CTF).explanation().contains("binary"), "Binary CTF not text");

        var partial = TraceConsoleCapabilities.inspect(Map.of(
                "CONFIG_TRACING", "y", "CONFIG_TRACING_CTF", "y"));
        check(partial.get(Backend.RAM_CTF).state() == State.NOT_CONFIGURED,
                "Missing RAM backend must not be considered ready");

        var m0 = TraceConsoleCapabilities.inspect(Map.of(
                "CONFIG_CPU_CORTEX_M0PLUS", "y", "CONFIG_LOG_BACKEND_SWO", "y"));
        check(m0.get(Backend.ITM_SWO).state() == State.UNSUPPORTED,
                "Cortex-M0+ cannot claim ITM/SWO even when requested in .config");

        var other = TraceConsoleCapabilities.inspect(Map.of(
                "CONFIG_CPU_CORTEX_M4", "y", "CONFIG_LOG_BACKEND_SWO", "y",
                "CONFIG_LOG_BACKEND_RTT", "y", "CONFIG_UART_CONSOLE", "y",
                "CONFIG_SEMIHOST_CONSOLE", "y"));
        check(other.get(Backend.ITM_SWO).state() == State.CONFIGURED, "SWO build intent");
        check(other.get(Backend.RTT).state() == State.CONFIGURED, "RTT build intent");
        check(other.get(Backend.UART_USB).state() == State.CONFIGURED, "UART build intent");
        check(other.get(Backend.SEMIHOST).state() == State.CONFIGURED, "Semihosting build intent");
        check(other.get(Backend.ITM_SWO).explanation().contains("unverified"),
                "Configured means build intent, never connected probe proof");
        check(other.channels().size() == 5, "All transports visible");
        System.out.println("TraceConsoleCapabilitiesTests PASS");
    }
}
