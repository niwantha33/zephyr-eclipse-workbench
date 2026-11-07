// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Pure selection policy for saved SVD snapshots: a debug context from another
 * Zephyr project must never be replaced silently by a fallback launch.
 */
public final class DebugSessionSelection {
    private DebugSessionSelection() {}

    public enum State { RESOLVED, UNRESOLVED, AMBIGUOUS, FOREIGN_PROJECT }
    public record Candidate(String sessionId, String project, boolean active) {
        public Candidate {
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(project);
        }
    }
    public record Decision(State state, String sessionId) {
        public Decision {
            if ((state == State.RESOLVED) != (sessionId != null))
                throw new IllegalArgumentException("Only resolved selections carry a session.");
        }
    }

    /**
     * Explicit selections are authoritative. Without one, only a uniquely
     * matching active Zephyr GDB launch is accepted.
     */
    public static Decision choose(String project, String selectedSessionId,
                                  List<Candidate> launches) {
        Objects.requireNonNull(launches);
        var unique = new HashSet<String>();
        boolean foreign = false;
        for (Candidate candidate : launches) {
            if (candidate == null || !candidate.active()) continue;
            if (selectedSessionId != null && selectedSessionId.equals(candidate.sessionId())) {
                if (project != null && !project.equals(candidate.project())) foreign = true;
                else return new Decision(State.RESOLVED, candidate.sessionId());
            }
            if ((project == null || project.equals(candidate.project()))
                    && !candidate.project().isBlank())
                unique.add(candidate.sessionId());
        }
        if (selectedSessionId != null)
            return new Decision(foreign ? State.FOREIGN_PROJECT : State.UNRESOLVED, null);
        if (unique.size() == 1) return new Decision(State.RESOLVED, unique.iterator().next());
        return new Decision(unique.isEmpty() ? State.UNRESOLVED : State.AMBIGUOUS, null);
    }

    /** A launch/container fallback is safe only when one suspended thread exists. */
    public static boolean uniqueHaltedThread(int suspendedThreadCount) {
        return suspendedThreadCount == 1;
    }
}
