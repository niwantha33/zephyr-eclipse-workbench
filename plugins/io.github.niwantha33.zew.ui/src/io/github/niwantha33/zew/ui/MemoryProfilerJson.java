// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Small, dependency-free, bounded JSON-to-table adapter for the Step-1 report.
 * It does not infer memory properties, validate a firmware build, or execute Python.
 * Values retain their raw JSON representation, including string quoting.
 */
public final class MemoryProfilerJson {
    public static final int MAX_CHARACTERS = 8 * 1024 * 1024;
    private static final int MAX_ROWS = 50000;
    private static final int MAX_DEPTH = 32;
    private static final Pattern NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");

    public record Row(String field, String rawValue) {}

    private MemoryProfilerJson() {}

    /** Reuse the bounded JSON tokenizer for other explicit report schemas. */
    public static List<Row> parseFields(String json) {
        if (json == null || json.length() > MAX_CHARACTERS)
            throw new IllegalArgumentException("JSON report is missing or exceeds 8 MiB");
        Parser parser = new Parser(json);
        parser.space();
        if (!parser.peek('{'))
            throw new IllegalArgumentException("JSON report must be an object");
        parser.value("", 0);
        parser.space();
        if (parser.pos != json.length())
            throw new IllegalArgumentException("Unexpected data after JSON report");
        return List.copyOf(parser.rows);
    }

    /** Decode one JSON string token, rejecting trailing content. */
    public static String unquote(String token) {
        Parser parser = new Parser(token);
        parser.space();
        String value = parser.quoted();
        parser.space();
        if (parser.pos != token.length())
            throw new IllegalArgumentException("Invalid JSON string token");
        return value;
    }

    public static List<Row> parse(String json) {
        List<Row> rows = parseFields(json);
        if (!has(rows, "schema_version", "1")
                || !exists(rows, "status") || !exists(rows, "mpu.status")
                || !existsPrefix(rows, "memory_regions")
                || !existsPrefix(rows, "output_sections"))
            throw new IllegalArgumentException("Unsupported or incomplete memory-profiler JSON v1");
        return rows;
    }

    private static boolean has(List<Row> rows, String key, String value) {
        return rows.stream().anyMatch(r -> r.field().equals(key) && r.rawValue().equals(value));
    }

    private static boolean exists(List<Row> rows, String key) {
        return rows.stream().anyMatch(r -> r.field().equals(key));
    }

    private static boolean existsPrefix(List<Row> rows, String key) {
        return rows.stream().anyMatch(r -> r.field().equals(key) ||
                r.field().startsWith(key + "["));
    }

    private static final class Parser {
        private final String json;
        private final List<Row> rows = new ArrayList<>();
        private int pos;

        Parser(String json) { this.json = json; }

        void space() {
            while (pos < json.length() && " \t\r\n".indexOf(json.charAt(pos)) >= 0) pos++;
        }

        boolean peek(char ch) { return pos < json.length() && json.charAt(pos) == ch; }

        void take(char ch) {
            space();
            if (!peek(ch)) throw error("Expected '" + ch + "'");
            pos++;
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at character " + pos);
        }

        void add(String path, String value) {
            if (rows.size() >= MAX_ROWS)
                throw error("Memory report contains too many JSON fields");
            rows.add(new Row(path, value));
        }

        void value(String path, int depth) {
            if (depth > MAX_DEPTH) throw error("JSON nesting exceeds limit");
            space();
            if (peek('{')) {
                pos++;
                space();
                if (peek('}')) { pos++; add(path, "{}"); return; }
                while (true) {
                    space();
                    String name = quoted();
                    take(':');
                    value(path.isEmpty() ? name : path + "." + name, depth + 1);
                    space();
                    if (peek('}')) { pos++; return; }
                    take(',');
                }
            }
            if (peek('[')) {
                pos++;
                space();
                if (peek(']')) { pos++; add(path, "[]"); return; }
                int index = 0;
                while (true) {
                    value(path + "[" + index++ + "]", depth + 1);
                    space();
                    if (peek(']')) { pos++; return; }
                    take(',');
                }
            }
            if (peek('"')) {
                int start = pos;
                quoted();
                add(path, json.substring(start, pos));
                return;
            }
            int start = pos;
            while (pos < json.length() && ",]} \t\r\n".indexOf(json.charAt(pos)) < 0) pos++;
            if (start == pos) throw error("Expected JSON value");
            String token = json.substring(start, pos);
            if (!(token.equals("true") || token.equals("false") || token.equals("null")
                    || NUMBER.matcher(token).matches()))
                throw error("Invalid JSON scalar");
            add(path, token);
        }

        String quoted() {
            if (!peek('"')) throw error("Expected JSON string");
            pos++;
            StringBuilder decoded = new StringBuilder();
            while (pos < json.length()) {
                char ch = json.charAt(pos++);
                if (ch == '"') return decoded.toString();
                if (ch < 0x20) throw error("Control character in JSON string");
                if (ch != '\\') { decoded.append(ch); continue; }
                if (pos >= json.length()) throw error("Incomplete JSON escape");
                char escape = json.charAt(pos++);
                switch (escape) {
                    case '"' -> decoded.append('"');
                    case '\\' -> decoded.append('\\');
                    case '/' -> decoded.append('/');
                    case 'b' -> decoded.append('\b');
                    case 'f' -> decoded.append('\f');
                    case 'n' -> decoded.append('\n');
                    case 'r' -> decoded.append('\r');
                    case 't' -> decoded.append('\t');
                    case 'u' -> {
                        if (pos + 4 > json.length()) throw error("Incomplete Unicode escape");
                        String hex = json.substring(pos, pos + 4);
                        if (!hex.matches("[0-9a-fA-F]{4}")) throw error("Invalid Unicode escape");
                        decoded.append((char) Integer.parseInt(hex, 16));
                        pos += 4;
                    }
                    default -> throw error("Invalid JSON escape");
                }
            }
            throw error("Unterminated JSON string");
        }
    }
}
