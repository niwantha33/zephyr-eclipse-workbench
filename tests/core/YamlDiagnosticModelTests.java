// SPDX-License-Identifier: Apache-2.0
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import io.github.niwantha33.zew.ui.YamlDiagnosticModel;
import io.github.niwantha33.zew.ui.YamlDiagnosticModel.Finding;
import io.github.niwantha33.zew.ui.YamlDiagnosticModel.Summary;
import io.github.niwantha33.zew.ui.YamlSourceLoader;

/** Headless Java 21 exact wire, source provenance and read-only row tests. */
public final class YamlDiagnosticModelTests {
    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String row(String kind, String... values) {
        StringBuilder builder = new StringBuilder(kind);
        for (String value : values) builder.append('\t').append(b64(value));
        return builder.append('\n').toString();
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        System.out.println("PASS YAML DIAGNOSTICS: " + label);
    }

    private static void reject(String input) {
        try {
            YamlDiagnosticModel.parse(input);
            throw new AssertionError("Unsafe YAML diagnostic protocol accepted");
        } catch (IllegalArgumentException expected) {
            check(!expected.getMessage().contains("SECRET"),
                    "rejected wire cannot leak source content");
        }
    }

    public static void main(String[] args) throws Exception {
        String invalid = "ZEW-YAMLDIAG-1\n"
                + row("H", "invalid", "duplicate-mapping-key", "a".repeat(64), "syntax")
                + row("D", "error", "syntax-duplicate-mapping-key",
                        "3", "3", "A YAML mapping contains a repeated key.");
        Summary duplicate = YamlDiagnosticModel.parse(invalid);
        check("invalid".equals(duplicate.state()) && duplicate.hasErrors()
                && duplicate.findings().size() == 1,
                "trusted syntax error propagates as one verified finding");
        Finding issue = duplicate.findings().get(0);
        check(issue.located() && "3:3".equals(issue.location()),
                "source line and column are retained for navigation");
        check(YamlDiagnosticModel.rows(duplicate).get(0).code()
                .equals("syntax-duplicate-mapping-key"),
                "the native tree keeps a stable explanatory code");
        try {
            duplicate.findings().clear();
            throw new AssertionError("Finding list is mutable");
        } catch (UnsupportedOperationException expected) {
            check(true, "finding list is immutable");
        }
        try {
            YamlDiagnosticModel.rows(duplicate).clear();
            throw new AssertionError("Diagnostic row list is mutable");
        } catch (UnsupportedOperationException expected) {
            check(true, "diagnostic rows are immutable");
        }

        String twister = "ZEW-YAMLDIAG-1\n"
                + row("H", "invalid", "twister-schema-rejected", "b".repeat(64), "twister")
                + row("D", "error", "schema-type", "9", "7",
                        "The selected Twister schema rejects the value type at this location.");
        check("twister".equals(YamlDiagnosticModel.parse(twister).scope())
                && YamlDiagnosticModel.parse(twister).findings().get(0).line() == 9,
                "upstream Twister schema error stays distinct from YAML syntax");
        String valid = "ZEW-YAMLDIAG-1\n"
                + row("H", "checked", "yaml-syntax-valid", "c".repeat(64), "syntax");
        check(YamlDiagnosticModel.parse(valid).findings().isEmpty(),
                "syntax valid status never fabricates a Zephyr schema error");
        String unavailable = "ZEW-YAMLDIAG-1\n"
                + row("H", "unavailable", "selected-twister-validation-unavailable",
                        "0".repeat(64), "none");
        check(!YamlDiagnosticModel.parse(unavailable).hasErrors()
                && YamlDiagnosticModel.rows(YamlDiagnosticModel.parse(unavailable)).isEmpty(),
                "missing upstream validation is not an error marker");
        check(YamlDiagnosticModel.rows(null).isEmpty(), "clearing a selection clears rows");
        String noPosition = "ZEW-YAMLDIAG-1\n"
                + row("H", "invalid", "invalid-utf8", "d".repeat(64), "syntax")
                + row("D", "error", "syntax-invalid-utf8", "0", "0",
                        "YAML source is not valid UTF-8.");
        check(!YamlDiagnosticModel.parse(noPosition).findings().get(0).located()
                && "Location unavailable".equals(YamlDiagnosticModel.rows(
                        YamlDiagnosticModel.parse(noPosition)).get(0).location()),
                "unknown positions never imply a fabricated editor line");

        reject(null);
        reject("");
        reject(invalid.replace("ZEW-YAMLDIAG-1", "ZEW-YAMLDIAG-2"));
        reject(invalid.replace(b64("invalid"), b64("checked")));
        reject(invalid.replace(b64("syntax"), b64("none")));
        reject(invalid.replace(b64("3"), b64("-1")));
        reject(invalid.replace(b64("3"), b64("1000000")));
        reject(invalid.replace(b64("a".repeat(64)), b64("short")));
        reject(invalid.replace(b64("error"), b64("critical")));
        reject(invalid.replace(
                row("D", "error", "syntax-duplicate-mapping-key",
                        "3", "3", "A YAML mapping contains a repeated key."),
                row("D", "error", "syntax-duplicate-mapping-key",
                        "0", "3", "A YAML mapping contains a repeated key.")));
        reject(unavailable + row("D", "error", "fake", "1", "1", "SECRET"));
        reject(invalid + "D\tSECRET\n");
        reject(" ".repeat(64 * 1024 + 1));
        StringBuilder tooMany = new StringBuilder("ZEW-YAMLDIAG-1\n")
                .append(row("H", "invalid", "yaml-syntax-invalid", "a".repeat(64), "syntax"));
        for (int i = 0; i < 9; i++)
            tooMany.append(row("D", "error", "syntax-yaml-syntax-invalid",
                    "1", "1", "YAML syntax could not be parsed."));
        reject(tooMany.toString());
        check(true, "invalid statuses, positions, severity and overlong reports are fail-closed");

        // Real managed-Python bridge qualification for generic YAML requires
        // no fabricated Zephyr checkout or fake upstream semantic result.
        String python = System.getenv("ZEW_TEST_PYTHON");
        check(python != null && !python.isBlank(),
                "core qualifier supplies its selected managed Python interpreter");
        Path root = Files.createTempDirectory("zew-yaml-diagnostic-java-");
        Path app = Files.createDirectory(root.resolve("app"));
        Path zephyr = Files.createDirectory(root.resolve("zephyr"));
        Path cfg = Files.createDirectory(app.resolve("config")).resolve("demo.yaml");
        Files.writeString(cfg, "setting:\n  value: 4\n", StandardCharsets.UTF_8);
        try {
            var source = YamlSourceLoader.resolve(cfg, app, zephyr);
            Path script = Path.of("plugins/io.github.niwantha33.zew.ui/scripts/yaml_diagnostic_inspect.py")
                    .toAbsolutePath();
            Summary observed = YamlDiagnosticModel.inspect(Path.of(python), script,
                    source, zephyr, app, () -> false);
            check("checked".equals(observed.state()) && "syntax".equals(observed.scope())
                    && observed.sourceSha256().equals(YamlSourceLoader.hash(cfg))
                    && observed.findings().isEmpty(),
                    "actual managed Python returns independently SHA-checked syntax evidence");
            try {
                YamlDiagnosticModel.inspect(Path.of(python), script,
                        source, zephyr, app, () -> true);
                throw new AssertionError("Cancelled diagnostic process succeeded");
            } catch (InterruptedException expected) {
                check(true, "cancellation interrupts managed diagnostic process safely");
            }
            Files.writeString(cfg, "setting: [invalid\n", StandardCharsets.UTF_8);
            Summary broken = YamlDiagnosticModel.inspect(Path.of(python), script,
                    source, zephyr, app, () -> false);
            check("invalid".equals(broken.state()) && broken.hasErrors(),
                    "real process returns actual YAML syntax failure, never a fake success");
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (java.io.IOException ignored) {}
                });
            }
        }
    }
}
