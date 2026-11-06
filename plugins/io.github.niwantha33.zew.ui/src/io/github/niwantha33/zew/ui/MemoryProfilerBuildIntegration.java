// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.ui.PlatformUI;

/**
 * Called only from ZephyrProjectBuilder's successful, verified West build path.
 * Runs on the existing background builder thread, never from the SWT UI thread.
 * A profiler failure is diagnostic-only and must not turn a valid build into
 * a failed build. It never invokes West, GDB, or a compiler.
 */
final class MemoryProfilerBuildIntegration {
    private static final Duration TIMEOUT = Duration.ofSeconds(25);

    private MemoryProfilerBuildIntegration() {}

    static void afterSuccessfulBuild(IProject project, Path build, String python,
                                     BooleanSupplier cancelled, Consumer<String> log) {
        if (cancelled.getAsBoolean()) return;
        try {
            Path map = MemoryProfilerReportGuard.map(build);
            String before = MemoryProfilerReportGuard.hashMap(map);
            Path output = MemoryProfilerReportGuard.output(build);
            if (python == null || python.isBlank())
                throw new IOException("Project Python executable is not configured");
            var resource = Activator.getDefault().getBundle().getEntry("scripts/memory_profiler.py");
            if (resource == null) throw new IOException("Installed workbench lacks memory_profiler.py");
            Path script = Path.of(FileLocator.toFileURL(resource).toURI());
            Path temporary = Files.createTempFile(build, ".zew-memory-", ".json.tmp");
            Path errors = Files.createTempFile(build, ".zew-memory-errors-", ".txt");
            try {
                var command = new ProcessBuilder(python, script.toString(), "--map", map.toString())
                        .directory(build.toFile())
                        .redirectOutput(temporary.toFile()).redirectError(errors.toFile());
                Process process = command.start();
                boolean exited = false;
                try {
                    long deadline = System.nanoTime() + TIMEOUT.toNanos();
                    while (!(exited = process.waitFor(100, TimeUnit.MILLISECONDS))) {
                        if (cancelled.getAsBoolean()) throw new InterruptedIOException("Memory profiling cancelled");
                        if (System.nanoTime() >= deadline) throw new IOException("Memory profiler exceeded 25-second limit");
                        if (Files.size(temporary) > MemoryProfilerReportGuard.MAX_JSON_BYTES)
                            throw new IOException("Memory profiler output exceeded 8 MiB");
                    }
                    if (cancelled.getAsBoolean()) throw new InterruptedIOException("Memory profiling cancelled");
                    if (process.exitValue() != 0) {
                        String reason = Files.size(errors) <= 4096
                                ? Files.readString(errors, StandardCharsets.UTF_8).trim() : "Python parser error";
                        throw new IOException("Memory map parser exited " + process.exitValue()
                                + (reason.isBlank() ? "" : ": " + reason));
                    }
                    MemoryProfilerReportGuard.publish(temporary, output, map, before);
                    log.accept("Memory profiler: " + output.getFileName() + " generated from verified "
                            + "successful build map SHA-256 " + before + ". Runtime MPU unknown.");
                    notifyOpenViews(project, build, output);
                } finally {
                    if (!exited && process.isAlive()) {
                        process.destroyForcibly();
                        process.waitFor(3, TimeUnit.SECONDS);
                    }
                }
            } finally {
                Files.deleteIfExists(temporary);
                Files.deleteIfExists(errors);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.accept("Memory profiler cancelled; firmware build result is unchanged.");
        } catch (Exception error) {
            // Capture/validation is advisory. Do not suppress a bad build by
            // claiming success or overwriting a previous valid report.
            log.accept("Memory profiler unavailable: " + error.getMessage()
                    + ". Firmware build result is unchanged; any older report may be stale.");
        }
    }

    private static void notifyOpenViews(IProject project, Path build, Path report) {
        if (!PlatformUI.isWorkbenchRunning()) return;
        var display = PlatformUI.getWorkbench().getDisplay();
        if (display == null || display.isDisposed()) return;
        display.asyncExec(() -> {
            if (display.isDisposed() || !PlatformUI.isWorkbenchRunning()) return;
            for (var window : PlatformUI.getWorkbench().getWorkbenchWindows())
                for (var page : window.getPages()) {
                    var part = page.findView(MemoryProfilerView.ID);
                    if (part instanceof MemoryProfilerView view)
                        view.displayBuiltReport(project, build, report);
                }
        });
    }
}
