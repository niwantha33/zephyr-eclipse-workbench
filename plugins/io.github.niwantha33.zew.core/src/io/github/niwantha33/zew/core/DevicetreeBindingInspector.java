// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Bounded read-only bridge to the selected build's Zephyr edtlib.
 *
 * The packaged script, not Java, parses upstream DTS/binding semantics.
 * Neither a matching binding nor a temporary preview proves runtime hardware.
 */
public final class DevicetreeBindingInspector {
    private DevicetreeBindingInspector() {}

    public record Result(String state, String snapshotSha256, String nodeState,
            String bindingState, String propertyState, String propertyType,
            String nodeStatus, String overlayState, String reason) {

        public boolean interpreted() { return "interpreted".equals(state); }

        public static Result unknown(String reason) {
            return new Result("unavailable", "0".repeat(64), "unknown", "unknown",
                    "unknown", "none", "unknown", "unavailable", reason);
        }

        public String describe(String selectedNode, String property, boolean preview, boolean buildOutdated) {
            return "DEVICETREE BINDING EVIDENCE — " + (preview ? "SAVED OVERLAY PREVIEW" : "BUILT SNAPSHOT")
                    + "\nSelected node: " + selectedNode
                    + "\nRequested property: " + (property == null ? "(node binding only)" : property)
                    + "\nEvidence: " + state + " (" + reason.replace('-', ' ') + ")"
                    + "\nBuilt zephyr.dts SHA-256: " + snapshotSha256
                    + "\nNode: " + nodeState + "\nBinding: " + bindingState
                    + "\nProperty: " + propertyState + " | Type: " + propertyType
                    + "\nGenerated status: " + nodeStatus
                    + "\nSaved overlay: " + overlayState
                    + (buildOutdated ? "\nBUILD REQUIRED: Source may have changed since the generated snapshot." : "")
                    + "\n\nThis is upstream edtlib's interpretation of the selected generated "
                    + "Devicetree and explicitly available bindings."
                    + (preview ? "\nA temporary preview does not prove the saved overlay "
                            + "is included by CMake or that the proposed configuration builds." : "")
                    + "\nMissing bindings or properties mean unknown/incomplete evidence, not unavailable hardware."
                    + "\nNo physical pins, package pads, electrical limits, driver initialisation, flash or live state were verified."
                    + "\nNo project, generated DTS or firmware files were changed.";
        }
    }

    public static Result parse(String output) throws IOException {
        if (output == null || output.length() > 8192)
            throw new IOException("Unexpected Devicetree probe size");
        String[] lines = output.strip().split("\\R", 3);
        if (lines.length != 2 || !"ZEW-DTBIND-1".equals(lines[0]))
            throw new IOException("Unexpected Devicetree probe protocol");
        String[] parts = lines[1].split("\\t", -1);
        if (parts.length != 9) throw new IOException("Unexpected Devicetree field count");
        String[] fields = new String[9];
        for (int i = 0; i < fields.length; i++) {
            try {
                fields[i] = new String(Base64.getDecoder().decode(parts[i]), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid Devicetree field", invalid);
            }
        }
        if (!List.of("interpreted", "incomplete", "rejected", "unavailable").contains(fields[0])
                || !fields[1].matches("[a-f0-9]{64}")
                || !List.of("present", "missing", "unknown").contains(fields[2])
                || !List.of("matched", "unmatched", "unknown").contains(fields[3])
                || !List.of("interpreted", "not-interpreted", "not-requested", "unknown").contains(fields[4])
                || !List.of("boolean", "int", "array", "uint8-array", "string", "string-array",
                           "phandle", "phandles", "phandle-array", "path", "other", "none").contains(fields[5])
                || !List.of("okay", "disabled", "reserved", "fail", "fail-sss", "other", "unknown")
                    .contains(fields[6])
                || !List.of("previewed", "none", "rejected", "unavailable").contains(fields[7])
                || !List.of("matching-binding", "node-or-binding-incomplete", "edtlib-rejected-overlay",
                           "edtlib-unavailable", "build-artifacts-missing", "checkout-mismatch",
                           "selected-edtlib-missing", "binding-root-missing", "preprocessing-required",
                           "invalid-input").contains(fields[8]))
            throw new IOException("Invalid Devicetree probe enumeration");
        if ("interpreted".equals(fields[0])
                && (!"present".equals(fields[2]) || !"matched".equals(fields[3])
                    || (!"interpreted".equals(fields[4]) && !"not-requested".equals(fields[4]))))
            throw new IOException("Contradictory Devicetree observation");
        return new Result(fields[0], fields[1], fields[2], fields[3],
                fields[4], fields[5], fields[6], fields[7], fields[8]);
    }

    /** Runs only the packaged script with bounded stdout, timeout and explicit paths. */
    public static Result inspect(Path python, Path zephyr, Path application, Path build,
            String node, String property, Path overlay, InputStream packagedScript) {
        if (node == null || node.length() > 240
                || !node.matches("/(?:[A-Za-z0-9,._+@-]+(?:/[A-Za-z0-9,._+@-]+)*)?")
                || property != null && !property.matches("[A-Za-z][A-Za-z0-9,._+?-]{0,99}"))
            return Result.unknown("invalid-input");
        if (python == null || zephyr == null || application == null || build == null
                || packagedScript == null || !Files.isRegularFile(python)
                || !Files.isDirectory(zephyr) || !Files.isDirectory(application)
                || !Files.isRegularFile(build.resolve("zephyr/zephyr.dts")))
            return Result.unknown("build-artifacts-missing");
        if (overlay != null && (!overlay.toAbsolutePath().normalize().startsWith(
                application.toAbsolutePath().normalize()) || !Files.isRegularFile(overlay)))
            return Result.unknown("invalid-input");
        Path temporaryScript = null;
        try (packagedScript) {
            temporaryScript = Files.createTempFile("zew-edt-inspect-", ".py");
            Files.copy(packagedScript, temporaryScript,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            var command = new java.util.ArrayList<String>();
            command.add(python.toString());
            command.add(temporaryScript.toString());
            command.addAll(List.of("--build", build.toString(), "--zephyr", zephyr.toString(),
                    "--application", application.toString(), "--node", node));
            if (property != null) command.addAll(List.of("--property", property));
            if (overlay != null) command.addAll(List.of("--overlay", overlay.toString()));
            var process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(3, TimeUnit.SECONDS);
                    return Result.unknown("edtlib-unavailable");
                }
            } catch (InterruptedException cancelled) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                return Result.unknown("edtlib-unavailable");
            }
            byte[] output = process.getInputStream().readNBytes(8193);
            if (output.length > 8192)
                return Result.unknown("edtlib-unavailable");
            try {
                Result parsed = parse(new String(output, StandardCharsets.UTF_8));
                if (process.exitValue() != (parsed.interpreted() ? 0 : 1))
                    return Result.unknown("edtlib-unavailable");
                return parsed;
            } catch (IOException badProtocol) {
                return Result.unknown("edtlib-unavailable");
            }
        } catch (IOException error) {
            return Result.unknown("edtlib-unavailable");
        } finally {
            if (temporaryScript != null) try { Files.deleteIfExists(temporaryScript); }
            catch (IOException ignored) { /* OS scratch only */ }
        }
    }
}
