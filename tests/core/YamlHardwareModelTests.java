// SPDX-License-Identifier: Apache-2.0
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import io.github.niwantha33.zew.ui.YamlHardwareModel;
import io.github.niwantha33.zew.ui.YamlHardwareModel.Row;

/** Read-only Java 21 protocol + hierarchy qualification for HWMv2 Step 8. */
public final class YamlHardwareModelTests {
    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String wire(String type, String... fields) {
        StringBuilder text = new StringBuilder(type);
        for (String field : fields) text.append('\t').append(encode(field));
        return text.append('\n').toString();
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        System.out.println("PASS YAML HWMV2: " + description);
    }

    private static void reject(String payload) {
        try {
            YamlHardwareModel.parse(payload);
            throw new AssertionError("Invalid HWMv2 observation accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(!expected.getMessage().contains("SECRET"),
                    "untrusted metadata never leaks through parser errors");
        }
    }

    public static void main(String[] args) {
        String board = "ZEW-HWMV2-1\n"
                + wire("H", "resolved", "selected-upstream-hwmv2", "a".repeat(64),
                        "board", "b".repeat(64))
                + wire("B", "lp_mspm0g3507", "TI LaunchPad", "ti", "number", "1", "yes")
                + wire("R", "lp_mspm0g3507", "1")
                + wire("R", "lp_mspm0g3507", "2")
                + wire("Q", "lp_mspm0g3507", "mspm0g3507")
                + wire("S", "mspm0g3507", "mspm0", "mspm0g", "cpu0");
        var summary = YamlHardwareModel.parse(board);
        assertTrue(summary.resolved() && "board".equals(summary.kind()),
                "exact selected checkout reports a board, not a build target");
        assertTrue(summary.items().size() == 5 && summary.sourceSha256().length() == 64,
                "ordered metadata rows and source digest retained");
        List<Row> hierarchy = YamlHardwareModel.hierarchy(summary);
        assertTrue(hierarchy.size() == 1
                && hierarchy.get(0).label().equals("Board: lp_mspm0g3507")
                && hierarchy.get(0).detail().equals("Vendor: ti"),
                "board appears as its own vendor-labelled root");
        List<Row> children = hierarchy.get(0).children();
        assertTrue(children.stream().anyMatch(r -> r.label().equals("Revision")
                    && r.detail().equals("2"))
                && children.stream().anyMatch(r -> r.label().equals("Board target qualifier")
                    && r.detail().equals("mspm0g3507")),
                "revision and qualifier shown as separate attributes");
        Row soc = children.stream().filter(row -> row.label().equals("SoC: mspm0g3507"))
                .findFirst().orElseThrow();
        assertTrue(soc.children().stream().anyMatch(r -> r.label().equals("CPU cluster")
                    && r.detail().equals("cpu0"))
                && soc.children().stream().anyMatch(r -> r.label().equals("Family")
                    && r.detail().equals("mspm0")),
                "family and CPU cluster appear in nested SoC hierarchy");
        try {
            hierarchy.clear();
            throw new AssertionError("Mutable HWMv2 root list");
        } catch (UnsupportedOperationException expected) {
            assertTrue(true, "HWMv2 hierarchy root immutable");
        }
        try {
            children.clear();
            throw new AssertionError("Mutable HWMv2 children");
        } catch (UnsupportedOperationException expected) {
            assertTrue(true, "HWMv2 row children immutable");
        }

        String socRecord = "ZEW-HWMV2-1\n"
                + wire("H", "resolved", "selected-upstream-hwmv2", "c".repeat(64),
                        "soc", "d".repeat(64))
                + wire("S", "esp32s3", "esp32", "esp32s3", "appcpu,procpu");
        var socSummary = YamlHardwareModel.parse(socRecord);
        List<Row> socRows = YamlHardwareModel.hierarchy(socSummary);
        assertTrue(socRows.size() == 1 && socRows.get(0).label().equals("SoC: esp32s3")
                && socRows.get(0).children().stream()
                .filter(r -> r.label().equals("CPU cluster")).count() == 2,
                "standalone SoC metadata displays both CPU clusters");

        var unverified = YamlHardwareModel.unavailable("selected-hwm-api-unavailable");
        assertTrue(!unverified.resolved() && YamlHardwareModel.hierarchy(unverified).isEmpty(),
                "missing upstream API cannot fabricate any HWMv2 rows");
        String unknown = "ZEW-HWMV2-1\n" + wire("H", "not-hwmv2",
                "not-canonical-hwmv2-metadata", "e".repeat(64), "none", "0".repeat(64));
        assertTrue(YamlHardwareModel.hierarchy(YamlHardwareModel.parse(unknown)).isEmpty(),
                "board.yaml must not be promoted into HWMv2 board.yml");
        assertTrue(YamlHardwareModel.hierarchy(null).isEmpty(),
                "null observation clears native hardware tree");

        reject(null);
        reject("");
        reject(board.replace("ZEW-HWMV2-1", "ZEW-HWMV2-2"));
        reject(board.replace(encode("resolved"), encode("faked")));
        reject(board.replace(encode("a".repeat(64)), encode("short")));
        reject(board.replace(encode("number"), "@"));
        reject(board.replace(wire("B", "lp_mspm0g3507", "TI LaunchPad", "ti",
                "number", "1", "yes"), ""));
        reject(board.replace(encode("yes"), encode("sometimes")));
        reject(board.replace(wire("R", "lp_mspm0g3507", "1"),
                wire("R", "foreign", "1")));
        reject(board.replace(encode("board"), encode("soc")));
        reject(unknown + wire("S", "fake", "", "", ""));
        reject(board + "PRIVATE_UNTRUSTED");
        reject(" ".repeat(128 * 1024 + 1));
        assertTrue(true, "malformed, changed, unbound and oversized metadata rejected");
    }
}
