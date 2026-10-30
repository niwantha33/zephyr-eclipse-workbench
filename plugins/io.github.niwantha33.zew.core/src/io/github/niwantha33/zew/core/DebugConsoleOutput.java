// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Suppress only known OpenOCD INFO polling; preserve every other diagnostic. */
public final class DebugConsoleOutput {
    private static final Pattern POLL = Pattern.compile("^Info\\s*:\\s*Getting thread [0-9]+ reg list\\s*$");
    private long omitted;
    private boolean explained;

    /** Time is retained in the interface for existing log-pump callers; no periodic polling is printed. */
    public List<String> accept(String line, long now) {
        if (POLL.matcher(line).matches()) {
            omitted++;
            if (!explained) {
                explained = true;
                return List.of("Workbench: routine OpenOCD thread-register polling is hidden. "
                        + "Inspect Variables, Expressions, Registers and SoC Registers for suspended target data. "
                        + "This console shows debugger/server diagnostics; application logs require their configured UART/RTT transport.");
            }
            return List.of();
        }
        var output = new ArrayList<String>(flush());
        output.add(line);
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
