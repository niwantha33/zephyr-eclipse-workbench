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
 * Step 10 read-only YAML diagnostics produced by the managed Python parser
 * and the selected checkout's upstream Twister validator.
 *
 * A "checked" syntax status does not prove EDTlib/HWMv2 validity, nor that
 * Twister selected or ran any scenario. Only verified local IFile sources
 * may publish separate, non-persistent Eclipse markers.
 */
public final class YamlDiagnosticModel {
    private static final String ZERO = "0".repeat(64);
    private static final int MAX_REPORT = 64 * 1024;
    private static final int MAX_FINDINGS = 8;
    private static final Set<String> STATES = Set.of("checked", "invalid", "unavailable", "unsupported");

    public record Finding(String severity, String code, int line, int column, String message) {
        public Finding {
            if (!Set.of("error", "warning").contains(severity)
                    || code == null || !code.matches("[a-z0-9-]{1,100}")
                    || message == null || message.isBlank()
                    || message.length() > 160 || message.chars().anyMatch(c -> c < 32 || c == 127)
                    || line < 0 || line > 999999 || column < 0 || column > 999999
                    || ((line == 0) != (column == 0)))
                throw invalid();
        }

        public boolean located() { return line > 0; }
        public String location() { return located() ? line + ":" + column : "Location unavailable"; }
    }

    public record Summary(String state, String reason, String sourceSha256,
                          String scope, List<Finding> findings) {
        public Summary {
            if (!STATES.contains(state) || reason == null
                    || !reason.matches("[a-z0-9-]{1,90}") || sourceSha256 == null
                    || !sourceSha256.matches("[0-9a-f]{64}")
                    || !Set.of("syntax", "twister", "none").contains(scope)
                    || findings == null || findings.size() > MAX_FINDINGS
                    || ("invalid".equals(state) && (findings.isEmpty() || "none".equals(scope)))
                    || (!"invalid".equals(state) && !findings.isEmpty())
                    || ("checked".equals(state) && "none".equals(scope))
                    || (Set.of("unsupported", "unavailable").contains(state)
                            && !"none".equals(scope)))
                throw invalid();
            findings = List.copyOf(findings);
        }
        public boolean hasErrors() {
            return findings.stream().anyMatch(finding -> "error".equals(finding.severity()));
        }
    }

    public record Row(String severity, String code, String location, String message,
                      int line, int column) {}

    private YamlDiagnosticModel() {}

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid YAML diagnostic observation");
    }

    public static Summary unavailable(String reason) {
        return new Summary("unavailable", reason, ZERO, "none", List.of());
    }

    private static String decode(String raw) {
        try {
            byte[] bytes = Base64.getDecoder().decode(raw);
            if (bytes.length > 4096) throw invalid();
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (IllegalArgumentException | CharacterCodingException error) {
            throw invalid();
        }
    }

    private static String[] fields(String input, String type, int count) {
        String[] parts = input.split("\t", -1);
        if (parts.length != count || !type.equals(parts[0])) throw invalid();
        for (int i = 1; i < parts.length; i++) parts[i] = decode(parts[i]);
        return parts;
    }

    /** Only the exact versioned, finite, source-safe diagnostic wire is accepted. */
    public static Summary parse(String wire) {
        if (wire == null || wire.length() > MAX_REPORT) throw invalid();
        String[] lines = wire.split("\r?\n", -1);
        if (lines.length < 2 || !"ZEW-YAMLDIAG-1".equals(lines[0])) throw invalid();
        String[] header = fields(lines[1], "H", 5);
        List<Finding> findings = new ArrayList<>();
        for (int i = 2; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                if (i != lines.length - 1) throw invalid();
                continue;
            }
            String[] issue = fields(lines[i], "D", 6);
            if (findings.size() >= MAX_FINDINGS
                    || !issue[3].matches("(?:0|[1-9][0-9]{0,5})")
                    || !issue[4].matches("(?:0|[1-9][0-9]{0,5})"))
                throw invalid();
            findings.add(new Finding(issue[1], issue[2],
                    Integer.parseInt(issue[3]), Integer.parseInt(issue[4]), issue[5]));
        }
        return new Summary(header[1], header[2], header[3], header[4], findings);
    }

    public static List<Row> rows(Summary summary) {
        if (summary == null || summary.findings().isEmpty()) return List.of();
        var result = new ArrayList<Row>();
        for (Finding finding : summary.findings())
            result.add(new Row(finding.severity(), finding.code(), finding.location(),
                    finding.message(), finding.line(), finding.column()));
        return List.copyOf(result);
    }

    /** Run on a background Job. Does not read from or modify unsaved buffers. */
    public static Summary inspect(Path managedPython, Path script,
            YamlSourceLoader.Source source, Path zephyr, Path application,
            BooleanSupplier cancelled) throws IOException, InterruptedException {
        if (managedPython == null || script == null || source == null || zephyr == null
                || application == null || cancelled == null || !Files.isRegularFile(script))
            throw new IOException("Managed YAML diagnostic inspector is unavailable");
        String original = YamlSourceLoader.hash(source.file());
        Path report = Files.createTempFile("zew-yaml-diagnostics-", ".txt");
        Process process = null;
        try {
            process = new ProcessBuilder(managedPython.toString(), script.toString(),
                    "--root", source.root().toString(),
                    "--relative", source.relative(),
                    "--source-root", source.kind(), "--zephyr", zephyr.toString(),
                    "--application", application.toString())
                    .redirectInput(ProcessBuilder.Redirect.PIPE)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(report.toFile()).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (true) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                    throw new InterruptedException("YAML diagnostics cancelled");
                if (process.waitFor(125, TimeUnit.MILLISECONDS)) break;
                if (System.nanoTime() > deadline)
                    throw new IOException("YAML diagnostics timed out");
            }
            if (process.exitValue() != 0 || Files.size(report) > MAX_REPORT)
                throw new IOException("YAML diagnostics failed or exceeded its output limit");
            Summary parsed = parse(Files.readString(report, StandardCharsets.UTF_8));
            if (!original.equals(YamlSourceLoader.hash(source.file()))
                    || (!"unavailable".equals(parsed.state())
                            && !original.equals(parsed.sourceSha256())))
                throw new IOException("YAML source changed or diagnostic provenance mismatched");
            return parsed;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Malformed YAML diagnostic observation", invalid);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            boolean deleted = false;
            for (int tries = 0; tries < 5; tries++) {
                try {
                    Files.deleteIfExists(report);
                    deleted = true;
                    break;
                } catch (java.nio.file.FileSystemException locked) {
                    try { Thread.sleep(50); }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
                }
            }
            if (!deleted) report.toFile().deleteOnExit();
        }
    }
}
