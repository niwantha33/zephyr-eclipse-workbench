// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Source-only and failed-configure diagnostics for projects which have never
 * produced zephyr/.config or zephyr.dts. This is READ ONLY: it consumes the
 * project's own requested settings and the Workbench's existing retained
 * failed-attempt log; it does not fake a generated configuration.
 */
public final class ConfigurationFailureEvidence {
    private ConfigurationFailureEvidence() {}

    public enum Mode { SOURCE_ONLY, PARTIAL_GENERATED, GENERATED }
    public enum Confidence { OBSERVED, REPORTED_BY_ZEPHYR, MISSING_EVIDENCE }
    public record Finding(Confidence confidence, String title, String explanation,
                          String nextAction) {}
    public record Report(Mode mode, int requestedSymbols, boolean recordedFailure,
                         List<Finding> findings) {
        public Report { findings = List.copyOf(findings); }
    }

    private static final long MAX_LOG_BYTES = 1_048_576;
    private static final int MAX_FINDINGS = 12;

    public static Report inspect(Path application, Path build) {
        var findings = new ArrayList<Finding>();
        if (application == null || !Files.isDirectory(application))
            return new Report(Mode.SOURCE_ONLY, 0, false, List.of(
                    new Finding(Confidence.MISSING_EVIDENCE, "No selected Zephyr application",
                            "Select an accessible project. No project files were inspected.",
                            "Select a project in Project Explorer.")));

        Path config = build == null ? null : build.resolve("zephyr/.config");
        Path dts = build == null ? null : build.resolve("zephyr/zephyr.dts");
        boolean hasConfig = regular(config);
        boolean hasDts = regular(dts);
        Mode mode = hasConfig && hasDts ? Mode.GENERATED
                : hasConfig || hasDts ? Mode.PARTIAL_GENERATED : Mode.SOURCE_ONLY;
        if (!hasConfig) {
            findings.add(new Finding(Confidence.MISSING_EVIDENCE, "Effective Kconfig not generated",
                    "Requested values can be read locally, but no effective CONFIG_ values are known.",
                    "Inspect the recorded configure failure, then run the selected Zephyr configuration."));
        }
        if (!hasDts) {
            findings.add(new Finding(Confidence.MISSING_EVIDENCE, "Resolved Devicetree not generated",
                    "No validated generated DTS is available for this exact build/profile.",
                    "Inspect CMake, Devicetree syntax and binding diagnostics; reconfigure this target."));
        }
        int requested = 0;
        try {
            var snapshot = ConfigurationStudioFiles.inspect(application, build, "source-only");
            requested = (int) snapshot.options().stream().filter(
                    ConfigurationStudioFiles.Option::overridden).count();
        } catch (IOException unreadable) {
            findings.add(new Finding(Confidence.MISSING_EVIDENCE,
                    "Configuration source could not be inspected",
                    "Local project configuration inputs were unavailable or exceeded safe bounds.",
                    "Open the original configuration source in Eclipse and inspect its size/format."));
        }

        boolean recorded = false;
        if (build != null) {
            try {
                Optional<Path> latest = BuildFailureLogs.latestFailure(application, build);
                if (latest.isPresent()) {
                    Path log = latest.get();
                    // The receipt is a historical failed attempt, never proof
                    // that the currently selected source or build is failing.
                    String report = BuildFailureLogs.report(log);
                    recorded = true;
                    if (findings.size() < MAX_FINDINGS)
                        findings.add(new Finding(Confidence.REPORTED_BY_ZEPHYR,
                                "Recorded failed build attempt",
                                "A saved failure belongs to this exact build directory. "
                                + "It may predate a successful build; it is not current source causality.",
                                "Open Build Failure Investigation to inspect the full recorded attempt."));
                    if (Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)
                            && !Files.isSymbolicLink(log)
                            && Files.size(log) <= MAX_LOG_BYTES) {
                        var seen = new LinkedHashSet<String>();
                        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                            var issue = KconfigFailure.parse(line);
                            if (issue.isEmpty()) continue;
                            var k = issue.get();
                            if (!seen.add(k.symbol()) || findings.size() >= MAX_FINDINGS) continue;
                            findings.add(new Finding(Confidence.REPORTED_BY_ZEPHYR,
                                    "Undefined Kconfig symbol: CONFIG_" + k.symbol(),
                                    "The saved Zephyr configure log reported this symbol undefined at "
                                    + basename(k.file()) + ":" + k.line() + ". "
                                    + "It does not prove the selected current Kconfig tree is identical.",
                                    "Inspect the actual symbol using the selected Zephyr Kconfig tree; "
                                    + "correct the project-owned config input and reconfigure."));
                        }
                    }
                }
            } catch (IOException unsafe) {
                if (findings.size() < MAX_FINDINGS)
                    findings.add(new Finding(Confidence.MISSING_EVIDENCE,
                            "Failed-build history unavailable",
                            "The saved failure record could not be read safely; no build error is inferred.",
                            "Open the Workbench build console or import a trusted log explicitly."));
            }
        }
        return new Report(mode, requested, recorded, findings);
    }

    private static boolean regular(Path file) {
        return file != null && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(file);
    }

    private static String basename(String raw) {
        if (raw == null || raw.isBlank()) return "unknown source";
        String clean = raw.replace('\\', '/');
        int i = clean.lastIndexOf('/');
        return clean.substring(i + 1);
    }
}
