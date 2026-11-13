// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.DebugConsoleOutput;
import java.util.List;

public final class DebugConsoleOutputTests {
    public static void main(String[] args) {
        var output = new DebugConsoleOutput();
        String poll = "Info : Getting thread 536871352 reg list";
        var first = output.accept(poll, 0);
        if (first.size() != 1 || !first.getFirst().contains("Variables") || !first.getFirst().contains("UART/RTT"))
            throw new AssertionError("Explain available data and application transport once");
        // A long-running suspended session must not produce timer-driven summaries.
        for (int i = 0; i < 10_000; i++)
            if (!output.accept(poll, i * 1_000_000_000L).isEmpty()) throw new AssertionError("Polling still floods console");
        var error = output.accept("Error: Only size_t of 4 bytes are supported", Long.MAX_VALUE);
        if (error.size() != 2 || !error.getFirst().contains("10001") || !error.get(1).startsWith("Error:"))
            throw new AssertionError("Preserve errors and pending count: " + error);
        for (String line : List.of("Warn : target access denied", "Info : Listening on port 3333 for gdb connections",
                "Error: Getting thread 1 reg list", "Info : unrelated output", "counter=42", "Info : Getting thread abc reg list"))
            if (!output.accept(line, 2_000_000_000L).equals(List.of(line))) throw new AssertionError("Non-polling output changed: " + line);
        if (!output.accept(poll, 3_000_000_000L).isEmpty()) throw new AssertionError("One-time explanation repeated");
        if (output.flush().size() != 1 || !output.flush().isEmpty()) throw new AssertionError("Final count lost or repeated");
        var faults = new DebugConsoleOutput();
        String fill = "Error: Failed to read memory at 0xaaaaaaaa";
        var firstFill = faults.accept(fill, 0);
        if (firstFill.size() != 2 || !firstFill.get(0).equals(fill)
                || !firstFill.get(1).contains("NOT a confirmed cause")
                || !firstFill.get(1).contains("ELF/flashed image"))
            throw new AssertionError("Preserve raw stack-fill read error and add bounded guidance: " + firstFill);
        if (!faults.accept("Error: Failed to read memory at 0xAAAAAAAA", 1).equals(
                List.of("Error: Failed to read memory at 0xAAAAAAAA")))
            throw new AssertionError("A repeat stack-fill error must remain visible without repeated advice");
        String otherBad = "Error: Failed to read memory at 0x6269b060";
        if (!faults.accept(otherBad, 2).equals(List.of(otherBad)))
            throw new AssertionError("Unrecognised read failure must remain unmodified");
        String delayed = "Warn : keep_alive() was not invoked in the 1000 ms timelimit. "
                + "GDB alive packet not sent! (1597 ms). Workaround: increase remotetimeout in GDB";
        var keepAlive = faults.accept(delayed, 3);
        if (keepAlive.size() != 2 || !keepAlive.get(0).equals(delayed)
                || !keepAlive.get(1).contains("does not repair invalid addresses"))
            throw new AssertionError("Keep-alive warning must be preserved with non-masking guidance: " + keepAlive);
        if (!faults.accept(delayed, 4).equals(List.of(delayed)))
            throw new AssertionError("Keep-alive guidance may only be emitted once");
        if (!faults.flush().isEmpty())
            throw new AssertionError("Fault guidance must not be counted as suppressed polling");
        System.out.println("DebugConsoleOutputTests PASS: poll count bounded, invalid read errors retained, and clues labelled");

    }
}
