// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;

/**
 * Installed SWT smoke for the passive Zephyr Debug Trace Console.
 * Does not connect to a UART, probe, RTT server, or reset firmware.
 */
public final class TraceConsoleSmoke {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void run() throws Exception {
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        var view = page.showView("io.github.niwantha33.zew.ui.traceConsole");
        try {
            var type = view.getClass();
            var contextField = type.getDeclaredField("context");
            contextField.setAccessible(true);
            var context = (Label) contextField.get(view);
            check(!context.isDisposed() && !context.getText().isBlank(),
                    "Trace Console must explain missing or discovered project context");

            var tableField = type.getDeclaredField("transports");
            tableField.setAccessible(true);
            var table = (Table) tableField.get(view);
            check(table.getItemCount() == 5, "All five trace backends are listed");

            var outputField = type.getDeclaredField("output");
            outputField.setAccessible(true);
            var output = (Text) outputField.get(view);
            check(output.getText().contains("host receive timestamps"),
                    "The trace text pane must not claim target-synchronised timestamps");

            var resolve = type.getDeclaredMethod("uniqueLiveDebugProject");
            resolve.setAccessible(true);
            // No native Zephyr debug launch has been started in this smoke.
            check(resolve.invoke(view) == null,
                    "The Trace Console must not select a random workspace project without a live launch");
            // Saved capture discovery is pure file I/O: a new CTF can be
            // selected without starting a probe, another GDB or a decoder.
            var bundle = org.eclipse.core.runtime.Platform.getBundle("io.github.niwantha33.zew.ui");
            var workflow = bundle.loadClass("io.github.niwantha33.zew.ui.TraceCaptureWorkflow");
            var latest = workflow.getDeclaredMethod("latest", java.nio.file.Path.class);
            latest.setAccessible(true);
            var folder = java.nio.file.Files.createTempDirectory("zew-ctf-navigation-smoke-");
            try {
                var saved = folder.resolve(".zew-traces");
                var older = saved.resolve("trace-0001").resolve("ctf");
                var newer = saved.resolve("trace-0002").resolve("ctf");
                var invalid = saved.resolve("trace-0003").resolve("ctf");
                for (var ctf : new java.nio.file.Path[] {older, newer, invalid})
                    java.nio.file.Files.createDirectories(ctf);
                java.nio.file.Files.writeString(older.resolve("metadata"), "synthetic");
                java.nio.file.Files.writeString(older.resolve("channel0_0"), "synthetic");
                java.nio.file.Files.writeString(newer.resolve("metadata"), "synthetic");
                java.nio.file.Files.writeString(newer.resolve("channel0_0"), "synthetic");
                // Newest folder intentionally lacks channel0_0 and must fail closed.
                java.nio.file.Files.writeString(invalid.resolve("metadata"), "synthetic");
                java.nio.file.Files.setLastModifiedTime(older.getParent(),
                        java.nio.file.attribute.FileTime.fromMillis(1000));
                java.nio.file.Files.setLastModifiedTime(newer.getParent(),
                        java.nio.file.attribute.FileTime.fromMillis(2000));
                java.nio.file.Files.setLastModifiedTime(invalid.getParent(),
                        java.nio.file.attribute.FileTime.fromMillis(3000));
                @SuppressWarnings("unchecked")
                var chosen = (java.util.Optional<java.nio.file.Path>) latest.invoke(null, folder);
                check(chosen.isPresent() && chosen.get().equals(newer),
                        "latest CTF finder selects newest complete folder, skips invalid newest");
            } finally {
                try (var files = java.nio.file.Files.walk(folder)) {
                    for (var p : files.sorted(java.util.Comparator.reverseOrder()).toList())
                        java.nio.file.Files.deleteIfExists(p);
                }
            }
            System.out.println("TraceConsoleSmoke PASS: passive view, five backends and exact saved CTF lookup");
        } finally {
            page.hideView(view);
        }
    }
}
