// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Suppress only known OpenOCD INFO polling; preserve every other diagnostic. */
public final class DebugConsoleOutput {
    private static final Pattern POLL = Pattern.compile("^Info\\s*:\\s*Getting thread [0-9]+ reg list\\s*$");
    // Zephyr can initialise stack bytes with 0xAA. This exact failed read is
    // a *clue*, not proof of the source of the bad pointer or an RTOS ABI error.
    private static final Pattern STACK_FILL_READ = Pattern.compile(
            "^Error\\s*:\\s*Failed to read memory at 0x[aA]{8}\\s*$");
    private static final Pattern KEEP_ALIVE_DELAY = Pattern.compile(
            "^Warn\\s*:\\s*keep_alive\\(\\) was not invoked in the .*timelimit.*$");
    private long omitted;
    private boolean explained;
    private boolean explainedStackFill;
    private boolean explainedKeepAlive;

    /** Time is retained in the interface for existing log-pump callers; no periodic polling is printed. */
    public List<String> accept(String line, long now) {
        if (POLL.matcher(line).matches()) {
            omitted++;
            if (!explained) {
                explained = true;
                return List.of("Workbench: routine OpenOCD thread-register polling is hidden. "
                        + "Inspect Variables, Expressions, Registers and Zephyr Hardware Register Explorer for suspended target data. "
                        + "This console shows debugger/server diagnostics; application logs require their configured UART/RTT transport.");
            }
            return List.of();
        }
        var output = new ArrayList<String>(flush());
        output.add(line);
        // Preserve all raw OpenOCD errors and warnings verbatim. Guidance is
        // issued once per independent symptom, never a replacement for evidence.
        if (STACK_FILL_READ.matcher(line).matches() && !explainedStackFill) {
            explainedStackFill = true;
            output.add("Workbench diagnostic: OpenOCD attempted to read 0xAAAAAAAA. "
                    + "Zephyr can fill unused thread stacks with 0xAA; invalid RTOS thread "
                    + "stack unwinding is a possibility, NOT a confirmed cause. "
                    + "Verify exact ELF/flashed image, halt in a valid frame, check saved "
                    + "thread SP/stack bounds and OpenOCD compatibility. "
                    + "Do not modify kernel metadata or suppress memory-read errors.");
        }
        if (KEEP_ALIVE_DELAY.matcher(line).matches() && !explainedKeepAlive) {
            explainedKeepAlive = true;
            output.add("Workbench diagnostic: OpenOCD reported delayed GDB keep-alive. "
                    + "A larger GDB remotetimeout can tolerate slow operations but does "
                    + "not repair invalid addresses; investigate the preceding failed "
                    + "reads and debugger thread-context requests first.");
        }
        return List.copyOf(output);
    }

    /** Summarise only when real output arrives or the server closes, never on a timer. */
    public List<String> flush() {
        long count = omitted;
        omitted = 0;
        return count == 0 ? List.of() : List.of("Workbench: suppressed " + count
                + " routine OpenOCD thread-register polling messages.");
    }
}
