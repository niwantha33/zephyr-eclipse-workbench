// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only, bounded snapshots. Never reads or writes MCU memory itself. */
public final class RegisterHistory {
    public static final int LIMIT = 16;
    public record Sample(String session, String svdIdentity, Instant timestamp,
                         Map<String, BigInteger> values) {
        public Sample {
            if (session == null || session.isBlank() || svdIdentity == null
                    || svdIdentity.isBlank() || timestamp == null || values == null
                    || values.size() > 256)
                throw new IllegalArgumentException("Snapshot identity/value limit invalid.");
            values = Map.copyOf(values);
            if (values.entrySet().stream().anyMatch(e -> e.getKey() == null
                    || e.getValue() == null || e.getValue().signum() < 0))
                throw new IllegalArgumentException("Snapshot includes invalid values.");
        }
    }
    public record Delta(String registerKey, BigInteger before, BigInteger after) {}
    private final List<Sample> samples = new ArrayList<>();
    private Sample baseline;

    public void clear() { samples.clear(); baseline = null; }
    public void record(Sample sample) {
        if (!samples.isEmpty()) {
            var last = samples.get(samples.size() - 1);
            if (!last.session().equals(sample.session())
                    || !last.svdIdentity().equals(sample.svdIdentity())) clear();
        }
        samples.add(sample);
        if (samples.size() > LIMIT) samples.remove(0);
    }
    public List<Sample> samples() { return List.copyOf(samples); }
    public Sample baseline() { return baseline; }
    public boolean pinLatest() {
        if (samples.isEmpty()) return false;
        baseline = samples.get(samples.size() - 1);
        return true;
    }
    public List<Delta> changes() {
        if (baseline == null || samples.isEmpty()) return List.of();
        var latest = samples.get(samples.size() - 1);
        if (!baseline.session().equals(latest.session())
                || !baseline.svdIdentity().equals(latest.svdIdentity())) return List.of();
        var changes = new LinkedHashMap<String, Delta>();
        for (var entry : latest.values().entrySet()) {
            var prior = baseline.values().get(entry.getKey());
            if (prior != null && !prior.equals(entry.getValue()))
                changes.put(entry.getKey(), new Delta(entry.getKey(), prior, entry.getValue()));
        }
        return changes.values().stream().sorted(
                java.util.Comparator.comparing(Delta::registerKey)).toList();
    }
}
