#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Opt-in JSON v1 adapter for saved CTF CPU and contention reports; no target access."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from cpu_analysis import account
from contention_detector import ContentionDetector, DEFAULT_THRESHOLD_US
from semantic_trace import EventKind, SemanticTraceParser
from thread_timeline import build_intervals


def _bounds(trace):
    stamps = [event.timestamp_ns for event in trace.events if event.timestamp_ns is not None]
    return (min(stamps), max(stamps)) if stamps else (None, None)


def _window_exclusive(trace, start_ns, end_ns):
    """Clip the same exclusive event segments that cpu_analysis.account accounts.

    Only the existing semantic switch/ISR transitions are used. There is no
    synthetic switch event at a window boundary and no guessed idle thread.
    """
    active = None
    irq_depth = 0
    last = trace.events[0].timestamp_ns
    running = {}
    names = {}
    irq_ns = unknown_ns = 0
    for event in trace.events:
        stamp = event.timestamp_ns
        overlap = max(0, min(end_ns, stamp) - max(start_ns, last))
        if overlap:
            if irq_depth:
                irq_ns += overlap
            elif active is None:
                unknown_ns += overlap
            else:
                running[active] = running.get(active, 0) + overlap
        if event.thread_id is not None and event.thread_name:
            names[str(event.thread_id)] = event.thread_name
        if event.kind == EventKind.THREAD_SWITCHED_IN:
            active = str(event.thread_id)
        elif event.kind == EventKind.THREAD_SWITCHED_OUT:
            active = None
        elif event.kind == EventKind.ISR_ENTER:
            irq_depth += 1
        elif event.kind == EventKind.ISR_EXIT:
            irq_depth -= 1
        last = stamp
    return running, names, irq_ns, unknown_ns


def cpu_report(trace, start_ns=None, end_ns=None):
    """Qualify with cpu_analysis.account and clip its exclusive event segments."""
    full = account(trace)
    timeline = build_intervals(trace)
    first, last = _bounds(trace)
    start = first if start_ns is None else start_ns
    end = last if end_ns is None else end_ns
    status = "ok"
    reason = ""
    incomplete = bool(trace.incomplete or trace.loss_reported or timeline.incomplete
                      or trace.input_out_of_order)
    if timeline.scheduler_events == 0:
        status = "no_scheduler"
        reason = "No recognized scheduler transitions in the saved CTF."
    elif first is None or last is None or last <= first:
        status, reason, incomplete = "incomplete", "No valid trace time range.", True
    elif start is None or end is None or not first <= start < end <= last:
        raise ValueError("Selected nanosecond window must be within the observed trace range.")
    elif full.get("status") == "unavailable":
        status, reason, incomplete = "incomplete", full.get("reason", "CPU evidence unavailable"), True
    elif incomplete:
        status, reason = "incomplete", trace.error or "Some trace transitions or packets are missing."

    rows = []
    states = []
    isr_ns = isr_pct = unknown_ns = None
    if status == "ok":
        running, names, isr_ns, unknown_ns = _window_exclusive(trace, start, end)
        window = end - start
        rows = [{"name": names.get(tid, f"thread ID {tid} (name unavailable)"),
                 "running_ns": duration, "percentage": round(100 * duration / window, 4)}
                for tid, duration in running.items()]
        rows.sort(key=lambda row: (-row["percentage"], row["name"]))
        # A paired Running interval is scheduled wall-clock time; it can
        # INCLUDE ISR preemption. It is not the exclusive CPU row above.
        for observed in timeline.intervals:
            begin, finish = max(start, observed.start_ns), min(end, observed.end_ns)
            if begin >= finish:
                continue
            state = "Scheduled" if observed.state == "Running" else observed.state
            states.append({"name": observed.thread_name, "state": state,
                           "start_ns": begin, "end_ns": finish,
                           "scheduled_includes_isr": state == "Scheduled"})
        isr_pct = round(100 * isr_ns / window, 4)
        # Whole-trace results must exactly agree with the existing CPU function.
        if start == first and end == last:
            if (running != full["thread_exclusive_ns"]
                    or isr_ns != full["isr_ns"] or unknown_ns != full["unknown_ns"]):
                status, reason, incomplete = "incomplete", "Exclusive CPU accounting disagrees.", True
                rows, states, isr_ns, isr_pct, unknown_ns = [], [], None, None, None

    return {"schema_version": 1, "kind": "cpu", "status": status,
            "incomplete": incomplete, "reason": reason,
            "first_timestamp_ns": first, "last_timestamp_ns": last,
            "start_ns": start, "end_ns": end, "scheduler_events": timeline.scheduler_events,
            "cpu": {"status": full.get("status", "unavailable"),
                    "cpu_busy_percent": full.get("cpu_busy_percent"),
                    "unknown_ns": unknown_ns,
                    "rows": rows, "isr_ns": isr_ns,
                    "isr_percentage": isr_pct, "thread_states": states,
                    # Zephyr's standard CTF ISR transitions provide no
                    # reliable interrupt identity; only total exclusive ISR
                    # duration is reportable. Do not fabricate per-ISR rows.
                    "per_isr_status": "identity_unavailable", "per_isr_rows": []}}


def contention_report(trace, threshold_us=DEFAULT_THRESHOLD_US):
    report = ContentionDetector().detect(trace, threshold_us)
    first, last = _bounds(trace)
    incomplete = bool(report.incomplete or trace.loss_reported or trace.input_out_of_order)
    return {"schema_version": 1, "kind": "contention",
            "status": "incomplete" if incomplete else "ok",
            "incomplete": incomplete, "reason": report.error or
            ("CTF loss or unordered input was observed." if incomplete else ""),
            "first_timestamp_ns": first, "last_timestamp_ns": last,
            "threshold_us": threshold_us, "inversion_enabled": report.inversion_enabled,
            "resource_events": report.observed_resource_events,
            "findings": [{"kind": f.kind, "object": f.object_name,
                          "waiter": f.waiter, "holder": None if f.holder_unknown else f.holder,
                          "holder_unknown": f.holder_unknown, "duration_ns": f.duration_ns,
                          "explanation": f.explanation}
                         for f in report.findings]}


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--mode", choices=("cpu", "contention"), required=True)
    cli.add_argument("--ctf", required=True, type=Path)
    cli.add_argument("--start-ns", type=int)
    cli.add_argument("--end-ns", type=int)
    cli.add_argument("--threshold-us", type=int, default=DEFAULT_THRESHOLD_US)
    args = cli.parse_args()
    if (args.ctf.is_symlink() or not args.ctf.is_dir()
            or not (args.ctf / "metadata").is_file()
            or not (args.ctf / "channel0_0").is_file()):
        cli.error("Saved CTF directory must contain readable metadata and channel0_0.")
    if (args.start_ns is None) != (args.end_ns is None):
        cli.error("Start and end must be supplied together.")
    if not 0 <= args.threshold_us <= 86_400_000_000:
        cli.error("Threshold must be 0..86400000000 microseconds.")
    trace = SemanticTraceParser().parse(args.ctf)
    if trace.decoder_unavailable:
        cli.error("Babeltrace 2 bt2 unavailable for the selected Python: " + trace.error)
    try:
        payload = (cpu_report(trace, args.start_ns, args.end_ns) if args.mode == "cpu"
                   else contention_report(trace, args.threshold_us))
    except ValueError as exc:
        cli.error(str(exc))
    print(json.dumps(payload, ensure_ascii=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
