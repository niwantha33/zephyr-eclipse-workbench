#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Evidence-bounded thread timing from normalized Zephyr CTF records.

Only paired scheduler events produce execution durations. A ready event can
establish ready-to-run latency, not the reason for an earlier wait. Values are
observed sample maxima, *never* worst-case execution times (WCET).
"""
from __future__ import annotations
from collections import defaultdict
from semantic_trace import EventKind, SemanticTrace, SemanticTraceParser


def analyze_timing(records, *, deadlines_ns=None):
    semantic = records if isinstance(records, SemanticTrace) else SemanticTraceParser().parse(records)
    deadlines_ns = deadlines_ns or {}
    result = {"status": "unavailable", "reason": "", "threads": {}, "warnings": [],
              "scope": "One ordered stream; scheduled bursts include any ISR time and are not exclusive CPU usage.",
              "units": "ns", "maxima": "observed, never proven WCET"}
    if semantic.incomplete:
        result["reason"] = "incomplete or malformed CTF trace"
        return result
    if semantic.input_out_of_order:
        result["reason"] = "clock reordered or wrapped without proven conversion"
        return result
    if len(semantic.events) > 2_000_000:
        result["reason"] = "bounded event count exceeded"
        return result
    if any(item.get("kind") in ("discarded_events", "discarded_packets")
           for item in semantic.raw_records):
        result["reason"] = "decoder-reported loss"
        return result
    stream_ids = set()
    last = None
    for record in semantic.events:
        timestamp = record.timestamp_ns
        if type(timestamp) is not int or timestamp < 0:
            result["reason"] = "missing or invalid monotonic clock"
            return result
        if last is not None and timestamp < last:
            result["reason"] = "clock reordered or wrapped without proven conversion"
            return result
        stream_ids.add(record.stream)
        if len(stream_ids) > 1:
            result["reason"] = "several streams/cores without time synchronization proof"
            return result
        last = timestamp
    if not stream_ids:
        result["reason"] = "no timestamped events"
        return result

    def entry(tid):
        return stats.setdefault(str(tid), {
            "name": "", "scheduled_bursts_ns": [], "switch_in_ns": [],
            "ready_to_run_ns": [], "observed_ready_events": 0,
            "observed_sleep_enters": 0, "observed_sleep_exits": 0,
            "observed_pending_events": 0, "observed_resume_events": 0,
            "observed_priority_changes": 0
        })
    stats = {}
    pending_ready = {}
    active = None
    for record in semantic.events:
        name = record.raw_event_name.rsplit(":", 1)[-1]
        kind = record.kind
        ts = record.timestamp_ns
        tid = record.thread_id
        if tid is None:
            continue
        key = str(tid)
        row = entry(key)
        if record.thread_name:
            row["name"] = record.thread_name[:96]
        if kind == EventKind.THREAD_READY:
            row["observed_ready_events"] += 1
            pending_ready[key] = ts
        elif kind == EventKind.THREAD_BLOCKED:
            row["observed_pending_events"] += 1
            pending_ready.pop(key, None)
        elif kind == EventKind.THREAD_SLEEPING:
            row["observed_sleep_enters"] += 1
            pending_ready.pop(key, None)
        elif name == "thread_sleep_exit":
            row["observed_sleep_exits"] += 1
        elif name == "thread_resume":
            row["observed_resume_events"] += 1
        elif name in ("thread_priority_set", "thread_sched_prio_set"):
            row["observed_priority_changes"] += 1
        elif kind == EventKind.THREAD_SWITCHED_IN:
            if active is not None:
                result["warnings"].append(
                    "Unmatched switch-in; invalidated open burst before " + key)
            active = (key, ts)
            row["switch_in_ns"].append(ts)
            if key in pending_ready:
                row["ready_to_run_ns"].append(ts - pending_ready.pop(key))
        elif kind == EventKind.THREAD_SWITCHED_OUT:
            if active is not None and active[0] == key:
                row["scheduled_bursts_ns"].append(ts - active[1])
            else:
                result["warnings"].append("Unmatched switch-out for " + key)
            active = None
    if active:
        result["warnings"].append("Incomplete trailing scheduled burst excluded.")
    totals = {}
    for key, row in sorted(stats.items()):
        bursts = row.pop("scheduled_bursts_ns")
        starts = row.pop("switch_in_ns")
        ready = row.pop("ready_to_run_ns")
        periods = [b - a for a, b in zip(starts, starts[1:])]
        deadline = deadlines_ns.get(key)
        if deadline is not None and (type(deadline) is not int or deadline <= 0):
            raise ValueError("Each deadline must be a positive integer nanosecond value")
        totals[key] = {
            **row, "complete_bursts": len(bursts),
            "total_scheduled_ns": sum(bursts), "max_observed_burst_ns": max(bursts, default=None),
            "min_observed_burst_ns": min(bursts, default=None),
            "mean_observed_burst_ns": sum(bursts) / len(bursts) if bursts else None,
            "observed_switch_in_count": len(starts),
            "inter_arrival_samples": len(periods),
            "period_min_ns": min(periods, default=None),
            "period_max_ns": max(periods, default=None),
            "period_range_jitter_ns": max(periods) - min(periods) if periods else None,
            "ready_latency_samples": len(ready),
            "max_observed_ready_latency_ns": max(ready, default=None),
            "observed_deadline_ns": deadline,
            "observed_bursts_exceeding_deadline": (
                sum(value > deadline for value in bursts) if deadline is not None else None),
        }
    result["status"] = "observed"
    result["reason"] = "Paired switch and explicit state events only; missing events may undercount."
    result["threads"] = totals
    result["warnings"] = list(dict.fromkeys(result["warnings"]))
    return result
