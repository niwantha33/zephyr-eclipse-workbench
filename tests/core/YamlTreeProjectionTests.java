// SPDX-License-Identifier: Apache-2.0
import java.util.List;

import io.github.niwantha33.zew.ui.YamlDocumentModel.Entry;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Mapping;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Position;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Range;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Scalar;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Sequence;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Snapshot;
import io.github.niwantha33.zew.ui.YamlTreeProjection;

/** Fast SWT-free tests of the exact rows used by the native TreeViewer. */
public final class YamlTreeProjectionTests {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        System.out.println("PASS YAML TREE: " + message);
    }

    private static Range range(int line, int column) {
        return new Range(new Position(line, column), new Position(line, column + 1));
    }

    private static Snapshot snapshot(io.github.niwantha33.zew.ui.YamlDocumentModel.Node node) {
        return new Snapshot("parsed", "syntax-observed", "devicetree-binding",
                "binding-directory-candidate", "zephyr", "dts/bindings/test.yaml",
                "a".repeat(64), 52, node);
    }

    public static void main(String[] args) {
        Scalar bool = new Scalar("bool", "true", range(2, 12));
        Scalar multiline = new Scalar("str", "first\nsecond\tpart", range(4, 7));
        Sequence items = new Sequence(List.of(bool, multiline), range(2, 5));
        Mapping root = new Mapping(List.of(
                new Entry("compatible", range(1, 1), new Scalar("str", "ti,example", range(1, 13))),
                new Entry("items", range(2, 1), items)), range(1, 1));

        var rows = YamlTreeProjection.rows(snapshot(root));
        check(rows.size() == 1 && "(document)".equals(rows.get(0).label()),
                "root has one labelled document row");
        var top = rows.get(0);
        check("mapping".equals(top.kind()) && "2 entries".equals(top.preview()),
                "mapping count and type are visible");
        check("compatible".equals(top.children().get(0).label())
                && "ti,example".equals(top.children().get(0).preview()),
                "declaration order and scalar value preserved");
        check("1:1".equals(top.children().get(0).location()),
                "mapping rows navigate to the YAML key, not the value");
        var list = top.children().get(1);
        check("items".equals(list.label()) && "sequence".equals(list.kind())
                && "2 items".equals(list.preview()), "sequence shows item count");
        check("[0]".equals(list.children().get(0).label())
                && "bool".equals(list.children().get(0).kind())
                && "true".equals(list.children().get(0).preview()),
                "index and YAML scalar spelling retained");
        check("2:12".equals(list.children().get(0).location()),
                "1-based source position displayed");
        check("first\\nsecond\\tpart".equals(list.children().get(1).preview()),
                "multiline scalar escaped for tree presentation");
        try {
            top.children().clear();
            throw new AssertionError("Mutable rows");
        } catch (UnsupportedOperationException expected) {
            check(true, "tree projection is immutable");
        }
        check(YamlTreeProjection.rows(null).isEmpty(), "null observation never invents a tree");
        var invalid = new Snapshot("invalid", "yaml-syntax-invalid", "generic-yaml",
                "no-specific-role-established", "application", "test.yaml", null, null, null);
        check(YamlTreeProjection.rows(invalid).isEmpty(),
                "invalid observation never displays stale tree data");
        check(YamlTreeProjection.rows(snapshot(null)).isEmpty(),
                "parsed empty document stays empty");

        String unicode = "🙂".repeat(180);
        String shortPreview = YamlTreeProjection.preview(unicode);
        check(shortPreview.codePointCount(0, shortPreview.length()) == 161
                && shortPreview.endsWith("…") && !shortPreview.contains("\ufffd"),
                "truncation is bounded without splitting Unicode code points");
        check(YamlTreeProjection.preview("a\\b").equals("a\\\\b"),
                "backslashes displayed literally");
        check(YamlTreeProjection.preview("ok").equals("ok"), "short values unchanged");
        check(true, "Step 5 rows compile without SWT or workspace dependencies");
    }
}
