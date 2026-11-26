// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Read-only catalogue for fresh setup, before a Python runtime exists. */
public final class SetupReleaseCatalog {
    public static final String PINNED = "v4.4.0";
    private static final String API = "https://api.github.com/repos/zephyrproject-rtos/zephyr/releases?per_page=100&page=";
    private static final String RELEASE_URL = "https://github.com/zephyrproject-rtos/zephyr/releases/tag/";
    private static final int MAX_BYTES = 6 * 1024 * 1024;
    private static final Pattern TAG = Pattern.compile("v(0|[1-9][0-9]{0,7})\\.[0-9]{1,8}\\.[0-9]{1,8}");
    private static final Pattern FIELD = Pattern.compile("releases\\[(\\d+)\\]\\.(tag_name|draft|prerelease|html_url|published_at)");
    private SetupReleaseCatalog() {}

    public static String label(String tag) {
        return tag.substring(1) + (PINNED.equals(tag) ? " — pinned first-build profile" : " — source preparation only");
    }

    private static int compare(String a, String b) {
        var left = a.substring(1).split("\\.");
        var right = b.substring(1).split("\\.");
        for (int i = 0; i < 3; i++) {
            int cmp = Integer.compare(Integer.parseInt(right[i]), Integer.parseInt(left[i]));
            if (cmp != 0) return cmp;
        }
        return a.compareTo(b);
    }

    /** Stable published entries only; never infer installability from catalogue metadata. */
    public static List<String> parse(String json) {
        if (json == null || !json.stripLeading().startsWith("[") || json.length() > MAX_BYTES)
            throw new IllegalArgumentException("Invalid release list");
        Map<String, Map<String, String>> rows = new HashMap<>();
        for (var row : MemoryProfilerJson.parseFields("{\"releases\":" + json + "}")) {
            var match = FIELD.matcher(row.field());
            if (!match.matches()) continue;
            var fields = rows.computeIfAbsent(match.group(1), key -> new HashMap<>());
            if (fields.putIfAbsent(match.group(2), row.rawValue()) != null)
                throw new IllegalArgumentException("Duplicate release field");
        }
        var tags = new TreeSet<String>(SetupReleaseCatalog::compare);
        for (var fields : rows.values()) {
            if (!"false".equals(fields.get("draft")) || !"false".equals(fields.get("prerelease"))) continue;
            try {
                String tag = MemoryProfilerJson.unquote(fields.getOrDefault("tag_name", ""));
                String url = MemoryProfilerJson.unquote(fields.getOrDefault("html_url", ""));
                String date = MemoryProfilerJson.unquote(fields.getOrDefault("published_at", ""));
                if (TAG.matcher(tag).matches() && url.equals(RELEASE_URL + tag) && !date.isBlank()) tags.add(tag);
            } catch (IllegalArgumentException ignored) { /* Incomplete record. */ }
        }
        return List.copyOf(tags);
    }

    public static List<String> fetch() throws IOException {
        var tags = new TreeSet<String>(SetupReleaseCatalog::compare);
        // A bounded recent catalogue, not a claim to contain every historical tag.
        for (int page = 1; page <= 2; page++) {
            var connection = (HttpURLConnection) URI.create(API + page).toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000); connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "Zephyr-Eclipse-Workbench-Setup");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            try {
                if (connection.getResponseCode() != 200) throw new IOException("GitHub HTTP " + connection.getResponseCode());
                try (var stream = connection.getInputStream()) {
                    byte[] raw = stream.readNBytes(MAX_BYTES + 1);
                    if (raw.length > MAX_BYTES) throw new IOException("Release response too large");
                    tags.addAll(parse(new String(raw, StandardCharsets.UTF_8)));
                }
                String link = connection.getHeaderField("Link");
                if (link == null || !link.contains("rel=\"next\"")) break;
            } finally { connection.disconnect(); }
        }
        tags.add(PINNED);
        return new ArrayList<>(tags);
    }
}
