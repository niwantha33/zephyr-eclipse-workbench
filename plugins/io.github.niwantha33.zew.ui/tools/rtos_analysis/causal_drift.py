#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Evidence-only periodic task drift and scheduler-interference analysis.

This is an OFFLINE analyzer. Context switches are not task activations.
Only explicit application cycle markers can establish periodic phase.
"""
from __future__ import annotations

from collections import defaultdict
from bisect import bisect_left, bisect_right
from semantic_trace import EventKind, SemanticTrace

MARKERS = frozenset(("zew_cycle_release", "zew_cycle_start", "zew_cycle_finish"))
MAX_CYCLES = 10000


def _unsigned(value):
    if type(value) is int:
        number = value
    elif isinstance(value, str):
        try:
            number = int(value, 0)
        except ValueError:
            return None
    else:
        return None
    return number if 0 <= number <= 0xFFFFFFFF else None


def _invalid(reason):
    return {"status": "incomplete", "reason": reason, "cycles": [],
            "measured_cycles": 0, "phase_trend_ns": None,
            "reference": "unavailable", "observed_max_abs_phase_ns": None}


def _tid(value):
    """Treat numeric Zephyr thread IDs in hex/decimal as the same identity."""
    if value is None:
        return None
    raw = str(value).strip()
    try:
        return str(int(raw, 0)) if raw.lower().startswith("0x") else str(int(raw))
    except (ValueError, OverflowError):
        return raw


def analyze_drift(trace: SemanticTrace, *, tag: int, period_ns: int,
                  thread_id: str | None = None, anchor_ns: int | None = None,
                  deadline_ns: int | None = None) -> dict:
    """Analyze explicit Zephyr named_event markers, with fail-closed boundaries.

    arg0 is the application-defined numeric tag, arg1 is the cycle sequence.
    A start event must be in the measured thread. A release event may occur
    in another thread/ISR. A finish event marks completion; do not mistake
    a context switch or a mutex release for cycle completion.
    """
    if type(tag) is not int or not 0 <= tag <= 0xFFFFFFFF:
        raise ValueError("tag must be a uint32")
    if type(period_ns) is not int or not 0 < period_ns <= 86_400_000_000_000:
        raise ValueError("period_ns must be a positive bounded integer")
    if anchor_ns is not None and (type(anchor_ns) is not int or anchor_ns < 0):
        raise ValueError("anchor_ns must be a nonnegative integer")
    if deadline_ns is not None and (type(deadline_ns) is not int or deadline_ns <= 0):
        raise ValueError("deadline_ns must be positive")
    if thread_id is not None and not str(thread_id).strip():
        raise ValueError("thread_id cannot be empty")
    if (trace.incomplete or trace.loss_reported or trace.input_out_of_order
            or trace.decoder_unavailable):
        return _invalid("Trace incomplete, lost, decoder-unavailable or out of order.")
    if any(event.timestamp_ns is None for event in trace.events):
        return _invalid("Missing timestamp evidence.")

    marker_by_seq = defaultdict(dict)
    observed_streams = set()
    last_sequence_for_phase = {}
    for raw in trace.raw_records:
        if raw.get("kind") != "event" or str(raw.get("name", "")).rsplit(":", 1)[-1] != "named_event":
            continue
        phase = raw.get("named_event_name")
        if phase not in MARKERS:
            continue
        observed_tag = _unsigned(raw.get("arg0"))
        if observed_tag != tag:
            continue
        seq = _unsigned(raw.get("arg1"))
        timestamp = raw.get("timestamp_ns")
        if seq is None or type(timestamp) is not int or timestamp < 0:
            return _invalid("Selected cycle contains an invalid sequence or timestamp.")
        if len(marker_by_seq) >= MAX_CYCLES and seq not in marker_by_seq:
            return _invalid("Cycle count exceeds the bounded limit.")
        if phase in marker_by_seq[seq]:
            return _invalid("Duplicate phase marker for one cycle; sequence identity ambiguous.")
        if phase in last_sequence_for_phase and seq <= last_sequence_for_phase[phase]:
            return _invalid("Phase sequence wraps or regresses; epoch is ambiguous.")
        last_sequence_for_phase[phase] = seq
        marker_by_seq[seq][phase] = timestamp
        observed_streams.add(raw.get("stream"))
    if not marker_by_seq:
        return {"status": "no_markers", "reason": "No zew_cycle_* markers for selected tag.",
                "cycles": [], "measured_cycles": 0, "phase_trend_ns": None,
                "reference": "unavailable", "observed_max_abs_phase_ns": None}
    if len(observed_streams) > 1:
        return _invalid("Cycle markers span streams without validated clock synchronization.")
    if any(m.get("zew_cycle_start") is None for m in marker_by_seq.values()):
        return _invalid("Missing cycle start marker; partial cycle cannot be timed.")
    if thread_id is not None:
        thread_id = _tid(thread_id)
    ordered = sorted(marker_by_seq.items())
    first_seq, first = ordered[0]
    relative_reference = first["zew_cycle_start"] - first_seq * period_ns
    reference = "explicit_anchor" if anchor_ns is not None else "first_observed_start"
    expected_anchor = anchor_ns if anchor_ns is not None else relative_reference
    # A relative first-observed reference may legitimately be negative if
    # the application started its sequence before this trace window.

    # A scheduler switch is evidence about who ran, not a cycle boundary.
    # Report switch interruption and observed intervening threads; never
    # convert unobserved off-CPU time into a preemption cause.
    scheduler = [e for e in trace.events if e.kind in (
        EventKind.THREAD_SWITCHED_IN, EventKind.THREAD_SWITCHED_OUT,
        EventKind.THREAD_BLOCKED, EventKind.THREAD_SLEEPING)]
    scheduler_times = [e.timestamp_ns for e in scheduler]
    known_names = {_tid(e.thread_id): e.thread_name for e in trace.events
                   if e.thread_id is not None and e.thread_name}
    if thread_id is not None and not any(
            _tid(e.thread_id) == thread_id for e in scheduler):
        return _invalid("Selected thread_id has no scheduler evidence.")

    # Mutex blocking evidence is an explicitly paired blocking -> acquired
    # transition of the same thread and object. Do not infer ownership from
    # an attempt, or attribute an unpaired wait.
    open_mutex = {}
    waits = []
    active_thread = {}
    irq_depth = defaultdict(int)
    for event in trace.events:
        # Standard Zephyr mutex CTF may record only the object ID. Attribute
        # it to the scheduler's active thread only while that identity is
        # observed on the SAME stream and no ISR is active.
        if event.kind == EventKind.THREAD_SWITCHED_IN:
            if event.thread_id is not None:
                active_thread[event.stream] = _tid(event.thread_id)
            else:
                active_thread.pop(event.stream, None)
        elif event.kind == EventKind.THREAD_SWITCHED_OUT:
            active_thread.pop(event.stream, None)
        elif event.kind in (EventKind.THREAD_BLOCKED, EventKind.THREAD_SLEEPING):
            if (event.thread_id is not None
                    and active_thread.get(event.stream) == _tid(event.thread_id)):
                active_thread.pop(event.stream, None)
        elif event.kind == EventKind.ISR_ENTER:
            irq_depth[event.stream] += 1
        elif event.kind == EventKind.ISR_EXIT:
            irq_depth[event.stream] = max(0, irq_depth[event.stream] - 1)
        if event.kind != EventKind.MUTEX_TAKE:
            continue
        if event.object_id is None and not event.object_name:
            continue
        tid = (_tid(event.thread_id) if event.thread_id is not None
               else active_thread.get(event.stream) if irq_depth[event.stream] == 0
               else None)
        if tid is None:
            continue
        key = (tid, event.object_id
               if event.object_id is not None else "name:" + event.object_name)
        if event.resource_phase == "blocking":
            open_mutex[key] = event.timestamp_ns
        elif event.resource_phase == "acquired":
            started = open_mutex.pop(key, None)
            if started is not None and started <= event.timestamp_ns:
                waits.append((key[0], started, event.timestamp_ns,
                              event.object_name or "object ID " + str(event.object_id)))
        elif event.resource_phase == "failed":
            open_mutex.pop(key, None)

    waits.sort(key=lambda item: item[1])
    wait_starts = [item[1] for item in waits]
    cycles = []
    for seq, phases in ordered:
        start = phases["zew_cycle_start"]
        release = phases.get("zew_cycle_release")
        finish = phases.get("zew_cycle_finish")
        if release is not None and release > start:
            return _invalid("Release marker occurs after start for cycle " + str(seq) + ".")
        if finish is not None and finish < start:
            return _invalid("Finish marker occurs before start for cycle " + str(seq) + ".")
        expected = expected_anchor + seq * period_ns
        if expected < 0 or expected > (1 << 63) - 1:
            return _invalid("Expected cycle timestamp overflows signed nanoseconds.")
        gap_interruptions = 0
        intervening = set()
        if thread_id is not None and finish is not None:
            outside = False
            for event in scheduler[bisect_left(scheduler_times, start):
                                   bisect_right(scheduler_times, finish)]:
                ts = event.timestamp_ns
                if ts < start or ts > finish:
                    continue
                tid = _tid(event.thread_id) if event.thread_id is not None else None
                if event.kind == EventKind.THREAD_SWITCHED_OUT and tid == thread_id:
                    outside = True
                elif event.kind == EventKind.THREAD_SWITCHED_IN:
                    if tid == thread_id and outside:
                        gap_interruptions += 1
                        outside = False
                    elif tid is not None and tid != thread_id and outside:
                        intervening.add(known_names.get(tid, "thread ID " + tid))
        paired_waits = [
            {"object": obj, "blocked_ns": end - begin}
            for tid, begin, end, obj in (
                waits[bisect_left(wait_starts, start):bisect_right(wait_starts, finish)]
                if finish is not None else ())
            if tid == thread_id and start <= begin <= end <= finish
        ] if thread_id is not None else []
        phase = start - expected
        completed = finish is not None
        due = ((release if release is not None else expected)
               if (release is not None or anchor_ns is not None) else None)
        cycles.append({
            "sequence": seq, "expected_start_ns": expected if anchor_ns is not None else None,
            "start_ns": start, "release_ns": release, "finish_ns": finish,
            "relative_phase_ns": phase if anchor_ns is None else None,
            "start_lateness_ns": phase if anchor_ns is not None else None,
            "release_to_start_ns": start - release if release is not None else None,
            "response_ns": finish - due if completed and due is not None else None,
            "deadline_missed": (finish > due + deadline_ns
                                if completed and due is not None and deadline_ns is not None else None),
            "scheduler_interruptions_observed": gap_interruptions if thread_id is not None and completed else None,
            "intervening_threads": sorted(intervening),
            "paired_mutex_blocks": paired_waits,
        })
    phases = [c["relative_phase_ns"] if anchor_ns is None else c["start_lateness_ns"]
              for c in cycles]
    return {
        "status": "ok", "reason": "Only observed, completed intervals are attributed.",
        "reference": reference, "tag": tag, "period_ns": period_ns,
        "thread_id": thread_id, "deadline_ns": deadline_ns,
        "measured_cycles": len(cycles),
        "phase_trend_ns": phases[-1] - phases[0] if len(phases) >= 2 else None,
        "observed_max_abs_phase_ns": max(map(abs, phases)),
        "cycles": cycles,
        "limitations": [
            "Without an explicit anchor, phase is relative to the first observed start, not start lateness.",
            "A scheduler interruption is not proof of higher-priority preemption.",
            "Mutex blocking requires paired blocking/acquired events with object and thread identity.",
            "No gate, semaphore, ISR identity or inferred task completion is claimed.",
        ],
    }
