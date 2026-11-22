// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * A bounded, source-backed static function-call graph. An edge only exists
 * when CDT resolves an actual call expression to a known project definition.
 * This is not an execution trace or an indirect/virtual dispatch graph.
 */
public final class UmlProjectGraph {
    public static final int MAX_FILES = 24;
    public static final int MAX_NODES = 64;
    public static final int MAX_EDGES = 96;

    private UmlProjectGraph() {}

    public record Node(String id, String label, String file, int offset) {
        public Node {
            Objects.requireNonNull(id);
            Objects.requireNonNull(label);
            Objects.requireNonNull(file);
            if (id.isBlank() || label.isBlank() || file.isBlank() || offset < 0)
                throw new IllegalArgumentException("Invalid source-backed function node");
        }
    }

    public record Edge(String from, String to) {
        public Edge {
            Objects.requireNonNull(from);
            Objects.requireNonNull(to);
            if (from.isBlank() || to.isBlank())
                throw new IllegalArgumentException("Missing function endpoint");
        }
    }

    public record Snapshot(String project, List<Node> nodes, List<Edge> edges,
                           int scannedFiles, boolean incomplete, String explanation) {
        public Snapshot {
            Objects.requireNonNull(project);
            Objects.requireNonNull(explanation);
            if (scannedFiles < 0) throw new IllegalArgumentException("Invalid file count");
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
        }
        public boolean available() { return !nodes.isEmpty(); }
    }

    public static Snapshot unavailable(String project, String explanation) {
        return new Snapshot(project, List.of(), List.of(), 0, false, explanation);
    }

    /** Removes dangling edges, duplicate definitions and duplicate call pairs. */
    public static Snapshot of(String project, List<Node> candidates, List<Edge> calls,
                              int scannedFiles, boolean incomplete, String explanation) {
        Objects.requireNonNull(candidates);
        Objects.requireNonNull(calls);
        LinkedHashMap<String, Node> unique = new LinkedHashMap<>();
        for (Node node : candidates) {
            if (unique.size() >= MAX_NODES && !unique.containsKey(node.id())) {
                incomplete = true;
                continue;
            }
            unique.putIfAbsent(node.id(), node);
        }
        var edges = new ArrayList<Edge>();
        var seen = new HashSet<Edge>();
        for (Edge edge : calls) {
            if (!unique.containsKey(edge.from()) || !unique.containsKey(edge.to())
                    || edge.from().equals(edge.to()) || !seen.add(edge)) continue;
            if (edges.size() == MAX_EDGES) {
                incomplete = true;
                break;
            }
            edges.add(edge);
        }
        return new Snapshot(project, List.copyOf(unique.values()), edges,
                scannedFiles, incomplete, explanation);
    }

    /** PlantUML contains only identifiers derived from validated CDT observations. */
    public static String plantUml(Snapshot graph) {
        StringBuilder text = new StringBuilder("@startuml\n");
        text.append("' Project-wide static CDT evidence; no runtime call order\n")
            .append("left to right direction\nskinparam classAttributeIconSize 0\n");
        var aliases = new LinkedHashMap<String, String>();
        for (int i = 0; i < graph.nodes().size(); i++) {
            Node n = graph.nodes().get(i);
            String alias = "ZEW_FN_" + i;
            aliases.put(n.id(), alias);
            text.append("class \"").append(safe(n.label())).append("\\n")
                .append(safe(n.file())).append("\" as ").append(alias)
                .append(" <<function>>\n");
        }
        for (Edge e : graph.edges()) {
            String from = aliases.get(e.from()), to = aliases.get(e.to());
            if (from != null && to != null)
                text.append(from).append(" --> ").append(to)
                    .append(" : direct call\n");
        }
        text.append("@enduml\n");
        return text.toString();
    }

    private static String safe(String input) {
        return input.replaceAll("[\\p{Cntrl}]", " ").replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("{", "&#123;")
                .replace("}", "&#125;").replace("@", "&#64;")
                .replace("!", "&#33;").replace("\\", "&#92;");
    }
}
