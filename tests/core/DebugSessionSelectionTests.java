// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.DebugSessionSelection;
import java.util.List;

/** Pure-JDK policy contract; no Eclipse runtime or target required. */
public final class DebugSessionSelectionTests {
    private static void check(boolean yes, String message) {
        if (!yes) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        var launches = List.of(
                new DebugSessionSelection.Candidate("a1", "projectA", true),
                new DebugSessionSelection.Candidate("b1", "projectB", true));
        var a = DebugSessionSelection.choose("projectA", null, launches);
        check(a.state() == DebugSessionSelection.State.RESOLVED
                && a.sessionId().equals("a1"), "single matching project fallback");
        check(DebugSessionSelection.choose("projectA", "b1", launches).state()
                == DebugSessionSelection.State.FOREIGN_PROJECT,
                "foreign selected frame must NOT fall back to project A");
        check(DebugSessionSelection.choose("projectA", "a1", launches).state()
                == DebugSessionSelection.State.RESOLVED, "direct DSF selection");
        check(DebugSessionSelection.choose("projectA", "stale", launches).state()
                == DebugSessionSelection.State.UNRESOLVED,
                "unrecognized explicit session cannot fall back");
        check(DebugSessionSelection.choose(null, null, launches).state()
                == DebugSessionSelection.State.AMBIGUOUS,
                "multiple live projects cannot be guessed");
        var two = List.of(new DebugSessionSelection.Candidate("a1", "projectA", true),
                new DebugSessionSelection.Candidate("a2", "projectA", true));
        check(DebugSessionSelection.choose("projectA", null, two).state()
                == DebugSessionSelection.State.AMBIGUOUS,
                "two same-project sessions require explicit selection");
        check(DebugSessionSelection.choose("projectA", "a2", two).sessionId().equals("a2"),
                "explicit frame chooses intended same-project launch");
        check(DebugSessionSelection.choose("projectA", null,
                List.of(new DebugSessionSelection.Candidate("a1", "projectA", false))).state()
                == DebugSessionSelection.State.UNRESOLVED, "stale launch is ignored");
        check(!DebugSessionSelection.uniqueHaltedThread(0)
                && DebugSessionSelection.uniqueHaltedThread(1)
                && !DebugSessionSelection.uniqueHaltedThread(2),
                "halted fallback must be unique; never pick first of many");
        System.out.println("PASS DebugSessionSelectionTests");
    }
}
