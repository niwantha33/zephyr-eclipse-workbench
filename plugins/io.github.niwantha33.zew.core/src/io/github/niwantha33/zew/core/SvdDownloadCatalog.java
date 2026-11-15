// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Offline vetted acquisition manifests. Missing SHA means NO network permission. */
public final class SvdDownloadCatalog {
    private SvdDownloadCatalog() {}

    public record Entry(String part, String qualifier, URI url, String sha256,
                        String type, String pdsc) {
        public boolean pinned() { return sha256.matches("[0-9a-f]{64}"); }
        public String identity() {
            return part + (qualifier.isBlank() ? "" : "/" + qualifier);
        }
        public String filename() {
            String path = url.getPath();
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    private static final Map<String, Entry> ENTRIES = load();

    public static Entry find(String board) {
        String part = SvdAutoDiscovery.partFromBoard(board);
        if (part.isBlank()) return null;
        String[] segments = board.split("/", -1);
        String core = segments.length == 3 ? segments[2] : "";
        return ENTRIES.get(part + (core.isBlank() ? "" : "/" + core));
    }
    public static Entry pinned(String board, String type) {
        Entry entry = find(board);
        return entry != null && entry.type().equals(type) && entry.pinned() ? entry : null;
    }
    public static List<Entry> entries() { return List.copyOf(ENTRIES.values()); }

    private static Map<String, Entry> load() {
        try (InputStream in = SvdDownloadCatalog.class.getResourceAsStream("/svd-download-catalog.json")) {
            return in == null ? Map.of() : parse(in);
        } catch (Exception failed) {
            return Map.of();
        }
    }

    /** Reject unknown keys, duplicate entries, script syntax, redirects or unbounded JSON. */
    public static Map<String, Entry> parse(InputStream input) throws Exception {
        byte[] bytes = input.readNBytes(16_385);
        if (bytes.length > 16_384) throw new IllegalArgumentException("Download catalog >16 KiB");
        String json = new String(bytes, StandardCharsets.UTF_8).strip();
        if (!json.startsWith("[") || !json.endsWith("]"))
            throw new IllegalArgumentException("Expected JSON catalog array");
        String body = json.substring(1, json.length() - 1).strip();
        if (body.isEmpty()) return Map.of();
        Pattern object = Pattern.compile("\\s*\\{([^{}]{1,1024})}\\s*(?:,|$)");
        Pattern field = Pattern.compile("\\s*\"([a-zA-Z0-9]+)\"\\s*:\\s*\"([^\"\\\\]{0,360})\"\\s*(?:,|$)");
        var catalog = new LinkedHashMap<String, Entry>();
        int offset = 0;
        while (offset < body.length()) {
            var match = object.matcher(body);
            match.region(offset, body.length());
            if (!match.lookingAt()) throw new IllegalArgumentException("Invalid catalog object");
            String members = match.group(1);
            var fields = new LinkedHashMap<String, String>();
            int position = 0;
            while (position < members.length()) {
                var token = field.matcher(members);
                token.region(position, members.length());
                if (!token.lookingAt()) throw new IllegalArgumentException("Malformed catalog field");
                String name = token.group(1);
                if (!Set.of("part", "qualifier", "url", "sha256", "type", "pdsc").contains(name)
                        || fields.putIfAbsent(name, token.group(2)) != null)
                    throw new IllegalArgumentException("Unknown/duplicate catalog field");
                position = token.end();
            }
            String part = fields.getOrDefault("part", "").toLowerCase(java.util.Locale.ROOT);
            String core = fields.getOrDefault("qualifier", "").toLowerCase(java.util.Locale.ROOT);
            String digest = fields.getOrDefault("sha256", "");
            String type = fields.getOrDefault("type", "");
            String pdsc = fields.getOrDefault("pdsc", "");
            if (!part.matches("[a-z0-9_+-]{5,72}")
                    || !core.matches("[a-z0-9_-]{0,24}")
                    || !(digest.isEmpty() || digest.matches("[a-f0-9]{64}"))
                    || !Set.of("svd", "pack").contains(type)
                    || !pdsc.matches("[A-Za-z0-9_.-]{0,180}"))
                throw new IllegalArgumentException("Unsafe catalog entry");
            URI url = URI.create(fields.getOrDefault("url", ""));
            if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null
                    || url.getUserInfo() != null || url.getFragment() != null
                    || url.getQuery() != null
                    || !Set.of("raw.githubusercontent.com", "software-dl.ti.com")
                        .contains(url.getHost().toLowerCase(java.util.Locale.ROOT)))
                throw new IllegalArgumentException("Unapproved catalog origin");
            if (url.getPath().contains("..") || !url.getPath().endsWith("." + type))
                throw new IllegalArgumentException("Unapproved catalog file path");
            var entry = new Entry(part, core, url, digest, type, pdsc);
            if (catalog.putIfAbsent(entry.identity(), entry) != null)
                throw new IllegalArgumentException("Duplicate catalog identity");
            if (catalog.size() > 128) throw new IllegalArgumentException("Too many catalog entries");
            offset = match.end();
        }
        return Map.copyOf(catalog);
    }
}
