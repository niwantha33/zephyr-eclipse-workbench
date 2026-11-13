// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A bounded, immutable projection of CDT's source outline. This is source
 * structure, NOT a whole-program dependency graph or a runtime observation.
 * Contains no SWT or Eclipse dependencies so the projection is testable in CI.
 */
public final class UmlClassModel {
    public static final int MAX_TYPES = 32;
    public static final int MAX_MEMBERS = 18;
    private static final Set<String> KINDS = Set.of("class", "struct", "union");

    private UmlClassModel() {}

    public record Type(String kind, String name, int offset,
                       List<String> attributes, List<String> operations) {
        public Type {
            if (!KINDS.contains(kind)) throw new IllegalArgumentException("Unsupported type kind");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Empty type name");
            if (offset < 0) throw new IllegalArgumentException("Invalid source offset");
            attributes = cleanMembers(attributes);
            operations = cleanMembers(operations);
            name = normalized(name);
        }
    }

    public record Snapshot(String source, List<Type> types, String explanation, boolean truncated) {
        public Snapshot {
            source = normalized(source);
            types = List.copyOf(types);
            explanation = normalized(explanation);
        }
        public boolean available() {
            return !types.isEmpty();
        }
    }

    public static Snapshot of(String source, List<Type> candidates, String explanation) {
        if (candidates == null) return unavailable(source, "CDT did not provide source structures.");
        boolean truncated = candidates.size() > MAX_TYPES;
        return new Snapshot(source, candidates.subList(0, Math.min(MAX_TYPES, candidates.size())),
                explanation, truncated);
    }

    public static Snapshot unavailable(String source, String why) {
        return new Snapshot(source, List.of(), why, false);
    }

    private static List<String> cleanMembers(List<String> members) {
        if (members == null) return List.of();
        var result = new ArrayList<String>();
        for (String member : members) {
            if (result.size() == MAX_MEMBERS) break;
            String value = normalized(member);
            if (!value.isBlank()) result.add(value);
        }
        return List.copyOf(result);
    }

    private static String normalized(String input) {
        if (input == null) return "";
        return input.replaceAll("[\\p{Cntrl}]", " ").strip();
    }

    /**
     * Portable text export. The live diagram is drawn by native SWT, without
     * PlantUML, Graphviz, a server, or downloaded dependencies.
     */
    public static String plantUml(Snapshot snapshot) {
        StringBuilder out = new StringBuilder("@startuml\n");
        out.append("' Source-outline diagram from Eclipse CDT; no inferred relationships\n")
           .append("skinparam classAttributeIconSize 0\n");
        for (int i = 0; i < snapshot.types().size(); i++) {
            Type type = snapshot.types().get(i);
            out.append("class \"").append(safe(type.name())).append("\" as ZEW_")
               .append(i).append(" <<").append(type.kind()).append(">> {\n");
            for (String field : type.attributes()) out.append("  ").append(safe(field)).append("\n");
            if (!type.attributes().isEmpty() && !type.operations().isEmpty())
                out.append("  --\n");
            for (String method : type.operations()) out.append("  ").append(safe(method)).append("\n");
            out.append("}\n");
        }
        out.append("@enduml\n");
        return out.toString();
    }

    /** Do not let source identifiers turn into PlantUML directives or blocks. */
    private static String safe(String text) {
        return normalized(text).replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;")
                .replace("{", "&#123;").replace("}", "&#125;")
                .replace("@", "&#64;").replace("!", "&#33;")
                .replace("\\", "&#92;");
    }
}
