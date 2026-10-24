// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned, bounded protocol. Parse completely before changing CDT metadata. */
public record EditorSettings(Map<String, Source> sources) {
    public record Entry(char kind, String name, String value) {}
    public record Source(String language, List<Entry> entries) {}
    public static final int MAX_BYTES = 8 * 1024 * 1024;

    public static EditorSettings parse(String text) throws IOException {
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IOException("Editor settings exceed size limit");
        var lines = text.lines().toList();
        if (lines.size() < 3 || !lines.getFirst().equals("ZEW_EDITOR_1")) throw new IOException("Unsupported editor settings protocol");
        var languages = new LinkedHashMap<String, String>();
        var entries = new LinkedHashMap<String, List<Entry>>();
        int count = 0;
        try {
            for (int i = 1; i < lines.size() - 1; i++) {
                String[] row = lines.get(i).split("\t", -1);
                if (row.length != 4 || row[0].length() != 1 || ++count > 100000) throw new IOException("Invalid editor record");
                char kind = row[0].charAt(0);
                String file = decode(row[1]), name = decode(row[2]), value = decode(row[3]);
                if (file.isBlank() || file.startsWith("/") || file.contains("\\") || file.contains(":")
                        || List.of(file.split("/", -1)).stream().anyMatch(s -> s.isEmpty() || s.equals(".") || s.equals("..")))
                    throw new IOException("Unsafe editor source path");
                if (kind == 'F') {
                    if ((!name.equals("c") && !name.equals("c++")) || !value.isEmpty() || languages.putIfAbsent(file, name) != null)
                        throw new IOException("Ambiguous editor source settings");
                    entries.put(file, new ArrayList<>());
                    if (entries.size() > 512) throw new IOException("Too many editor source files");
                } else {
                    if (!entries.containsKey(file) || "IHAD".indexOf(kind) < 0 || name.isBlank()) throw new IOException("Invalid editor setting");
                    if (kind != 'D' && (!value.isEmpty() || !(name.startsWith("/") || name.matches("[A-Za-z]:[/\\\\].*") || name.startsWith("\\\\"))))
                        throw new IOException("Editor include path must be absolute");
                    entries.get(file).add(new Entry(kind, name, value));
                }
            }
            String[] done = lines.getLast().split("\t", -1);
            if (entries.isEmpty() || done.length != 3 || !done[0].equals("DONE") || Integer.parseInt(done[1]) != entries.size()
                    || Integer.parseInt(done[2]) != count) throw new IOException("Truncated editor settings");
        } catch (IllegalArgumentException error) { throw new IOException("Malformed editor settings", error); }
        var result = new LinkedHashMap<String, Source>();
        entries.forEach((file, values) -> result.put(file, new Source(languages.get(file), List.copyOf(values))));
        return new EditorSettings(java.util.Collections.unmodifiableMap(result));
    }
    private static String decode(String value) throws IOException {
        String decoded = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        if (decoded.indexOf('\0') >= 0 || decoded.indexOf('\r') >= 0 || decoded.indexOf('\n') >= 0) throw new IOException("Invalid editor field");
        return decoded;
    }
}
