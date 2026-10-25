// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fail-closed physical-operation guard until per-probe identities and
 * cross-IDE ownership can be verified. This intentionally serializes
 * physical operations inside one Eclipse process, regardless of project.
 * It is not a substitute for OS-level or hardware probe ownership.
 */
final class TargetOperationGuard {
    private static final AtomicBoolean physicalBusy = new AtomicBoolean();
    private TargetOperationGuard() {}

    static Lease acquire(String action) throws IOException {
        if ("run".equals(action)) return new Lease(false);
        if (!"flash".equals(action) && !"debugserver".equals(action))
            throw new IOException("Unsupported operation: " + action);
        if (!physicalBusy.compareAndSet(false, true))
            throw new IOException("A Flash or Debug Server operation already owns the hardware interface in this Eclipse instance. Terminate it before starting another.");
        return new Lease(true);
    }

    static final class Lease implements AutoCloseable {
        private final boolean physical;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Lease(boolean physical) { this.physical = physical; }
        @Override public void close() {
            if (closed.compareAndSet(false, true) && physical) physicalBusy.set(false);
        }
    }
}
