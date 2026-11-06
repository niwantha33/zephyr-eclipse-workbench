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
 * Read-only, typed Twister schema observations using the selected checkout.
 *
 * An upstream schema-valid test scenario is NOT evidence that a Twister
 * test plan selected it, a board is compatible, or any test was run/passed.
 */
public final class YamlTwisterModel {
    private static final String ZERO = "0".repeat(64);
    private static final int MAX_REPORT = 128 * 1024;
    private static final int MAX_ROWS = 256;
    private static final Set<String> STATES = Set.of("resolved", "unavailable", "not-twister");

    public sealed interface Item permits Scenario, Attribute, Common, Sample, Option, Platform, Level {}
    public record Scenario(String name) implements Item {}
    public record Attribute(String scenario, String key, String preview) implements Item {}
    public record Common(String key, String preview) implements Item {}
    public record Sample(String key, String preview) implements Item {}
    public record Option(String key, String preview) implements Item {}
    public record Platform(String name) implements Item {}
    public record Level(String name, String description, String adds, String inherits) implements Item {}

    public record Summary(String state, String reason, String sourceSha256,
                          String apiSha256, String schemaSha256, String kind,
                          List<Item> items) {
        public Summary {
            if (!STATES.contains(state) || reason == null || sourceSha256 == null
                    || apiSha256 == null || schemaSha256 == null
                    || !sourceSha256.matches("[0-9a-f]{64}")
                    || !apiSha256.matches("[0-9a-f]{64}")
                    || !schemaSha256.matches("[0-9a-f]{64}")
                    || !Set.of("testsuite", "test-config", "none").contains(kind)
                    || items == null || items.size() > MAX_ROWS
                    || ("resolved".equals(state) && "none".equals(kind))
                    || (!"resolved".equals(state) && (!items.isEmpty() || !"none".equals(kind))))
                throw invalid();
            items = List.copyOf(items);
        }
        public boolean resolved() { return "resolved".equals(state); }
    }
    public record Row(String name, String preview, List<Row> children) {
        public Row {
            if (name == null || preview == null || children == null) throw invalid();
            children = List.copyOf(children);
        }
    }

    private YamlTwisterModel() {}

    public static Summary unavailable(String reason) {
        if (reason == null || !reason.matches("[a-z0-9-]{1,80}")) throw invalid();
        return new Summary("unavailable", reason, ZERO, ZERO, ZERO, "none", List.of());
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid Twister observation");
    }

    private static String decode(String encoded) {
        try {
            byte[] source = Base64.getDecoder().decode(encoded);
            if (source.length > 2048) throw invalid();
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(source)).toString();
        } catch (IllegalArgumentException | CharacterCodingException error) {
            throw invalid();
        }
    }

    private static String[] fields(String wire, String type, int count) {
        String[] encoded = wire.split("\t", -1);
        if (encoded.length != count || !type.equals(encoded[0])) throw invalid();
        for (int i = 1; i < encoded.length; i++) encoded[i] = decode(encoded[i]);
        return encoded;
    }

    /** Strict, length-bounded versioned wire protocol with no raw YAML in errors. */
    public static Summary parse(String wire) {
        if (wire == null || wire.length() > MAX_REPORT) throw invalid();
        String[] lines = wire.split("\r?\n", -1);
        if (lines.length < 2 || !"ZEW-TWISTER-1".equals(lines[0])) throw invalid();
        String[] h = fields(lines[1], "H", 7);
        String state = h[1], kind = h[6];
        if (!STATES.contains(state) || !h[2].matches("[a-z0-9-]{1,80}")
                || !h[3].matches("[0-9a-f]{64}")
                || !h[4].matches("[0-9a-f]{64}")
                || !h[5].matches("[0-9a-f]{64}")
                || !Set.of("testsuite", "test-config", "none").contains(kind))
            throw invalid();
        var items = new ArrayList<Item>();
        String currentScenario = null;
        int scenarios = 0;
        for (int i = 2; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                if (i != lines.length - 1) throw invalid();
                continue;
            }
            if (items.size() >= MAX_ROWS) throw invalid();
            String line = lines[i];
            if (line.startsWith("T\t")) {
                String[] t = fields(line, "T", 2);
                if (!"testsuite".equals(kind) || t[1].isBlank()) throw invalid();
                currentScenario = t[1];
                scenarios++;
                items.add(new Scenario(t[1]));
            } else if (line.startsWith("A\t")) {
                String[] a = fields(line, "A", 4);
                if (!"testsuite".equals(kind) || currentScenario == null
                        || !currentScenario.equals(a[1]) || a[2].isBlank()) throw invalid();
                items.add(new Attribute(a[1], a[2], a[3]));
            } else if (line.startsWith("C\t") || line.startsWith("M\t")) {
                String code = line.substring(0, 1);
                String[] a = fields(line, code, 3);
                if (!"testsuite".equals(kind) || currentScenario != null || a[1].isBlank())
                    throw invalid();
                items.add("C".equals(code) ? new Common(a[1], a[2]) : new Sample(a[1], a[2]));
            } else if (line.startsWith("O\t")) {
                String[] o = fields(line, "O", 3);
                if (!"test-config".equals(kind) || o[1].isBlank()) throw invalid();
                items.add(new Option(o[1], o[2]));
            } else if (line.startsWith("P\t")) {
                String[] p = fields(line, "P", 2);
                if (!"test-config".equals(kind) || p[1].isBlank()) throw invalid();
                items.add(new Platform(p[1]));
            } else if (line.startsWith("L\t")) {
                String[] l = fields(line, "L", 5);
                if (!"test-config".equals(kind) || l[1].isBlank()) throw invalid();
                items.add(new Level(l[1], l[2], l[3], l[4]));
            } else throw invalid();
        }
        if ("resolved".equals(state) && "testsuite".equals(kind) && scenarios == 0)
            throw invalid();
        return new Summary(state, h[2], h[3], h[4], h[5], kind, items);
    }

    /** Presentation is metadata only, not a rendered execution plan. */
    public static List<Row> hierarchy(Summary summary) {
        if (summary == null || !summary.resolved()) return List.of();
        List<Row> root = new ArrayList<>();
        List<Row> sample = new ArrayList<>();
        List<Row> common = new ArrayList<>();
        List<Row> config = new ArrayList<>();
        List<Row> platforms = new ArrayList<>();
        List<Row> levels = new ArrayList<>();
        String scenarioName = null;
        List<Row> scenarioAttributes = new ArrayList<>();
        for (Item item : summary.items()) {
            if (item instanceof Scenario scenario) {
                if (scenarioName != null)
                    root.add(new Row("Scenario: " + scenarioName, "Declared in YAML", scenarioAttributes));
                scenarioName = scenario.name();
                scenarioAttributes = new ArrayList<>();
            } else if (item instanceof Attribute attribute) {
                scenarioAttributes.add(new Row(attribute.key(), attribute.preview(), List.of()));
            } else if (item instanceof Common field) {
                common.add(new Row(field.key(), field.preview(), List.of()));
            } else if (item instanceof Sample field) {
                sample.add(new Row(field.key(), field.preview(), List.of()));
            } else if (item instanceof Option option) {
                config.add(new Row(option.key(), option.preview(), List.of()));
            } else if (item instanceof Platform platform) {
                platforms.add(new Row("Default platform", platform.name(), List.of()));
            } else if (item instanceof Level level) {
                List<Row> attrs = new ArrayList<>();
                if (!level.description().isEmpty())
                    attrs.add(new Row("Description", level.description(), List.of()));
                if (!level.adds().isEmpty())
                    attrs.add(new Row("Adds", level.adds(), List.of()));
                if (!level.inherits().isEmpty())
                    attrs.add(new Row("Inherits", level.inherits(), List.of()));
                levels.add(new Row("Level: " + level.name(), "Declared level", attrs));
            }
        }
        if (scenarioName != null)
            root.add(new Row("Scenario: " + scenarioName, "Declared in YAML", scenarioAttributes));
        if (!sample.isEmpty()) root.add(0, new Row("Sample metadata", "", sample));
        if (!common.isEmpty()) root.add(sample.isEmpty() ? 0 : 1,
                new Row("Common defaults (declared)", "", common));
        if (!config.isEmpty()) root.add(new Row("Config options", "", config));
        if (!platforms.isEmpty()) root.add(new Row("Default platforms (declared)", "", platforms));
        if (!levels.isEmpty()) root.add(new Row("Levels (declared)", "", levels));
        return List.copyOf(root);
    }

    /** Read-only managed-Python bridge; all source observations stay in a Job. */
    public static Summary inspect(Path python, Path script, YamlSourceLoader.Source source,
                                  Path zephyr, Path application, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        if (python == null || script == null || source == null || zephyr == null
                || application == null || cancelled == null || !Files.isRegularFile(script))
            throw new IOException("Selected Twister metadata inspector unavailable");
        String before = YamlSourceLoader.hash(source.file());
        Path output = Files.createTempFile("zew-twister-", ".txt");
        Process process = null;
        try {
            process = new ProcessBuilder(python.toString(), script.toString(),
                    "--root", source.root().toString(), "--relative", source.relative(),
                    "--source-root", source.kind(), "--zephyr", zephyr.toString(),
                    "--application", application.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(output.toFile()).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (true) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                    throw new InterruptedException("Twister observation cancelled");
                if (process.waitFor(125, TimeUnit.MILLISECONDS)) break;
                if (System.nanoTime() > deadline)
                    throw new IOException("Selected Twister inspector timed out");
            }
            if (process.exitValue() != 0 || Files.size(output) > MAX_REPORT)
                throw new IOException("Twister inspector failed or exceeded report limit");
            Summary summary = parse(Files.readString(output, StandardCharsets.UTF_8));
            if (!before.equals(YamlSourceLoader.hash(source.file()))
                    || (summary.resolved() && !before.equals(summary.sourceSha256())))
                throw new IOException("Twister source changed during inspection");
            return summary;
        } catch (IllegalArgumentException error) {
            throw new IOException("Unrecognized Twister metadata report", error);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
            boolean deleted = false;
            for (int attempt = 0; attempt < 5; attempt++) {
                try {
                    Files.deleteIfExists(output);
                    deleted = true;
                    break;
                } catch (java.nio.file.FileSystemException locked) {
                    try { Thread.sleep(50); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); break; }
                }
            }
            if (!deleted) output.toFile().deleteOnExit();
        }
    }
}
