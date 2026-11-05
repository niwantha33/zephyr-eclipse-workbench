// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Immutable, bounded snapshot of upstream dtlib/edtlib observations. */
final class DevicetreeIndex {
    record Node(String label, String path, String status, String compatible, int line) {}
    record Property(String path, String name, String type, String description,
                    boolean required, boolean deprecated, List<String> choices, String constant, String source) {}
    record Compatible(String name, String bus, String description) {}
    record Snapshot(String state, String explanation, Path dts,
                    List<Node> nodes, List<Property> properties, List<Compatible> compatibles) {
        Snapshot {
            nodes = List.copyOf(nodes);
            properties = List.copyOf(properties);
            compatibles = List.copyOf(compatibles);
        }
        Node label(String wanted) {
            return nodes.stream().filter(node -> node.label().equals(wanted)).findFirst().orElse(null);
        }
        List<Property> properties(String path) {
            return properties.stream().filter(p -> p.path().equals(path)).toList();
        }
        boolean built() { return "built".equals(state); }
    }
    private DevicetreeIndex() {}
    static Snapshot empty(String state, String explanation) {
        return new Snapshot(state, explanation, null, List.of(), List.of(), List.of());
    }
    static Snapshot parse(String value, Path dts) {
        if (value == null || value.length() > 3_000_000) return empty("unavailable", "Index too large");
        var lines = value.split("\\R");
        if (lines.length == 0 || lines.length > 18_005 || !"ZEW-DTASSIST-1".equals(lines[0]))
            return empty("unavailable", "Unexpected index format");
        var nodes = new ArrayList<Node>();
        var properties = new ArrayList<Property>();
        var compatibles = new ArrayList<Compatible>();
        String state = "unavailable", explanation = "No evidence";
        try {
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].isBlank()) continue;
                String[] fields = lines[i].split("\\t", -1);
                for (int n = 1; n < fields.length; n++)
                    fields[n] = new String(Base64.getDecoder().decode(fields[n]), StandardCharsets.UTF_8);
                switch (fields[0]) {
                    case "S" -> {
                        if (fields.length != 3) throw new IllegalArgumentException();
                        state = fields[1]; explanation = fields[2];
                    }
                    case "N" -> {
                        if (fields.length != 6) throw new IllegalArgumentException();
                        nodes.add(new Node(fields[1], fields[2], fields[3], fields[4], Integer.parseInt(fields[5])));
                    }
                    case "P" -> {
                        if (fields.length != 10) throw new IllegalArgumentException();
                        properties.add(new Property(fields[1], fields[2], fields[3], fields[4],
                                "yes".equals(fields[5]), "yes".equals(fields[6]),
                                fields[7].isEmpty() ? List.of() : List.of(fields[7].split("\u001f", -1)),
                                fields[8], fields[9]));
                    }
                    case "C" -> {
                        if (fields.length != 4) throw new IllegalArgumentException();
                        compatibles.add(new Compatible(fields[1], fields[2], fields[3]));
                    }
                    default -> throw new IllegalArgumentException();
                }
            }
            if (!Set.of("built", "no-build", "unavailable", "mismatch").contains(state))
                throw new IllegalArgumentException();
            if (!"built".equals(state) && (!nodes.isEmpty() || !properties.isEmpty()))
                throw new IllegalArgumentException();
            return new Snapshot(state, explanation, "built".equals(state) ? dts : null,
                    nodes, properties, compatibles);
        } catch (IllegalArgumentException error) {
            return empty("unavailable", "Malformed editor evidence");
        }
    }
    /** Must be called on a background Job, not SWT's display thread. */
    static Snapshot load(Path python, Path script, Path zephyr, Path app, Path build) {
        if (python == null || script == null || zephyr == null || app == null || build == null)
            return empty("unavailable", "Select project/board context");
        try {
            var command = List.of(python.toString(), script.toString(), "--zephyr", zephyr.toString(),
                    "--application", app.toString(), "--build", build.toString());
            var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            boolean finished = process.waitFor(25, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return empty("unavailable", "Devicetree indexing timed out");
            }
            byte[] data = process.getInputStream().readNBytes(3_000_001);
            if (process.exitValue() != 0 || data.length > 3_000_000)
                return empty("unavailable", "Devicetree indexing failed");
            return parse(new String(data, StandardCharsets.UTF_8), build.resolve("zephyr/zephyr.dts"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return empty("unavailable", "Index cancelled");
        } catch (Exception e) {
            return empty("unavailable", "Index unavailable: " + e.getClass().getSimpleName());
        }
    }
}
