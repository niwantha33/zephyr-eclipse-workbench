// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Executes the packaged, opt-in real Zephyr configuration probe off the UI
 * thread in isolated scratch. This is not edtlib-only and is not a security
 * sandbox: trusted application CMake scripts and Zephyr modules may execute.
 */
public final class DevicetreeRealConfigureInspector {
    private DevicetreeRealConfigureInspector() {}

    public record Result(String status, String reason, String selectedDtsSha256,
            String generatedDtsSha256, String node, String property,
            String overlayMode, String overlaySha256, int snapshotFiles,
            int sourceLineHint, String diagnosticConfidence) {
        public boolean configured() { return "configured".equals(status); }

        public static Result unavailable(String code) {
            return new Result("unavailable", code, "0".repeat(64), "0".repeat(64),
                    "unknown", "unknown", "normal", "0".repeat(64), 0, 0, "observed");
        }

        public String describe(String selectedNode, String selectedProperty, boolean outdated) {
            return "REAL ZEPHYR CONFIGURATION — ISOLATED SCRATCH\n"
                    + "Selected Devicetree node: " + selectedNode
                    + "\nSelected property: " + (selectedProperty == null ? "(node only)" : selectedProperty)
                    + "\nConfigure result: " + status + " (" + reason.replace('-', ' ') + ")"
                    + "\nSelected build DTS SHA-256: " + selectedDtsSha256
                    + "\nScratch generated DTS SHA-256: " + generatedDtsSha256
                    + "\nRequested node in scratch: " + node
                    + "\nRequested property in scratch: " + property
                    + "\nSaved overlay mode: " + overlayMode
                    + (overlayMode.equals("additional") ? "\nSaved overlay SHA-256: " + overlaySha256 : "")
                    + "\nApplication files copied: " + snapshotFiles
                    + (sourceLineHint > 0
                            ? "\nCandidate overlay line hint: " + sourceLineHint
                              + " (heuristic from bounded build log; not verified source mapping)"
                            : "")
                    + (outdated ? "\nOriginal selected build may be stale: source changed since build." : "")
                    + "\n\nThis requested action executed the trusted application's real Zephyr "
                    + "west/CMake configuration on a temporary source/build copy."
                    + "\nAn additional overlay is applied after normally discovered overlays, "
                    + "not substituted for their selection."
                    + "\nConfiguration success does not prove firmware compilation, flash, "
                    + "electrical pins, runtime device readiness or equivalence to the original build."
                    + "\nCustom CMake hooks can access outside scratch; this is not a security sandbox."
                    + "\nNo original source or selected build was intentionally changed, "
                    + "and no target was programmed.";
        }
    }

    public static Result parse(String output) throws IOException {
        if (output == null || output.length() > 8192)
            throw new IOException("Invalid real configure probe output length");
        var lines = output.strip().split("\\R", -1);
        if (lines.length != 2 || !"ZEW-REAL-DT-1".equals(lines[0]))
            throw new IOException("Unexpected real configuration probe header");
        String[] encoded = lines[1].split("\\t", -1);
        if (encoded.length != 11)
            throw new IOException("Unexpected real configuration field count");
        String[] f = new String[11];
        for (int i = 0; i < 11; i++) {
            try {
                byte[] decoded = Base64.getDecoder().decode(encoded[i]);
                if (decoded.length > 160)
                    throw new IOException("Real configure field exceeds safe length");
                for (byte b : decoded) if (b < 0x20 || b > 0x7e)
                    throw new IOException("Real configure protocol contains control/non-ASCII characters");
                f[i] = new String(decoded, StandardCharsets.US_ASCII);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid real configuration field encoding", invalid);
            }
        }
        if (!List.of("configured", "incomplete", "failed", "unavailable").contains(f[0])
                || !List.of("real-cmake-configured", "node-or-property-unverified",
                    "unknown-node-or-label", "missing-binding-property",
                    "devicetree-parse-error", "kconfig-error",
                    "tool-or-module-unavailable", "cmake-configure-failed",
                    "configure-timeout-or-launch-failed", "scratch-identity-unverified",
                    "input-changed", "invalid-or-missing-input").contains(f[1])
                || !f[2].matches("[a-f0-9]{64}") || !f[3].matches("[a-f0-9]{64}")
                || !List.of("observed", "not-observed", "unknown").contains(f[4])
                || !List.of("observed", "not-observed", "not-requested", "unknown").contains(f[5])
                || !List.of("additional", "normal").contains(f[6])
                || !f[7].matches("[a-f0-9]{64}")
                || !f[8].matches("\\d{1,5}") || !f[9].matches("\\d{1,6}")
                || !List.of("observed", "heuristic", "unknown").contains(f[10]))
            throw new IOException("Invalid real configuration enumeration");
        int files = Integer.parseInt(f[8]);
        int lineHint = Integer.parseInt(f[9]);
        if (files > 3000 || lineHint > 100000)
            throw new IOException("Out of bounds real configuration counts");
        if ("configured".equals(f[0]) && (!"real-cmake-configured".equals(f[1])
                || !"observed".equals(f[4])
                || !List.of("observed", "not-requested").contains(f[5])
                || "0".repeat(64).equals(f[3])))
            throw new IOException("Contradictory real configuration success report");
        if ("additional".equals(f[6]) && "configured".equals(f[0])
                && "0".repeat(64).equals(f[7]))
            throw new IOException("Real configuration lacks candidate hash");
        return new Result(f[0], f[1], f[2], f[3], f[4], f[5],
                f[6], f[7], files, lineHint, f[10]);
    }

    /**
     * Runs the trusted selected Python interpreter. The caller explicitly
     * confirmed CMake execution and provides the existing managed environment.
     */
    public static Result inspect(Path python, Path zephyr, Path application, Path build,
            Path ninja, String board, String node, String property, Path overlay,
            Map<String, String> environment, InputStream packagedScript,
            BooleanSupplier cancelled) {
        if (board == null || !board.matches("[A-Za-z0-9][A-Za-z0-9_.@/+~-]{0,159}")
                || board.contains("..") || node == null || node.length() > 240
                || !node.matches("/(?:[A-Za-z0-9,._+@-]+(?:/[A-Za-z0-9,._+@-]+)*)?")
                || property != null && !property.matches("[A-Za-z][A-Za-z0-9,._+?-]{0,99}"))
            return Result.unavailable("invalid-or-missing-input");
        if (python == null || zephyr == null || application == null || build == null
                || packagedScript == null || environment == null || cancelled == null
                || !Files.isRegularFile(python) || !Files.isDirectory(zephyr)
                || !Files.isDirectory(application)
                || !Files.isRegularFile(build.resolve("CMakeCache.txt"))
                || !Files.isRegularFile(build.resolve("zephyr/zephyr.dts")))
            return Result.unavailable("invalid-or-missing-input");
        if (overlay != null && (!overlay.toAbsolutePath().normalize()
                        .startsWith(application.toAbsolutePath().normalize())
                || !Files.isRegularFile(overlay)))
            return Result.unavailable("invalid-or-missing-input");
        if (ninja != null && !Files.isRegularFile(ninja))
            return Result.unavailable("invalid-or-missing-input");
        Path script = null;
        try (packagedScript) {
            if (cancelled.getAsBoolean()) return Result.unavailable("input-changed");
            script = Files.createTempFile("zew-real-dt-native-", ".py");
            Files.copy(packagedScript, script,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            List<String> command = new ArrayList<>(List.of(python.toString(),
                    script.toString(), "--build", build.toString(),
                    "--application", application.toString(), "--zephyr",
                    zephyr.toString(), "--board", board, "--node", node));
            if (property != null) command.addAll(List.of("--property", property));
            if (overlay != null) command.addAll(List.of("--overlay", overlay.toString()));
            if (ninja != null) command.addAll(List.of("--ninja", ninja.toString()));
            var builder = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().putAll(environment);
            Process process = builder.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(345);
            while (process.isAlive()) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()
                        || System.nanoTime() >= deadline) {
                    // First descendants, then parent; west can spawn CMake/Ninja.
                    process.descendants().forEach(child -> child.destroyForcibly());
                    process.destroyForcibly();
                    process.waitFor(3, TimeUnit.SECONDS);
                    return Result.unavailable("configure-timeout-or-launch-failed");
                }
                process.waitFor(250, TimeUnit.MILLISECONDS);
            }
            byte[] bytes = process.getInputStream().readNBytes(8193);
            if (bytes.length > 8192)
                return Result.unavailable("invalid-or-missing-input");
            try {
                Result result = parse(new String(bytes, StandardCharsets.US_ASCII));
                if (process.exitValue() != (result.configured() ? 0 : 1))
                    return Result.unavailable("invalid-or-missing-input");
                return result;
            } catch (IOException rejected) {
                return Result.unavailable("invalid-or-missing-input");
            }
        } catch (IOException error) {
            return Result.unavailable("invalid-or-missing-input");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.unavailable("configure-timeout-or-launch-failed");
        } finally {
            if (script != null) try { Files.deleteIfExists(script); }
            catch (IOException ignored) { /* only disposable packaged script */ }
        }
    }
}
