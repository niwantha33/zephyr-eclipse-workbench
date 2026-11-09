// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.Consumer;

import org.eclipse.core.resources.IProject;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchWindow;
import io.github.niwantha33.zew.core.ZephyrSelectionIdentity;

/**
 * Window-scoped, UI-thread selection bus. Carries selected-build identities,
 * never inferred register addresses, debugger state or runtime measurements.
 * Subscribers must close subscriptions in ViewPart.dispose().
 */
final class ZephyrViewContextHub {
    record Selection(IProject project, ZephyrSelectionIdentity identity, String origin) {
        Selection {
            Objects.requireNonNull(project, "project");
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(origin, "origin");
        }
    }
    private static final WeakHashMap<IWorkbenchWindow, ZephyrViewContextHub> WINDOWS =
            new WeakHashMap<>();

    static synchronized ZephyrViewContextHub forWindow(IWorkbenchWindow window) {
        return WINDOWS.computeIfAbsent(Objects.requireNonNull(window),
                ignored -> new ZephyrViewContextHub(window.getShell().getDisplay()));
    }
    private final Display display;
    private final ArrayList<Consumer<Selection>> listeners = new ArrayList<>();
    private Selection current;

    private ZephyrViewContextHub(Display display) { this.display = display; }

    void publish(IProject project, Path build, String nodePath, String origin) {
        requireUiThread();
        if (project == null || !project.isAccessible() || build == null) return;
        var next = new Selection(project,
                new ZephyrSelectionIdentity(project.getName(), build, nodePath), origin);
        if (next.equals(current)) return;
        current = next;
        // A view may unhook itself while receiving a selection.
        for (var listener : new ArrayList<>(listeners)) listener.accept(next);
    }

    AutoCloseable subscribe(Consumer<Selection> listener) {
        requireUiThread();
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        if (current != null) listener.accept(current);
        return () -> {
            requireUiThread();
            listeners.remove(listener);
        };
    }

    void replay(Consumer<Selection> listener) {
        requireUiThread();
        if (current != null) listener.accept(current);
    }

    private void requireUiThread() {
        if (Display.getCurrent() != display || display.isDisposed())
            throw new IllegalStateException("Zephyr view selection must use its window UI thread");
    }
}
