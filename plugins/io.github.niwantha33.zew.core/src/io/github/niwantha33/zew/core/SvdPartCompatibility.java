// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Exact SoC and core-qualified SVD aliases from one offline bundled JSON table. */
public final class SvdPartCompatibility {
    private SvdPartCompatibility() {}

    private static final Map<String, String> MAPPINGS = loadBundled();

    private static String normal(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "")
                .toLowerCase(Locale.ROOT);
    }
    private static String key(String part, String qualifier) {
        String chip = normal(part), core = normal(qualifier);
        return core.isEmpty() ? chip : chip + "/" + core;
    }
    private static String sourcePart(String text) {
        if (text == null) return "";
        String[] segments = text.split("/", -1);
        return normal(segments[0]);
    }
    private static String sourceQualifier(String text) {
        if (text == null) return "";
        String[] segments = text.split("/", -1);
        return segments.length > 1 ? normal(segments[1]) : "";
    }
    public static boolean requiresQualifier(String part) {
        return DUAL_CORE.contains(normal(part))
                || MAPPINGS.keySet().stream().anyMatch(k -> k.startsWith(normal(part) + "/"));
    }
    public static boolean hasQualifier(String part, String qualifier) {
        return MAPPINGS.containsKey(key(part, qualifier));
    }
    public static boolean matches(String part, String svdModel) {
        String chip = sourcePart(part), core = sourceQualifier(part), model = normal(svdModel);
        if (chip.isBlank() || model.isBlank()) return false;
        if (requiresQualifier(chip) && core.isBlank()) return false;
        if (!requiresQualifier(chip) && chip.equals(model)) return true;
        String alias = MAPPINGS.get(key(chip, core));
        if (alias == null && !requiresQualifier(chip))
            alias = MAPPINGS.get(chip);
        return model.equals(alias);
    }
    /** Normalised SVD file stems accepted as discovery *candidates*, never proof. */
    public static Set<String> modelsFor(String part) {
        String chip = sourcePart(part), qualifier = sourceQualifier(part);
        if (chip.isBlank()) return Set.of();
        if (requiresQualifier(chip) && qualifier.isBlank()) return Set.of();
        var names = new LinkedHashSet<String>();
        if (!requiresQualifier(chip)) names.add(chip);
        String alias = MAPPINGS.get(key(chip, qualifier));
        if (alias == null && !requiresQualifier(chip)) alias = MAPPINGS.get(chip);
        if (alias != null) names.add(alias);
        return Set.copyOf(names);
    }

    private static Map<String, String> loadBundled() {
        try (InputStream in = SvdPartCompatibility.class.getResourceAsStream("/svd-part-mappings.json")) {
            return in == null ? Map.of() : parse(in);
        } catch (Exception invalid) {
            return Map.of(); // missing/invalid bundled aliases never grant access
        }
    }

    /**
     * Bounded exact schema: [{"part":"...","qualifier":"...", "svdModel":"..."}].
     * qualifier is optional; source evidence belongs in docs, not executable JSON.
     * Key order may vary, duplicate fields and unknown fields fail closed.
     */
    public static Map<String, String> parse(InputStream input) throws Exception {
        byte[] bytes = input.readNBytes(16_385);
        if (bytes.length > 16_384) throw new IllegalArgumentException("SVD map exceeds 16 KiB");
        String json = new String(bytes, StandardCharsets.UTF_8).trim();
        if (!json.startsWith("[") || !json.endsWith("]"))
            throw new IllegalArgumentException("Expected SVD mapping JSON array");
        String body = json.substring(1, json.length() - 1).trim();
        if (body.isEmpty()) return Map.of();
        var output = new LinkedHashMap<String, String>();
        var object = Pattern.compile("\\s*\\{([^{}]{1,1024})}\\s*(?:,|$)");
        var field = Pattern.compile("\\s*\\\"([A-Za-z]+)\\\"\\s*:\\s*\\\"([A-Za-z0-9_-]{1,72})\\\"\\s*(?:,|$)");
        int pos = 0;
        while (pos < body.length()) {
            var match = object.matcher(body);
            match.region(pos, body.length());
            if (!match.lookingAt()) throw new IllegalArgumentException("Invalid SVD map object");
            String fields = match.group(1);
            var item = new LinkedHashMap<String, String>();
            int offset = 0;
            while (offset < fields.length()) {
                var token = field.matcher(fields);
                token.region(offset, fields.length());
                if (!token.lookingAt()) throw new IllegalArgumentException("Invalid SVD map field");
                if (!Set.of("part", "qualifier", "svdModel").contains(token.group(1))
                        || item.putIfAbsent(token.group(1), token.group(2)) != null)
                    throw new IllegalArgumentException("Duplicate/unknown SVD map field");
                offset = token.end();
            }
            if (!item.containsKey("part") || !item.containsKey("svdModel"))
                throw new IllegalArgumentException("SVD map requires part and svdModel");
            String k = key(item.get("part"), item.getOrDefault("qualifier", ""));
            if (output.putIfAbsent(k, normal(item.get("svdModel"))) != null)
                throw new IllegalArgumentException("Duplicate SVD mapping: " + k);
            if (output.size() > 128) throw new IllegalArgumentException("Too many SVD mappings");
            pos = match.end();
        }
        return Map.copyOf(output);
    }
}
