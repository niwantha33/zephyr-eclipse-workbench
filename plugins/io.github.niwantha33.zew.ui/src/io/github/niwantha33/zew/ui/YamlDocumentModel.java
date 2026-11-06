// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable read-only Java contract for zew.yaml-observation/1.
 *
 * Reads observations produced by the headless Python collector; this is NOT
 * a second YAML parser and does not establish Zephyr schema validity. No
 * Eclipse, OSGi, filesystem, subprocess or third-party JSON dependencies.
 */
public final class YamlDocumentModel {
    private static final int MAX_JSON_CHARS = 4 * 1024 * 1024;
    private static final int MAX_JSON_DEPTH = 64;
    private static final int MAX_JSON_VALUES = 50000;
    private static final int MAX_YAML_NODES = 4096;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> STATES = Set.of("parsed", "invalid", "unsupported", "unavailable");
    private static final Set<String> ROOTS = Set.of("zephyr", "module", "application", "unknown");
    private static final Set<String> SCALARS = Set.of("str", "int", "float", "bool", "null", "timestamp", "binary");

    public record Position(int line, int column) {
        public Position {
            if (line < 1 || column < 1) throw invalid();
        }
    }
    public record Range(Position start, Position end) {
        public Range {
            if (start == null || end == null || start.line() > end.line()
                    || (start.line() == end.line() && start.column() > end.column()))
                throw invalid();
        }
    }
    public sealed interface Node permits Scalar, Sequence, Mapping {
        Range range();
    }
    /** YAML scalar spelling is preserved; no implicit Java/YAML type conversion. */
    public record Scalar(String scalarType, String value, Range range) implements Node {
        public Scalar {
            if (!SCALARS.contains(scalarType) || value == null || range == null) throw invalid();
        }
    }
    public record Sequence(List<Node> items, Range range) implements Node {
        public Sequence {
            if (items == null || range == null) throw invalid();
            items = List.copyOf(items);
        }
    }
    public record Entry(String key, Range keyRange, Node value) {
        public Entry {
            if (key == null || keyRange == null || value == null) throw invalid();
        }
    }
    public record Mapping(List<Entry> entries, Range range) implements Node {
        public Mapping {
            if (entries == null || range == null) throw invalid();
            entries = List.copyOf(entries);
        }
    }
    /** Root is null for a parsed empty document or any non-parsed status. */
    public record Snapshot(String status, String reason, String kind, String classificationReason,
                           String sourceRoot, String relativePath, String sha256,
                           Integer sizeBytes, Node root) {
        public Snapshot {
            if (!STATES.contains(status) || reason == null || kind == null
                    || classificationReason == null || !ROOTS.contains(sourceRoot)) throw invalid();
            if (sizeBytes != null && (sizeBytes < 0 || sizeBytes > 256 * 1024)) throw invalid();
            if (sha256 != null && !SHA256.matcher(sha256).matches()) throw invalid();
            if (relativePath != null && relativePath.length() > 4096) throw invalid();
            if (!"parsed".equals(status) && root != null) throw invalid();
            if ("parsed".equals(status) && sha256 == null) throw invalid();
        }
        public boolean parsed() { return "parsed".equals(status); }
    }

    private YamlDocumentModel() {}

    /** Reject malformed/mismatched reports without copying private YAML into errors. */
    public static Snapshot parse(String json) {
        if (json == null || json.length() > MAX_JSON_CHARS)
            throw invalid();
        var reader = new JsonReader(json);
        Object decoded = reader.value(0);
        reader.space();
        if (reader.pos != json.length()) throw invalid();
        Map<String, Object> report = object(decoded);
        if (!"zew.yaml-observation/1".equals(string(report, "schema"))) throw invalid();
        String status = string(report, "status");
        String reason = string(report, "reason");
        Map<String, Object> classification = object(required(report, "classification"));
        Map<String, Object> provenance = object(required(report, "provenance"));
        List<Object> documents = array(required(report, "documents"));
        if (documents.size() > 1 || (!"parsed".equals(status) && !documents.isEmpty())) throw invalid();
        int[] count = {0};
        Node root = documents.isEmpty() ? null
                : node(required(object(documents.get(0)), "root"), 0, count);
        Integer size = optionalInt(provenance, "size_bytes");
        String sha = optionalString(provenance, "sha256");
        if ("parsed".equals(status) && size == null) throw invalid();
        return new Snapshot(status, reason,
                string(classification, "kind"), string(classification, "reason"),
                string(provenance, "source_root"), optionalString(provenance, "relative_path"),
                sha, size, root);
    }

    private static Node node(Object input, int depth, int[] count) {
        if (depth > 32 || ++count[0] > MAX_YAML_NODES) throw invalid();
        Map<String, Object> entry = object(input);
        Range range = range(required(entry, "range"));
        return switch (string(entry, "type")) {
            case "scalar" -> new Scalar(string(entry, "scalar_type"), string(entry, "value"), range);
            case "sequence" -> {
                List<Node> children = new ArrayList<>();
                for (Object child : array(required(entry, "items")))
                    children.add(node(child, depth + 1, count));
                yield new Sequence(children, range);
            }
            case "mapping" -> {
                List<Entry> children = new ArrayList<>();
                for (Object child : array(required(entry, "entries"))) {
                    Map<String, Object> row = object(child);
                    children.add(new Entry(string(row, "key"), range(required(row, "key_range")),
                            node(required(row, "value"), depth + 1, count)));
                }
                yield new Mapping(children, range);
            }
            default -> throw invalid();
        };
    }

    private static Range range(Object input) {
        Map<String, Object> value = object(input);
        return new Range(position(required(value, "start")), position(required(value, "end")));
    }

    private static Position position(Object input) {
        Map<String, Object> value = object(input);
        return new Position(integer(value, "line"), integer(value, "column"));
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = required(map, key);
        if (!(value instanceof String result)) throw invalid();
        return result;
    }
    private static String optionalString(Map<String, Object> map, String key) {
        Object value = required(map, key);
        if (value == null) return null;
        if (!(value instanceof String result)) throw invalid();
        return result;
    }
    private static int integer(Map<String, Object> map, String key) {
        Object value = required(map, key);
        if (!(value instanceof Integer integer)) throw invalid();
        return integer;
    }
    private static Integer optionalInt(Map<String, Object> map, String key) {
        Object value = required(map, key);
        if (value == null) return null;
        if (!(value instanceof Integer integer)) throw invalid();
        return integer;
    }
    private static Object required(Map<String, Object> map, String key) {
        if (!map.containsKey(key)) throw invalid();
        return map.get(key);
    }
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> raw)) throw invalid();
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String name)) throw invalid();
            result.put(name, entry.getValue());
        }
        return result;
    }
    private static List<Object> array(Object value) {
        if (!(value instanceof List<?> list)) throw invalid();
        return new ArrayList<>(list);
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid or unsupported YAML observation v1");
    }

    /** Small strict JSON reader, bounded independently from the YAML node model. */
    private static final class JsonReader {
        private final String text;
        private int pos;
        private int values;
        JsonReader(String text) { this.text = text; }
        void space() {
            while (pos < text.length() && " \n\r\t".indexOf(text.charAt(pos)) >= 0) pos++;
        }
        boolean take(char expected) {
            space();
            if (pos < text.length() && text.charAt(pos) == expected) { pos++; return true; }
            return false;
        }
        void expect(char expected) { if (!take(expected)) throw invalid(); }
        Object value(int depth) {
            if (depth > MAX_JSON_DEPTH || ++values > MAX_JSON_VALUES) throw invalid();
            space();
            if (take('{')) {
                Map<String, Object> map = new LinkedHashMap<>();
                if (take('}')) return map;
                do {
                    space();
                    String key = quoted();
                    expect(':');
                    Object value = value(depth + 1);
                    if (map.containsKey(key)) throw invalid();
                    map.put(key, value);
                    if (take('}')) return map;
                    expect(',');
                } while (true);
            }
            if (take('[')) {
                List<Object> list = new ArrayList<>();
                if (take(']')) return list;
                do {
                    list.add(value(depth + 1));
                    if (take(']')) return list;
                    expect(',');
                } while (true);
            }
            if (pos >= text.length()) throw invalid();
            if (text.charAt(pos) == '"') return quoted();
            for (String literal : List.of("true", "false", "null")) {
                if (text.startsWith(literal, pos)) {
                    pos += literal.length();
                    return switch (literal) { case "true" -> Boolean.TRUE;
                        case "false" -> Boolean.FALSE; default -> null; };
                }
            }
            int start = pos;
            if (text.charAt(pos) == '-') pos++;
            if (pos >= text.length()) throw invalid();
            if (text.charAt(pos) == '0') pos++;
            else {
                if (text.charAt(pos) < '1' || text.charAt(pos) > '9') throw invalid();
                do { pos++; } while (pos < text.length() && text.charAt(pos) >= '0'
                        && text.charAt(pos) <= '9');
            }
            if (pos < text.length() && ".eE".indexOf(text.charAt(pos)) >= 0) throw invalid();
            try { return Integer.valueOf(text.substring(start, pos)); }
            catch (NumberFormatException error) { throw invalid(); }
        }
        String quoted() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            boolean closed = false;
            while (pos < text.length()) {
                char ch = text.charAt(pos++);
                if (ch == '"') { closed = true; break; }
                if (ch < 0x20) throw invalid();
                if (ch != '\\') {
                    if (Character.isHighSurrogate(ch)) {
                        if (pos >= text.length() || !Character.isLowSurrogate(text.charAt(pos)))
                            throw invalid();
                        builder.append(ch).append(text.charAt(pos++));
                    } else if (Character.isLowSurrogate(ch)) throw invalid();
                    else builder.append(ch);
                    continue;
                }
                if (pos >= text.length()) throw invalid();
                char escape = text.charAt(pos++);
                switch (escape) {
                    case '"' -> builder.append('"');
                    case '\\' -> builder.append('\\');
                    case '/' -> builder.append('/');
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) throw invalid();
                        int cp;
                        try { cp = Integer.parseInt(text.substring(pos, pos + 4), 16); }
                        catch (NumberFormatException error) { throw invalid(); }
                        pos += 4;
                        char unit = (char) cp;
                        if (Character.isHighSurrogate(unit)) {
                            if (pos + 6 > text.length() || text.charAt(pos) != '\\'
                                    || text.charAt(pos + 1) != 'u') throw invalid();
                            int low;
                            try { low = Integer.parseInt(text.substring(pos + 2, pos + 6), 16); }
                            catch (NumberFormatException error) { throw invalid(); }
                            if (!Character.isLowSurrogate((char) low)) throw invalid();
                            builder.append(unit).append((char) low);
                            pos += 6;
                        } else if (Character.isLowSurrogate(unit)) throw invalid();
                        else builder.append(unit);
                    }
                    default -> throw invalid();
                }
            }
            if (!closed) throw invalid();
            return builder.toString();
        }
    }
}
