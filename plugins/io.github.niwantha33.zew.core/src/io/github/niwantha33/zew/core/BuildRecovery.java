// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Bounded, capability-based recovery. Compile failures never enter this path. */
public final class BuildRecovery {
    @FunctionalInterface public interface Provider { void prepare() throws Exception; }
    private final Map<String, Provider> providers;
    private final Set<String> attempted = new HashSet<>();
    private final int limit;
    public BuildRecovery(Map<String, Provider> providers, int limit) {
        this.providers = Map.copyOf(providers);
        if (limit < 1) throw new IllegalArgumentException("Positive recovery limit required");
        this.limit = limit;
    }
    public void repair(Set<String> missing) throws Exception {
        var unsupported = new HashSet<>(missing); unsupported.removeAll(providers.keySet());
        if (!unsupported.isEmpty()) throw new IOException("Required build tools have no reviewed pinned provider: " + unsupported
            + ". No tools were guessed or downloaded; use a supported managed profile.");
        var total = new HashSet<>(attempted); total.addAll(missing);
        if (missing.stream().anyMatch(attempted::contains) || total.size() > limit)
            throw new IOException("Dependency recovery exhausted for " + missing + ". Inspect the original configure diagnostics.");
        attempted.addAll(missing);
        for (String tool : new java.util.TreeSet<>(missing)) providers.get(tool).prepare();
    }
}
