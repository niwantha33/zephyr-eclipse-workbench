// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read-only, on-demand JSON contract shared by saved-CTF CPU and contention views. */
public final class OfflineTraceAnalysisService {
    public enum ErrorKind { TRACE_UNREADABLE, MODULE_FAILED, INCOMPLETE }

    public static final class AnalysisFailure extends Exception {
        private final ErrorKind kind;
        private final CpuLoadResult cpu;
        private final ContentionResult contention;

        AnalysisFailure(ErrorKind kind, String message, CpuLoadResult cpu, ContentionResult contention) {
            super(message);
            this.kind = kind;
            this.cpu = cpu;
            this.contention = contention;
        }
        public ErrorKind kind() { return kind; }
        public CpuLoadResult cpu() { return cpu; }
        public ContentionResult contention() { return contention; }
    }

    public record Request(String mode, Path trace, Path build, Long startNs, Long endNs,
                          long thresholdUs) {}
    @FunctionalInterface public interface Bridge {
        String invoke(Request request) throws Exception;
    }

    public record CpuThread(String name, long runningNs, BigDecimal percentage) {}
    public enum ObservedState { SCHEDULED, READY, BLOCKED, SLEEPING, UNKNOWN }
    /** Scheduled intervals include preempting ISR time; CPU rows exclude it. */
    public record CpuStateInterval(String name, ObservedState state, long startNs,
                                   long endNs, boolean scheduledIncludesIsr) {}
    public record CpuLoadResult(Path trace, Path build, String status, boolean incomplete, String reason,
                                Long firstNs, Long lastNs, Long startNs, Long endNs,
                                int schedulerEvents, String cpuStatus, BigDecimal cpuBusyPercent,
                                Long unknownNs, List<CpuThread> rows, Long isrNs, BigDecimal isrPercentage,
                                List<CpuStateInterval> stateIntervals) {
        public CpuLoadResult {
            rows = List.copyOf(rows);
            stateIntervals = List.copyOf(stateIntervals);
        }
        /** Source-compatible constructor for older saved-CTF reports and tests. */
        public CpuLoadResult(Path trace, Path build, String status, boolean incomplete, String reason,
                             Long firstNs, Long lastNs, Long startNs, Long endNs,
                             int schedulerEvents, String cpuStatus, BigDecimal cpuBusyPercent,
                             Long unknownNs, List<CpuThread> rows, Long isrNs, BigDecimal isrPercentage) {
            this(trace, build, status, incomplete, reason, firstNs, lastNs, startNs, endNs,
                    schedulerEvents, cpuStatus, cpuBusyPercent, unknownNs, rows,
                    isrNs, isrPercentage, List.of());
        }
    }
    /** Explicit ownership state; unknown is not represented by an invented name. */
    public enum HolderState { KNOWN, UNKNOWN }
    public record Holder(HolderState state, String name) {
        public Holder {
            if (state == null || (state == HolderState.KNOWN && (name == null || name.isBlank()))
                    || (state == HolderState.UNKNOWN && name != null))
                throw new IllegalArgumentException("Invalid observed holder state.");
        }
    }
    public record ContentionFinding(String kind, String object, String waiter, String holder,
                                    boolean holderUnknown, long durationNs, String explanation) {
        /** Typed projection preserves the existing JSON/constructor compatibility. */
        public Holder owner() {
            return holderUnknown ? new Holder(HolderState.UNKNOWN, null)
                    : new Holder(HolderState.KNOWN, holder);
        }
    }
    public record ContentionResult(Path trace, Path build, boolean incomplete, String reason,
                                   long thresholdUs, boolean inversionEnabled, int resourceEvents,
                                   List<ContentionFinding> findings) {
        public ContentionResult { findings = List.copyOf(findings); }
    }

    private record CacheKey(Path trace, long streamMtime, long streamSize, long metadataMtime,
                            long metadataSize, Path build, String mode, Long startNs,
                            Long endNs, long thresholdUs) {}
    private final Bridge bridge;
    private final Map<CacheKey, Object> cache = new HashMap<>();

    /** Construction never accesses the file system or starts Python. */
    public OfflineTraceAnalysisService(Bridge bridge) {
        this.bridge = java.util.Objects.requireNonNull(bridge);
    }

    public synchronized CpuLoadResult analyseCpu(Path trace, Path build, Long startNs, Long endNs)
            throws AnalysisFailure {
        if ((startNs == null) != (endNs == null))
            throw failure(ErrorKind.MODULE_FAILED, "Both time window boundaries are required.");
        if (startNs != null && startNs >= endNs)
            throw failure(ErrorKind.MODULE_FAILED, "End of CPU window must be greater than start.");
        CacheKey key = key(trace, build, "cpu", startNs, endNs, 1000);
        Object cached = cache.get(key);
        CpuLoadResult result;
        if (cached instanceof CpuLoadResult cpu) result = cpu;
        else {
            Map<String, String> fields = execute(new Request("cpu", key.trace, key.build, startNs, endNs, 1000));
            require(fields, "kind", "cpu");
            List<CpuThread> rows = new ArrayList<>();
            for (int i = 0; fields.containsKey("cpu.rows[" + i + "].name"); i++) {
                String at = "cpu.rows[" + i + "].";
                rows.add(new CpuThread(string(fields, at + "name"),
                        integer(fields, at + "running_ns"), decimal(fields, at + "percentage")));
                if (i >= 10000) throw failure(ErrorKind.MODULE_FAILED, "CPU row limit exceeded.");
            }
            List<CpuStateInterval> states = new ArrayList<>();
            for (int i = 0; fields.containsKey("cpu.thread_states[" + i + "].state"); i++) {
                if (i >= 10000) throw failure(ErrorKind.MODULE_FAILED, "CPU state interval limit exceeded.");
                String at = "cpu.thread_states[" + i + "].";
                final ObservedState state;
                try {
                    state = ObservedState.valueOf(string(fields, at + "state").toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException invalid) {
                    throw failure(ErrorKind.MODULE_FAILED, "Unsupported observed thread state.");
                }
                states.add(new CpuStateInterval(string(fields, at + "name"), state,
                        integer(fields, at + "start_ns"), integer(fields, at + "end_ns"),
                        booleanField(fields, at + "scheduled_includes_isr")));
            }
            result = new CpuLoadResult(key.trace, key.build, string(fields, "status"),
                    booleanField(fields, "incomplete"), string(fields, "reason"),
                    nullableInteger(fields, "first_timestamp_ns"), nullableInteger(fields, "last_timestamp_ns"),
                    nullableInteger(fields, "start_ns"), nullableInteger(fields, "end_ns"),
                    intField(fields, "scheduler_events"), string(fields, "cpu.status"),
                    nullableDecimal(fields, "cpu.cpu_busy_percent"),
                    nullableInteger(fields, "cpu.unknown_ns"), rows,
                    nullableInteger(fields, "cpu.isr_ns"), nullableDecimal(fields, "cpu.isr_percentage"),
                    states);
            validateCpu(result);
            store(key, result);
        }
        if (result.incomplete()) throw new AnalysisFailure(ErrorKind.INCOMPLETE,
                result.reason().isBlank() ? "Saved CTF is incomplete." : result.reason(), result, null);
        return result;
    }

    public synchronized ContentionResult analyseContention(Path trace, Path build, long thresholdUs)
            throws AnalysisFailure {
        if (thresholdUs < 0 || thresholdUs > 86_400_000_000L)
            throw failure(ErrorKind.MODULE_FAILED, "Threshold must be 0..86400000000 us.");
        CacheKey key = key(trace, build, "contention", null, null, thresholdUs);
        Object cached = cache.get(key);
        ContentionResult result;
        if (cached instanceof ContentionResult c) result = c;
        else {
            Map<String, String> fields = execute(new Request("contention", key.trace, key.build,
                    null, null, thresholdUs));
            require(fields, "kind", "contention");
            List<ContentionFinding> findings = new ArrayList<>();
            for (int i = 0; fields.containsKey("findings[" + i + "].kind"); i++) {
                String at = "findings[" + i + "].";
                String kind = string(fields, at + "kind");
                if (!kind.equals("Inversion") && !kind.equals("Long Wait"))
                    throw failure(ErrorKind.MODULE_FAILED, "Unknown contention finding kind.");
                boolean unknown = booleanField(fields, at + "holder_unknown");
                String holder = nullableString(fields, at + "holder");
                if (unknown && holder != null)
                    throw failure(ErrorKind.MODULE_FAILED, "Unknown holder must have a null name.");
                if (!unknown && (holder == null || holder.isBlank()))
                    throw failure(ErrorKind.MODULE_FAILED, "Known holder must have an observed name.");
                if (integer(fields, at + "duration_ns") < 0)
                    throw failure(ErrorKind.MODULE_FAILED, "Negative contention duration.");
                findings.add(new ContentionFinding(kind, string(fields, at + "object"),
                        string(fields, at + "waiter"), holder, unknown,
                        integer(fields, at + "duration_ns"), string(fields, at + "explanation")));
                if (i >= 5000) throw failure(ErrorKind.MODULE_FAILED, "Contention finding limit exceeded.");
            }
            result = new ContentionResult(key.trace, key.build, booleanField(fields, "incomplete"),
                    string(fields, "reason"), integer(fields, "threshold_us"),
                    booleanField(fields, "inversion_enabled"), intField(fields, "resource_events"),
                    findings);
            store(key, result);
        }
        if (result.incomplete()) throw new AnalysisFailure(ErrorKind.INCOMPLETE,
                result.reason().isBlank() ? "Saved CTF is incomplete." : result.reason(), null, result);
        return result;
    }

    private CacheKey key(Path trace, Path build, String mode, Long start, Long end, long threshold)
            throws AnalysisFailure {
        if (trace == null || build == null)
            throw failure(ErrorKind.TRACE_UNREADABLE, "Select a saved CTF trace and a build.");
        Path folder = trace.toAbsolutePath().normalize();
        if (Files.isDirectory(folder.resolve("ctf"))) folder = folder.resolve("ctf");
        Path stream = folder.resolve("channel0_0");
        Path metadata = folder.resolve("metadata");
        if (Files.isSymbolicLink(folder) || !Files.isDirectory(folder)
                || !Files.isRegularFile(stream, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)
                || !Files.isReadable(stream) || !Files.isReadable(metadata))
            throw failure(ErrorKind.TRACE_UNREADABLE,
                    "Saved CTF is missing or unreadable: require metadata and channel0_0 in " + folder);
        Path selectedBuild = build.toAbsolutePath().normalize();
        if (!Files.isDirectory(selectedBuild))
            throw failure(ErrorKind.TRACE_UNREADABLE, "Selected build directory is missing: " + selectedBuild);
        try {
            // Fingerprint actual CTF members, not just the directory mtime.
            return new CacheKey(folder, Files.getLastModifiedTime(stream).toMillis(), Files.size(stream),
                    Files.getLastModifiedTime(metadata).toMillis(), Files.size(metadata),
                    selectedBuild, mode, start, end, threshold);
        } catch (java.io.IOException error) {
            throw failure(ErrorKind.TRACE_UNREADABLE, "Cannot read trace file identity: " + error.getMessage());
        }
    }

    private Map<String, String> execute(Request request) throws AnalysisFailure {
        final String json;
        try {
            json = bridge.invoke(request);
        } catch (Exception error) {
            throw failure(ErrorKind.MODULE_FAILED, "Offline Python analysis failed: "
                    + bounded(error.getMessage()));
        }
        try {
            Map<String, String> fields = new HashMap<>();
            for (MemoryProfilerJson.Row item : MemoryProfilerJson.parseFields(json)) {
                if (fields.putIfAbsent(item.field(), item.rawValue()) != null)
                    throw new IllegalArgumentException("Duplicate JSON field: " + item.field());
            }
            if (integer(fields, "schema_version") != 1)
                throw new IllegalArgumentException("Unsupported offline trace JSON version.");
            String status = string(fields, "status");
            if (!List.of("ok", "incomplete", "no_scheduler").contains(status))
                throw new IllegalArgumentException("Unsupported analysis status: " + status);
            return fields;
        } catch (IllegalArgumentException error) {
            throw failure(ErrorKind.MODULE_FAILED, "Invalid offline analysis JSON: " + error.getMessage());
        }
    }

    private static void validateCpu(CpuLoadResult result) throws AnalysisFailure {
        if (!List.of("ok", "incomplete", "no_scheduler").contains(result.status())
                || result.schedulerEvents() < 0
                || ("ok".equals(result.status()) && result.schedulerEvents() == 0)
                || ("no_scheduler".equals(result.status()) && result.schedulerEvents() != 0)
                || ("incomplete".equals(result.status()) && !result.incomplete()))
            throw failure(ErrorKind.MODULE_FAILED, "Inconsistent scheduler coverage status.");
        if (!"ok".equals(result.status())) {
            // Partial and no-scheduler reports must not advertise numerical estimates.
            if (!result.rows().isEmpty() || result.isrPercentage() != null
                    || !result.stateIntervals().isEmpty())
                throw failure(ErrorKind.MODULE_FAILED, "Unavailable CPU evidence contains percentages.");
            return;
        }
        if (result.incomplete() || result.startNs() == null || result.endNs() == null
                || result.endNs() <= result.startNs()
                || result.unknownNs() == null || result.unknownNs() < 0
                || result.isrNs() == null || result.isrNs() < 0
                || result.isrPercentage() == null)
            throw failure(ErrorKind.MODULE_FAILED, "Missing exclusive CPU window evidence.");
        long total = 0;
        for (CpuThread row : result.rows()) {
            if (row.runningNs() < 0 || !percentage(row.percentage()))
                throw failure(ErrorKind.MODULE_FAILED, "Invalid observed CPU thread segment.");
            try { total = Math.addExact(total, row.runningNs()); }
            catch (ArithmeticException overflow) {
                throw failure(ErrorKind.MODULE_FAILED, "CPU segment duration exceeds limits.");
            }
        }
        long length;
        try {
            total = Math.addExact(Math.addExact(total, result.isrNs()), result.unknownNs());
            length = Math.subtractExact(result.endNs(), result.startNs());
        } catch (ArithmeticException overflow) {
            throw failure(ErrorKind.MODULE_FAILED, "CPU window duration exceeds limits.");
        }
        if (total != length || !percentage(result.isrPercentage())
                || (result.cpuBusyPercent() != null && !percentage(result.cpuBusyPercent())))
            throw failure(ErrorKind.MODULE_FAILED, "Exclusive CPU segments do not cover the window.");
        for (CpuStateInterval state : result.stateIntervals()) {
            if (state.startNs() >= state.endNs() || state.startNs() < result.startNs()
                    || state.endNs() > result.endNs()
                    || (state.scheduledIncludesIsr() != (state.state() == ObservedState.SCHEDULED)))
                throw failure(ErrorKind.MODULE_FAILED, "Invalid scheduled/observed thread state boundary.");
        }
    }

    private static boolean percentage(BigDecimal percent) {
        return percent != null && percent.compareTo(BigDecimal.ZERO) >= 0
                && percent.compareTo(BigDecimal.valueOf(100)) <= 0;
    }

    private static String bounded(String message) {
        if (message == null || message.isBlank()) return "no diagnostic supplied";
        String flat = message.replaceAll("[\\p{Cntrl}]", " ").strip();
        return flat.length() <= 240 ? flat : flat.substring(0, 240) + "…";
    }

    private void store(CacheKey key, Object result) {
        // Bounded cache. The fingerprint makes changed content a different key.
        if (cache.size() >= 12) cache.clear();
        cache.put(key, result);
    }

    private static AnalysisFailure failure(ErrorKind kind, String detail) {
        return new AnalysisFailure(kind, detail, null, null);
    }

    private static void require(Map<String, String> fields, String key, String expected)
            throws AnalysisFailure {
        if (!expected.equals(string(fields, key)))
            throw failure(ErrorKind.MODULE_FAILED, "Unexpected JSON report kind.");
    }
    private static String string(Map<String, String> fields, String key) throws AnalysisFailure {
        String raw = fields.get(key);
        if (raw == null) throw failure(ErrorKind.MODULE_FAILED, "Missing JSON field: " + key);
        try { return MemoryProfilerJson.unquote(raw); }
        catch (IllegalArgumentException error) {
            throw failure(ErrorKind.MODULE_FAILED, "Invalid string field " + key + ": " + error.getMessage());
        }
    }
    private static String nullableString(Map<String, String> fields, String key) throws AnalysisFailure {
        return "null".equals(fields.get(key)) ? null : string(fields, key);
    }
    private static long integer(Map<String, String> fields, String key) throws AnalysisFailure {
        try { return Long.parseLong(fields.get(key)); }
        catch (RuntimeException error) { throw failure(ErrorKind.MODULE_FAILED, "Invalid integer field: " + key); }
    }
    private static int intField(Map<String, String> fields, String key) throws AnalysisFailure {
        long value = integer(fields, key);
        if (value < 0 || value > Integer.MAX_VALUE)
            throw failure(ErrorKind.MODULE_FAILED, "Integer out of range: " + key);
        return (int) value;
    }
    private static Long nullableInteger(Map<String, String> fields, String key) throws AnalysisFailure {
        return "null".equals(fields.get(key)) ? null : integer(fields, key);
    }
    private static BigDecimal decimal(Map<String, String> fields, String key) throws AnalysisFailure {
        try { return new BigDecimal(fields.get(key)); }
        catch (RuntimeException error) { throw failure(ErrorKind.MODULE_FAILED, "Invalid decimal field: " + key); }
    }
    private static BigDecimal nullableDecimal(Map<String, String> fields, String key)
            throws AnalysisFailure {
        return "null".equals(fields.get(key)) ? null : decimal(fields, key);
    }
    private static boolean booleanField(Map<String, String> fields, String key) throws AnalysisFailure {
        String value = fields.get(key);
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw failure(ErrorKind.MODULE_FAILED, "Invalid boolean field: " + key);
    }
}
