// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure, testable UI projections. These are observations, never inferred target state. */
public final class OfflineTraceViewModels {
    public static final String CPU_DISCLAIMER =
            "Derived from context-switch events. Not a hardware measurement.";
    public static final String CPU_INCOMPLETE =
            "Trace incomplete. Events after the truncation point are missing. Percentages below do not sum to 100.";
    public static final String NO_SCHEDULER =
            "No scheduler events found. Enable CONFIG_TRACING and scheduler tracing in the Zephyr build to use this view.";
    public static final String CONTENTION_DISCLAIMER =
            "Findings are inferred from trace events. A reported priority inversion means the pattern was observed; it does not prove a deadline was missed.";
    public static final String CONTENTION_EMPTY = "No contention patterns detected in this trace.";
    public static final String NO_PRIORITIES =
            "Priority-inversion findings disabled: no numeric thread priorities were observed; long waits remain available.";

    public record CpuRow(String threadName, String runningUs, String percentage) {}
    public record CpuPresentation(List<CpuRow> rows, boolean incomplete, boolean noScheduler,
                                  String message) {
        public CpuPresentation { rows = List.copyOf(rows); }
    }
    public record ContentionRow(String kind, String object, String waiter, String holder,
                                String durationUs, String explanation) {}
    public record ContentionPresentation(List<ContentionRow> rows, boolean incomplete,
                                        boolean noPriorities, String message) {
        public ContentionPresentation { rows = List.copyOf(rows); }
    }

    private OfflineTraceViewModels() {}

    public static CpuPresentation cpu(OfflineTraceAnalysisService.CpuLoadResult result) {
        boolean missing = result.schedulerEvents() == 0 || "no_scheduler".equals(result.status());
        if (missing) return new CpuPresentation(List.of(), result.incomplete(), true, NO_SCHEDULER);
        var threads = new ArrayList<OfflineTraceAnalysisService.CpuThread>(result.rows());
        threads.sort(Comparator.comparing(OfflineTraceAnalysisService.CpuThread::percentage).reversed()
                .thenComparing(OfflineTraceAnalysisService.CpuThread::name));
        var rows = new ArrayList<CpuRow>();
        for (var thread : threads)
            rows.add(new CpuRow(thread.name().isBlank() ? "(unnamed thread)" : thread.name(),
                    micros(thread.runningNs()), percent(thread.percentage())));
        if (result.isrNs() != null && result.isrPercentage() != null) {
            // The JSON adapter accounts ISR spans exclusively from thread execution.
            rows.add(new CpuRow("ISR (exclusive)",
                    micros(result.isrNs()), percent(result.isrPercentage())));
        }
        rows.sort(Comparator.comparing((CpuRow row) -> new BigDecimal(row.percentage().replace("%", "")))
                .reversed().thenComparing(CpuRow::threadName));
        return new CpuPresentation(rows, result.incomplete(), false,
                result.reason().isBlank() ? "" : result.reason());
    }

    public static ContentionPresentation contention(OfflineTraceAnalysisService.ContentionResult report) {
        var rows = new ArrayList<ContentionRow>();
        for (var finding : report.findings()) {
            if (!report.inversionEnabled() && "Inversion".equals(finding.kind())) continue;
            String holder = finding.holderUnknown() || finding.holder() == null
                    || finding.holder().isBlank() ? "unknown" : finding.holder();
            rows.add(new ContentionRow("Inversion".equals(finding.kind())
                    ? "Priority inversion" : "Long wait", finding.object(), finding.waiter(),
                    holder, micros(finding.durationNs()), finding.explanation()));
        }
        return new ContentionPresentation(rows, report.incomplete(), !report.inversionEnabled(),
                rows.isEmpty() ? CONTENTION_EMPTY : "");
    }

    private static String micros(long ns) {
        if (ns < 0) throw new IllegalArgumentException("Negative observed time.");
        return ns / 1000 + "." + String.format(java.util.Locale.ROOT, "%03d", ns % 1000);
    }
    private static String percent(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
