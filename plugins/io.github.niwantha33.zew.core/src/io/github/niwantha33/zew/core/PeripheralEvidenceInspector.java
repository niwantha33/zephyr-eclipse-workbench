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
 * Thin installed-Eclipse interface to the exact headless zew peripheral core.
 * No parsing of DTS/Kconfig in Java and no inferred pin or driver causality.
 */
public final class PeripheralEvidenceInspector {
    private PeripheralEvidenceInspector() {}
    private static final String ZERO = "0".repeat(64);
    private static final String HEADER = "ZEW-PERIPHERAL-1";
    private static final Set<String> ACTIONS = Set.of(
            "verify-exact-zephyr-checkout-and-generated-dts",
            "locate-node-in-generated-devicetree",
            "supply-actual-module-binding-roots",
            "review-source-overlay-and-reconfigure",
            "check-selected-property-against-upstream-binding",
            "inspect-generated-kconfig-with-kconfiglib",
            "generate-selected-build-config");

    public record Result(String state, String dtsSha, String configSha, String nodeSha,
            String symbolSha, String nodeState, String binding, String configuredStatus,
            String property, String propertyType, String kconfig, boolean propertyRequested,
            boolean symbolRequested, List<String> actions) {
        public boolean observationsPresent() { return "selected-observations-present".equals(state); }
        public static Result unavailable() {
            return new Result("unavailable", ZERO, ZERO, ZERO, ZERO,
                    "unknown", "unknown", "unknown", "unknown", "unknown",
                    "unknown", false, false, List.of());
        }

        public String describe(String nodePath, String propertyName, String configSymbol, boolean stale) {
            var text = new StringBuilder("SELECTED PERIPHERAL — INDEPENDENT BUILD EVIDENCE\n")
                    .append("Selected node: ").append(nodePath)
                    .append("\nSelected property: ").append(propertyName == null ? "(none)" : propertyName)
                    .append("\nIndependently selected Kconfig symbol: ")
                    .append(configSymbol == null ? "(none)" : configSymbol)
                    .append("\nEvidence: ").append(state)
                    .append("\n\nGENERATED DEVICETREE (CONFIGURED, NOT LIVE)\n")
                    .append("Node observation: ").append(nodeState)
                    .append("\nMatched binding: ").append(binding)
                    .append("\nGenerated node status: ").append(configuredStatus)
                    .append("\nBinding property: ").append(property)
                    .append("\nBinding property type: ").append(propertyType)
                    .append("\nGenerated DTS SHA-256: ").append(dtsSha)
                    .append("\n\nINDEPENDENT GENERATED KCONFIG\n")
                    .append("Selected CONFIG_ value: ").append(kconfig)
                    .append("\nCausal link to this Devicetree node: UNVERIFIED")
                    .append("\nGenerated .config SHA-256: ").append(configSha);
            if (stale) text.append("\n\nWARNING: Project source may have changed after the selected build.");
            if (!actions.isEmpty()) {
                text.append("\n\nSuggested checks (not automatic fixes):");
                for (String action : actions) text.append("\n• ").append(action.replace('-', ' '));
            }
            return text.append("\n\nCONFIDENCE BOUNDARIES\n")
                    .append("Observed values describe one existing generated build. ")
                    .append("A matching binding or status okay does NOT prove driver compilation, ")
                    .append("a free GPIO/pad, package-pin validity, physical wiring, flashing, or runtime operation.")
                    .append("\nNo source, build, or target was modified. ")
                    .append("Missing module bindings, properties or symbols remain UNKNOWN.")
                    .toString();
        }
    }

    public static boolean allowedSymbol(String symbol) {
        if (symbol == null) return true;
        if (!symbol.matches("CONFIG_[A-Z0-9_]{1,160}")) return false;
        return !symbol.matches("(?i).*"
                + "(PASSWORD|TOKEN|SECRET|AUTH|PRIVATE|CREDENTIAL|SSID|PSK|PASSCODE|"
                + "(?:^|_)(?:PIN|KEY|CERT)(?:_|$)).*");
    }

    public static Result parse(String output, String node, String property, String symbol)
            throws IOException {
        if (output == null || output.length() > 8192)
            throw new IOException("Invalid peripheral probe output length");
        String[] lines = output.strip().split("\\R", -1);
        if (lines.length != 2 || !HEADER.equals(lines[0]))
            throw new IOException("Unknown peripheral protocol version");
        String[] parts = lines[1].split("\\t", -1);
        if (parts.length != 14)
            throw new IOException("Invalid peripheral protocol field count");
        var fields = new String[14];
        for (int i = 0; i < fields.length; i++) {
            try {
                byte[] decoded = Base64.getDecoder().decode(parts[i]);
                if (decoded.length > 320) throw new IOException("Oversized protocol field");
                for (byte b : decoded) if (b < 0x20 || b > 0x7e)
                    throw new IOException("Non-ASCII protocol field");
                fields[i] = new String(decoded, StandardCharsets.US_ASCII);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid encoded peripheral field", invalid);
            }
        }
        if (!Set.of("selected-observations-present", "incomplete-or-unavailable",
                    "unavailable").contains(fields[0])
                || !isHash(fields[1]) || !isHash(fields[2])
                || !isHash(fields[3]) || !isHash(fields[4])
                || !Set.of("node-found", "node-not-present", "unknown").contains(fields[5])
                || !Set.of("matched", "unmatched", "unknown").contains(fields[6])
                || !Set.of("okay", "disabled", "reserved", "fail", "fail-sss",
                        "other", "unknown").contains(fields[7])
                || !Set.of("interpreted-by-binding", "not-interpreted-by-binding",
                        "unknown-without-binding", "not-requested", "unknown").contains(fields[8])
                || !Set.of("boolean", "int", "array", "uint8-array", "string", "string-array",
                        "phandle", "phandles", "phandle-array", "path", "other", "unknown")
                        .contains(fields[9])
                || !Set.of("enabled", "disabled", "module", "not-requested",
                        "not-observed-or-redacted", "no-generated-config", "unknown")
                        .contains(fields[10])
                || !Set.of("yes", "no").contains(fields[11])
                || !Set.of("yes", "no").contains(fields[12]))
            throw new IOException("Invalid peripheral evidence enum/hash");

        if (("yes".equals(fields[11])) != (property != null)
                || ("yes".equals(fields[12])) != (symbol != null))
            throw new IOException("Peripheral selection mismatch");
        if (!"unavailable".equals(fields[0])) {
            if (!hash(node).equals(fields[3])
                    || !hashOrZero(symbol).equals(fields[4]))
                throw new IOException("Evidence belongs to another selected node/symbol");
        }
        var actions = new ArrayList<String>();
        if (!fields[13].equals("none")) {
            String[] tokens = fields[13].split(",", -1);
            for (String action : tokens) {
                if (!ACTIONS.contains(action) || actions.contains(action))
                    throw new IOException("Unsupported peripheral diagnostic");
                actions.add(action);
            }
        }
        boolean good = fields[0].equals("selected-observations-present");
        if (good && (fields[1].equals(ZERO) || fields[3].equals(ZERO)
                || !"node-found".equals(fields[5]) || !"matched".equals(fields[6])
                || !Set.of("okay", "disabled", "reserved", "fail", "fail-sss").contains(fields[7])
                || property != null && !"interpreted-by-binding".equals(fields[8])
                || symbol != null && (!Set.of("enabled", "disabled", "module").contains(fields[10])
                                      || fields[2].equals(ZERO))))
            throw new IOException("Contradictory successful peripheral report");
        if (property == null && good && !"not-requested".equals(fields[8]))
            throw new IOException("Unrequested property returned as evidence");
        if (symbol == null && good && !"not-requested".equals(fields[10]))
            throw new IOException("Unrequested symbol returned as evidence");
        return new Result(fields[0], fields[1], fields[2], fields[3], fields[4],
                fields[5], fields[6], fields[7], fields[8], fields[9], fields[10],
                fields[11].equals("yes"), fields[12].equals("yes"), List.copyOf(actions));
    }

    private static boolean isHash(String hex) {
        return hex.matches("[a-f0-9]{64}");
    }

    private static String hashOrZero(String value) throws IOException {
        return value == null ? ZERO : hash(value);
    }
    private static String hash(String value) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException cannotHappen) {
            throw new IOException("SHA-256 unavailable", cannotHappen);
        }
    }

    private static void killTree(Process process) {
        process.descendants().forEach(p -> p.destroyForcibly());
        process.destroyForcibly();
    }

    /**
     * Caller provides only packaged reviewed sources. Their equality with the
     * headless implementation is enforced in native test_core Python checks.
     */
    public static Result inspect(Path python, Path zephyr, Path build,
            String node, String property, String symbol, Map<String, InputStream> packaged,
            BooleanSupplier cancelled) {
        if (python == null || zephyr == null || build == null || node == null
                || !node.matches("/(?:[A-Za-z0-9,._+@-]+(?:/[A-Za-z0-9,._+@-]+)*)?")
                || node.length() > 240 || property != null
                && !property.matches("[A-Za-z][A-Za-z0-9,._+?-]{0,99}")
                || !allowedSymbol(symbol) || packaged == null || cancelled == null
                || !Files.isRegularFile(python) || !Files.isDirectory(zephyr)
                || !Files.isRegularFile(build.resolve("zephyr/zephyr.dts"))
                || !Files.isRegularFile(build.resolve("CMakeCache.txt")))
            return Result.unavailable();
        Path directory = null;
        try {
            if (cancelled.getAsBoolean()) return Result.unavailable();
            directory = Files.createTempDirectory("zew-native-peripheral-");
            Path pkg = directory.resolve("zew_evidence");
            Files.createDirectory(pkg);
            for (String name : List.of("peripheral_inspect.py", "zew_evidence/__init__.py",
                    "zew_evidence/collector.py", "zew_evidence/dt_binding_preview.py",
                    "zew_evidence/peripheral_evidence.py")) {
                InputStream stream = packaged.get(name);
                if (stream == null) return Result.unavailable();
                try (stream) {
                    Files.copy(stream, directory.resolve(name));
                }
            }
            List<String> command = new ArrayList<>(List.of(python.toString(), "-B",
                    directory.resolve("peripheral_inspect.py").toString(),
                    "--build", build.toString(), "--zephyr", zephyr.toString(),
                    "--node", node));
            if (property != null) command.addAll(List.of("--property", property));
            if (symbol != null) command.addAll(List.of("--symbol", symbol));
            Process process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (process.isAlive()) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()
                        || System.nanoTime() > deadline) {
                    killTree(process);
                    process.waitFor(3, TimeUnit.SECONDS);
                    return Result.unavailable();
                }
                process.waitFor(200, TimeUnit.MILLISECONDS);
            }
            byte[] bytes = process.getInputStream().readNBytes(8193);
            if (bytes.length > 8192) return Result.unavailable();
            Result result = parse(new String(bytes, StandardCharsets.US_ASCII),
                    node, property, symbol);
            if (process.exitValue() != (result.observationsPresent() ? 0 : 1))
                return Result.unavailable();
            if (!result.dtsSha().equals(ZERO)
                    && !result.dtsSha().equals(fileHash(build.resolve("zephyr/zephyr.dts"))))
                return Result.unavailable();
            if (!result.configSha().equals(ZERO)
                    && !result.configSha().equals(fileHash(build.resolve("zephyr/.config"))))
                return Result.unavailable();
            return result;
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException)
                Thread.currentThread().interrupt();
            return Result.unavailable();
        } finally {
            if (packaged != null) for (InputStream resource : packaged.values()) {
                if (resource != null) try { resource.close(); }
                catch (IOException ignored) { /* packaged read-only stream */ }
            }
            if (directory != null) {
                // Only remove the private known files in the new temporary
                // folder. No user-provided path can enter this deletion list.
                for (String name : List.of("peripheral_inspect.py", "zew_evidence/__init__.py",
                        "zew_evidence/collector.py", "zew_evidence/dt_binding_preview.py",
                        "zew_evidence/peripheral_evidence.py")) {
                    try { Files.deleteIfExists(directory.resolve(name)); }
                    catch (IOException ignored) { /* temporary resource only */ }
                }
                try { Files.deleteIfExists(directory.resolve("zew_evidence")); }
                catch (IOException ignored) { /* managed Python may create __pycache__; retained temp only */ }
                try { Files.deleteIfExists(directory); }
                catch (IOException ignored) { /* temp-only cleanup failure */ }
            }
        }
    }

    private static String fileHash(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) > 8 * 1024 * 1024)
            throw new IOException("Artifact missing/oversized");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException cannotHappen) {
            throw new IOException("SHA-256 unavailable", cannotHappen);
        }
    }
}
