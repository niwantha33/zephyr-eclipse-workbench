// SPDX-License-Identifier: Apache-2.0
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import io.github.niwantha33.zew.ui.YamlBindingIntelligence;

/** Protocol and presentation checks independent of Eclipse and hardware. */
public final class YamlBindingIntelligenceTests {
    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String row(String code, String... values) {
        StringBuilder line = new StringBuilder(code);
        for (String value : values) line.append('\t').append(encode(value));
        return line.append('\n').toString();
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        System.out.println("PASS YAML BINDING: " + message);
    }

    private static void reject(String wire) {
        try {
            YamlBindingIntelligence.parse(wire);
            throw new AssertionError("Invalid binding protocol accepted");
        } catch (IllegalArgumentException expected) {
            check(!expected.getMessage().contains("PRIVATE"),
                    "malformed binding protocol errors do not expose source literals");
        }
    }

    public static void main(String[] args) {
        String normal = "ZEW-YAMLBIND-1\n"
                + row("S", "resolved", "selected-upstream-edtlib",
                        "a".repeat(64), "b".repeat(64),
                        "ti,example", "Example driver", "A GPIO controller", "2")
                + row("P", "demo-gpios", "phandle-array", "yes", "no", "GPIO pins")
                + row("P", "old-value", "int", "no", "yes", "Deprecated property");
        var report = YamlBindingIntelligence.parse(normal);
        check(report.resolved() && "ti,example".equals(report.compatible()),
                "selected edtlib resolution and compatible retained");
        check(report.includedCount() == 2 && report.properties().size() == 2,
                "included bindings and bounded property count");
        check(report.properties().get(0).required()
                && report.properties().get(1).deprecated(),
                "required/deprecated flags preserved");
        check(YamlBindingIntelligence.describe(report).contains("No built-DTS match")
                && YamlBindingIntelligence.describe(report).contains("demo-gpios"),
                "read-only summary distinguishes binding resolution from build use");
        try {
            report.properties().clear();
            throw new AssertionError("Mutable binding properties");
        } catch (UnsupportedOperationException expected) {
            check(true, "property rows are immutable");
        }
        String unavailable = "ZEW-YAMLBIND-1\n" + row("S", "unavailable",
                "selected-edtlib-missing", "0".repeat(64),
                "0".repeat(64), "", "", "", "0");
        var unknown = YamlBindingIntelligence.parse(unavailable);
        check(!unknown.resolved() && unknown.properties().isEmpty()
                && YamlBindingIntelligence.describe(unknown).contains("Upstream semantics not established"),
                "unavailable checkout is never shown as validated");
        reject(null);
        reject("");
        reject(normal.replace("ZEW-YAMLBIND-1", "ZEW-YAMLBIND-2"));
        reject(normal.replace(row("S", "resolved", "selected-upstream-edtlib",
                "a".repeat(64), "b".repeat(64), "ti,example", "Example driver",
                "A GPIO controller", "2"), ""));
        reject(normal.replace(encode("resolved"), encode("invalid")));
        reject(normal.replace(encode("2"), encode("65")));
        reject(normal.replace(encode("yes"), encode("maybe")));
        reject(normal.replace(encode("a".repeat(64)), encode("invalid")));
        reject(normal + "P\tPRIVATE\n");
        reject(normal + "garbage");
        reject(unavailable + row("P", "x", "int", "no", "no", ""));
        reject(" ".repeat(128 * 1024 + 1));
        StringBuilder overflow = new StringBuilder("ZEW-YAMLBIND-1\n")
                .append(row("S", "resolved", "selected-upstream-edtlib",
                        "a".repeat(64), "b".repeat(64), "test", "", "", "0"));
        for (int index = 0; index < 257; index++)
            overflow.append(row("P", "x", "int", "no", "no", ""));
        reject(overflow.toString());
        check(true, "bounded protocol rejects unsafe and malformed content");
    }
}
