// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact, bundled device-to-SVD aliases; qualified dual-core identities never guess a core. */
public final class SvdPartCompatibility {
    private SvdPartCompatibility() {}
    public record Mapping(String part, String svdModel, String qualifier) {}
    private static final Pattern ENTRY = Pattern.compile(
            "\\s*\\{\\s*\\\"part\\\"\\s*:\\s*\\\"([A-Za-z0-9_-]{1,64})\\\""
            + "\\s*,\\s*\\\"svdModel\\\"\\s*:\\s*\\\"([A-Za-z0-9_-]{1,64})\\\""
            + "(?:\\s*,\\s*\\\"qualifier\\\"\\s*:\\s*\\\"([A-Za-z0-9_-]{1,32})\\\")?\\s*}\\s*");
    private static final List<Mapping> MAPPINGS = loadBundled();

    public static String normal(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "")
                .toLowerCase(Locale.ROOT);
    }
    public static boolean requiresQualifier(String part) {
        String chip = normal(part);
        return MAPPINGS.stream().anyMatch(m -> m.part().equals(chip) && !m.qualifier().isEmpty());
    }
    /** Filename-only discovery hint; never proof of valid SVD contents. */
    public static Set<String> modelsFor(String part) {
        String chip = normal(part);
        if (chip.isBlank()) return Set.of();
        var models = new HashSet<String>();
        models.add(chip);
        for (Mapping m : MAPPINGS) if (m.part().equals(chip)) models.add(m.svdModel());
        return Set.copyOf(models);
    }
    /** For qualified SoCs, do not mix application and network SVD candidates. */
    public static Set<String> modelsFor(String part, String qualifier) {
        String chip = normal(part);
        if (chip.isEmpty()) return Set.of();
        if (!requiresQualifier(chip)) return modelsFor(chip);
        String core = normal(qualifier);
        var models = new HashSet<String>();
        for (Mapping m : MAPPINGS)
            if (m.part().equals(chip) && m.qualifier().equals(core)) models.add(m.svdModel());
        return Set.copyOf(models);
    }
    public static boolean matches(String part, String svdModel) {
        return matches(part, "", svdModel);
    }
    public static boolean matches(String part, String qualifier, String svdModel) {
        String chip = normal(part), model = normal(svdModel);
        if (chip.isEmpty() || model.isEmpty()) return false;
        if (requiresQualifier(chip)) {
            String core = normal(qualifier);
            return !core.isEmpty() && MAPPINGS.stream().anyMatch(
                    m -> m.part().equals(chip) && m.qualifier().equals(core) && m.svdModel().equals(model));
        }
        if (chip.equals(model)) return true;
        return MAPPINGS.stream().anyMatch(
                m -> m.part().equals(chip) && m.qualifier().isEmpty() && m.svdModel().equals(model));
    }
    public static boolean knownQualifier(String part, String qualifier) {
        String chip = normal(part), core = normal(qualifier);
        return MAPPINGS.stream().anyMatch(m ->
                m.part().equals(chip) && m.qualifier().equals(core) && !core.isEmpty());
    }
    private static List<Mapping> loadBundled() {
        try (InputStream in = SvdPartCompatibility.class.getResourceAsStream("/svd-part-mappings.json")) {
            return in == null ? List.of() : parseMappings(in);
        } catch (Exception failure) {
            return List.of(); // unusable resource does not grant an alias
        }
    }
    /** Existing simple-map API for unqualified aliases. */
    public static Map<String, String> parse(InputStream in) throws Exception {
        var result = new HashMap<String, String>();
        for (Mapping m : parseMappings(in))
            if (m.qualifier().isEmpty()) result.put(m.part(), m.svdModel());
        return Map.copyOf(result);
    }
    public static List<Mapping> parseMappings(InputStream input) throws Exception {
        byte[] bytes = input.readNBytes(16_385);
        if (bytes.length > 16_384) throw new IllegalArgumentException("SVD part map exceeds 16 KiB");
        String source = new String(bytes, StandardCharsets.UTF_8).strip();
        if (!source.startsWith("[") || !source.endsWith("]"))
            throw new IllegalArgumentException("Invalid SVD part map JSON array");
        String items = source.substring(1, source.length() - 1).strip();
        if (items.isEmpty()) return List.of();
        var result = new ArrayList<Mapping>();
        var used = new HashSet<String>();
        for (int pos = 0; pos < items.length();) {
            var match = ENTRY.matcher(items).region(pos, items.length());
            if (!match.lookingAt()) throw new IllegalArgumentException("Invalid SVD part map entry");
            String chip = normal(match.group(1)), model = normal(match.group(2));
            String core = normal(match.group(3));
            if (chip.isBlank() || model.isBlank() || !used.add(chip + "/" + core))
                throw new IllegalArgumentException("Duplicate or empty SVD part/qualifier mapping");
            result.add(new Mapping(chip, model, core));
            if (result.size() > 128) throw new IllegalArgumentException("Too many SVD mappings");
            pos = match.end();
            if (pos == items.length()) break;
            if (items.charAt(pos++) != ',' || pos == items.length())
                throw new IllegalArgumentException("Invalid separator in SVD part map");
        }
        return List.copyOf(result);
    }
}
