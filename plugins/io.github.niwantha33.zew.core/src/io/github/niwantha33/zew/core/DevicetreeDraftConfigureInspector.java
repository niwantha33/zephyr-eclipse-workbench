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

/**
 * Deliberately requested trusted West/CMake check on a COPY of an unsaved,
 * selected binding-guided draft. Never writes the original application.
 */
public final class DevicetreeDraftConfigureInspector {
    private DevicetreeDraftConfigureInspector() {}
    private static final String ZERO = "0".repeat(64);
    private static final List<String> RESOURCES = List.of(
            "dt_draft_configure_inspect.py", "zew_evidence/__init__.py",
            "zew_evidence/collector.py", "zew_evidence/dt_binding_preview.py",
            "zew_evidence/peripheral_guide.py", "zew_evidence/dt_build_validation.py",
            "zew_evidence/dt_draft_validation.py");
    private static final Set<String> REASONS = Set.of(
            "real-zephyr-cmake-accepted", "requested-node-or-property-not-observed",
            "scratch-build-identity-or-overlay-unverified",
            "source-or-build-changed-during-check",
            "configure-timeout-or-launch-failed",
            "undefined-devicetree-node-or-label", "required-binding-property",
            "devicetree-syntax-or-preprocessing", "kconfig-configuration",
            "missing-tool-or-module", "cmake-configure-failed",
            "binding-evidence-incomplete", "reviewed-draft-changed",
            "invalid-or-missing-input");

    public record Result(String status, String reason, String dtsSha, String scratchSha,
            String originalOverlaySha, String proposedOverlaySha, String preparedOverlaySha,
            String nodeSha, String propertySha, String valueState,
            String staging, String identity, int snapshotFiles) {
        public boolean configured() { return "configured".equals(status); }
        public static Result unavailable() {
            return new Result("unavailable", "invalid-or-missing-input", ZERO, ZERO,
                    ZERO, ZERO, ZERO, ZERO, ZERO, "not-evaluated",
                    "not-staged", "identity-unknown", 0);
        }
        public String describe(String node, String property, String request, boolean stale) {
            return "UNSAVED GUIDED DRAFT — REAL ZEPHYR CMAKE PREFLIGHT\n"
                    + "Selected node: " + node + "\nSelected property: " + property
                    + "\nRequested value: " + request + "\nScratch result: " + status
                    + "\nDiagnostic class: " + reason
                    + "\nExact requested generated value: " + valueState
                    + "\nStage: " + staging + "\nBuild identity: " + identity
                    + "\nGenerated DTS SHA-256 (original): " + dtsSha
                    + "\nGenerated DTS SHA-256 (scratch): " + scratchSha
                    + "\nSaved overlay SHA-256 (original): " + originalOverlaySha
                    + "\nProposed overlay SHA-256: " + proposedOverlaySha
                    + "\nPrepared scratch overlay SHA-256: " + preparedOverlaySha
                    + "\nCopied application file count: " + snapshotFiles
                    + (stale ? "\nWARNING: Original generated build may be stale." : "")
                    + "\n\nThe addition was staged ONLY in a disposable application copy. "
                    + "No original overlay or selected build was written or flashed."
                    + "\nTrusted project CMake and module hooks were executed with normal "
                    + "host privileges; this is NOT a security sandbox."
                    + "\nA configured result means only that exact requested generated "
                    + "Devicetree value was observed after scratch configuration."
                    + "\nIt does NOT establish compiled firmware, GPIO/pad availability, "
                    + "electrical suitability, driver inclusion, or runtime operation."
                    + "\nReview and deliberately save the original source separately "
                    + "before building or flashing.";
        }
    }

    public static String digest(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }
    private static String hashFile(Path source) throws IOException {
        if (!Files.isRegularFile(source) || Files.size(source) > 4 * 1024 * 1024)
            throw new IOException("Generated evidence is missing/oversized");
        return digest(Files.readAllBytes(source));
    }
    public static Result parse(String output, String node, String property,
            String expectedDtsSha, String expectedOverlaySha,
            String expectedAdditionSha, String expectedProposedSha) throws IOException {
        if (output == null || output.length() > 8192)
            throw new IOException("Invalid draft CMake response size");
        var lines = output.strip().split("\\R", -1);
        if (lines.length != 2 || !"ZEW-DRAFT-CMAKE-1".equals(lines[0]))
            throw new IOException("Unexpected draft CMake protocol");
        String[] encoded = lines[1].split("\\t", -1);
        if (encoded.length != 13) throw new IOException("Invalid draft CMake field count");
        String[] f = new String[13];
        for (int i = 0; i < f.length; i++) {
            try {
                byte[] decoded = Base64.getDecoder().decode(encoded[i]);
                if (decoded.length > 128) throw new IOException("Oversized draft CMake field");
                for (byte b : decoded) if (b < 0x20 || b > 0x7e)
                    throw new IOException("Invalid draft CMake field bytes");
                f[i] = new String(decoded, StandardCharsets.US_ASCII);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid encoded draft CMake evidence", invalid);
            }
        }
        if (!Set.of("configured", "incomplete", "failed", "unavailable").contains(f[0])
                || !REASONS.contains(f[1])
                || !f[2].matches("[a-f0-9]{64}") || !f[3].matches("[a-f0-9]{64}")
                || !f[4].matches("[a-f0-9]{64}") || !f[5].matches("[a-f0-9]{64}")
                || !f[6].matches("[a-f0-9]{64}") || !f[7].matches("[a-f0-9]{64}")
                || !f[8].matches("[a-f0-9]{64}")
                || !Set.of("exact-value-observed", "unavailable-or-mismatched",
                    "not-evaluated").contains(f[9])
                || !Set.of("staged", "not-staged").contains(f[10])
                || !Set.of("identity-verified", "identity-unknown").contains(f[11])
                || !f[12].matches("[0-9]{1,5}"))
            throw new IOException("Unsupported or contradictory draft CMake evidence");
        int count = Integer.parseInt(f[12]);
        if (count > 3000) throw new IOException("Invalid application copy count");
        if ("configured".equals(f[0])) {
            if (!"real-zephyr-cmake-accepted".equals(f[1])
                    || !"exact-value-observed".equals(f[9])
                    || !"staged".equals(f[10]) || !"identity-verified".equals(f[11])
                    || f[3].equals(ZERO) || f[6].equals(ZERO)
                    || !f[6].equals(f[5]) || count == 0)
                throw new IOException("Draft value not proven in configured output");
        } else if ("exact-value-observed".equals(f[9])
                || "identity-verified".equals(f[11]))
            throw new IOException("Non-configured draft claimed successful evidence");
        if (!"unavailable".equals(f[0]) || !ZERO.equals(f[7])) {
            if (!f[7].equals(digest(node.getBytes(StandardCharsets.UTF_8)))
                    || !f[8].equals(digest(property.getBytes(StandardCharsets.UTF_8)))
                    || !f[2].equals(expectedDtsSha)
                    || !f[4].equals(expectedOverlaySha)
                    || !f[5].equals(expectedProposedSha))
                throw new IOException("Draft CMake returned evidence for a different source review");
        }
        // Hash of addition is given to the adapter and re-checked before
        // any CMake launch; ensure it is a valid exact SHA-256 identifier.
        if (!expectedAdditionSha.matches("[a-f0-9]{64}"))
            throw new IOException("Missing selected addition identity");
        return new Result(f[0], f[1], f[2], f[3], f[4], f[5], f[6],
                f[7], f[8], f[9], f[10], f[11], count);
    }

    private static void kill(Process p) {
        p.descendants().forEach(descendant -> descendant.destroyForcibly());
        p.destroyForcibly();
    }

    public static Result inspect(Path python, Path zephyr, Path application,
            Path build, Path overlay, Path ninja, String board, String node,
            String property, String intent, String raw,
            String dtsSha, String originalOverlaySha, String additionSha,
            String proposedSha, Map<String, String> environment,
            Map<String, InputStream> resources, BooleanSupplier cancelled) {
        if (python == null || zephyr == null || application == null || build == null
                || overlay == null || board == null || node == null || property == null
                || intent == null || raw == null || dtsSha == null || additionSha == null
                || originalOverlaySha == null || proposedSha == null
                || resources == null || environment == null || cancelled == null
                || !board.matches("[A-Za-z0-9][A-Za-z0-9_.@/+~-]{0,159}")
                || board.contains("..")
                || !node.matches("/(?:[A-Za-z0-9,._+@-]+(?:/[A-Za-z0-9,._+@-]+)*)?")
                || node.length() > 240
                || !property.matches("[A-Za-z][A-Za-z0-9,._+?-]{0,99}")
                || !List.of("status", "int", "enum").contains(intent)
                || !dtsSha.matches("[a-f0-9]{64}") || !additionSha.matches("[a-f0-9]{64}")
                || !originalOverlaySha.matches("[a-f0-9]{64}")
                || !proposedSha.matches("[a-f0-9]{64}")
                || !Files.isRegularFile(python) || !Files.isDirectory(zephyr)
                || !Files.isDirectory(application)
                || !Files.isRegularFile(build.resolve("CMakeCache.txt"))
                || !Files.isRegularFile(build.resolve("zephyr/zephyr.dts"))
                || !overlay.toAbsolutePath().normalize()
                    .startsWith(application.toAbsolutePath().normalize())
                || !overlay.toString().endsWith(".overlay")
                || ninja != null && !Files.isRegularFile(ninja))
            return Result.unavailable();
        Path dir = null;
        try {
            if (cancelled.getAsBoolean()) return Result.unavailable();
            if (!hashFile(build.resolve("zephyr/zephyr.dts")).equals(dtsSha))
                return Result.unavailable();
            String actualOverlaySha = Files.isRegularFile(overlay) ? hashFile(overlay) : ZERO;
            if (!actualOverlaySha.equals(originalOverlaySha)) return Result.unavailable();
            dir = Files.createTempDirectory("zew-unsaved-cmake-");
            Files.createDirectory(dir.resolve("zew_evidence"));
            for (String name : RESOURCES) {
                InputStream stream = resources.get(name);
                if (stream == null) return Result.unavailable();
                try (stream) { Files.copy(stream, dir.resolve(name)); }
            }
            var command = new ArrayList<String>(List.of(python.toString(), "-I", "-B",
                    dir.resolve("dt_draft_configure_inspect.py").toString(),
                    "--build", build.toString(), "--application", application.toString(),
                    "--zephyr", zephyr.toString(), "--board", board, "--node", node,
                    "--overlay", overlay.toString(), "--expected-dts-sha256", dtsSha,
                    "--expected-overlay-sha256", originalOverlaySha,
                    "--expected-addition-sha256", additionSha));
            if ("status".equals(intent)) command.addAll(List.of("--status", raw));
            else command.addAll(List.of("--set-" + intent, property + "=" + raw));
            if (ninja != null) command.addAll(List.of("--ninja", ninja.toString()));
            Path response = dir.resolve("response.txt");
            var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(response.toFile());
            builder.environment().putAll(environment);
            var process = builder.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(345);
            while (process.isAlive()) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()
                        || System.nanoTime() >= deadline) {
                    kill(process);
                    process.waitFor(3, TimeUnit.SECONDS);
                    return Result.unavailable();
                }
                process.waitFor(250, TimeUnit.MILLISECONDS);
            }
            if (Files.size(response) > 8192) return Result.unavailable();
            Result result = parse(Files.readString(response, StandardCharsets.US_ASCII),
                    node, property, dtsSha, originalOverlaySha, additionSha, proposedSha);
            if (process.exitValue() != (result.configured() ? 0 : 1))
                return Result.unavailable();
            if (!hashFile(build.resolve("zephyr/zephyr.dts")).equals(dtsSha))
                return Result.unavailable();
            if (!(Files.isRegularFile(overlay) ? hashFile(overlay) : ZERO)
                    .equals(originalOverlaySha))
                return Result.unavailable();
            return result;
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException)
                Thread.currentThread().interrupt();
            return Result.unavailable();
        } finally {
            if (resources != null) for (InputStream stream : resources.values()) {
                if (stream != null) try { stream.close(); } catch (IOException ignored) { }
            }
            if (dir != null) {
                for (String name : RESOURCES) {
                    try { Files.deleteIfExists(dir.resolve(name)); }
                    catch (IOException ignored) { }
                }
                try { Files.deleteIfExists(dir.resolve("response.txt")); }
                catch (IOException ignored) { }
                try { Files.deleteIfExists(dir.resolve("zew_evidence")); }
                catch (IOException ignored) { }
                try { Files.deleteIfExists(dir); } catch (IOException ignored) { }
            }
        }
    }
}
