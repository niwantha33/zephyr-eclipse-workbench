// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Executes only the packaged read-only Studio CLI, without a shell or target build. */
public final class StudioEvidenceInspector {
    private StudioEvidenceInspector() {}
    public record Result(String output, String equivalentCommand, int exitCode) {
        public String display() {
            return "SELECTED BUILD — HEADLESS KCONFIG EVIDENCE\n"
                    + "Equivalent CLI: " + equivalentCommand
                    + "\nThis is recorded evaluator output, not a runtime or source-freshness claim."
                    + "\nNo target build, flash or source edit was requested.\n\n" + output;
        }
    }

    /** The caller must supply only installed, version-controlled resource bytes. */
    public static Result kconfig(Path python, Path ninja, Path build, Path snippet,
            Map<String, InputStream> resources, Map<String, String> environment,
            BooleanSupplier cancelled) throws IOException, InterruptedException {
        Path temporary = null;
        try {
            if (python == null || ninja == null || build == null || snippet == null
                    || resources == null || cancelled == null || !Files.isRegularFile(python)
                    || !Files.isRegularFile(ninja) || !Files.isDirectory(build)
                    || !Files.isRegularFile(snippet) || resources.size() > 64
                    || !resources.containsKey("studio.py")
                    || !resources.containsKey("zew_evidence/__init__.py"))
                throw new IOException("Selected Python, Ninja, source fragment or packaged Studio CLI is unavailable.");
            temporary = Files.createTempDirectory("zew-studio-cli-Ω ");
            Files.createDirectory(temporary.resolve("zew_evidence"));
            long total = 0;
            for (var resource : resources.entrySet()) {
                if (cancelled.getAsBoolean()) throw new java.io.InterruptedIOException("Diagnosis cancelled.");
                String name = resource.getKey();
                if (!name.equals("studio.py") && !name.matches("zew_evidence/[a-z_][a-z0-9_]*\\.py")
                        || resource.getValue() == null)
                    throw new IOException("Invalid packaged Studio resource.");
                byte[] bytes = resource.getValue().readNBytes(512 * 1024 + 1);
                total += bytes.length;
                if (bytes.length > 512 * 1024 || total > 8 * 1024 * 1024)
                    throw new IOException("Packaged Studio resources exceed their bounded size.");
                Files.write(temporary.resolve(name), bytes);
            }
            var command = new ArrayList<>(List.of(python.toString(), "-B",
                    temporary.resolve("studio.py").toString(), "studio-kconfig", "--build",
                    build.toString(), "--snippet", snippet.toString(), "--ninja", ninja.toString()));
            var result = new ProcessRunner().run(command, temporary, Duration.ofSeconds(90),
                    cancelled, ignored -> {}, environment);
            if (result.exitCode() != 0 && result.exitCode() != 1) result.requireSuccess();
            if (result.truncated()) throw new IOException("Studio report exceeds the display limit; use the CLI directly.");
            String equivalent = "zew studio-kconfig --build " + quoted(build)
                    + " --snippet " + quoted(snippet) + " --ninja " + quoted(ninja);
            return new Result(result.output(), equivalent, result.exitCode());
        } finally {
            if (resources != null) for (var stream : resources.values()) {
                if (stream != null) try { stream.close(); } catch (IOException ignored) { }
            }
            if (temporary != null) try (var walk = Files.walk(temporary)) {
                for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    private static String quoted(Path path) {
        // This is a display-only argv representation, never submitted to a shell.
        return "\"" + path.toString().replace("\"", "\\\"") + "\"";
    }
}
