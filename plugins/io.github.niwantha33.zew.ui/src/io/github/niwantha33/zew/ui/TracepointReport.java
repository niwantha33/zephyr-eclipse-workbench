// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;

/** Strict bounded wire adapter for offline declared CTF named_event samples. */
public final class TracepointReport {
    public static final int MAX_CHARACTERS = 2_000_000;
    private static final int MAX_SAMPLES = 10_000;

    public record Sample(long timestampNs, BigDecimal value) { }

    public record Series(String name, String label, String type, List<Sample> samples) {
        public Series { samples = List.copyOf(samples); }
        public boolean found() { return !samples.isEmpty(); }
        public BigDecimal min() {
            return samples.stream().map(Sample::value).min(BigDecimal::compareTo).orElseThrow();
        }
        public BigDecimal max() {
            return samples.stream().map(Sample::value).max(BigDecimal::compareTo).orElseThrow();
        }
        public BigDecimal mean() {
            if (samples.isEmpty()) throw new IllegalStateException("No tracepoint samples");
            BigDecimal sum = BigDecimal.ZERO;
            for (Sample sample : samples) sum = sum.add(sample.value());
            return sum.divide(BigDecimal.valueOf(samples.size()), MathContext.DECIMAL64);
        }
    }

    public record Snapshot(String status, String configError, boolean incomplete,
                           int unknownEvents, String detail, List<Series> series) {
        public Snapshot { series = List.copyOf(series); }
    }

    private TracepointReport() { }

    public static Snapshot parse(String report) {
        if (report == null || report.isEmpty() || report.length() > MAX_CHARACTERS)
            throw new IllegalArgumentException("Missing or oversized tracepoint report");
        String[] lines = report.split("\\r?\\n", -1);
        if (lines.length < 2 || !lines[lines.length - 1].isEmpty())
            throw new IllegalArgumentException("Truncated tracepoint report");
        String[] header = lines[0].split("\\t", -1);
        if (header.length != 7 || !"ZEW_TRACEPOINTS".equals(header[0])
                || !"1".equals(header[1]))
            throw new IllegalArgumentException("Unsupported tracepoint report format");
        String status = header[2];
        if (!List.of("active", "inactive", "invalid", "decode-unavailable").contains(status))
            throw new IllegalArgumentException("Unknown tracepoint report status");
        boolean incomplete = flag(header[4]);
        int unknown = count(header[5]);
        String error = decode(header[3]);
        String detail = decode(header[6]);

        record Mutable(String name, String label, String type, int expected, ArrayList<Sample> samples) {}
        var declarations = new LinkedHashMap<String, Mutable>();
        int total = 0;
        boolean enteredSamples = false;
        for (int i = 1; i < lines.length - 1; i++) {
            String[] columns = lines[i].split("\\t", -1);
            if (columns.length == 5 && columns[0].equals("D") && !enteredSamples) {
                String name = decode(columns[1]);
                String label = decode(columns[2]);
                String type = columns[3];
                int expected = count(columns[4]);
                if (name.isEmpty() || label.isEmpty() || declarations.containsKey(name)
                        || !(type.equals("integer") || type.equals("float"))
                        || declarations.size() >= 64 || expected > MAX_SAMPLES)
                    throw new IllegalArgumentException("Invalid tracepoint declaration on line " + (i + 1));
                declarations.put(name, new Mutable(name, label, type, expected, new ArrayList<>()));
            } else if (columns.length == 4 && columns[0].equals("S")) {
                enteredSamples = true;
                Mutable series = declarations.get(decode(columns[1]));
                if (series == null || ++total > MAX_SAMPLES)
                    throw new IllegalArgumentException("Unknown tracepoint or excessive samples on line " + (i + 1));
                long timestamp = nanoseconds(columns[2]);
                BigDecimal number;
                try {
                    if (series.type().equals("integer") && !columns[3].matches("-?[0-9]+"))
                        throw new NumberFormatException("not an integer");
                    number = new BigDecimal(columns[3]);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("Invalid sample value on line " + (i + 1), ex);
                }
                if (!series.samples().isEmpty() && timestamp < series.samples().getLast().timestampNs())
                    throw new IllegalArgumentException("Non-monotonic tracepoint timestamps on line " + (i + 1));
                series.samples().add(new Sample(timestamp, number));
            } else {
                throw new IllegalArgumentException("Malformed tracepoint report line " + (i + 1));
            }
        }
        List<Series> list = new ArrayList<>();
        for (Mutable m : declarations.values()) {
            if (m.samples().size() != m.expected())
                throw new IllegalArgumentException("Tracepoint sample count mismatch: " + m.name());
            list.add(new Series(m.name(), m.label(), m.type(), m.samples()));
        }
        if (!(status.equals("active") || status.equals("decode-unavailable")) && !list.isEmpty())
            throw new IllegalArgumentException("Inactive/invalid report cannot contain declarations");
        return new Snapshot(status, error, incomplete, unknown, detail, list);
    }

    private static boolean flag(String value) {
        if (value.equals("0")) return false;
        if (value.equals("1")) return true;
        throw new IllegalArgumentException("Invalid tracepoint report flag");
    }

    private static int count(String value) {
        try {
            int n = Integer.parseInt(value);
            if (n < 0) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid tracepoint count", ex);
        }
    }

    private static long nanoseconds(String value) {
        try {
            long n = Long.parseLong(value);
            if (n < 0) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid tracepoint timestamp", ex);
        }
    }

    private static String decode(String encoded) {
        try {
            byte[] value = Base64.getDecoder().decode(encoded);
            if (value.length > 16384) throw new IllegalArgumentException("Oversized text field");
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value)).toString();
        } catch (IllegalArgumentException | CharacterCodingException ex) {
            throw new IllegalArgumentException("Invalid tracepoint UTF-8/base64 value", ex);
        }
    }
}
