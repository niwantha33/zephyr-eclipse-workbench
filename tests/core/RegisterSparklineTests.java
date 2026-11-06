// SPDX-License-Identifier: Apache-2.0
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import io.github.niwantha33.zew.core.RegisterHistory;
import io.github.niwantha33.zew.core.RegisterSparkline;
public final class RegisterSparklineTests {
    private static void yes(boolean b) { if (!b) throw new AssertionError(); }
    private static RegisterHistory.Sample sample(String id, int n) {
        return new RegisterHistory.Sample(id, "svd", Instant.ofEpochSecond(n),
                Map.of("r", BigInteger.valueOf(n)));
    }
    public static void main(String[] args) {
        var points = RegisterSparkline.series(List.of(sample("one", 4), sample("one", 5),
                sample("one", 6)), "r");
        yes(points.size() == 3 && points.get(0).level() == 0
                && points.get(1).level() == 0.5 && points.get(2).level() == 1);
        yes(RegisterSparkline.series(List.of(sample("one", 1)), "missing").isEmpty());
        yes(RegisterSparkline.series(List.of(sample("one", 1), sample("two", 2)), "r").size() == 1);
        yes(RegisterSparkline.series(List.of(sample("one", 1), sample("one", 1)), "r")
                .get(0).level() == 0.5);
        var bounded = new RegisterHistory();
        for (int i = 1; i < 100; i++) bounded.record(sample("one", i));
        yes(bounded.samples().size() == RegisterHistory.LIMIT
                && RegisterSparkline.series(bounded.samples(), "r").size() == RegisterHistory.LIMIT);
        System.out.println("RegisterSparklineTests PASS");
    }
}
