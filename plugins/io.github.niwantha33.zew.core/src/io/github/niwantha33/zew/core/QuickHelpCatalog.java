// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, offline Zephyr help index. No Eclipse dependency or network access.
 * Every fact is carried by an attributed, pinned upstream documentation record.
 */
public final class QuickHelpCatalog {
    private static final String ROOT = "/quick-help/v1/";
    private static final int MAX_DOCUMENT_CHARS = 24000;
    private static final int MAX_ITEMS = 128;
    private static final List<String> SECTIONS = List.of(
            "Explanation", "Parameters", "Example", "Requirements", "Mistakes");

    public record Entry(String id, String title, String category, String version,
            List<String> aliases, String signature, String source, List<String> related,
            String explanation, String parameters, String example, String requirements,
            String mistakes) {
        public Entry {
            aliases = List.copyOf(aliases);
            related = List.copyOf(related);
        }
    }

    /** An intention is a user phrase, NOT a claim that the phrase is an API. */
    public record Hit(Entry entry, int score, String matchedTerm, boolean intention,
            boolean spellingSuggestion) {}

    public enum VersionState { MATCH, MISMATCH, UNKNOWN }

    private final List<Entry> entries;

    private QuickHelpCatalog(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public List<Entry> entries() { return entries; }

    public static QuickHelpCatalog load() throws IOException {
        List<String> listing = readResource(ROOT + "index.txt", 8192).lines().toList();
        if (listing.isEmpty() || !listing.get(0).equals("format: 1"))
            throw new IOException("Unsupported Quick Help catalogue format");
        List<Entry> found = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (String line : listing.subList(1, listing.size())) {
            String item = line.strip();
            if (item.isEmpty() || item.startsWith("#")) continue;
            if (!item.matches("[a-z][a-z0-9_]*\\.help") || found.size() >= MAX_ITEMS)
                throw new IOException("Invalid Quick Help index entry: " + item);
            Entry parsed = parse(item, readResource(ROOT + item, MAX_DOCUMENT_CHARS));
            if (!ids.add(parsed.id())) throw new IOException("Duplicate Quick Help API: " + parsed.id());
            found.add(parsed);
        }
        if (found.isEmpty()) throw new IOException("Quick Help catalogue has no entries");
        return new QuickHelpCatalog(found);
    }

    private static String readResource(String resource, int limit) throws IOException {
        try (InputStream stream = QuickHelpCatalog.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IOException("Missing bundled Quick Help resource: " + resource);
            byte[] bytes = stream.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("Quick Help resource is too large: " + resource);
            return new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    private static Entry parse(String file, String text) throws IOException {
        Map<String, String> header = new LinkedHashMap<>();
        Map<String, StringBuilder> sections = new LinkedHashMap<>();
        String section = null;
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("## ")) {
                section = line.substring(3).strip();
                if (!SECTIONS.contains(section) || sections.containsKey(section))
                    throw new IOException("Unknown/duplicate help section in " + file + ": " + section);
                sections.put(section, new StringBuilder());
            } else if (section != null) {
                sections.get(section).append(line).append('\n');
            } else if (!line.isBlank() && !line.startsWith("#")) {
                int split = line.indexOf(": ");
                if (split < 1) throw new IOException("Malformed header in " + file);
                String key = line.substring(0, split);
                if (header.putIfAbsent(key, line.substring(split + 2).strip()) != null)
                    throw new IOException("Duplicate metadata key in " + file + ": " + key);
            }
        }
        for (String key : List.of("id", "title", "category", "version", "aliases", "signature", "source", "related"))
            if (header.getOrDefault(key, "").isBlank())
                throw new IOException("Missing " + key + " in " + file);
        for (String key : SECTIONS)
            if (!sections.containsKey(key) || sections.get(key).toString().isBlank())
                throw new IOException("Missing section " + key + " in " + file);
        String id = header.get("id");
        if (!file.equals(id + ".help") || !id.matches("[A-Za-z_][A-Za-z0-9_]*"))
            throw new IOException("Invalid API ID for " + file);
        if (!header.get("version").matches("[0-9]+\\.[0-9]+\\.[0-9]+"))
            throw new IOException("Invalid source version for " + file);
        String url = header.get("source");
        if (!url.startsWith("https://github.com/zephyrproject-rtos/zephyr/blob/v"
                + header.get("version") + "/")
                && !url.startsWith("https://docs.zephyrproject.org/"
                + header.get("version") + "/"))
            throw new IOException("Unpinned or unofficial source for " + file);
        if (!header.get("signature").contains(id))
            throw new IOException("Missing API in signature for " + file);
        return new Entry(id, header.get("title"), header.get("category"), header.get("version"),
                splitPipe(header.get("aliases")), header.get("signature"), url,
                splitPipe(header.get("related")),
                sections.get("Explanation").toString().strip(),
                sections.get("Parameters").toString().strip(),
                sections.get("Example").toString().strip(),
                sections.get("Requirements").toString().strip(),
                sections.get("Mistakes").toString().strip());
    }

    private static List<String> splitPipe(String text) {
        return List.of(text.split("\\s*\\|\\s*"));
    }

    public static VersionState versionState(Entry entry, String release) {
        Objects.requireNonNull(entry);
        if (release == null || !release.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))
            return VersionState.UNKNOWN;
        return entry.version().equals(release) ? VersionState.MATCH : VersionState.MISMATCH;
    }

    public List<Hit> search(String query, int limit) {
        return QuickHelpSearch.rank(entries, query, limit);
    }
}
