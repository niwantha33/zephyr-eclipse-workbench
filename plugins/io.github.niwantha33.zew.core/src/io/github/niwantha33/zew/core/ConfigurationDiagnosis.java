// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Deterministic, read-only diagnosis of the selected build's actual generated evidence. */
public final class ConfigurationDiagnosis {
    private ConfigurationDiagnosis() {}

    public enum Confidence { CONFIRMED, POSSIBLE, MISSING_EVIDENCE }
    public record Finding(Confidence confidence, String observation, String evidence, String nextAction) {}
    public record Report(String subject, List<Finding> findings) {
        public Report { findings = List.copyOf(findings); }
        public String display() {
            StringBuilder result = new StringBuilder("ZEPHYR CONFIGURATION DIAGNOSIS\n")
                    .append("Scope: ").append(subject).append("\n")
                    .append("Evidence is from the selected build, not a live target. No files were changed.\n");
            if (findings.isEmpty()) {
                return result.append("\nNo deterministic configuration problem found in the available evidence. ")
                        .append("This does not prove the firmware or wiring is correct.\n").toString();
            }
            int index = 0;
            for (Finding finding : findings) {
                result.append("\n").append(++index).append(". [").append(finding.confidence())
                        .append("] ").append(finding.observation())
                        .append("\nEvidence: ").append(finding.evidence())
                        .append("\nNext action: ").append(finding.nextAction()).append("\n");
            }
            return result.toString();
        }
    }

    /**
     * A non-null node path must be taken from the current generated Devicetree.
     * No driver compatibility or physical pin assignment is inferred.
     */
    public static Report inspect(Path application, Path build, String selectedNode) throws IOException {
        if (application == null || !Files.isDirectory(application))
            throw new IOException("Select an accessible Zephyr application before diagnosing its configuration.");
        var findings = new ArrayList<Finding>();
        String subject = selectedNode == null || selectedNode.isBlank() ? "Selected project/build" : selectedNode;
        Path config = build == null ? null : build.resolve("zephyr/.config");
        Path tree = build == null ? null : build.resolve("zephyr/zephyr.dts");
        boolean hasConfig = regular(config);
        boolean hasTree = regular(tree);
        if (!hasConfig) {
            findings.add(new Finding(Confidence.MISSING_EVIDENCE, "Effective Kconfig is unavailable.",
                    config == null ? "No selected build directory" : config.toString() + " (missing)",
                    "Configure and build the exact board/profile, then run Diagnose again."));
        }
        if (!hasTree) {
            findings.add(new Finding(Confidence.MISSING_EVIDENCE, "Resolved Devicetree is unavailable.",
                    tree == null ? "No selected build directory" : tree.toString() + " (missing)",
                    "Build the exact target to generate zephyr.dts; inspect any DTS compiler errors."));
        }
        var snapshot = ConfigurationStudioFiles.inspect(application, build, "selected build");
        Path source = application.resolve("prj.conf");
        boolean newerSource = hasConfig && regular(source)
                && Files.getLastModifiedTime(source).compareTo(Files.getLastModifiedTime(config)) > 0;
        if (newerSource) {
            findings.add(new Finding(Confidence.CONFIRMED,
                    "prj.conf was modified after the generated Kconfig snapshot.",
                    source + " is newer than " + config,
                    "Rebuild the selected profile before relying on effective values or comparing settings."));
        }
        if (hasConfig) {
            int mismatches = 0;
            for (var option : snapshot.options()) {
                if (!option.overridden() || java.util.Objects.equals(option.requested(), option.effective())) continue;
                mismatches++;
                if (mismatches > 20) continue;
                boolean absent = option.unresolved();
                findings.add(new Finding(Confidence.POSSIBLE,
                        "CONFIG_" + option.symbol() + (absent
                            ? " is requested but has no generated value."
                            : " differs between requested and effective configuration."),
                        source + ":" + option.requestedLine() + " versus " + config,
                        newerSource ? "Rebuild first; then inspect Kconfiglib dependencies and symbol visibility."
                                : "Inspect effective Kconfig dependencies/visibility and the configuration fragments; do not force-edit generated .config."));
            }
            if (mismatches > 20) findings.add(new Finding(Confidence.POSSIBLE,
                    (mismatches - 20) + " additional requested/effective differences were suppressed.",
                    source + " and " + config,
                    "Review the complete configuration in Features and investigate the relevant Kconfig symbols."));
        }
        if (hasTree && selectedNode != null && !selectedNode.isBlank()) {
            var devices = DevicetreeHardware.inspect(snapshot.devicetree());
            var selected = devices.stream().filter(d -> d.path().equals(selectedNode)).findFirst();
            if (selected.isEmpty()) {
                findings.add(new Finding(Confidence.MISSING_EVIDENCE,
                        "Selected node is not in the current generated Devicetree.", tree.toString(),
                        "Refresh Hardware after rebuilding the selected board/profile and select the node again."));
            } else {
                var device = selected.get();
                if (!device.enabled()) findings.add(new Finding(Confidence.CONFIRMED,
                        "Selected node has status = " + device.status() + ".",
                        tree + ":" + device.line() + " (" + device.path() + ")",
                        "Check board DTS and selected overlays; preview changes in app.overlay and rebuild."));
                String parent = device.path();
                while (parent.lastIndexOf('/') > 0) {
                    parent = parent.substring(0, parent.lastIndexOf('/'));
                    String ancestor = parent;
                    var match = devices.stream().filter(d -> d.path().equals(ancestor)).findFirst();
                    if (match.isPresent() && !match.get().enabled()) {
                        var disabled = match.get();
                        findings.add(new Finding(Confidence.CONFIRMED,
                                "Ancestor " + ancestor + " has status = " + disabled.status() + ".",
                                tree + ":" + disabled.line(),
                                "Check the parent bus/controller status before diagnosing the selected peripheral."));
                    }
                }
            }
        }
        return new Report(subject, findings);
    }

    private static boolean regular(Path file) {
        return file != null && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
    }
}
