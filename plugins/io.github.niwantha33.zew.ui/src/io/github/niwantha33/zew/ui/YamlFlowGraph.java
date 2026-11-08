// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.ArrayList;
import java.util.List;

import io.github.niwantha33.zew.ui.YamlTreeProjection.Row;

/**
 * Immutable, bounded LEFT-TO-RIGHT layout from an authenticated YAML syntax
 * observation. Edges mean parent/child in the parsed document ONLY, never a
 * Devicetree reference, dependency, hardware connection or validated binding.
 * No SWT dependencies; safe to test without the Eclipse product.
 */
public final class YamlFlowGraph {
    public static final int LIMIT = 180;
    public static final int MAX_DEPTH = 10;
    public static final int CARD_WIDTH = 248;
    public static final int CARD_HEIGHT = 64;
    public static final int COLUMN_GAP = 82;
    public static final int ROW_GAP = 27;
    public static final int MARGIN = 38;

    public record Block(int id, int parent, int depth, int x, int y,
                        String name, String type, String preview,
                        int line, int column, int childCount) {}
    public record Edge(int parent, int child) {}
    public record Layout(List<Block> blocks, List<Edge> edges, int width, int height,
                         boolean limited, String reason) {
        public Layout {
            blocks = List.copyOf(blocks);
            edges = List.copyOf(edges);
        }
    }
    private static final class Mutable {
        final Row row;
        final int id;
        final int parent;
        final int depth;
        final List<Mutable> children = new ArrayList<>();
        int centerY;
        Mutable(Row row, int id, int parent, int depth) {
            this.row = row;
            this.id = id;
            this.parent = parent;
            this.depth = depth;
        }
    }
    private final List<Mutable> flattened = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();
    private int leafCount;
    private int deepest;
    private boolean limited;

    private YamlFlowGraph() {}

    public static Layout from(YamlDocumentModel.Snapshot snapshot) {
        if (snapshot == null || !snapshot.parsed() || snapshot.root() == null)
            return new Layout(List.of(), List.of(), 0, 0, false,
                    snapshot == null ? "No YAML selected" :
                    "parsed".equals(snapshot.status()) ? "Empty YAML document" :
                    "No parsed YAML structure: " + snapshot.status());
        YamlFlowGraph graph = new YamlFlowGraph();
        List<Row> roots = YamlTreeProjection.rows(snapshot);
        if (roots.isEmpty()) return new Layout(List.of(), List.of(), 0, 0, false,
                "Empty YAML document");
        Mutable root = graph.add(roots.get(0), -1, 0);
        graph.position(root);
        List<Block> blocks = new ArrayList<>();
        for (Mutable node : graph.flattened) {
            int x = MARGIN + node.depth * (CARD_WIDTH + COLUMN_GAP);
            blocks.add(new Block(node.id, node.parent, node.depth, x,
                    node.centerY - CARD_HEIGHT / 2,
                    node.row.label(), node.row.kind(), node.row.preview(),
                    node.row.sourceRange().start().line(),
                    node.row.sourceRange().start().column(),
                    node.row.children().size()));
        }
        return new Layout(blocks, graph.edges,
                2 * MARGIN + (graph.deepest + 1) * CARD_WIDTH + graph.deepest * COLUMN_GAP,
                2 * MARGIN + graph.leafCount * (CARD_HEIGHT + ROW_GAP),
                graph.limited,
                graph.limited ? "Showing the first " + LIMIT
                        + " observed nodes/depth " + MAX_DEPTH + "; remaining nodes are not shown."
                        : "Observed YAML parent-child structure only (not validated Zephyr relationships)");
    }

    private Mutable add(Row row, int parent, int depth) {
        Mutable node = new Mutable(row, flattened.size(), parent, depth);
        flattened.add(node);
        deepest = Math.max(deepest, depth);
        if (parent >= 0) edges.add(new Edge(parent, node.id));
        if (depth >= MAX_DEPTH && !row.children().isEmpty()) limited = true;
        if (depth < MAX_DEPTH) {
            for (Row child : row.children()) {
                if (flattened.size() >= LIMIT) {
                    limited = true;
                    break;
                }
                node.children.add(add(child, node.id, depth + 1));
            }
        }
        return node;
    }

    private void position(Mutable node) {
        if (node.children.isEmpty()) {
            node.centerY = MARGIN + CARD_HEIGHT / 2
                    + leafCount++ * (CARD_HEIGHT + ROW_GAP);
            return;
        }
        for (Mutable child : node.children) position(child);
        node.centerY = (node.children.get(0).centerY
                + node.children.get(node.children.size() - 1).centerY) / 2;
    }
}
