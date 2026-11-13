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
    public record ObservedEvent(Long timestampNs, String eventName,
                                String threadName, String threadId, String kind) { }

    public record Snapshot(List<Interval> intervals, List<ObservedEvent> events,
                           long totalEvents, boolean incomplete,
                           long unknownEvents, long schedulerEvents,
                           String detail, boolean decoderUnavailable,
                           Long traceStartNs, Long traceEndNs) {
        public Snapshot {
            intervals = List.copyOf(intervals);
            events = List.copyOf(events);
        }
    }

    private ThreadTimelineReport() { }

    public static Snapshot parse(String output) {
        if (output == null || output.isEmpty() || output.length() > MAX_CHARACTERS)
            throw new IllegalArgumentException("Missing or oversized thread timeline output");
        String[] lines = output.split("\\r?\\n", -1);
        if (lines.length < 2 || !lines[lines.length - 1].isEmpty())
            throw new IllegalArgumentException("Incomplete thread timeline output");
        String[] header = lines[0].split("\\t", -1);
        if (!"ZEW_THREAD_TIMELINE".equals(header[0])
                || !(header.length == 7 && "1".equals(header[1])
                        || header.length == 9 && "2".equals(header[1])
                        || header.length == 11 && "3".equals(header[1])))
            throw new IllegalArgumentException("Unsupported thread timeline protocol");
        boolean incomplete = flag(header[2]);
        long unknown = positive(header[3]);
        long scheduler = positive(header[4]);
        String detail = decoded(header[5]);
        boolean decoderMissing = flag(header[6]);
        Long traceStart = header.length >= 9 && !header[7].isEmpty() ? positive(header[7]) : null;
        Long traceEnd = header.length >= 9 && !header[8].isEmpty() ? positive(header[8]) : null;
        long totalEvents = header.length == 11 ? positive(header[9]) : 0;
        long expectedEvents = header.length == 11 ? positive(header[10]) : 0;
        if (expectedEvents > 1024 || expectedEvents > totalEvents)
            throw new IllegalArgumentException("Invalid observed event count");
        if ((traceStart == null) != (traceEnd == null)
                || traceStart != null && traceEnd < traceStart)
            throw new IllegalArgumentException("Invalid observed trace bounds");
        List<Interval> rows = new ArrayList<>();
        List<ObservedEvent> events = new ArrayList<>();
        for (int i = 1; i < lines.length - 1; i++) {
            String[] tokens = lines[i].split("\\t", -1);
            if (tokens.length == 6 && "E".equals(tokens[0]) && header.length == 11) {
                if (events.size() >= expectedEvents)
                    throw new IllegalArgumentException("Unexpected extra observed event");
                Long timestamp = tokens[1].isEmpty() ? null : positive(tokens[1]);
                if (timestamp != null && traceStart != null
                        && (timestamp < traceStart || timestamp > traceEnd))
                    throw new IllegalArgumentException("Observed event outside trace bounds");
                String kind = tokens[5];
                if (!kind.matches("[A-Z_]{3,48}"))
                    throw new IllegalArgumentException("Invalid semantic event kind");
                events.add(new ObservedEvent(timestamp, decoded(tokens[2]),
                        decoded(tokens[3]), decoded(tokens[4]), kind));
                continue;
            }
            if (rows.size() >= MAX_ROWS)
                throw new IllegalArgumentException("Timeline exceeds supported row limit");
            if (tokens.length != 6 || !"I".equals(tokens[0]))
                throw new IllegalArgumentException("Malformed timeline interval line " + i);
            long start = positive(tokens[1]);
            long end = positive(tokens[2]);
            if (end <= start || !STATES.contains(tokens[5])
                    || traceStart != null && (start < traceStart || end > traceEnd))
                throw new IllegalArgumentException("Invalid timeline boundaries or state on line " + i);
            BigInteger nanos = BigInteger.valueOf(end).subtract(BigInteger.valueOf(start));
            String micros = nanos.divide(BigInteger.valueOf(1000)).toString()
                    + "." + String.format(java.util.Locale.ROOT, "%03d",
                                         nanos.remainder(BigInteger.valueOf(1000)).intValue());
            rows.add(new Interval(start, end, micros, decoded(tokens[3]), decoded(tokens[4]), tokens[5]));
        }
        if (events.size() != expectedEvents)
            throw new IllegalArgumentException("Missing observed event rows");
        return new Snapshot(rows, events, totalEvents, incomplete, unknown, scheduler,
                detail, decoderMissing, traceStart, traceEnd);
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
