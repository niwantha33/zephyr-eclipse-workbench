// SPDX-License-Identifier: Apache-2.0
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import io.github.niwantha33.zew.ui.YamlTwisterModel;
import io.github.niwantha33.zew.ui.YamlTwisterModel.Row;

/** Headless Twister protocol and immutable hierarchy tests, no SWT needed. */
public final class YamlTwisterModelTests {
    private static String enc(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String row(String type, String... values) {
        StringBuilder result = new StringBuilder(type);
        for (String value : values) result.append('\t').append(enc(value));
        return result.append('\n').toString();
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        System.out.println("PASS YAML TWISTER: " + label);
    }

    private static void reject(String input) {
        try {
            YamlTwisterModel.parse(input);
            throw new AssertionError("Invalid protocol accepted");
        } catch (IllegalArgumentException expected) {
            check(!expected.getMessage().contains("SECRET"),
                    "bad protocol errors exclude raw source values");
        }
    }

    public static void main(String[] args) {
        String test = "ZEW-TWISTER-1\n"
                + row("H", "resolved", "selected-twister-schema-validated",
                        "a".repeat(64), "b".repeat(64), "c".repeat(64), "testsuite")
                + row("M", "name", "Test sample")
                + row("C", "tags", "[\"rtos\",\"smoke\"]")
                + row("T", "kernel.basic")
                + row("A", "kernel.basic", "harness", "ztest")
                + row("A", "kernel.basic", "timeout", "10")
                + row("T", "kernel.advanced")
                + row("A", "kernel.advanced", "sysbuild", "true");
        var summary = YamlTwisterModel.parse(test);
        check(summary.resolved() && "testsuite".equals(summary.kind())
                && summary.items().size() == 7,
                "source-verified scenarios and attributes preserved");
        check(summary.apiSha256().length() == 64 && summary.schemaSha256().length() == 64,
                "selected upstream validator/schema hashes retained");
        List<Row> hierarchy = YamlTwisterModel.hierarchy(summary);
        check(hierarchy.size() == 4 && "Sample metadata".equals(hierarchy.get(0).name())
                && "Common defaults (declared)".equals(hierarchy.get(1).name()),
                "sample/common groups clearly distinguish declared defaults");
        check("Scenario: kernel.basic".equals(hierarchy.get(2).name())
                && hierarchy.get(2).children().size() == 2,
                "individual scenarios and declared settings rendered");
        check("Scenario: kernel.advanced".equals(hierarchy.get(3).name())
                && "true".equals(hierarchy.get(3).children().get(0).preview()),
                "second scenario does not inherit fabricated attributes");
        try {
            hierarchy.clear();
            throw new AssertionError("Mutable tree roots");
        } catch (UnsupportedOperationException expected) {
            check(true, "hierarchy root immutable");
        }
        try {
            hierarchy.get(2).children().clear();
            throw new AssertionError("Mutable children");
        } catch (UnsupportedOperationException expected) {
            check(true, "hierarchy children immutable");
        }

        String config = "ZEW-TWISTER-1\n"
                + row("H", "resolved", "selected-twister-schema-validated",
                        "d".repeat(64), "e".repeat(64), "f".repeat(64), "test-config")
                + row("O", "platforms.override_default_platforms", "true")
                + row("P", "qemu_x86")
                + row("P", "native_sim")
                + row("L", "smoke", "Quick checks", "[\"kernel.basic\"]", "[]")
                + row("L", "release", "", "[]", "[\"smoke\"]");
        var cfg = YamlTwisterModel.parse(config);
        List<Row> groups = YamlTwisterModel.hierarchy(cfg);
        check(cfg.resolved() && groups.size() == 3, "config options, platforms and levels grouped");
        check("Config options".equals(groups.get(0).name())
                && "Default platforms (declared)".equals(groups.get(1).name())
                && "Levels (declared)".equals(groups.get(2).name()),
                "no assumed active platform or test execution state");
        check(groups.get(2).children().size() == 2
                && groups.get(2).children().get(1).children().stream().anyMatch(
                        r -> "Inherits".equals(r.name()) && "[\"smoke\"]".equals(r.preview())),
                "Twister level inheritance displayed as declared metadata");

        var unavailable = YamlTwisterModel.unavailable("selected-twister-validator-unavailable");
        check(!unavailable.resolved() && YamlTwisterModel.hierarchy(unavailable).isEmpty(),
                "unavailable validator never creates resolved rows");
        String notTwister = "ZEW-TWISTER-1\n" + row("H", "not-twister",
                "not-a-supported-twister-file", "0".repeat(64), "0".repeat(64),
                "0".repeat(64), "none");
        check(YamlTwisterModel.hierarchy(YamlTwisterModel.parse(notTwister)).isEmpty(),
                "generic yaml not promoted to a Twister scenario");
        check(YamlTwisterModel.hierarchy(null).isEmpty(), "no stale rows after deselection");

        reject(null);
        reject("");
        reject(test.replace("ZEW-TWISTER-1", "ZEW-TWISTER-2"));
        reject(test.replace(enc("resolved"), enc("failed")));
        reject(test.replace(enc("a".repeat(64)), enc("short")));
        reject(test.replace(enc("testsuite"), enc("test-config")));
        reject(test.replace(row("T", "kernel.basic"), ""));
        reject(test.replace(row("A", "kernel.basic", "harness", "ztest"),
                row("A", "other", "harness", "ztest")));
        reject(test.replace(row("T", "kernel.basic"), row("T", "")));
        reject(test + "SECRET:raw-field");
        reject(notTwister + row("T", "fake.test"));
        reject(" ".repeat(128 * 1024 + 1));
        StringBuilder tooMany = new StringBuilder("ZEW-TWISTER-1\n")
                .append(row("H", "resolved", "selected-twister-schema-validated",
                        "a".repeat(64), "b".repeat(64), "c".repeat(64), "testsuite"))
                .append(row("T", "first"));
        for (int i = 0; i < 256; i++)
            tooMany.append(row("A", "first", "tag" + i, "value"));
        reject(tooMany.toString());
        check(true, "strict versioned bounded protocol rejects malformed or fabricated rows");
    }
}
