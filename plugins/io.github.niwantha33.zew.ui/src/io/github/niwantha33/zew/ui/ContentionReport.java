// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/** Strict, read-only wire adapter for the offline semantic contention detector. */
public final class ContentionReport {
    public static final int MAX_CHARACTERS = 2_000_000;
    public static final int MAX_FINDINGS = 5_000;

    public record Finding(String kind, String objectName, String waiter,
                          String holder, long durationNs, String medium,
                          Integer waiterPriority, Integer holderPriority,
                          Integer mediumPriority, boolean holderUnknown,
                          String explanation) {
        public String durationUs() {
            return (durationNs / 1_000) + "."
                    + String.format(Locale.ROOT, "%03d", durationNs % 1_000);
        }
    }

    public record Snapshot(List<Finding> findings, boolean inversionEnabled,
                           boolean incomplete, boolean decoderUnavailable,
                           int resourceEvents, String error) {
        public Snapshot { findings = List.copyOf(findings); }
    }

    private ContentionReport() { }

    public static Snapshot parse(String report) {
        if (report == null || report.isEmpty() || report.length() > MAX_CHARACTERS)
            throw new IllegalArgumentException("Missing or oversized contention report");
        String[] lines = report.split("\\r?\\n", -1);
        if (lines.length < 2 || !lines[lines.length - 1].isEmpty())
            throw new IllegalArgumentException("Incomplete contention report");
        String[] head = lines[0].split("\\t", -1);
        if (head.length != 7 || !"ZEW_CONTENTION".equals(head[0])
                || !"1".equals(head[1]))
            throw new IllegalArgumentException("Unsupported contention report protocol");
        boolean inversionEnabled = flag(head[2]);
        boolean incomplete = flag(head[3]);
        boolean decoderUnavailable = flag(head[4]);
        int resourceEvents = nonnegativeInt(head[5]);
        String error = decode(head[6]);

        List<Finding> findings = new ArrayList<>();
        for (int i = 1; i < lines.length - 1; i++) {
            if (findings.size() == MAX_FINDINGS)
                throw new IllegalArgumentException("Too many contention findings");
            String[] row = lines[i].split("\\t", -1);
            if (row.length != 12 || !"F".equals(row[0])
                    || !(row[1].equals("Inversion") || row[1].equals("Long Wait")))
                throw new IllegalArgumentException("Invalid contention finding on line " + (i + 1));
            String objectName = decode(row[2]);
            String waiter = decode(row[3]);
            String holder = decode(row[4]);
            long duration = nonnegativeLong(row[5]);
            String medium = decode(row[6]);
            Integer waiterPriority = optionalInt(row[7]);
            Integer holderPriority = optionalInt(row[8]);
            Integer mediumPriority = optionalInt(row[9]);
            boolean holderUnknown = flag(row[10]);
            String explanation = decode(row[11]);
            if (holderUnknown && !holder.isEmpty() || !holderUnknown && holder.isEmpty()
                    || row[1].equals("Inversion") && (
                            !inversionEnabled || medium.isEmpty() || holderUnknown
                            || waiterPriority == null || holderPriority == null
                            || mediumPriority == null
                            || !(waiterPriority < mediumPriority
                                    && mediumPriority < holderPriority))
                    || explanation.isEmpty())
                throw new IllegalArgumentException("Unsupported or contradictory finding on line " + (i + 1));
            findings.add(new Finding(row[1], objectName, waiter, holder, duration, medium,
                                     waiterPriority, holderPriority, mediumPriority,
                                     holderUnknown, explanation));
        }
        return new Snapshot(findings, inversionEnabled, incomplete,
                            decoderUnavailable, resourceEvents, error);
    }

    private static boolean flag(String value) {
        if (value.equals("0")) return false;
        if (value.equals("1")) return true;
        throw new IllegalArgumentException("Invalid contention boolean flag");
    }

    private static int nonnegativeInt(String value) {
        try {
            int n = Integer.parseInt(value);
            if (n < 0) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid contention count", ex);
        }
    }

    private static long nonnegativeLong(String value) {
        try {
            long n = Long.parseLong(value);
            if (n < 0) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid contention duration", ex);
        }
    }

    private static Integer optionalInt(String value) {
        if (value.isEmpty()) return null;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid thread priority", ex);
        }
    }

    private static String decode(String encoded) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length > 16_384)
                throw new IllegalArgumentException("Oversized contention text");
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid contention UTF-8/base64 text", ex);
        }
    }
}
