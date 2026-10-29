// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.DebugConsoleOutput;
import java.util.List;

public final class DebugConsoleOutputTests {
    public static void main(String[] args) {
        var output = new DebugConsoleOutput();
        String poll = "Info : Getting thread 536871352 reg list";
        if (!output.accept(poll, 0).equals(List.of(poll))) throw new AssertionError("First polling sample lost");
        for (int i = 0; i < 10_000; i++) if (!output.accept(poll, 1).isEmpty()) throw new AssertionError("Unbounded polling output");
        var window = output.accept(poll, 1_000_000_000L);
        if (window.size() != 2 || !window.get(0).contains("10000") || !window.get(1).equals(poll)) throw new AssertionError(window);
        output.accept(poll, 1_000_000_001L);
        var error = output.accept("Error: Only size_t of 4 bytes are supported", 1_000_000_002L);
        if (error.size() != 2 || !error.get(1).startsWith("Error:")) throw new AssertionError("Error hidden by polling output");
        for (String line : List.of("Warn : target access denied", "Info : Listening on port 3333 for gdb connections", "Error: Getting thread 1 reg list", "Info : unrelated output"))
            if (!output.accept(line, 2_000_000_000L).equals(List.of(line))) throw new AssertionError("Non-polling output changed: " + line);
        output.accept(poll, 3_000_000_000L); output.accept(poll, 3_000_000_001L);
        if (output.flush().size() != 1 || !output.flush().isEmpty()) throw new AssertionError("Last polling count lost or repeated");
        System.out.println("DebugConsoleOutputTests PASS: bounded polling; errors/warnings/readiness retained");
    }
}
