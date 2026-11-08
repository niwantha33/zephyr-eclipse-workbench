// SPDX-License-Identifier: Apache-2.0
import java.util.ArrayList;
import java.util.List;

import io.github.niwantha33.zew.ui.YamlDocumentModel.Entry;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Mapping;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Node;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Position;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Range;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Scalar;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Sequence;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Snapshot;
import io.github.niwantha33.zew.ui.YamlFlowGraph;

/** Tests that graph nodes/edges are bounded and derived only from YAML syntax. */
public final class YamlFlowGraphTests {
    private static Range at(int line) {
        return new Range(new Position(line, 1), new Position(line, 10));
    }
    private static Snapshot observation(Node root) {
        return new Snapshot("parsed", "syntax-observed", "generic-yaml", "source",
                "application", "build_info.yml", "a".repeat(64), 100, root);
    }
    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
        System.out.println("PASS YAML FLOW: " + message);
    }
    public static void main(String[] args) {
        Mapping child = new Mapping(List.of(new Entry("name", at(3),
                new Scalar("str", "demo", at(3)))), at(2));
        Sequence list = new Sequence(List.of(
                new Scalar("bool", "true", at(5)),
                child), at(4));
        Mapping root = new Mapping(List.of(
                new Entry("description", at(1), new Scalar("str", "test", at(1))),
                new Entry("items", at(4), list)), at(1));
        var layout = YamlFlowGraph.from(observation(root));
        check(layout.blocks().size() == 6 && layout.edges().size() == 5,
                "every displayed child has one observed hierarchy edge");
        for (var edge : layout.edges()) {
            check(edge.parent() < edge.child()
                    && edge.parent() >= 0 && edge.child() < layout.blocks().size(),
                    "no speculative, dangling or backwards dependency edge");
        }
        check(layout.blocks().get(2).name().equals("items")
                && layout.blocks().get(2).type().equals("sequence")
                && layout.blocks().get(2).line() == 4,
                "graph preserves key, YAML type and original source line");
        check(layout.blocks().get(5).name().equals("name")
                && layout.blocks().get(5).preview().equals("demo"),
                "nested mapping and scalar preserve source spelling");
        check(layout.blocks().get(5).x() > layout.blocks().get(4).x(),
                "descendants appear in successive horizontal columns");
        check(!layout.limited() && layout.width() > 0 && layout.height() > 0,
                "small graph has positive scroll extent and no false truncation");
        try {
            layout.blocks().clear();
            throw new AssertionError("Mutable graph blocks");
        } catch (UnsupportedOperationException correct) {
            check(true, "projection exposes immutable graph blocks");
        }
        check(YamlFlowGraph.from(null).blocks().isEmpty(), "null is not drawn as YAML");
        Snapshot invalid = new Snapshot("invalid", "syntax-error", "generic-yaml",
                "unknown", "application", "test.yml", null, null, null);
        check(YamlFlowGraph.from(invalid).blocks().isEmpty(),
                "invalid YAML never reuses previous graph or fabricates nodes");
        List<Entry> many = new ArrayList<>();
        for (int i = 0; i < 500; i++)
            many.add(new Entry("key" + i, at(i + 1),
                    new Scalar("int", Integer.toString(i), at(i + 1))));
        var large = YamlFlowGraph.from(observation(new Mapping(many, at(1))));
        check(large.limited() && large.blocks().size() == YamlFlowGraph.LIMIT
                && large.edges().size() == YamlFlowGraph.LIMIT - 1,
                "large YAML graphs enforce a visible honest node cap");
        Node deep = new Scalar("str", "end", at(15));
        for (int i = 14; i >= 1; i--)
            deep = new Mapping(List.of(new Entry("level" + i, at(i), deep)), at(i));
        var nested = YamlFlowGraph.from(observation(deep));
        check(nested.limited() && nested.blocks().size() == YamlFlowGraph.MAX_DEPTH + 1,
                "deep graphs are bounded without crashing or claiming unseen nodes");
    }
}
