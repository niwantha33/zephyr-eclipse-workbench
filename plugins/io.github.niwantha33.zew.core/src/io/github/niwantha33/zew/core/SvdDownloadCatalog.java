// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Offline bundled catalog. Only independently pinned SHA-256 records can contact a vendor. */
public final class SvdDownloadCatalog {
    private SvdDownloadCatalog() {}
    public record Entry(String part, String qualifier, URI url, String sha256,
            String type, String pdsc) {
        public boolean pinned() { return sha256.matches("[a-f0-9]{64}"); }
        public String filename() {
            String path = url.getPath();
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }
    private static final Pattern ITEM = Pattern.compile(
            "\\s*\\{\\s*\"part\"\\s*:\\s*\"([A-Za-z0-9_-]{1,64})\""
            + "(?:\\s*,\\s*\"qualifier\"\\s*:\\s*\"([A-Za-z0-9_-]{1,32})\")?"
            + "\\s*,\\s*\"url\"\\s*:\\s*\"(https://[A-Za-z0-9:/._?&=%+-]{1,510})\""
            + "\\s*,\\s*\"sha256\"\\s*:\\s*\"([a-f0-9]{64})?\""
            + "\\s*,\\s*\"type\"\\s*:\\s*\"(pack|svd)\""
            + "(?:\\s*,\\s*\"pdsc\"\\s*:\\s*\"([A-Za-z0-9._/-]{1,200})\")?"
            + "\\s*}\\s*");
    private static final List<Entry> ENTRIES = load();
    private static List<Entry> load() {
        try (InputStream stream = SvdDownloadCatalog.class.getResourceAsStream("/svd-download-catalog.json")) {
            return stream == null ? List.of() : parse(stream);
        } catch (Exception invalid) {
            return List.of(); // no permissive fallback
        }
    }
    public static List<Entry> entries() { return ENTRIES; }
    public static Entry forBoard(String board) {
        String part = SvdAutoDiscovery.partFromBoard(board);
        String[] segments = board == null ? new String[0] : board.split("/", -1);
        String qualifier = segments.length == 3 ? SvdPartCompatibility.normal(segments[2]) : "";
        var options = ENTRIES.stream().filter(e -> e.part().equals(part)
                && (e.qualifier().isEmpty() || e.qualifier().equals(qualifier))).toList();
        return options.size() == 1 ? options.get(0) : null;
    }
    public static Entry forPart(String part) {
        var matches = ENTRIES.stream().filter(e -> e.part().equals(SvdPartCompatibility.normal(part))
                && e.qualifier().isEmpty()).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }
    public static List<Entry> parse(InputStream stream) throws Exception {
        byte[] bytes = stream.readNBytes(32_769);
        if (bytes.length > 32_768) throw new IllegalArgumentException("Catalog exceeds 32 KiB");
        String json = new String(bytes, StandardCharsets.UTF_8).strip();
        if (!json.startsWith("[") || !json.endsWith("]"))
            throw new IllegalArgumentException("Catalog must be a JSON array");
        String body = json.substring(1, json.length() - 1).strip();
        if (body.isEmpty()) return List.of();
        var entries = new ArrayList<Entry>();
        var seen = new HashSet<String>();
        for (int pos = 0; pos < body.length();) {
            var m = ITEM.matcher(body).region(pos, body.length());
            if (!m.lookingAt()) throw new IllegalArgumentException("Malformed catalog entry");
            String part = SvdPartCompatibility.normal(m.group(1));
            String qualifier = SvdPartCompatibility.normal(m.group(2));
            String pdsc = m.group(6) == null ? "" : m.group(6);
            URI url = URI.create(m.group(3));
            String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equals(url.getScheme()) || url.getUserInfo() != null
                    || url.getPort() != -1 || url.getQuery() != null || url.getFragment() != null
                    || !Set.of("software-dl.ti.com", "raw.githubusercontent.com").contains(host))
                throw new IllegalArgumentException("Unapproved SVD catalog download host");
            String filename = url.getPath().substring(url.getPath().lastIndexOf('/') + 1);
            if (!filename.matches("[A-Za-z0-9_.-]{1,180}")
                    || (!filename.endsWith("." + m.group(5))))
                throw new IllegalArgumentException("Invalid catalog filename/type");
            if (!pdsc.isEmpty() && (pdsc.startsWith("/") || pdsc.contains("..")))
                throw new IllegalArgumentException("Unsafe PDSC path");
            if (!seen.add(part + "/" + qualifier))
                throw new IllegalArgumentException("Duplicate SVD catalog key");
            entries.add(new Entry(part, qualifier, url, m.group(4), m.group(5), pdsc));
            if (entries.size() > 64) throw new IllegalArgumentException("Catalog entry limit exceeded");
            pos = m.end();
            if (pos == body.length()) break;
            if (body.charAt(pos++) != ',' || pos == body.length())
                throw new IllegalArgumentException("Catalog entry separator invalid");
        }
        return List.copyOf(entries);
    }
}
