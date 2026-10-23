// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Streams merged output while keeping cancellation and timeout independent of pipe reads. */
public final class ProcessRunner {
    private static final int MAX_CAPTURE = 2_000_000;
    public record Result(int exitCode, String output, boolean truncated) {
        public String requireSuccess() throws IOException {
            if (exitCode != 0) throw new IOException("Tool exited with code " + exitCode + ". See the Zephyr console.");
            return output;
        }
        public String requireCompleteOutput() throws IOException {
            requireSuccess();
            if (truncated) throw new IOException("Tool output exceeded the capture limit; see the Zephyr console.");
            return output;
        }
    }

    public Result run(List<String> command, Path directory, Duration timeout,
            BooleanSupplier cancelled, Consumer<String> output) throws IOException, InterruptedException {
        if (command.isEmpty() || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("A command and positive timeout are required.");
        }
        if (cancelled.getAsBoolean()) throw new InterruptedIOException("Operation cancelled.");
        var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        // Do not rely on shell activation. The selected venv supplies Python; prepend its Scripts/bin
        // directory so West and Python-installed helper executables resolve consistently.
        var environment = builder.environment();
        var pathKey = environment.keySet().stream().filter(k -> k.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
        var executableParent = Path.of(command.getFirst()).toAbsolutePath().getParent();
        environment.put(pathKey, executableParent + java.io.File.pathSeparator + environment.getOrDefault(pathKey, ""));
        environment.put("PYTHONUNBUFFERED", "1");
        var process = builder.start();
        process.getOutputStream().close(); // No interactive input for this preview's commands.
        var captured = new StringBuilder();
        var readFailure = new AtomicReference<IOException>();
        boolean[] truncated = {false};
        var reader = new Thread(() -> {
            try (var input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    output.accept(line);
                    if (captured.length() + line.length() + 1 <= MAX_CAPTURE) captured.append(line).append('\n');
                    else truncated[0] = true;
                }
            } catch (IOException error) { readFailure.set(error); }
            catch (RuntimeException error) { readFailure.set(new IOException("Output handler failed.", error)); }
        }, "zew-process-output");
        reader.setDaemon(true);
        reader.start();
        long start = System.nanoTime();
        try {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) throw new InterruptedIOException("Operation cancelled.");
                if (System.nanoTime() - start > timeout.toNanos()) throw new IOException("Tool timed out after " + timeout.toSeconds() + " seconds.");
            }
            if (cancelled.getAsBoolean()) throw new InterruptedIOException("Operation cancelled.");
            reader.join(2000);
            if (reader.isAlive()) throw new IOException("Tool output did not close after exit.");
            if (readFailure.get() != null) throw readFailure.get();
            return new Result(process.exitValue(), captured.toString(), truncated[0]);
        } finally {
            terminate(process);
            process.getInputStream().close();
            reader.join(2000);
        }
    }

    private static void terminate(Process process) {
        var children = process.descendants().toList();
        for (var child : children) child.destroy();
        // Give the parent a brief opportunity to reap children before terminating it.
        try { if (!children.isEmpty()) process.waitFor(300, TimeUnit.MILLISECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        for (var child : children) if (child.isAlive()) child.destroyForcibly();
        process.destroy();
        if (process.isAlive()) process.destroyForcibly();
    }
}
