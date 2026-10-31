// SPDX-License-Identifier: Apache-2.0
import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;
import io.github.niwantha33.zew.core.RegisterHistory;

/** No hardware needed: tests register evidence identity, bounds and deltas. */
public final class RegisterHistoryTests {
    private static void check(boolean b, String message) {
        if (!b) throw new AssertionError(message);
    }
    private static RegisterHistory.Sample sample(String session, String model, int number) {
        return new RegisterHistory.Sample(session, model, Instant.ofEpochSecond(number),
                Map.of("GPIO.DATA", BigInteger.valueOf(number), "GPIO.CTRL", BigInteger.ONE));
    }
    public static void main(String[] ignored) {
        var history = new RegisterHistory();
        check(!history.pinLatest() && history.changes().isEmpty(), "empty history cannot pin");
        history.record(sample("gdb-1", "MSPM0G3507:symbol-sha", 1));
        check(history.pinLatest(), "first sample pins baseline");
        history.record(sample("gdb-1", "MSPM0G3507:symbol-sha", 2));
        check(history.changes().size() == 1, "only observed changes included");
        check(history.changes().get(0).registerKey().equals("GPIO.DATA"), "register id retained");
        check(history.changes().get(0).before().equals(BigInteger.ONE)
                && history.changes().get(0).after().equals(BigInteger.TWO), "delta evidence retained");
        var sample = history.samples().get(0);
        try {
            sample.values().put("GPIO.DATA", BigInteger.valueOf(99));
            throw new AssertionError("sample must be immutable");
        } catch (UnsupportedOperationException expected) {}
        for (int i = 3; i <= 25; i++) history.record(sample("gdb-1", "MSPM0G3507:symbol-sha", i));
        check(history.samples().size() == RegisterHistory.LIMIT, "history bounded");
        history.record(sample("gdb-2", "MSPM0G3507:symbol-sha", 26));
        check(history.samples().size() == 1 && history.changes().isEmpty(),
                "new debug session invalidates previous baseline");
        history.pinLatest();
        history.record(sample("gdb-2", "different-SVD", 27));
        check(history.samples().size() == 1 && history.changes().isEmpty(),
                "SVD change invalidates previous baseline");
        System.out.println("RegisterHistoryTests PASS");
    }
}
