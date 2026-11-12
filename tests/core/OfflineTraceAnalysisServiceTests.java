// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.ui.OfflineTraceAnalysisService;
import io.github.niwantha33.zew.ui.OfflineTraceViewModels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** Pure-JDK fake-bridge tests: no Python, Eclipse, or target required. */
public final class OfflineTraceAnalysisServiceTests {
    private static final String CPU = """
            {"schema_version":1,"kind":"cpu","status":"ok","incomplete":false,
             "reason":"","first_timestamp_ns":0,"last_timestamp_ns":1000000,
             "start_ns":0,"end_ns":1000000,"scheduler_events":4,
             "cpu":{"status":"partial","cpu_busy_percent":null,"unknown_ns":0,
               "rows":[{"name":"worker","running_ns":250000,"percentage":25.0},
                       {"name":"idle","running_ns":650000,"percentage":65.0}],
               "isr_ns":100000,"isr_percentage":10.0}}
            """;
    private static final String CONTENTION = """
            {"schema_version":1,"kind":"contention","status":"ok","incomplete":false,
             "reason":"","first_timestamp_ns":0,"last_timestamp_ns":3000000,
             "threshold_us":1000,"inversion_enabled":false,"resource_events":2,
             "findings":[{"kind":"Long Wait","object":"lock_A","waiter":"high",
                          "holder":null,"holder_unknown":true,"duration_ns":1500000,
                          "explanation":"Holder not observed"}],
             "unattributed_events":2,
             "objects":[{"kind":"Message queue","object":"object ID 0x40 (name unavailable)",
               "take_get":2,"give_put":3,"failures":1,"blocking_events":2,
               "paired_blocks":1,"long_blocks":1,"max_blocked_ns":2500000,
               "unpaired_blocks":1}]}
            """;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void expect(OfflineTraceAnalysisService.ErrorKind kind, Checked run)
            throws Exception {
        try { run.run(); throw new AssertionError("Expected " + kind); }
        catch (OfflineTraceAnalysisService.AnalysisFailure error) {
            check(error.kind() == kind, "typed failure: " + kind);
        }
    }
    @FunctionalInterface interface Checked { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("zew-service-test-");
        Path ctf = Files.createDirectory(root.resolve("ctf"));
        Path build = Files.createDirectory(root.resolve("build"));
        Path stream = ctf.resolve("channel0_0"), metadata = ctf.resolve("metadata");
        Files.writeString(stream, "events");
        Files.writeString(metadata, "metadata");
        try {
            AtomicInteger calls = new AtomicInteger();
            var service = new OfflineTraceAnalysisService(request -> {
                calls.incrementAndGet();
                return request.mode().equals("cpu") ? CPU : CONTENTION;
            });
            check(calls.get() == 0, "construction is side-effect free");
            var cpu = service.analyseCpu(ctf, build, null, null);
            check(cpu.schedulerEvents() == 4 && cpu.rows().size() == 2
                    && cpu.cpuBusyPercent() == null, "typed CPU JSON and withheld busy percent");
            check(service.analyseCpu(ctf, build, null, null) == cpu && calls.get() == 1,
                    "same trace/build/window served from cache");
            String stateJson = CPU.replace("\"isr_percentage\":10.0}",
                    "\"isr_percentage\":10.0,\"thread_states\":["
                    + "{\"name\":\"worker\",\"state\":\"Scheduled\",\"start_ns\":0,"
                    + "\"end_ns\":350000,\"scheduled_includes_isr\":true},"
                    + "{\"name\":\"idle\",\"state\":\"Sleeping\",\"start_ns\":0,"
                    + "\"end_ns\":350000,\"scheduled_includes_isr\":false}]}");
            var states = new OfflineTraceAnalysisService(request -> stateJson)
                    .analyseCpu(ctf, build, null, null).stateIntervals();
            check(states.size() == 2 && states.getFirst().state()
                    == OfflineTraceAnalysisService.ObservedState.SCHEDULED
                    && states.getFirst().scheduledIncludesIsr()
                    && states.getLast().state() == OfflineTraceAnalysisService.ObservedState.SLEEPING,
                    "typed observed states distinguish ISR-inclusive schedule from exclusive CPU time");
            expect(OfflineTraceAnalysisService.ErrorKind.MODULE_FAILED,
                    () -> new OfflineTraceAnalysisService(request -> stateJson.replace(
                            "\"end_ns\":350000,\"scheduled_includes_isr\":true",
                            "\"end_ns\":350000,\"scheduled_includes_isr\":false"))
                            .analyseCpu(ctf, build, null, null));
            Files.writeString(stream, "changed stream with a different size");
            service.analyseCpu(ctf, build, null, null);
            check(calls.get() == 2, "changed stream fingerprint invalidates cache");
            var report = service.analyseContention(ctf, build, 1000);
            check(!report.inversionEnabled() && report.findings().size() == 1,
                    "typed contention finding with no numeric priorities");
            check(report.findings().getFirst().owner().state()
                    == OfflineTraceAnalysisService.HolderState.UNKNOWN
                    && report.findings().getFirst().owner().name() == null,
                    "holder is a typed, explicit unknown");
            check(report.unattributedEvents() == 2 && report.objects().size() == 1,
                    "typed object activity and unattributed resource counts");
            var queue = report.objects().getFirst();
            check(queue.kind().equals("Message queue") && queue.takeGet() == 2
                    && queue.givePut() == 3 && queue.longBlocks() == 1
                    && queue.unpairedBlocks() == 1 && queue.maxBlockedNs() == 2500000,
                    "explicit queue operation outcome and paired blocking evidence");
            var projection = OfflineTraceViewModels.contention(report);
            check(projection.noPriorities() && projection.rows().size() == 1
                    && projection.rows().getFirst().holder().equals("unknown")
                    && projection.rows().getFirst().kind().equals("Long wait"),
                    "unknown holder and long wait shown while inversions disabled");
            var table = OfflineTraceViewModels.cpu(cpu);
            check(table.rows().size() == 3
                    && table.rows().getFirst().threadName().equals("idle")
                    && table.rows().getFirst().percentage().equals("65.00%")
                    && table.rows().get(2).threadName().startsWith("ISR"),
                    "CPU table sorts descending and labels the separate ISR row");
            expect(OfflineTraceAnalysisService.ErrorKind.TRACE_UNREADABLE,
                    () -> service.analyseCpu(root.resolve("absent"), build, null, null));
            expect(OfflineTraceAnalysisService.ErrorKind.MODULE_FAILED,
                    () -> service.analyseCpu(ctf, build, 10L, 10L));
            expect(OfflineTraceAnalysisService.ErrorKind.MODULE_FAILED,
                    () -> new OfflineTraceAnalysisService(request -> CPU
                            .replace("\"running_ns\":650000", "\"running_ns\":850000"))
                            .analyseCpu(ctf, build, null, null));
            expect(OfflineTraceAnalysisService.ErrorKind.MODULE_FAILED,
                    () -> new OfflineTraceAnalysisService(request -> CPU
                            .replace("\"percentage\":25.0", "\"percentage\":75.0"))
                            .analyseCpu(ctf, build, null, null));
            expect(OfflineTraceAnalysisService.ErrorKind.MODULE_FAILED,
                    () -> new OfflineTraceAnalysisService(request -> CPU
                            .replace("\"isr_percentage\":10.0", "\"isr_percentage\":100.0"))
                            .analyseCpu(ctf, build, null, null));
            expect(OfflineTraceAnalysisService.ErrorKind.MODULE_FAILED,
                    () -> new OfflineTraceAnalysisService(request -> CPU
                            .replace("\"scheduler_events\":4", "\"scheduler_events\":0"))
                            .analyseCpu(ctf, build, null, null));
            expect(OfflineTraceAnalysisService.ErrorKind.MODULE_FAILED,
                    () -> new OfflineTraceAnalysisService(request -> CONTENTION
                            .replace("\"holder\":null,\"holder_unknown\":true",
                                    "\"holder\":\"invented\",\"holder_unknown\":true"))
                            .analyseContention(ctf, build, 1000));
            expect(OfflineTraceAnalysisService.ErrorKind.MODULE_FAILED,
                    () -> new OfflineTraceAnalysisService(request -> CONTENTION
                            .replace("\"holder\":null,\"holder_unknown\":true",
                                    "\"holder\":null,\"holder_unknown\":false"))
                            .analyseContention(ctf, build, 1000));
            expect(OfflineTraceAnalysisService.ErrorKind.MODULE_FAILED,
                    () -> new OfflineTraceAnalysisService(request -> CONTENTION
                            .replace("\"unpaired_blocks\":1", "\"unpaired_blocks\":0"))
                            .analyseContention(ctf, build, 1000));
            expect(OfflineTraceAnalysisService.ErrorKind.MODULE_FAILED,
                    () -> new OfflineTraceAnalysisService(request -> CONTENTION
                            .replace("\"max_blocked_ns\":2500000", "\"max_blocked_ns\":null"))
                            .analyseContention(ctf, build, 1000));
            var failed = new OfflineTraceAnalysisService(request -> {
                throw new java.io.IOException("stderr: Python module failure");
            });
            try {
                failed.analyseCpu(ctf, build, null, null);
                throw new AssertionError("Missing typed module failure");
            } catch (OfflineTraceAnalysisService.AnalysisFailure error) {
                check(error.kind() == OfflineTraceAnalysisService.ErrorKind.MODULE_FAILED
                        && error.getMessage().contains("stderr: Python module failure"),
                        "module stderr retained as typed error");
            }
            var traceback = new OfflineTraceAnalysisService(
                    request -> { throw new IllegalStateException(
                            "Traceback (most recent call last):\n  at module.py:10\n"
                                    + "x".repeat(4096)); });
            try {
                traceback.analyseCpu(ctf, build, null, null);
                throw new AssertionError("traceback should become a typed error");
            } catch (OfflineTraceAnalysisService.AnalysisFailure error) {
                check(error.kind() == OfflineTraceAnalysisService.ErrorKind.MODULE_FAILED
                        && error.getMessage().length() < 241
                        && !error.getMessage().contains("module.py"),
                        "raw stack trace must never escape the typed UI service");
            }
            String truncated = """
                    {"schema_version":1,"kind":"cpu","status":"incomplete","incomplete":true,
                     "reason":"truncated","first_timestamp_ns":0,"last_timestamp_ns":1000000,
                     "start_ns":0,"end_ns":1000000,"scheduler_events":4,
                     "cpu":{"status":"unavailable","cpu_busy_percent":null,"unknown_ns":null,
                       "rows":[],"isr_ns":null,"isr_percentage":null}}
                    """;
            var partial = new OfflineTraceAnalysisService(request -> truncated);
            try {
                partial.analyseCpu(ctf, build, null, null);
                throw new AssertionError("Expected incomplete error");
            } catch (OfflineTraceAnalysisService.AnalysisFailure error) {
                check(error.kind() == OfflineTraceAnalysisService.ErrorKind.INCOMPLETE
                        && error.cpu() != null && error.cpu().incomplete(),
                        "typed incomplete error retains observed data for warning banner");
                check(OfflineTraceViewModels.cpu(error.cpu()).incomplete(), "CPU banner state");
            }
            String noEvents = """
                    {"schema_version":1,"kind":"cpu","status":"no_scheduler","incomplete":false,
                     "reason":"No recognized scheduler transitions","first_timestamp_ns":0,
                     "last_timestamp_ns":1000000,"start_ns":0,"end_ns":1000000,
                     "scheduler_events":0,"cpu":{"status":"unavailable",
                     "cpu_busy_percent":null,"unknown_ns":null,"rows":[],
                     "isr_ns":null,"isr_percentage":null}}
                    """;
            var empty = new OfflineTraceAnalysisService(request -> noEvents)
                    .analyseCpu(ctf, build, null, null);
            var noScheduler = OfflineTraceViewModels.cpu(empty);
            check(noScheduler.noScheduler() && noScheduler.rows().isEmpty()
                    && noScheduler.message().equals(OfflineTraceViewModels.NO_SCHEDULER),
                    "no scheduler message and hidden table state");
            check(OfflineTraceViewModels.CPU_DISCLAIMER.equals(
                    "Derived from context-switch events. Not a hardware measurement."),
                    "verbatim permanent CPU disclaimer");
            check(OfflineTraceViewModels.CONTENTION_DISCLAIMER.equals(
                    "Findings are inferred from trace events. A reported priority inversion means "
                    + "the pattern was observed; it does not prove a deadline was missed."),
                    "verbatim permanent contention disclaimer");
            check(OfflineTraceViewModels.contention(new OfflineTraceAnalysisService.ContentionResult(
                    ctf, build, false, "", 1000, true, 0, java.util.List.of()))
                    .message().equals(OfflineTraceViewModels.CONTENTION_EMPTY),
                    "zero-findings explicit empty state");
        } finally {
            Files.deleteIfExists(stream);
            Files.deleteIfExists(metadata);
            Files.deleteIfExists(ctf);
            Files.deleteIfExists(build);
            Files.deleteIfExists(root);
        }
        System.out.println("PASS OfflineTraceAnalysisServiceTests");
    }
}
