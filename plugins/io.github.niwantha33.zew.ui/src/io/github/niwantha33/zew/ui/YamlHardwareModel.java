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
 * Step 8: typed, read-only selected-checkout HWMv2 evidence.
 *
 * The resolver is Zephyr's list_boards / list_hardware Python code, not a
 * locally maintained board/schema interpreter. No build or device state.
 */
public final class YamlHardwareModel {
    private static final int MAX_REPORT = 128 * 1024;
    private static final int MAX_ROWS = 256;
    private static final String ZERO = "0".repeat(64);

    public sealed interface Item permits Board, Soc, Revision, Qualifier {}
    public record Board(String name, String fullName, String vendor,
                        String revisionFormat, String revisionDefault,
                        boolean revisionExact) implements Item {}
    public record Soc(String name, String family, String series,
                      List<String> cpuclusters) implements Item {
        public Soc { cpuclusters = List.copyOf(cpuclusters); }
    }
    public record Revision(String board, String name) implements Item {}
    public record Qualifier(String board, String name) implements Item {}

    public record Summary(String state, String reason, String sourceSha256,
                          String kind, String apiSha256, List<Item> items) {
        public Summary {
            if (!Set.of("resolved", "unavailable", "not-hwmv2").contains(state)
                    || reason == null || sourceSha256 == null || apiSha256 == null
                    || !sourceSha256.matches("[0-9a-f]{64}")
                    || !apiSha256.matches("[0-9a-f]{64}")
                    || !Set.of("board", "soc", "none").contains(kind)
                    || items == null || items.size() > MAX_ROWS
                    || ("resolved".equals(state) && (items.isEmpty() || "none".equals(kind)))
                    || (!"resolved".equals(state) && !items.isEmpty()))
                throw invalid();
            items = List.copyOf(items);
        }
        public boolean resolved() { return "resolved".equals(state); }
    }

    /** Immutable, SWT-independent hierarchy rows for the dedicated view tab. */
    public record Row(String label, String detail, List<Row> children) {
        public Row {
            if (label == null || detail == null || children == null) throw invalid();
            children = List.copyOf(children);
        }
    }

    private YamlHardwareModel() {}

    public static Summary unavailable(String reason) {
        if (reason == null || !reason.matches("[a-z0-9-]{1,80}")) throw invalid();
        return new Summary("unavailable", reason, ZERO, "none", ZERO, List.of());
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid HWMv2 observation");
    }

    private static String decoded(String encoded) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length > 2048) throw invalid();
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (IllegalArgumentException | CharacterCodingException error) {
            throw invalid();
        }
    }

    private static String[] fields(String line, String type, int count) {
        String[] parts = line.split("\t", -1);
        if (parts.length != count || !parts[0].equals(type)) throw invalid();
        for (int i = 1; i < parts.length; i++) parts[i] = decoded(parts[i]);
        return parts;
    }

    public static Summary parse(String wire) {
        if (wire == null || wire.length() > MAX_REPORT) throw invalid();
        String[] lines = wire.split("\r?\n", -1);
        if (lines.length < 2 || !"ZEW-HWMV2-1".equals(lines[0])) throw invalid();
        String[] header = fields(lines[1], "H", 6);
        if (!Set.of("resolved", "unavailable", "not-hwmv2").contains(header[1]))
            throw invalid();
        List<Item> items = new ArrayList<>();
        String currentBoard = null;
        for (int i = 2; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                if (i != lines.length - 1) throw invalid();
                continue;
            }
            if (items.size() >= MAX_ROWS) throw invalid();
            if (line.startsWith("B\t")) {
                String[] b = fields(line, "B", 7);
                if (!"board".equals(header[4]) || b[1].isBlank()
                        || !Set.of("yes", "no").contains(b[6])) throw invalid();
                currentBoard = b[1];
                items.add(new Board(b[1], b[2], b[3], b[4], b[5],
                        "yes".equals(b[6])));
            } else if (line.startsWith("R\t")) {
                String[] r = fields(line, "R", 3);
                if (currentBoard == null || !currentBoard.equals(r[1]) || r[2].isBlank())
                    throw invalid();
                items.add(new Revision(r[1], r[2]));
            } else if (line.startsWith("Q\t")) {
                String[] q = fields(line, "Q", 3);
                if (currentBoard == null || !currentBoard.equals(q[1]) || q[2].isBlank())
                    throw invalid();
                items.add(new Qualifier(q[1], q[2]));
            } else if (line.startsWith("S\t")) {
                String[] s = fields(line, "S", 5);
                if (s[1].isBlank() || ("board".equals(header[4]) && currentBoard == null))
                    throw invalid();
                List<String> clusters = s[4].isEmpty()
                        ? List.of() : List.of(s[4].split(",", -1));
                if (clusters.size() > 32 || clusters.stream().anyMatch(String::isBlank))
                    throw invalid();
                items.add(new Soc(s[1], s[2], s[3], clusters));
            } else throw invalid();
        }
        return new Summary(header[1], header[2], header[3], header[4], header[5], items);
    }

    private static Row soc(Soc soc) {
        List<Row> details = new ArrayList<>();
        if (!soc.family().isEmpty())
            details.add(new Row("Family", soc.family(), List.of()));
        if (!soc.series().isEmpty())
            details.add(new Row("Series", soc.series(), List.of()));
        for (String cluster : soc.cpuclusters())
            details.add(new Row("CPU cluster", cluster, List.of()));
        return new Row("SoC: " + soc.name(), "Resolved by selected checkout", details);
    }

    public static List<Row> hierarchy(Summary summary) {
        if (summary == null || !summary.resolved()) return List.of();
        List<Row> rows = new ArrayList<>();
        Board current = null;
        List<Row> children = new ArrayList<>();
        for (Item item : summary.items()) {
            if (item instanceof Board board) {
                if (current != null) rows.add(board(current, children));
                current = board;
                children = new ArrayList<>();
            } else if (item instanceof Soc soc) {
                if (current == null) rows.add(soc(soc));
                else children.add(soc(soc));
            } else if (item instanceof Revision revision) {
                children.add(new Row("Revision", revision.name(), List.of()));
            } else if (item instanceof Qualifier qualifier) {
                children.add(new Row("Board target qualifier",
                        qualifier.name(), List.of()));
            }
        }
        if (current != null) rows.add(board(current, children));
        return List.copyOf(rows);
    }

    private static Row board(Board value, List<Row> associated) {
        List<Row> children = new ArrayList<>();
        if (!value.fullName().isEmpty())
            children.add(new Row("Full name", value.fullName(), List.of()));
        if (!value.revisionFormat().isEmpty())
            children.add(new Row("Revision format", value.revisionFormat(), List.of()));
        if (!value.revisionDefault().isEmpty())
            children.add(new Row("Default revision", value.revisionDefault(), List.of()));
        children.add(new Row("Revision exact", value.revisionExact() ? "yes" : "no", List.of()));
        children.addAll(associated);
        return new Row("Board: " + value.name(),
                "Vendor: " + (value.vendor().isEmpty() ? "(unknown)" : value.vendor()),
                children);
    }

    /**
     * Cancellable, read-only inspection. Java independently checks the
     * selected YAML hash; upstream metadata is explicitly separate from a
     * successful firmware build or selected board target.
     */
    public static Summary inspect(Path python, Path script, YamlSourceLoader.Source source,
            Path zephyr, Path application, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        if (python == null || script == null || source == null || zephyr == null
                || application == null || cancelled == null || !Files.isRegularFile(script))
            throw new IOException("Selected HWMv2 resolver is unavailable");
        String before = YamlSourceLoader.hash(source.file());
        Path output = Files.createTempFile("zew-hwmv2-", ".txt");
        Process process = null;
        try {
            process = new ProcessBuilder(YamlPythonLaunch.command(python, script,
                    "--root", source.root().toString(), "--relative", source.relative(),
                    "--source-root", source.kind(), "--zephyr", zephyr.toString(),
                    "--application", application.toString()))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(output.toFile()).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (true) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                    throw new InterruptedException("HWMv2 inspection cancelled");
                if (process.waitFor(125, TimeUnit.MILLISECONDS)) break;
                if (System.nanoTime() > deadline)
                    throw new IOException("Selected HWMv2 resolver timed out");
            }
            if (process.exitValue() != 0 || Files.size(output) > MAX_REPORT)
                throw new IOException("Selected HWMv2 resolver failed or overflowed");
            Summary summary = parse(Files.readString(output, StandardCharsets.UTF_8));
            if (!before.equals(YamlSourceLoader.hash(source.file()))
                    || (summary.resolved() && !before.equals(summary.sourceSha256())))
                throw new IOException("HWMv2 metadata changed during inspection");
            return summary;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid HWMv2 resolver output", invalid);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            boolean deleted = false;
            for (int attempts = 0; attempts < 5; attempts++) {
                try {
                    Files.deleteIfExists(output);
                    deleted = true;
                    break;
                } catch (java.nio.file.FileSystemException locked) {
                    try { Thread.sleep(50); }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
                }
            }
            if (!deleted) output.toFile().deleteOnExit();
        }
    }
}
