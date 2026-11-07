// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Strict bounded, read-only adapter for saved CTF allocation evidence. */
public final class MemoryTraceReport {
    public static final String NO_EVENTS =
            "No memory allocation events found. Enable CONFIG_TRACING_HEAP or the equivalent "
            + "memory tracing option in the Zephyr build to use this view.";
    public static final String INCOMPLETE =
            "Trace is incomplete; outstanding allocation imbalance cannot be assessed.";
    private static final int MAX_CHARACTERS = 2_000_000;
    private static final int MAX_POOLS = 512;
    private static final int MAX_STEPS = 5_000;

    public record Pool(String name, int allocCount, int freeCount, Integer currentCount,
                       BigInteger currentBytes, BigInteger peakBytes) { }
    public record Step(long timestampNs, Integer outstandingCount, BigInteger outstandingBytes) { }
    public record Snapshot(int memoryEvents, boolean incomplete, boolean decoderUnavailable,
                           boolean countComplete, boolean bytesComplete,
                           Integer currentCount, BigInteger currentBytes,
                           BigInteger peakBytes, Long peakTimestampNs,
                           String message, String imbalance, String diagnostics,
                           boolean stepsLimited, boolean poolsLimited,
                           List<Pool> pools, List<Step> steps) {
        public Snapshot {
            pools = List.copyOf(pools);
            steps = List.copyOf(steps);
        }
        public boolean noEvents() { return memoryEvents == 0; }
    }

    private MemoryTraceReport() { }

    public static Snapshot parse(String report) {
        if (report == null || report.isEmpty() || report.length() > MAX_CHARACTERS)
            throw new IllegalArgumentException("Missing or oversized memory trace report");
        String[] lines = report.split("\\r?\\n", -1);
        if (lines.length < 2 || !lines[lines.length - 1].isEmpty())
            throw new IllegalArgumentException("Truncated memory trace report");
        String[] h = lines[0].split("\\t", -1);
        if (h.length != 16 || !"ZEW_MEMORY".equals(h[0]) || !"1".equals(h[1]))
            throw new IllegalArgumentException("Unsupported memory trace report format");
        int eventCount = count(h[2]);
        boolean incomplete = flag(h[3]);
        boolean missingDecoder = flag(h[4]);
        boolean countComplete = flag(h[5]);
        boolean bytesComplete = flag(h[6]);
        Integer currentCount = optionalCount(h[7]);
        BigInteger currentBytes = optionalBytes(h[8]);
        BigInteger peakBytes = optionalBytes(h[9]);
        Long peakTime = optionalTime(h[10]);
        String message = decode(h[11]);
        String imbalance = decode(h[12]);
        String diagnostics = decode(h[13]);
        boolean stepsLimited = flag(h[14]);
        boolean poolsLimited = flag(h[15]);

        if (!countComplete && currentCount != null
                || !bytesComplete && (currentBytes != null || peakBytes != null || peakTime != null)
                || bytesComplete && !countComplete
                || peakBytes == null && peakTime != null
                || currentBytes != null && currentCount == null
                || incomplete && !imbalance.isEmpty()
                || !imbalance.isEmpty() && (currentCount == null || currentCount <= 0
                        || currentBytes == null || !countComplete || !bytesComplete)
                || eventCount == 0 && !message.equals(NO_EVENTS))
            throw new IllegalArgumentException("Contradictory memory evidence fields");

        List<Pool> pools = new ArrayList<>();
        List<Step> steps = new ArrayList<>();
        boolean inSteps = false;
        long priorTimestamp = -1;
        for (int i = 1; i < lines.length - 1; i++) {
            String[] row = lines[i].split("\\t", -1);
            if (row.length == 7 && row[0].equals("P") && !inSteps) {
                if (pools.size() == MAX_POOLS)
                    throw new IllegalArgumentException("Excessive memory pools");
                String name = decode(row[1]);
                if (name.isEmpty()) throw new IllegalArgumentException("Empty memory pool name");
                pools.add(new Pool(name, count(row[2]), count(row[3]), optionalCount(row[4]),
                                   optionalBytes(row[5]), optionalBytes(row[6])));
            } else if (row.length == 4 && row[0].equals("S")) {
                inSteps = true;
                if (steps.size() == MAX_STEPS)
                    throw new IllegalArgumentException("Excessive memory step samples");
                long timestamp = time(row[1]);
                if (timestamp < priorTimestamp)
                    throw new IllegalArgumentException("Memory samples are not ordered");
                priorTimestamp = timestamp;
                Integer observedCount = optionalCount(row[2]);
                BigInteger observedBytes = optionalBytes(row[3]);
                if (!countComplete && observedCount != null
                        || !bytesComplete && observedBytes != null)
                    throw new IllegalArgumentException("Unproven memory step count or byte amount");
                steps.add(new Step(timestamp, observedCount, observedBytes));
            } else {
                throw new IllegalArgumentException("Malformed memory report row " + (i + 1));
            }
        }
        if (eventCount == 0 && (!pools.isEmpty() || !steps.isEmpty())
                || !stepsLimited && eventCount > 0 && steps.size() > eventCount
                || !imbalance.isEmpty() && !message.equals(imbalance))
            throw new IllegalArgumentException("Memory report event counts or imbalance inconsistent");
        return new Snapshot(eventCount, incomplete, missingDecoder, countComplete, bytesComplete,
                currentCount, currentBytes, peakBytes, peakTime, message, imbalance,
                diagnostics, stepsLimited, poolsLimited, pools, steps);
    }

    private static boolean flag(String input) {
        if ("1".equals(input)) return true;
        if ("0".equals(input)) return false;
        throw new IllegalArgumentException("Invalid memory report boolean");
    }

    private static int count(String input) {
        try {
            int count = Integer.parseInt(input);
            if (count < 0) throw new NumberFormatException();
            return count;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid memory event count", ex);
        }
    }

    private static Integer optionalCount(String input) {
        return input.isEmpty() ? null : count(input);
    }

    private static BigInteger optionalBytes(String input) {
        if (input.isEmpty()) return null;
        try {
            BigInteger value = new BigInteger(input);
            if (value.signum() < 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid memory byte amount", ex);
        }
    }

    private static long time(String input) {
        try {
            long value = Long.parseLong(input);
            if (value < 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid memory timestamp", ex);
        }
    }

    private static Long optionalTime(String input) {
        return input.isEmpty() ? null : time(input);
    }

    private static String decode(String text) {
        try {
            byte[] bytes = Base64.getDecoder().decode(text);
            if (bytes.length > 16_384)
                throw new IllegalArgumentException("Oversized memory report text");
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (IllegalArgumentException | CharacterCodingException ex) {
            throw new IllegalArgumentException("Invalid memory report UTF-8/base64", ex);
        }
    }
}
