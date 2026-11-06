// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Step 6: bounded, read-only source selection and managed-Python bridge.
 *
 * A source is accepted only inside the exact managed application or Zephyr
 * checkout; no guessed workspace roots or arbitrary Python interpreter.
 * No Eclipse/SWT dependencies so source safety and execution are tested headless.
 */
public final class YamlSourceLoader {
    public static final long MAX_SOURCE_BYTES = 256 * 1024;
    public static final long MAX_REPORT_BYTES = 4 * 1024 * 1024;

    public record Source(Path root, Path file, String relative, String kind) {
        public Source {
            if (root == null || file == null || relative == null || kind == null
                    || !List.of("application", "zephyr").contains(kind))
                throw new IllegalArgumentException("Invalid selected YAML source");
        }
    }

    private YamlSourceLoader() {}

    /** Only physically present, non-symlink YAML files within verified roots. */
    public static Source resolve(Path selected, Path application, Path zephyr) throws IOException {
        if (selected == null) throw new IOException("Select one YAML source file.");
        Path file = selected.toAbsolutePath().normalize();
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!(name.endsWith(".yaml") || name.endsWith(".yml")))
            throw new IOException("Select a YAML file (.yaml or .yml).");
        Source source = under(file, application, "application");
        if (source == null) source = under(file, zephyr, "zephyr");
        if (source == null)
            throw new IOException("The source is outside the selected Zephyr application and checkout.");
        return source;
    }

    private static Source under(Path file, Path rootCandidate, String kind) throws IOException {
        if (rootCandidate == null) return null;
        Path root = rootCandidate.toAbsolutePath().normalize();
        if (!file.startsWith(root)) return null;
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root))
            throw new IOException("Selected source root is not an existing regular directory.");
        Path relative = root.relativize(file);
        if (relative.getNameCount() == 0 || relative.getNameCount() > 128
                || relative.toString().length() > 4096)
            throw new IOException("Invalid source path.");
        Path step = root;
        for (Path part : relative) {
            step = step.resolve(part);
            if (Files.isSymbolicLink(step))
                throw new IOException("Symlinked YAML source paths are unsupported.");
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !file.toRealPath().startsWith(root.toRealPath()))
            throw new IOException("The selected YAML file is unavailable or outside its root.");
        String posix = relative.toString().replace('\\', '/');
        return new Source(root, file, posix, kind);
    }

    /** Fresh digest: hashes bytes, not timestamps, and refuses oversized files. */
    public static String hash(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) > MAX_SOURCE_BYTES) throw new IOException("YAML source is unavailable or oversized.");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
                byte[] block = new byte[8192];
                long read = 0;
                int count;
                while ((count = input.read(block)) != -1) {
                    read += count;
                    if (read > MAX_SOURCE_BYTES) throw new IOException("YAML source exceeds 256 KiB.");
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 unavailable", error);
        }
    }

    /**
     * Must run in a background Job. The bundled script performs its own
     * independent path/symlink/size checks and calls upstream PyYAML.
     */
    public static YamlDocumentModel.Snapshot inspect(
            Path managedPython, Path bundledScript, Source source,
            BooleanSupplier cancelled) throws IOException, InterruptedException {
        if (managedPython == null || bundledScript == null || source == null || cancelled == null
                || !Files.isRegularFile(bundledScript))
            throw new IOException("Managed YAML parser is unavailable.");
        Source verified = resolve(source.file(), "application".equals(source.kind()) ? source.root() : null,
                "zephyr".equals(source.kind()) ? source.root() : null);
        if (!verified.equals(source)) throw new IOException("Selected YAML source context changed.");
        String before = hash(source.file());
        long size = Files.size(source.file());
        Path output = Files.createTempFile("zew-yaml-observation-", ".json");
        Process process = null;
        try {
            ProcessBuilder command = new ProcessBuilder(managedPython.toString(),
                    bundledScript.toString(), "--root", source.root().toString(),
                    "--relative", source.relative(), "--source-root", source.kind());
            command.redirectInput(ProcessBuilder.Redirect.PIPE);
            command.redirectOutput(output.toFile());
            command.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = command.start();
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (true) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                    throw new InterruptedException("YAML inspection cancelled.");
                if (process.waitFor(125, TimeUnit.MILLISECONDS)) break;
                if (System.nanoTime() > limit)
                    throw new IOException("Managed YAML inspection timed out.");
            }
            if (process.exitValue() != 0 || Files.size(output) > MAX_REPORT_BYTES)
                throw new IOException("Managed parser rejected the source or exceeded the output limit.");
            YamlDocumentModel.Snapshot snapshot = YamlDocumentModel.parse(
                    Files.readString(output, StandardCharsets.UTF_8));
            if (!source.kind().equals(snapshot.sourceRoot())
                    || !source.relative().equals(snapshot.relativePath())
                    || !before.equals(snapshot.sha256()) || snapshot.sizeBytes() == null
                    || size != snapshot.sizeBytes()
                    || !before.equals(hash(source.file())))
                throw new IOException("YAML source changed or parser provenance did not match.");
            return snapshot;
        } catch (IllegalArgumentException error) {
            throw new IOException("Managed parser produced an invalid YAML observation.", error);
        } finally {
            // On Windows, destroyForcibly() is asynchronous. Wait until the
            // child has released its redirected stdout handle before unlinking.
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            // A completed Windows child may still release inherited handles
            // a fraction later. Retry briefly; do not override cancellation.
            boolean removed = false;
            for (int attempt = 0; attempt < 5; attempt++) {
                try {
                    Files.deleteIfExists(output);
                    removed = true;
                    break;
                } catch (java.nio.file.FileSystemException locked) {
                    if (attempt == 4) break;
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (!removed) output.toFile().deleteOnExit();
        }
    }
}
