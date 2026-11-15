// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Exact SoC to vendor SVD model table loaded from a bundled, bounded JSON resource. */
public final class SvdPartCompatibility {
    private SvdPartCompatibility() {}

    private static final Map<String, String> MAPPINGS = loadBundled();

    private static String normal(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "")
                .toLowerCase(Locale.ROOT);
    }

    public static boolean matches(String part, String svdModel) {
        String chip = normal(part);
        String model = normal(svdModel);
        if (chip.isBlank() || model.isBlank()) return false;
        if (chip.equals(model)) return true;
        return model.equals(MAPPINGS.get(chip)); // never apply an X wildcard
    }

    private static Map<String, String> loadBundled() {
        try (InputStream in = SvdPartCompatibility.class.getResourceAsStream("/svd-part-mappings.json")) {
            return in == null ? Map.of() : parse(in);
        } catch (Exception invalid) {
            return Map.of(); // fail closed if bundle is corrupt
        }
    }

    /** Strict JSON string shape; no escapes, unknown fields or duplicate identities. */
    public static Map<String, String> parse(InputStream input) throws Exception {
        byte[] content = input.readNBytes(16_385);
        if (content.length > 16_384)
            throw new IllegalArgumentException("SVD part map exceeds 16 KiB");
        String source = new String(content, StandardCharsets.UTF_8).strip();
        if (!source.startsWith("[") || !source.endsWith("]"))
            throw new IllegalArgumentException("Invalid SVD part map array");
        String elements = source.substring(1, source.length() - 1).strip();
        if (elements.isEmpty()) return Map.of();
        Pattern entry = Pattern.compile("\\s*\\{\\s*\\\"part\\\"\\s*:\\s*\\\"([A-Za-z0-9_-]{1,64})\\\"\\s*,\\s*\\\"svdModel\\\"\\s*:\\s*\\\"([A-Za-z0-9_-]{1,64})\\\"\\s*}\\s*");
        var mapping = new HashMap<String, String>();
        int index = 0;
        while (index < elements.length()) {
            var match = entry.matcher(elements);
            match.region(index, elements.length());
            if (!match.lookingAt()) throw new IllegalArgumentException("Invalid SVD mapping entry");
            String part = normal(match.group(1)), model = normal(match.group(2));
            if (mapping.putIfAbsent(part, model) != null)
                throw new IllegalArgumentException("Duplicate SVD mapping for " + part);
            if (mapping.size() > 128) throw new IllegalArgumentException("Too many SVD mappings");
            index = match.end();
            if (index == elements.length()) break;
            if (elements.charAt(index) != ',') throw new IllegalArgumentException("Missing mapping separator");
            index++;
            if (index == elements.length()) throw new IllegalArgumentException("Trailing JSON comma");
        }
        return Map.copyOf(mapping);
    }
}
