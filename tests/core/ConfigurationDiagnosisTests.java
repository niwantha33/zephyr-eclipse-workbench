// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.ConfigurationDiagnosis;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

public class ConfigurationDiagnosisTests {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("zew-config-diagnosis-");
        Path app = Files.createDirectory(dir.resolve("app"));
        Path build = Files.createDirectory(dir.resolve("build"));
        try {
            var noBuild = ConfigurationDiagnosis.inspect(app, null, null);
            check(noBuild.findings().size() == 2, "source-only mode reports missing evidence");
            check(noBuild.display().contains("No files were changed"), "read-only label");
            Path generated = Files.createDirectories(build.resolve("zephyr"));
            Path config = generated.resolve(".config");
            Path dts = generated.resolve("zephyr.dts");
            Path source = app.resolve("prj.conf");
            Files.writeString(source, "CONFIG_ADC=y\nCONFIG_OBSOLETE=y\n");
            Files.writeString(config, "# CONFIG_ADC is not set\nCONFIG_GPIO=y\n");
            Files.writeString(dts, "/ {\n    soc {\n        status = \"disabled\";\n"
                    + "        adc0: adc@4000 {\n            compatible = \"vnd,adc\";\n"
                    + "            status = \"okay\";\n        };\n    };\n};\n");
            Files.setLastModifiedTime(config, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
            Files.setLastModifiedTime(source, FileTime.from(Instant.parse("2026-01-02T00:00:00Z")));
            var report = ConfigurationDiagnosis.inspect(app, build, "/soc/adc@4000");
            check(report.findings().stream().anyMatch(f -> f.observation().contains("modified after")), "stale source");
            check(report.findings().stream().anyMatch(f -> f.observation().contains("CONFIG_ADC")), "value mismatch");
            check(report.findings().stream().anyMatch(f -> f.observation().contains("CONFIG_OBSOLETE")), "unknown symbol");
            check(report.findings().stream().anyMatch(f -> f.observation().contains("Ancestor /soc")), "disabled parent");
            check(report.findings().stream().noneMatch(f -> f.observation().contains("Selected node has status")), "enabled selected node");
            check(Files.readString(source).equals("CONFIG_ADC=y\nCONFIG_OBSOLETE=y\n"), "source not edited");
            check(Files.readString(config).contains("CONFIG_GPIO=y"), "generated config not edited");
            Files.writeString(source, "CONFIG_GPIO=y\n");
            Files.setLastModifiedTime(source, FileTime.from(Instant.parse("2025-01-01T00:00:00Z")));
            var clean = ConfigurationDiagnosis.inspect(app, build, "/soc/adc@4000");
            check(clean.findings().size() == 1 && clean.findings().getFirst().observation().contains("Ancestor"),
                    "only proven disabled-parent issue remains");
            var staleNode = ConfigurationDiagnosis.inspect(app, build, "/soc/nonexistent");
            check(staleNode.findings().stream().anyMatch(f -> f.confidence() == ConfigurationDiagnosis.Confidence.MISSING_EVIDENCE),
                    "stale selected node is not silently accepted");
            System.out.println("ConfigurationDiagnosisTests PASS");
        } finally {
            try (var walk = Files.walk(dir)) {
                for (var p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }
    private static void check(boolean yes, String message) { if (!yes) throw new AssertionError(message); }
}
