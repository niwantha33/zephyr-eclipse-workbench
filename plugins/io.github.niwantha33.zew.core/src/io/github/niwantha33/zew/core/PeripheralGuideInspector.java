// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Thin native SWT bridge to exactly the reviewed zew peripheral-guide. */
public final class PeripheralGuideInspector {
    private PeripheralGuideInspector() {}
    private static final String ZERO = "0".repeat(64);
    private static final List<String> RESOURCES = List.of(
            "peripheral_guide_inspect.py",
            "zew_evidence/__init__.py",
            "zew_evidence/collector.py",
            "zew_evidence/dt_binding_preview.py",
            "zew_evidence/peripheral_guide.py");

    public record Result(String state, String kind, String nodeHash, String propertyHash,
            String dtsHash, String previousOverlayHash, String newOverlayHash,
            String binding, String confidence, String propertyType,
            String nodeStatus, String overlayMode, String diff, String addition) {
        public boolean hasDraft() { return "draft-requires-real-configuration".equals(state); }
        public static Result unavailable() {
            return new Result("unavailable", "unknown", ZERO, ZERO, ZERO,
                    ZERO, ZERO, "unknown", "unknown", "unknown", "unknown",
                    "unknown", "", "");
        }
        public String describe(String node, String field, String intent, boolean stale) {
            return "GUIDED PERIPHERAL SOURCE DRAFT — REVIEW ONLY\n"
                    + "Selected generated node: " + node
                    + "\nRequested change: " + intent
                    + "\nSelected field: " + field
                    + "\nGuide result: " + state
                    + "\nUpstream Zephyr binding: " + binding
                    + "\nMatching property type: " + propertyType
                    + "\nGenerated node status: " + nodeStatus
                    + "\nSelection evidence: " + confidence
                    + "\nOriginal generated DTS SHA-256: " + dtsHash
                    + "\nOriginal selected overlay SHA-256: " + previousOverlayHash
                    + "\nProposed resulting overlay SHA-256: " + newOverlayHash
                    + "\nOverlay mode: " + overlayMode
                    + (stale ? "\nWARNING: Selected generated build may be stale." : "")
                    + "\n\nNo file was written. This is NOT proof the source will configure, "
                    + "compile or run. It does not establish GPIO pad availability, "
                    + "pinctrl, driver inclusion, electrical characteristics, or physical wiring."
                    + "\n\nCopy the proposed ADDITION only, review it inside your selected "
                    + "application overlay, save it deliberately, and use "
                    + "'Real configure + saved overlay' before a firmware build.";
        }
    }

    public static Result parse(String output, String node, String property) throws IOException {
        if (output == null || output.getBytes(StandardCharsets.UTF_8).length > 300000)
            throw new IOException("Guide response size limit");
        String[] lines = output.strip().split("\\R", 2);
        if (lines.length != 2 || !"ZEW-GUIDE-1".equals(lines[0]))
            throw new IOException("Unknown guided overlay protocol");
        String[] encoded = lines[1].split("\\t", -1);
        if (encoded.length != 14) throw new IOException("Wrong number of guide fields");
        String[] f = new String[14];
        for (int i = 0; i < f.length; i++) {
            try {
                byte[] bytes = Base64.getDecoder().decode(encoded[i]);
                if (bytes.length > (i >= 12 ? 102400 : 128))
                    throw new IOException("Oversized guided overlay field");
                f[i] = new String(bytes, StandardCharsets.UTF_8);
                if (i >= 12 && "~".equals(f[i])) f[i] = "";
                if (i < 12 && !f[i].matches("[a-z0-9-]{1,100}|[a-f0-9]{64}"))
                    throw new IOException("Invalid guide scalar");
                if (i >= 12 && (f[i].contains("\u0000") || f[i].contains("\r")))
                    throw new IOException("Unsafe guide diff");
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Bad guide base64 encoding", invalid);
            }
        }
        if (!Set.of("draft-requires-real-configuration", "manual-review-required",
                "unavailable").contains(f[0])
                || !Set.of("status", "int", "enum", "unknown").contains(f[1])
                || !hashValue(f[2]) || !hashValue(f[3]) || !hashValue(f[4])
                || !hashValue(f[5]) || !hashValue(f[6])
                || !Set.of("matched", "unmatched", "unknown").contains(f[7])
                || !Set.of("confirmed", "unknown").contains(f[8])
                || !Set.of("status-string", "int", "string", "boolean", "array",
                    "string-array", "phandle", "phandle-array", "uint8-array",
                    "other", "unknown").contains(f[9])
                || !Set.of("okay", "disabled", "reserved", "fail", "fail-sss",
                    "unknown").contains(f[10])
                || !Set.of("no-overlay-selected", "new-file-proposal",
                    "existing-file-proposal", "unknown").contains(f[11]))
            throw new IOException("Unrecognised guide state");

        if (!f[0].equals("unavailable") &&
                (!f[2].equals(sha(node)) || !f[3].equals(sha(property))))
            throw new IOException("Guided overlay response belongs to another selection");
        if ("draft-requires-real-configuration".equals(f[0])) {
            if (!"matched".equals(f[7]) || !"confirmed".equals(f[8])
                    || ZERO.equals(f[4]) || ZERO.equals(f[6])
                    || f[12].isBlank() || f[13].isBlank()
                    || !f[13].contains("&{" + node + "} {")
                    || !f[13].contains("\n    " + property + " = ")
                    || !f[12].contains("+++ selected-overlay-proposal")
                    || !f[12].contains("+" + f[13].split("\n", -1)[0]))
                throw new IOException("Unverified source diff claimed accepted");
        } else if (!f[12].isEmpty() || !f[13].isEmpty())
            throw new IOException("Unverified guide includes arbitrary source text");
        return new Result(f[0], f[1], f[2], f[3], f[4], f[5], f[6],
                f[7], f[8], f[9], f[10], f[11], f[12], f[13]);
    }

    private static boolean hashValue(String x) { return x.matches("[a-f0-9]{64}"); }
    private static String sha(String value) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException absent) {
            throw new IOException("SHA-256 unavailable", absent);
        }
    }
    private static String fileHash(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > 4 * 1024 * 1024)
            throw new IOException("Missing/oversized evidence");
        return shaBytes(Files.readAllBytes(path));
    }
    private static String shaBytes(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException absent) { throw new IOException("SHA-256 unavailable", absent); }
    }
    private static void stop(Process p) {
        p.descendants().forEach(child -> child.destroyForcibly());
        p.destroyForcibly();
    }

    /** All inputs must be from the selected project; no actual overlay is written. */
    public static Result inspect(Path python, Path zephyr, Path build, Path application,
            Path overlay, String node, String property, String intent, String raw,
            Map<String, InputStream> packaged, BooleanSupplier cancelled) {
        if (python == null || zephyr == null || build == null || application == null
                || overlay == null || node == null || property == null || intent == null
                || packaged == null || cancelled == null
                || !List.of("status", "int", "enum").contains(intent)
                || !node.matches("/(?:[A-Za-z0-9,._+@-]+(?:/[A-Za-z0-9,._+@-]+)*)?")
                || !property.matches("[A-Za-z][A-Za-z0-9,._+?-]{0,99}")
                || !Files.isRegularFile(python) || !Files.isDirectory(zephyr)
                || !Files.isRegularFile(build.resolve("zephyr/zephyr.dts"))
                || !Files.isRegularFile(build.resolve("CMakeCache.txt"))
                || !overlay.toAbsolutePath().normalize()
                        .startsWith(application.toAbsolutePath().normalize())
                || !overlay.toString().endsWith(".overlay"))
            return Result.unavailable();
        Path directory = null;
        try {
            if (cancelled.getAsBoolean()) return Result.unavailable();
            directory = Files.createTempDirectory("zew-studio-guide-");
            Files.createDirectories(directory.resolve("zew_evidence"));
            for (String name : RESOURCES) {
                InputStream resource = packaged.get(name);
                if (resource == null) return Result.unavailable();
                try (resource) { Files.copy(resource, directory.resolve(name)); }
            }
            var command = new ArrayList<String>(List.of(python.toString(), "-I", "-B",
                    directory.resolve("peripheral_guide_inspect.py").toString(),
                    "--build", build.toString(), "--zephyr", zephyr.toString(),
                    "--node", node, "--application", application.toString(),
                    "--overlay", overlay.toString()));
            if ("status".equals(intent)) command.addAll(List.of("--status", raw));
            else command.addAll(List.of("--set-" + intent, property + "=" + raw));
            Path stdout = directory.resolve("response.txt");
            var process = new ProcessBuilder(command)
                    .redirectOutput(stdout).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (process.isAlive()) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()
                        || System.nanoTime() > until) {
                    stop(process);
                    process.waitFor(3, TimeUnit.SECONDS);
                    return Result.unavailable();
                }
                process.waitFor(200, TimeUnit.MILLISECONDS);
            }
            if (Files.size(stdout) > 300000) return Result.unavailable();
            Result result = parse(Files.readString(stdout, StandardCharsets.UTF_8),
                    node, property);
            if (process.exitValue() != (result.hasDraft() ? 0 : 1))
                return Result.unavailable();
            if (!ZERO.equals(result.dtsHash())
                    && !fileHash(build.resolve("zephyr/zephyr.dts")).equals(result.dtsHash()))
                return Result.unavailable();
            if (Files.isRegularFile(overlay)) {
                if (!fileHash(overlay).equals(result.previousOverlayHash()))
                    return Result.unavailable();
            } else if (!ZERO.equals(result.previousOverlayHash()))
                return Result.unavailable();
            return result;
        } catch (Exception rejected) {
            if (rejected instanceof InterruptedException) Thread.currentThread().interrupt();
            return Result.unavailable();
        } finally {
            if (packaged != null) for (var stream : packaged.values()) {
                if (stream != null) try { stream.close(); } catch (IOException ignored) { }
            }
            if (directory != null) {
                for (String name : RESOURCES) {
                    try { Files.deleteIfExists(directory.resolve(name)); }
                    catch (IOException ignored) { }
                }
                try { Files.deleteIfExists(directory.resolve("response.txt")); }
                catch (IOException ignored) { }
                try { Files.deleteIfExists(directory.resolve("zew_evidence")); }
                catch (IOException ignored) { }
                try { Files.deleteIfExists(directory); } catch (IOException ignored) { }
            }
        }
    }
}
