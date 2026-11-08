// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Safe managed-Python syntax preflight of an in-memory scalar edit candidate. */
public final class YamlPatchValidator {
    private YamlPatchValidator() {}

    public static YamlDocumentModel.Snapshot validate(Path python, Path script,
            YamlScalarEdit.Preview preview, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        if (python == null || script == null || preview == null || cancelled == null
                || !Files.isRegularFile(script))
            throw new IOException("Managed YAML candidate parser is unavailable.");
        // The temporary input is only a private candidate snapshot; never an
        // original application file. Files are removed even on exceptions.
        Path input = Files.createTempFile("zew-yaml-patch-input-", ".tmp");
        Path output = Files.createTempFile("zew-yaml-patch-report-", ".json");
        Process process = null;
        try {
            byte[] bytes = preview.candidate();
            if (bytes.length > YamlSourceLoader.MAX_SOURCE_BYTES)
                throw new IOException("Edited YAML exceeds the safe source limit.");
            Files.write(input, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            process = new ProcessBuilder(YamlPythonLaunch.command(python, script,
                    "--source-root", "application",
                    "--relative", preview.source().relative()))
                    .redirectInput(input.toFile())
                    .redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (true) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                    throw new InterruptedException("Candidate YAML validation cancelled.");
                if (process.waitFor(125, TimeUnit.MILLISECONDS)) break;
                if (System.nanoTime() >= deadline)
                    throw new IOException("Managed YAML candidate validation timed out.");
            }
            if (process.exitValue() != 0
                    || Files.size(output) > YamlSourceLoader.MAX_REPORT_BYTES)
                throw new IOException("Managed candidate validator rejected the patch.");
            YamlDocumentModel.Snapshot observed = YamlDocumentModel.parse(
                    Files.readString(output, StandardCharsets.UTF_8));
            YamlScalarEdit.verifyCandidate(preview, observed);
            return observed;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Managed candidate parser returned an invalid observation.", invalid);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            remove(input);
            remove(output);
        }
    }

    private static void remove(Path file) {
        for (int i = 0; i < 6; i++) {
            try {
                Files.deleteIfExists(file);
                return;
            } catch (java.nio.file.FileSystemException locked) {
                try { Thread.sleep(50); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (IOException unavailable) { break; }
        }
        file.toFile().deleteOnExit();
    }
}
