#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Conservative single-stream RTOS CPU accounting over normalized CTF events.

This measures *observed* intervals only. It never treats absence of events as
sleep or idle, and does not infer WCET, CPU frequency or unreported packet loss.
"""
from __future__ import annotations


def account(records, *, idle_thread_id=None):
    """Return an evidence-qualified window. Durations are nanoseconds.

    Accept one ordered clock/stream. A switch-in marks a running thread;
    a switch-out ends that known execution; nested ISR time supersedes thread
    time without double counting. Gaps are UNKNOWN, never silently CPU idle.
    """
    unknown = lambda reason: {
        "status": "unavailable", "reason": reason, "clock": "CTF timestamp_ns",
        "cpu_busy_percent": None, "thread_exclusive_ns": None, "isr_ns": None,
        "idle_ns": None, "unknown_ns": None, "observed_window_ns": None,
    }
    timestamped = []
    stream_ids = set()
    for item in records:
        if item.get("kind") in ("discarded_events", "discarded_packets"):
            return unknown("decoder-reported loss; CPU accounting withheld")
        if item.get("kind") != "event":
            continue
        if item.get("timestamp_ns") is None or not isinstance(item["timestamp_ns"], int):
            return unknown("missing or non-integer timestamp")
        stream_ids.add(item.get("stream"))
        if len(stream_ids) != 1:
            return unknown("multiple streams/cores without an explicit clock and core mapping")
        timestamped.append(item)
        if len(timestamped) > 2_000_000:
            return unknown("bounded event limit exceeded")
    if len(timestamped) < 2:
        return unknown("fewer than two timestamped events")

    threads = {}
    irq_ns = 0
    gap_ns = 0
    active = None
    irq_depth = 0
    nested_max = 0
    irq_entries = 0
    switches = 0
    first = last = timestamped[0]["timestamp_ns"]
    for item in timestamped:
        stamp = item["timestamp_ns"]
        if stamp < last:
            return unknown("reordered timestamps or clock wrap without conversion")
        delta = stamp - last
        if irq_depth:
            irq_ns += delta
        elif active is None:
            gap_ns += delta
        else:
            threads[active] = threads.get(active, 0) + delta
        name = item["name"].rsplit(":", 1)[-1]
        tid = item.get("thread_id")
        if name == "thread_switched_in":
            if tid is None or irq_depth or active is not None:
                return unknown("ambiguous or overlapping thread switch-in")
            active = str(tid)
            switches += 1
        elif name == "thread_switched_out":
            if tid is None or active != str(tid) or irq_depth:
                return unknown("unmatched thread switch-out")
            active = None
        elif name in ("isr_enter", "isr_enter_user"):
            irq_depth += 1
            irq_entries += 1
            nested_max = max(nested_max, irq_depth)
        elif name in ("isr_exit", "isr_exit_user"):
            if not irq_depth:
                return unknown("unmatched ISR exit")
            irq_depth -= 1
        last = stamp

    if irq_depth:
        return unknown("capture ends with an unmatched ISR entry")
    window = last - first
    if window <= 0:
        return unknown("zero-length clock window")
    total_thread = sum(threads.values())
    if total_thread + irq_ns + gap_ns != window:
        return unknown("exclusive time accounting mismatch")
    idle_known = idle_thread_id is not None
    idle_ns = threads.get(str(idle_thread_id), 0) if idle_known else None
    busy = None
    # Unknown gaps and unknown idle identity make total CPU% unknowable.
    if idle_known and gap_ns == 0:
        busy = round(100 * (window - idle_ns) / window, 4)
    warnings = []
    if gap_ns:
        warnings.append("There are intervals with no proven running thread/ISR; not counted as idle.")
    if not idle_known:
        warnings.append("No explicitly identified idle thread; CPU busy percentage withheld.")
    if active is not None:
        warnings.append("Capture ends inside a running thread; final execution may extend beyond window.")
    if switches == 0:
        warnings.append("No thread switch-in events; scheduler coverage not established.")
    warnings.append("No proof of unreported CTF loss, timebase accuracy, or complete capture boundaries.")
    return {
        "status": "measured" if busy is not None else "partial",
        "clock": "CTF timestamp_ns; no wall-clock accuracy independently verified",
        "first_timestamp_ns": first, "last_timestamp_ns": last,
        "observed_window_ns": window,
        "thread_exclusive_ns": dict(sorted(threads.items())),
        "thread_exclusive_total_ns": total_thread,
        "isr_ns": irq_ns, "isr_entries": irq_entries, "max_isr_nesting": nested_max,
        "unknown_ns": gap_ns, "idle_thread_id": str(idle_thread_id) if idle_known else None,
        "idle_ns": idle_ns, "cpu_busy_percent": busy,
        "thread_switch_ins": switches, "warnings": warnings,
        "scope": "Single-stream intervals only; ISR time is not charged to interrupted threads.",
    }
