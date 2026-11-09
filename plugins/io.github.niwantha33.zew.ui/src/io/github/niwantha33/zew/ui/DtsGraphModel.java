// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable evidence-only topology and a bounded focused graph layout. No SWT dependencies. */
public final class DtsGraphModel {
    public static final int MAX_NODES = 3500;
    public static final int MAX_EDGES = 6000;
    public static final int MAX_VISIBLE = 72;
    public static final int CARD_WIDTH = 240;
    public static final int CARD_HEIGHT = 82;
    public static final int COLUMN_STEP = 326;
    public static final int ROW_STEP = 110;

    public record Node(String path, String name, String status, String compatible,
                       String labels, int line) {}
    public record Edge(String source, String target, String kind, String property, String detail) {}
    public record Snapshot(String state, String explanation, String sha256, Path generated,
                           List<Node> nodes, List<Edge> edges) {
        public Snapshot {
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
        }
        public boolean observed() {
            return "built".equals(state) || "hierarchy-only".equals(state);
        }
    }
    public record Card(Node node, int x, int y, int column) {}
    public record Layout(List<Card> cards, List<Edge> edges, int width, int height,
                         String focus, boolean limited, String message) {
        public Layout {
            cards = List.copyOf(cards);
            edges = List.copyOf(edges);
        }
    }
    private DtsGraphModel() {}

    public static Snapshot unavailable(String explanation) {
        return new Snapshot("unavailable", explanation, "", null, List.of(), List.of());
    }

    /** Parse strict, bounded, base64-encoded records from the selected Zephyr checkout. */
    public static Snapshot parse(String output, Path generated) {
        if (output == null || output.length() > 3_000_000)
            return unavailable("Graph response exceeds the safety limit.");
        String[] lines = output.split("\\R", -1);
        if (lines.length < 2 || lines.length > MAX_NODES + MAX_EDGES + 5
                || !"ZEW-DTSGRAPH-1".equals(lines[0]))
            return unavailable("Graph observation has an unexpected format.");
        try {
            String state = null, sha = "", reason = "";
            List<Node> nodes = new ArrayList<>();
            List<Edge> edges = new ArrayList<>();
            Map<String, Node> paths = new HashMap<>();
            Set<Edge> distinct = new HashSet<>();
            for (int i = 1; i < lines.length; ++i) {
                if (lines[i].isEmpty()) continue;
                String[] values = lines[i].split("\\t", -1);
                String tag = values[0];
                for (int j = 1; j < values.length; j++) {
                    byte[] bytes = Base64.getDecoder().decode(values[j]);
                    if (bytes.length > 1024)
                        return unavailable("Graph field is too large.");
                    values[j] = new String(bytes, StandardCharsets.UTF_8);
                }
                switch (tag) {
                    case "S" -> {
                        if (values.length != 4 || state != null)
                            return unavailable("Duplicate or malformed graph status.");
                        state = values[1];
                        sha = values[2];
                        reason = values[3];
                    }
                    case "N" -> {
                        if (values.length != 7 || state == null || nodes.size() >= MAX_NODES)
                            return unavailable("Malformed or oversized graph node set.");
                        int line = Integer.parseInt(values[6]);
                        if (line < 1 || line > 2_000_000 || !isNodePath(values[1]))
                            return unavailable("Unsafe generated Devicetree node path.");
                        Node node = new Node(values[1], values[2], values[3],
                                values[4], values[5], line);
                        if (paths.putIfAbsent(node.path(), node) != null)
                            return unavailable("Duplicate generated Devicetree node.");
                        nodes.add(node);
                    }
                    case "E" -> {
                        if (values.length != 6 || state == null || edges.size() >= MAX_EDGES)
                            return unavailable("Malformed or oversized graph edge set.");
                        Edge edge = new Edge(values[1], values[2], values[3], values[4], values[5]);
                        if (!Set.of("phandle", "phandle-array", "gpio", "pinctrl",
                                "clock", "interrupt", "reset", "path").contains(edge.kind())
                                || !distinct.add(edge))
                            return unavailable("Unknown or duplicate Devicetree relation.");
                        edges.add(edge);
                    }
                    default -> {
                        return unavailable("Unexpected graph observation record.");
                    }
                }
            }
            if (state == null || !Set.of("built", "hierarchy-only", "unavailable",
                    "mismatch", "no-build").contains(state))
                return unavailable("Unknown graph observation state.");
            if (Set.of("built", "hierarchy-only").contains(state)) {
                if (sha.length() != 64 || !sha.matches("[0-9a-f]{64}")
                        || !paths.containsKey("/"))
                    return unavailable("Unverified generated DTS identity.");
                for (Edge edge : edges)
                    if (!paths.containsKey(edge.source()) || !paths.containsKey(edge.target()))
                        return unavailable("Graph contains an unresolved reference target.");
            } else if (!nodes.isEmpty() || !edges.isEmpty()) {
                return unavailable("Unavailable observation included unsupported node claims.");
            }
            return new Snapshot(state, reason, sha,
                    Set.of("built", "hierarchy-only").contains(state) ? generated : null,
                    nodes, edges);
        } catch (RuntimeException malformed) {
            return unavailable("Malformed Devicetree graph observation.");
        }
    }

    private static boolean isNodePath(String path) {
        return path != null && path.startsWith("/") && path.length() <= 500
                && !path.contains("//") && !path.contains("/../") && !path.contains("/./");
    }

    private static String parent(String path) {
        if (path.equals("/")) return null;
        int split = path.lastIndexOf('/');
        return split <= 0 ? "/" : path.substring(0, split);
    }

    /**
     * Selected node, verified ancestors, immediate children, outgoing typed
     * references, and consumers of the selected node. Never claim that omitted
     * nodes/edges are absent from the full DTS; label this as a focused view.
     */
    public static Layout focus(Snapshot snapshot, String selected) {
        if (snapshot == null || !snapshot.observed())
            return new Layout(List.of(), List.of(), 0, 0, "", false,
                    snapshot == null ? "Select an exact built Zephyr application."
                            : snapshot.explanation());
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (Node node : snapshot.nodes()) nodes.put(node.path(), node);
        String focus = selected != null && nodes.containsKey(selected) ? selected
                : nodes.containsKey("/soc") ? "/soc" : "/";
        LinkedHashMap<String, Integer> columns = new LinkedHashMap<>();
        // Ancestors are verified by actual node identity, never a guessed path.
        ArrayList<String> ancestors = new ArrayList<>();
        for (String path = parent(focus); path != null && nodes.containsKey(path);
                path = parent(path)) ancestors.add(path);
        Collections.reverse(ancestors);
        for (String path : ancestors) columns.put(path, 0);
        columns.put(focus, 1);
        boolean limited = false;
        for (Node node : snapshot.nodes()) {
            if (focus.equals(parent(node.path()))) {
                if (columns.size() >= MAX_VISIBLE) { limited = true; break; }
                columns.putIfAbsent(node.path(), 2);
            }
        }
        for (Edge edge : snapshot.edges()) {
            // Only the focused consumer and its direct children get outgoing
            // phandle expansion, not every unrelated ancestor in the tree.
            if (focus.equals(edge.source()) || focus.equals(parent(edge.source()))) {
                if (!columns.containsKey(edge.target())) {
                    // Include actual ancestors of the referenced controller,
                    // so a GPIO does not appear as a disconnected orphan.
                    ArrayList<String> targetAncestors = new ArrayList<>();
                    for (String p = parent(edge.target()); p != null && nodes.containsKey(p);
                            p = parent(p)) targetAncestors.add(p);
                    Collections.reverse(targetAncestors);
                    for (String path : targetAncestors) {
                        if (columns.containsKey(path)) continue;
                        if (columns.size() >= MAX_VISIBLE) { limited = true; break; }
                        columns.put(path, 2);
                    }
                    if (columns.size() >= MAX_VISIBLE) { limited = true; break; }
                    columns.put(edge.target(), 3);
                }
            }
            if (focus.equals(edge.target()) && !columns.containsKey(edge.source())) {
                if (columns.size() >= MAX_VISIBLE) { limited = true; break; }
                columns.put(edge.source(), 2);
            }
        }
        Map<Integer, Integer> rows = new HashMap<>();
        ArrayList<Card> cards = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : columns.entrySet()) {
            int col = entry.getValue();
            int row = rows.merge(col, 1, Integer::sum) - 1;
            cards.add(new Card(nodes.get(entry.getKey()), 30 + col * COLUMN_STEP,
                    30 + row * ROW_STEP, col));
        }
        List<Edge> visibleEdges = new ArrayList<>();
        for (String path : columns.keySet()) {
            String p = parent(path);
            if (p != null && columns.containsKey(p))
                visibleEdges.add(new Edge(p, path, "hierarchy", "", ""));
        }
        for (Edge edge : snapshot.edges()) {
            if (columns.containsKey(edge.source()) && columns.containsKey(edge.target()))
                visibleEdges.add(edge);
        }
        int maximumRows = rows.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        int maximumColumn = rows.keySet().stream().mapToInt(Integer::intValue).max().orElse(1);
        return new Layout(cards, visibleEdges,
                65 + maximumColumn * COLUMN_STEP + CARD_WIDTH,
                65 + maximumRows * ROW_STEP, focus, limited,
                "Focused generated Devicetree; " + (limited ? "some nodes are hidden by the 72-card limit."
                        : "other nodes are outside this neighborhood.")
                + ("hierarchy-only".equals(snapshot.state())
                        ? " Binding-aware links unavailable." : " Runtime wiring not measured."));
    }
}
