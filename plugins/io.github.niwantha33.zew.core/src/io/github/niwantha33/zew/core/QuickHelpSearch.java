// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import io.github.niwantha33.zew.core.QuickHelpCatalog.Entry;
import io.github.niwantha33.zew.core.QuickHelpCatalog.Hit;

/** Independent, deterministic offline synonym/prefix/fuzzy ranking service. */
public final class QuickHelpSearch {
    private QuickHelpSearch() {}

    public static List<Hit> rank(List<Entry> entries, String query, int limit) {
        String needle = normalize(query);
        if (needle.isEmpty() || limit <= 0) return List.of();
        List<Hit> results = new ArrayList<>();
        for (Entry entry : entries) {
            Hit best = new Hit(entry, 0, "", false, false);
            best = choose(best, score(needle, entry.id(), 1000, false, entry));
            best = choose(best, score(needle, entry.title(), 800, true, entry));
            for (String alias : entry.aliases())
                best = choose(best, score(needle, alias, 700, true, entry));
            if (best.score() > 0) results.add(best);
        }
        results.sort(Comparator.comparingInt(Hit::score).reversed()
                .thenComparing(hit -> hit.entry().id()));
        return List.copyOf(results.subList(0, Math.min(limit, results.size())));
    }

    private static Hit choose(Hit a, Hit b) { return b.score() > a.score() ? b : a; }

    private static Hit score(String needle, String candidate, int exactScore,
            boolean intention, Entry entry) {
        String key = normalize(candidate);
        if (needle.equals(key)) return new Hit(entry, exactScore, candidate, intention, false);
        if (key.startsWith(needle)) return new Hit(entry, exactScore - 180, candidate, intention, false);
        if (needle.length() >= 4 && key.contains(needle))
            return new Hit(entry, exactScore - 340, candidate, intention, false);
        if (needle.length() >= 4 && Math.abs(needle.length() - key.length()) <= 2
                && editDistanceAtMost(needle, key, 2))
            return new Hit(entry, exactScore - 440, candidate, intention, true);
        String[] tokens = needle.split(" ");
        String[] words = key.split(" ");
        if (tokens.length == 1 && needle.length() >= 3) {
            for (String word : words) {
                if (word.startsWith(needle))
                    return new Hit(entry, exactScore - 400, candidate, intention, false);
                if (needle.length() >= 4 && Math.abs(word.length() - needle.length()) <= 2
                        && editDistanceAtMost(needle, word, 2))
                    return new Hit(entry, exactScore - 500, candidate, intention, true);
            }
        }
        if (tokens.length > 1) {
            int matched = 0;
            for (String token : tokens) {
                if (token.length() < 2) continue;
                for (String word : words) {
                    if (word.startsWith(token) || (token.length() >= 4
                            && editDistanceAtMost(token, word, 1))) {
                        matched++;
                        break;
                    }
                }
            }
            if (matched == tokens.length)
                return new Hit(entry, exactScore - 550, candidate, intention, true);
        }
        return new Hit(entry, 0, "", false, false);
    }

    private static String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip()
                .replaceAll("\\s+", " ");
    }

    // Bounded dynamic programming: 128-character terms, never unbounded editor content.
    private static boolean editDistanceAtMost(String a, String b, int max) {
        if (a.length() > 128 || b.length() > 128 || Math.abs(a.length() - b.length()) > max)
            return false;
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] next = new int[b.length() + 1];
            next[0] = i;
            int rowMin = next[0];
            for (int j = 1; j <= b.length(); j++) {
                next[j] = Math.min(Math.min(next[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
                rowMin = Math.min(rowMin, next[j]);
            }
            if (rowMin > max) return false;
            prev = next;
        }
        return prev[b.length()] <= max;
    }
}
