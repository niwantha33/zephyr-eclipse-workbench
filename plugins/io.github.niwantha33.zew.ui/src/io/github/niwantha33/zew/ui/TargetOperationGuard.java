// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.Path;
import io.github.niwantha33.zew.core.PhysicalOperationLock;

/**
 * Fail-closed physical-operation guard until per-probe identities and
 * cross-IDE ownership can be verified. Serializes physical operations across
 * cooperating Workbench processes for the same OS user, regardless of project.
 * Other IDEs do not participate in this advisory lock.
 */
final class TargetOperationGuard {
    private TargetOperationGuard() {}

    static Lease acquire(String action) throws IOException {
        if ("run".equals(action)) return new Lease(null);
        if (!"flash".equals(action) && !"debugserver".equals(action))
            throw new IOException("Unsupported operation: " + action);
        return new Lease(PhysicalOperationLock.acquire(Path.of(System.getProperty("user.home"),
                ".zephyr-eclipse-workbench", "locks")));
    }

    static final class Lease implements AutoCloseable {
        private final PhysicalOperationLock physical;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Lease(PhysicalOperationLock physical) { this.physical = physical; }
        @Override public void close() {
            if (closed.compareAndSet(false, true) && physical != null) {
                try { physical.close(); }
                catch (IOException error) {
                    if (Activator.getDefault() != null) Activator.getDefault().getLog().log(
                            new org.eclipse.core.runtime.Status(org.eclipse.core.runtime.IStatus.ERROR,
                                    Activator.ID, "Could not close hardware-operation lock", error));
                }
            }
        }
    }
}
