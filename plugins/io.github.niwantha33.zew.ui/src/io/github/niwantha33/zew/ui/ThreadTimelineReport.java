// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Read-only, bounded wire protocol from thread_timeline.py. Keeps the SWT view
 * independent of JSON libraries and never interprets raw CTF event names.
 */
public final class ThreadTimelineReport {
    public static final int MAX_CHARACTERS = 2_000_000;
    private static final int MAX_ROWS = 10_000;
    private static final Set<String> STATES =
            Set.of("Running", "Ready", "Blocked", "Sleeping", "ISR", "Unknown");

    public record Interval(long startNs, long endNs, String durationUs,
                           String threadName, String priority, String state) { }

    public record Snapshot(List<Interval> intervals, boolean incomplete,
                           long unknownEvents, long schedulerEvents,
                           String detail, boolean decoderUnavailable) {
        public Snapshot { intervals = List.copyOf(intervals); }
    }

    private ThreadTimelineReport() { }

    public static Snapshot parse(String output) {
        if (output == null || output.isEmpty() || output.length() > MAX_CHARACTERS)
            throw new IllegalArgumentException("Missing or oversized thread timeline output");
        String[] lines = output.split("\\r?\\n", -1);
        if (lines.length < 2 || !lines[lines.length - 1].isEmpty())
            throw new IllegalArgumentException("Incomplete thread timeline output");
        String[] header = lines[0].split("\\t", -1);
        if (header.length != 7 || !"ZEW_THREAD_TIMELINE".equals(header[0])
                || !"1".equals(header[1]))
            throw new IllegalArgumentException("Unsupported thread timeline protocol");
        boolean incomplete = flag(header[2]);
        long unknown = positive(header[3]);
        long scheduler = positive(header[4]);
        String detail = decoded(header[5]);
        boolean decoderMissing = flag(header[6]);
        List<Interval> rows = new ArrayList<>();
        for (int i = 1; i < lines.length - 1; i++) {
            if (rows.size() >= MAX_ROWS)
                throw new IllegalArgumentException("Timeline exceeds supported row limit");
            String[] tokens = lines[i].split("\\t", -1);
            if (tokens.length != 6 || !"I".equals(tokens[0]))
                throw new IllegalArgumentException("Malformed timeline interval line " + i);
            long start = positive(tokens[1]);
            long end = positive(tokens[2]);
            if (end <= start || !STATES.contains(tokens[5]))
                throw new IllegalArgumentException("Invalid timeline boundaries or state on line " + i);
            BigInteger nanos = BigInteger.valueOf(end).subtract(BigInteger.valueOf(start));
            String micros = nanos.divide(BigInteger.valueOf(1000)).toString()
                    + "." + String.format(java.util.Locale.ROOT, "%03d",
                                         nanos.remainder(BigInteger.valueOf(1000)).intValue());
            rows.add(new Interval(start, end, micros, decoded(tokens[3]), decoded(tokens[4]), tokens[5]));
        }
        return new Snapshot(rows, incomplete, unknown, scheduler, detail, decoderMissing);
    }

    private static boolean flag(String text) {
        if ("0".equals(text)) return false;
        if ("1".equals(text)) return true;
        throw new IllegalArgumentException("Invalid timeline boolean flag");
    }

    private static long positive(String text) {
        try {
            long number = Long.parseLong(text);
            if (number < 0) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid unsigned timeline value", ex);
        }
    }

    private static String decoded(String encoded) {
        try {
            var bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length > 16_384) throw new IllegalArgumentException("Oversized timeline text field");
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (IllegalArgumentException | CharacterCodingException ex) {
            throw new IllegalArgumentException("Invalid timeline UTF-8/base64 text", ex);
        }
    }
}
