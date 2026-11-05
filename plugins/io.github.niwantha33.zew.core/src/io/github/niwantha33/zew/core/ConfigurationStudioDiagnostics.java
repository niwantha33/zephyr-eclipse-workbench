// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Hash-linked source request observations, not Kconfig semantic/root-cause evaluation. */
public final class ConfigurationStudioDiagnostics {
    private static final long MAX_BYTES = 2_000_000;
    private ConfigurationStudioDiagnostics() {}
    public record Finding(String ruleId, String severity, String confidence, String symbol,
            String message, Path source, int line, String sourceSha, String nextAction) {}
    public record Report(Path application, Path build, List<Finding> findings) {
        public Report { findings = List.copyOf(findings); }
        public String display() {
            var text = new StringBuilder("CONFIGURATION SOURCE DIAGNOSTICS\n")
                    .append("Selected build: ").append(build == null ? "unavailable" : build)
                    .append("\nObserved request differences do not prove a Kconfig cause.")
                    .append("\nNo source or generated build was changed.\n");
            if (findings.isEmpty()) text.append("\nNo request/snapshot difference found. ")
                    .append("Dependency semantics and target operation remain unverified.\n");
            for (var finding : findings) text.append("\n[").append(finding.ruleId())
                    .append("] ").append(finding.message()).append("\nConfidence: ")
                    .append(finding.confidence()).append("\nSource: ")
                    .append(finding.source() == null ? "not available" : finding.source())
                    .append(finding.line() > 0 ? ":" + finding.line() : "")
                    .append("\nSHA-256: ").append(finding.sourceSha())
                    .append("\nNext: ").append(finding.nextAction()).append('\n');
            return text.toString();
        }
    }

    public static Report inspect(Path application, Path build) throws IOException {
        if (application == null || !Files.isDirectory(application))
            throw new IOException("Select an accessible application.");
        Path root = application.toRealPath();
        Path source = root.resolve("prj.conf");
        if (Files.isSymbolicLink(source)) throw new IOException("Symlinked source is not inspected.");
        String sourceSha = regular(source) ? digest(source) : "";
        Path generated = build == null ? null : build.resolve("zephyr/.config");
        var findings = new ArrayList<Finding>();
        if (!regular(generated)) {
            findings.add(new Finding("CS-KCONFIG-GENERATED-MISSING", "info", "unknown", "",
                    "The selected build has no generated Kconfig snapshot.",
                    null, 0, "", "Configure this exact board/profile; inspect the failed configure log if needed."));
            return new Report(root, build, findings);
        }
        String configSha = digest(generated);
        var snapshot = ConfigurationStudioFiles.inspect(root, build, "selected build");
        for (var option : snapshot.options()) {
            if (!option.overridden() || Objects.equals(option.requested(), option.effective())) continue;
            findings.add(new Finding("CS-KCONFIG-REQUEST-MISMATCH", "warning", "observed",
                    "CONFIG_" + option.symbol(), "CONFIG_" + option.symbol()
                    + (option.unresolved() ? " is requested but absent from the generated snapshot."
                            : " has different requested and generated values."),
                    source, option.requestedLine(), sourceSha,
                    "Reconfigure first, then inspect the selected Kconfiglib dependencies, visibility and active fragments."));
            if (findings.size() >= 256) break;
        }
        if (!sourceSha.isEmpty() && !sourceSha.equals(digest(source))
                || !configSha.equals(digest(generated)))
            throw new IOException("Source or generated configuration changed during diagnosis. Refresh and retry.");
        return new Report(root, build, findings);
    }

    /** A source jump/marker may use the recorded line only while the bytes still match. */
    public static boolean sourceMatches(Finding finding) {
        if (finding == null || finding.source() == null || finding.line() < 1
                || finding.sourceSha() == null || !finding.sourceSha().matches("[a-f0-9]{64}")) return false;
        try { return regular(finding.source()) && digest(finding.source()).equals(finding.sourceSha()); }
        catch (IOException unavailable) { return false; }
    }

    private static boolean regular(Path file) {
        return file != null && !Files.isSymbolicLink(file)
                && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
    }
    private static String digest(Path file) throws IOException {
        if (!regular(file) || Files.size(file) > MAX_BYTES)
            throw new IOException("Configuration source is unavailable or exceeds the inspection limit.");
        byte[] bytes;
        try (var input = Files.newInputStream(file)) { bytes = input.readNBytes((int) MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) throw new IOException("Configuration exceeds the inspection limit.");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }
}
