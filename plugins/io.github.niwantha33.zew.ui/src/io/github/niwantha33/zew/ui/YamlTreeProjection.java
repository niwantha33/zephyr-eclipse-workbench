// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.niwantha33.zew.ui.YamlDocumentModel.Entry;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Mapping;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Node;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Range;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Scalar;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Sequence;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Snapshot;

/**
 * Read-only tree projection of a verified Step-3 observation.
 *
 * Contains no SWT, workspace, filesystem, schema inference or parser. The
 * view uses these immutable rows to render exactly the original YAML order.
 */
public final class YamlTreeProjection {
    private static final int VALUE_PREVIEW_CODEPOINTS = 160;

    public record Row(String label, String kind, String preview, Range sourceRange,
                      List<Row> children) {
        public Row {
            Objects.requireNonNull(label);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(preview);
            Objects.requireNonNull(sourceRange);
            children = List.copyOf(children);
        }

        public String location() {
            return sourceRange.start().line() + ":" + sourceRange.start().column();
        }
    }

    private YamlTreeProjection() {}

    /** No fabricated nodes for invalid, unavailable or empty observations. */
    public static List<Row> rows(Snapshot snapshot) {
        if (snapshot == null || !snapshot.parsed() || snapshot.root() == null)
            return List.of();
        return List.of(row("(document)", snapshot.root()));
    }

    private static Row row(String label, Node node) {
        if (node instanceof Scalar scalar)
            return new Row(label, scalar.scalarType(), preview(scalar.value()),
                    scalar.range(), List.of());
        if (node instanceof Mapping mapping) {
            List<Row> children = new ArrayList<>(mapping.entries().size());
            for (Entry entry : mapping.entries())
                children.add(row(entry.key(), entry.value()));
            return new Row(label, "mapping", mapping.entries().size() + " entries",
                    mapping.range(), children);
        }
        if (node instanceof Sequence sequence) {
            List<Row> children = new ArrayList<>(sequence.items().size());
            for (int index = 0; index < sequence.items().size(); index++)
                children.add(row("[" + index + "]", sequence.items().get(index)));
            return new Row(label, "sequence", sequence.items().size() + " items",
                    sequence.range(), children);
        }
        throw new IllegalArgumentException("Unsupported YAML observation node");
    }

    /** Keep huge/multiline scalars from overwhelming an SWT cell. */
    public static String preview(String value) {
        Objects.requireNonNull(value);
        String readable = value.replace("\\", "\\\\").replace("\r", "\\r")
                .replace("\n", "\\n").replace("\t", "\\t");
        int codepoints = readable.codePointCount(0, readable.length());
        if (codepoints <= VALUE_PREVIEW_CODEPOINTS) return readable;
        return readable.substring(0, readable.offsetByCodePoints(0, VALUE_PREVIEW_CODEPOINTS))
                + "…";
    }
}
