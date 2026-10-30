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
        System.out.println("DebugConsoleOutputTests PASS: no periodic poll spam; useful diagnostics retained");
    }
}
