// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Bound only OpenOCD's repetitive INFO register-polling chatter; never discard a warning or error. */
public final class DebugConsoleOutput {
    private static final Pattern POLL = Pattern.compile("^Info\\s*:\\s*Getting thread [0-9]+ reg list\\s*$");
    private long windowStart;
    private boolean polling;
    private long omitted;
    public List<String> accept(String line, long now) {
        var output = new ArrayList<String>();
        if (!POLL.matcher(line).matches()) {
            output.addAll(flush());
            output.add(line);
        } else if (!polling || now - windowStart >= 1_000_000_000L) {
            output.addAll(flush());
            output.add(line);
            windowStart = now;
            polling = true;
        } else {
            omitted++;
        }
        return List.copyOf(output);
    }
    public List<String> flush() {
        long count = omitted;
        omitted = 0;
        polling = false;
        return count == 0 ? List.of() : List.of("Workbench: condensed " + count
                + " repetitive OpenOCD INFO thread-register polling messages. These are register reads, not errors.");
    }
}
