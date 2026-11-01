// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Exact generated-DTS phandle dependencies, not physical pin, driver or runtime evidence.
 * Parses only bounded numeric generated cells; never guesses the provider for specifier args.
 */
public final class DevicetreeDependencyEvidence {
    private DevicetreeDependencyEvidence() {}

    public record Reference(String property, String target, String state, int line) {}
    private static final Pattern INTEGER = Pattern.compile("(?:0[xX][0-9a-fA-F]+|[0-9]+)");
    private static final Pattern CELLS = Pattern.compile("<([^<>]*)>");
    private static final Pattern PINCTRL = Pattern.compile("pinctrl-[0-9]+");
    private static final Map<String, String> SPECIFIERS = Map.ofEntries(
            Map.entry("clocks", "#clock-cells"),
            Map.entry("resets", "#reset-cells"),
            Map.entry("dmas", "#dma-cells"),
            Map.entry("io-channels", "#io-channel-cells"),
            Map.entry("power-domains", "#power-domain-cells"),
            Map.entry("pwms", "#pwm-cells"),
            Map.entry("phys", "#phy-cells"),
            Map.entry("interrupts-extended", "#interrupt-cells"),
            Map.entry("mboxes", "#mbox-cells"));
    private static final int MAX_REFERENCES = 128;

    private static Long number(String raw) {
        if (!INTEGER.matcher(raw).matches()) return null;
        try {
            long n = raw.startsWith("0x") || raw.startsWith("0X")
                    ? Long.parseUnsignedLong(raw.substring(2), 16)
                    : Long.parseUnsignedLong(raw, 10);
            return n <= 0xffff_ffffL ? n : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static List<List<Long>> groups(String value) {
        if (value == null || value.length() > 8192) return null;
        var m = CELLS.matcher(value);
        var result = new ArrayList<List<Long>>();
        int end = 0;
        while (m.find()) {
            if (!value.substring(end, m.start()).matches("[\\s,]*")) return null;
            var words = m.group(1).trim();
            if (words.isEmpty()) return null;
            var values = new ArrayList<Long>();
            for (String word : words.split("\\s+")) {
                Long parsed = number(word);
                if (parsed == null) return null;
                values.add(parsed);
            }
            result.add(List.copyOf(values));
            end = m.end();
        }
        if (result.isEmpty() || !value.substring(end).matches("[\\s,]*")) return null;
        return result;
    }

    private static String role(String name) {
        if (PINCTRL.matcher(name).matches()) return "pinctrl";
        if (name.equals("interrupt-parent") || name.endsWith("-supply")
                || name.equals("remote-endpoint")) return "scalar";
        if (name.equals("gpios") || name.endsWith("-gpios") || name.equals("cs-gpios"))
            return "#gpio-cells";
        if (name.equals("memory-region")) return "scalar-list";
        return SPECIFIERS.get(name);
    }

    /** One consumer's immediate dependencies only; no descendant ownership inference. */
    public static List<Reference> inspect(List<DevicetreeParameters.Property> properties, String owner) {
        Objects.requireNonNull(properties);
        Objects.requireNonNull(owner);
        var providers = new HashMap<Long, String>();
        var duplicates = new HashSet<Long>();
        var providerCells = new HashMap<String, Map<String, Integer>>();
        for (var p : properties) {
            if (p.name().equals("phandle") || p.name().equals("linux,phandle")) {
                var groups = groups(p.value());
                if (groups == null || groups.size() != 1 || groups.get(0).size() != 1) continue;
                long handle = groups.get(0).get(0);
                if (handle == 0) continue;
                String former = providers.putIfAbsent(handle, p.path());
                if (former != null && !former.equals(p.path())) duplicates.add(handle);
            } else if (p.name().startsWith("#") && p.name().endsWith("-cells")) {
                var groups = groups(p.value());
                if (groups != null && groups.size() == 1 && groups.get(0).size() == 1) {
                    long count = groups.get(0).get(0);
                    if (count <= 16) providerCells.computeIfAbsent(p.path(), unused -> new HashMap<>())
                            .put(p.name(), (int) count);
                }
            }
        }
        var links = new ArrayList<Reference>();
        for (var p : properties) {
            if (!p.path().equals(owner)) continue;
            String relation = role(p.name());
            if (relation == null) continue;
            var parsed = groups(p.value());
            if (parsed == null) {
                links.add(new Reference(p.name(), "", "unparsed-generated-cells", p.line()));
                continue;
            }
            for (var group : parsed) {
                int at = 0;
                while (at < group.size()) {
                    if (links.size() >= MAX_REFERENCES) {
                        links.add(new Reference("limit", "", "truncated-at-128", p.line()));
                        return List.copyOf(links);
                    }
                    long handle = group.get(at);
                    String target = providers.get(handle);
                    String state = handle == 0 || target == null ? "unknown-provider"
                            : duplicates.contains(handle) ? "ambiguous-provider" : "resolved-dts-node";
                    int count = 0;
                    if ("scalar".equals(relation)) {
                        if (group.size() != 1) state = "unexpected-scalar-shape";
                    } else if ("pinctrl".equals(relation) || "scalar-list".equals(relation)) {
                        // Each cell is a separate phandle, no specifier arguments.
                    } else {
                        if (target == null || duplicates.contains(handle)) {
                            state = "unresolved-specifier-boundary";
                        } else {
                            Integer expected = providerCells.getOrDefault(target, Map.of()).get(relation);
                            if (expected == null) state = "unknown-provider-cell-count";
                            else count = expected;
                        }
                        if (!state.equals("resolved-dts-node") || at + 1 + count > group.size()) {
                            if (at + 1 + count > group.size()) state = "truncated-specifier";
                            links.add(new Reference(p.name(), "", state, p.line()));
                            break; // Never treat argument cells as another phandle.
                        }
                    }
                    links.add(new Reference(p.name(),
                            state.equals("resolved-dts-node") ? target : "", state, p.line()));
                    if (relation.equals("scalar") || relation.equals("pinctrl")
                            || relation.equals("scalar-list")) at++;
                    else at += 1 + count;
                    if (relation.equals("scalar")) break;
                }
            }
        }
        return List.copyOf(links);
    }

    public static String describe(List<Reference> references) {
        var report = new StringBuilder("\n\nDEVICETREE DEPENDENCIES (generated snapshot only)");
        if (references.isEmpty()) report.append("\nNo supported direct phandle dependencies observed.");
        for (var ref : references) {
            report.append("\n").append(ref.property()).append(" [line ")
                    .append(ref.line()).append("]: ").append(ref.state());
            if (!ref.target().isEmpty()) report.append(" -> ").append(ref.target());
        }
        report.append("\nNo physical pad, electrical, compiled-driver or runtime usage is inferred.");
        return report.toString();
    }
}
