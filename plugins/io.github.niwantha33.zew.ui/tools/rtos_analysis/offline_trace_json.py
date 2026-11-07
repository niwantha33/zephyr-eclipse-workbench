#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Opt-in JSON v1 adapter for saved CTF CPU and contention reports; no target access."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from cpu_analysis import account
from contention_detector import ContentionDetector, DEFAULT_THRESHOLD_US
from semantic_trace import SemanticTraceParser
from thread_timeline import build_intervals


def _bounds(trace):
    stamps = [event.timestamp_ns for event in trace.events if event.timestamp_ns is not None]
    return (min(stamps), max(stamps)) if stamps else (None, None)


def cpu_report(trace, start_ns=None, end_ns=None):
    """Use cpu_analysis for evidence qualification and observed timeline for clipped shares."""
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
    isr = 0
    if start is not None and end is not None and end > start and status != "no_scheduler":
        running = {}
        for interval in timeline.intervals:
            overlap = max(0, min(end, interval.end_ns) - max(start, interval.start_ns))
            if not overlap:
                continue
            if interval.state == "Running":
                running[interval.thread_name] = running.get(interval.thread_name, 0) + overlap
            elif interval.state == "ISR":
                isr += overlap
        window = end - start
        rows = [{"name": name, "running_ns": duration,
                 "percentage": round(100 * duration / window, 4)}
                for name, duration in running.items()]
        rows.sort(key=lambda row: (-row["percentage"], row["name"]))
        isr_pct = round(100 * isr / window, 4)
    else:
        isr_pct = None

    return {"schema_version": 1, "kind": "cpu", "status": status,
            "incomplete": incomplete, "reason": reason,
            "first_timestamp_ns": first, "last_timestamp_ns": last,
            "start_ns": start, "end_ns": end, "scheduler_events": timeline.scheduler_events,
            "cpu": {"status": full.get("status", "unavailable"),
                    "cpu_busy_percent": full.get("cpu_busy_percent"),
                    "unknown_ns": full.get("unknown_ns"),
                    "rows": rows, "isr_ns": isr if isr_pct is not None else None,
                    "isr_percentage": isr_pct}}


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
