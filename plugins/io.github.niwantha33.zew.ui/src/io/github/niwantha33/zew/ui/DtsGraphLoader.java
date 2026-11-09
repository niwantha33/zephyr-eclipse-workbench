// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Invoke the installed, read-only Zephyr dtlib/edtlib observer off the SWT thread. */
public final class DtsGraphLoader {
    private DtsGraphLoader() {}
    public static DtsGraphModel.Snapshot load(Path python, Path script, Path zephyr,
                                              Path application, Path build) {
        if (python == null || script == null || zephyr == null || application == null
                || build == null)
            return DtsGraphModel.unavailable("Select an exact board, build profile and Zephyr environment.");
        Path generated = build.resolve("zephyr/zephyr.dts");
        if (!Files.isRegularFile(generated))
            return DtsGraphModel.unavailable("No generated zephyr.dts for this board/profile. Build first.");
        Path output = null;
        Process process = null;
        try {
            output = Files.createTempFile("zew-dts-graph-", ".txt");
            var command = List.of(python.toString(), "-I", "-X", "utf8", script.toString(),
                    "--zephyr", zephyr.toString(), "--application", application.toString(),
                    "--build", build.toString());
            process = new ProcessBuilder(command)
                    .redirectInput(ProcessBuilder.Redirect.PIPE)
                    .redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(50, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return DtsGraphModel.unavailable("Selected Devicetree inspection timed out.");
            }
            if (process.exitValue() != 0)
                return DtsGraphModel.unavailable("Installed Devicetree observer failed.");
            if (Files.size(output) > 3_000_000)
                return DtsGraphModel.unavailable("Devicetree graph exceeds the output safety limit.");
            return DtsGraphModel.parse(Files.readString(output, StandardCharsets.UTF_8), generated);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return DtsGraphModel.unavailable("Devicetree graph inspection cancelled.");
        } catch (Exception error) {
            return DtsGraphModel.unavailable("Devicetree graph unavailable: "
                    + error.getClass().getSimpleName());
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            if (output != null) {
                try { Files.deleteIfExists(output); }
                catch (java.io.IOException locked) { output.toFile().deleteOnExit(); }
            }
        }
    }
}
