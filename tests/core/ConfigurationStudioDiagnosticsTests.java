// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.ConfigurationStudioDiagnostics;
import java.nio.file.Files;
import java.nio.file.Path;
public final class ConfigurationStudioDiagnosticsTests {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Path app = Files.createTempDirectory("zew-diagnostics-Ω ");
        try {
            Path build = app.resolve("build");
            Path source = app.resolve("prj.conf");
            Files.writeString(source, "# fixture\nCONFIG_ADC=y\nCONFIG_SECRET=\"private\"\n");
            var missing = ConfigurationStudioDiagnostics.inspect(app, build);
            check(missing.findings().size() == 1 && missing.findings().get(0).ruleId()
                    .equals("CS-KCONFIG-GENERATED-MISSING"), "No inferred mismatch without snapshot");
            Files.createDirectories(build.resolve("zephyr"));
            Files.writeString(build.resolve("zephyr/.config"), "# CONFIG_ADC is not set\n");
            var report = ConfigurationStudioDiagnostics.inspect(app, build);
            check(report.findings().size() == 2, "Request observations include absent symbols");
            check(report.findings().get(0).line() == 2, "Exact source line retained");
            check(report.findings().get(0).confidence().equals("derived"), "Shared confidence vocabulary");
            check(ConfigurationStudioDiagnostics.sourceMatches(report.findings().get(0)), "Matching source is navigable");
            check(report.equals(ConfigurationStudioDiagnostics.inspect(app, build)), "Deterministic observations");
            check(!report.display().contains("private"), "Private setting values excluded from report");
            Files.writeString(source, "CONFIG_ADC=n\n");
            check(!ConfigurationStudioDiagnostics.sourceMatches(report.findings().get(0)), "Changed source line cannot navigate");
            check(ConfigurationStudioDiagnostics.inspect(app, build).findings().isEmpty(), "Matching request has no warning");
            System.out.println("PASS native source diagnostics: 9 assertions");
        } finally {
            try (var paths = Files.walk(app)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
