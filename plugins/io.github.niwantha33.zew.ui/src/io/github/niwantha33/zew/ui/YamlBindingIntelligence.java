// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Read-only adapter for the selected Zephyr checkout's edtlib.Binding API.
 *
 * No YAML schema heuristics or Java YAML parsing. A resolved binding is not
 * evidence that any generated DTS node matched it or a build used it.
 */
public final class YamlBindingIntelligence {
    private static final int MAX_REPORT = 128 * 1024;
    private static final int MAX_PROPERTIES = 256;
    private static final Set<String> STATES = Set.of("resolved", "unavailable", "not-binding");

    public record Property(String name, String type, boolean required,
                           boolean deprecated, String description) {}

    public record Summary(String state, String reason, String sourceSha256,
                          String edtlibSha256, String compatible, String title,
                          String description, int includedCount, List<Property> properties) {
        public Summary {
            if (!STATES.contains(state) || reason == null || sourceSha256 == null
                    || edtlibSha256 == null || compatible == null || title == null
                    || description == null || includedCount < 0 || includedCount > 64
                    || properties == null || properties.size() > MAX_PROPERTIES)
                throw new IllegalArgumentException("Invalid binding observation");
            properties = List.copyOf(properties);
        }
        public boolean resolved() { return "resolved".equals(state); }
    }

    private YamlBindingIntelligence() {}

    public static Summary unavailable(String reason) {
        return new Summary("unavailable", reason, "0".repeat(64), "0".repeat(64),
                "", "", "", 0, List.of());
    }

    private static String decode(String raw) {
        try {
            byte[] bytes = Base64.getDecoder().decode(raw);
            if (bytes.length > 2048) throw new IllegalArgumentException();
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (IllegalArgumentException | CharacterCodingException problem) {
            throw new IllegalArgumentException("Invalid binding observation");
        }
    }

    private static String[] fields(String text, int total) {
        String[] parts = text.split("\t", -1);
        if (parts.length != total) throw new IllegalArgumentException("Invalid binding observation");
        for (int index = 1; index < parts.length; index++) parts[index] = decode(parts[index]);
        return parts;
    }

    /** Strict, bounded, tab+base64 read-only protocol; no source-text errors. */
    public static Summary parse(String wire) {
        if (wire == null || wire.length() > MAX_REPORT)
            throw new IllegalArgumentException("Invalid binding observation");
        String[] lines = wire.split("\r?\n", -1);
        if (lines.length < 3 || !"ZEW-YAMLBIND-1".equals(lines[0]))
            throw new IllegalArgumentException("Invalid binding observation");
        String[] row = fields(lines[1], 9);
        if (!"S".equals(row[0]) || !STATES.contains(row[1])
                || !row[2].matches("[a-z0-9-]{1,80}")
                || !row[3].matches("[0-9a-f]{64}") || !row[4].matches("[0-9a-f]{64}")
                || !row[8].matches("(?:0|[1-9][0-9]?)"))
            throw new IllegalArgumentException("Invalid binding observation");
        int count = Integer.parseInt(row[8]);
        if (count > 64) throw new IllegalArgumentException("Invalid binding observation");
        var props = new ArrayList<Property>();
        for (int i = 2; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                if (i != lines.length - 1) throw new IllegalArgumentException("Invalid binding observation");
                continue;
            }
            String[] prop = fields(lines[i], 6);
            if (!"P".equals(prop[0]) || props.size() >= MAX_PROPERTIES
                    || prop[1].isBlank() || prop[2].isBlank()
                    || !Set.of("yes", "no").contains(prop[3])
                    || !Set.of("yes", "no").contains(prop[4]))
                throw new IllegalArgumentException("Invalid binding observation");
            props.add(new Property(prop[1], prop[2], "yes".equals(prop[3]),
                    "yes".equals(prop[4]), prop[5]));
        }
        if (!"resolved".equals(row[1]) && !props.isEmpty())
            throw new IllegalArgumentException("Invalid binding observation");
        return new Summary(row[1], row[2], row[3], row[4],
                row[5], row[6], row[7], count, props);
    }

    /**
     * Must be run on an Eclipse background Job; no shell or ambient Python.
     * Returns explicit unavailable state when the checkout rejects the binding.
     */
    public static Summary inspect(Path managedPython, Path script,
            YamlSourceLoader.Source source, Path zephyr, Path application,
            BooleanSupplier cancelled) throws IOException, InterruptedException {
        if (managedPython == null || script == null || source == null
                || zephyr == null || application == null || cancelled == null
                || !Files.isRegularFile(script))
            throw new IOException("Selected binding inspector is unavailable");
        if (!source.relative().startsWith("dts/bindings/"))
            return new Summary("not-binding", "not-a-binding-path", "0".repeat(64),
                    "0".repeat(64), "", "", "", 0, List.of());
        String sha = YamlSourceLoader.hash(source.file());
        Path output = Files.createTempFile("zew-yaml-binding-", ".txt");
        Process process = null;
        try {
            process = new ProcessBuilder(managedPython.toString(), script.toString(),
                    "--root", source.root().toString(),
                    "--relative", source.relative(), "--source-root", source.kind(),
                    "--zephyr", zephyr.toString(), "--application", application.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(output.toFile()).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (true) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                    throw new InterruptedException("Binding inspection cancelled");
                if (process.waitFor(125, TimeUnit.MILLISECONDS)) break;
                if (System.nanoTime() > deadline) throw new IOException("Binding inspection timed out");
            }
            if (process.exitValue() != 0 || Files.size(output) > MAX_REPORT)
                throw new IOException("Selected binding inspector did not return bounded evidence");
            Summary summary = parse(Files.readString(output, StandardCharsets.UTF_8));
            if (!sha.equals(YamlSourceLoader.hash(source.file())))
                throw new IOException("Selected YAML changed during binding inspection");
            if (summary.resolved() && !sha.equals(summary.sourceSha256()))
                throw new IOException("Binding inspection source digest does not match");
            return summary;
        } catch (IllegalArgumentException problem) {
            throw new IOException("Invalid selected binding inspection", problem);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            boolean removed = false;
            for (int attempt = 0; attempt < 5; attempt++) {
                try {
                    Files.deleteIfExists(output);
                    removed = true;
                    break;
                } catch (java.nio.file.FileSystemException locked) {
                    if (attempt == 4) break;
                    try { Thread.sleep(50); }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
                }
            }
            if (!removed) output.toFile().deleteOnExit();
        }
    }

    /** Compact text rendered in the existing YAML Structure view, not a new editor. */
    public static String describe(Summary result) {
        if (result == null) return "Binding intelligence: unavailable";
        if (!result.resolved())
            return "Binding intelligence: " + result.state() + " (" + result.reason()
                    + "). Upstream semantics not established.";
        StringBuilder view = new StringBuilder();
        view.append("Resolved by the selected edtlib.Binding (read-only)\n");
        view.append("Compatible: ").append(result.compatible().isEmpty() ? "(none)" : result.compatible());
        view.append("   Includes: ").append(result.includedCount()).append('\n');
        if (!result.title().isEmpty()) view.append("Title: ").append(result.title()).append('\n');
        if (!result.description().isEmpty()) view.append("Description: ").append(result.description()).append('\n');
        view.append("Properties (including inherited): ").append(result.properties().size()).append('\n');
        for (Property spec : result.properties()) {
            view.append("  ").append(spec.name()).append(" : ").append(spec.type());
            if (spec.required()) view.append("  [required]");
            if (spec.deprecated()) view.append("  [deprecated]");
            if (!spec.description().isEmpty())
                view.append("  — ").append(spec.description().replace("\r", " ").replace("\n", " "));
            view.append('\n');
        }
        view.append("No built-DTS match, build inclusion or electrical hardware state is implied.");
        return view.toString();
    }
}
