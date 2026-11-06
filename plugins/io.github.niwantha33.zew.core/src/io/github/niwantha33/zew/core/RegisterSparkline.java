// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Pure projection from host-side halted snapshots, never target readings. */
public final class RegisterSparkline {
    private RegisterSparkline() {}
    public record Point(double level, String hex) {}
    public static List<Point> series(List<RegisterHistory.Sample> history, String key) {
        if (history == null || history.isEmpty() || key == null) return List.of();
        var latest = history.get(history.size() - 1);
        var values = new ArrayList<BigInteger>();
        BigInteger min = null, max = null;
        for (var sample : history) {
            if (!sample.session().equals(latest.session())
                    || !sample.svdIdentity().equals(latest.svdIdentity())) continue;
            var value = sample.values().get(key);
            if (value == null) continue;
            values.add(value);
            if (min == null || min.compareTo(value) > 0) min = value;
            if (max == null || max.compareTo(value) < 0) max = value;
        }
        if (min == null) return List.of();
        double span = max.subtract(min).doubleValue();
        var points = new ArrayList<Point>();
        for (var value : values)
            points.add(new Point(span == 0 ? 0.5 : value.subtract(min).doubleValue() / span,
                    "0x" + value.toString(16)));
        return List.copyOf(points);
    }
}
