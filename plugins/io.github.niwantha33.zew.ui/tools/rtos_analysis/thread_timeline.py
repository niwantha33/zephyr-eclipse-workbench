#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read-only thread timeline intervals derived solely from semantic CTF events.

No firmware access, synthetic switch-outs, CPU percentages or guessed states.
Run against a saved CTF directory with --ctf; output is a bounded TSV protocol
consumed by the Eclipse view. Importing this module never decodes a trace.
"""
from __future__ import annotations

import argparse
import base64
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable

from semantic_trace import EventKind, SemanticEvent, SemanticTrace, SemanticTraceParser

SCHEDULER = {EventKind.THREAD_SWITCHED_IN, EventKind.THREAD_SWITCHED_OUT}
THREAD_STATES = {
    EventKind.THREAD_READY: "Ready",
    EventKind.THREAD_BLOCKED: "Blocked",
    EventKind.THREAD_SLEEPING: "Sleeping",
}
MAX_ROWS = 10000
EMPTY_MESSAGE = "No scheduler events found. Scheduler tracing was likely not enabled in the Zephyr build."
INCOMPLETE_BANNER = "This trace is incomplete. Events after the truncation point are missing."


@dataclass(frozen=True)
class Interval:
    start_ns: int
    end_ns: int
    thread_name: str
    priority: str
    state: str

    @property
    def duration_us(self) -> str:
        ns = self.end_ns - self.start_ns
        return f"{ns // 1000}.{ns % 1000:03d}"


@dataclass
class Timeline:
    intervals: list[Interval] = field(default_factory=list)
    incomplete: bool = False
    scheduler_events: int = 0
    unknown_events: int = 0
    detail: str = ""
    decoder_unavailable: bool = False


def build_intervals(trace: SemanticTrace) -> Timeline:
    """Pair explicit transitions. Unknown gaps are not attributed to any thread.

    A Running row exists only for a matching switched-in/switched-out pair
    for the same thread ID on the same stream. Never close Running at the
    next switched-in event for a *different* thread: that would invent an out.
    Ready/Blocked/Sleeping rows need an explicit starting event and a later
    event for that same thread to prove their ending boundary.
    """
    result = Timeline(incomplete=trace.incomplete, unknown_events=trace.unknown_count,
                      detail=trace.error, decoder_unavailable=trace.decoder_unavailable)
    result.scheduler_events = sum(event.kind in SCHEDULER for event in trace.events)
    if not result.scheduler_events:
        result.detail = result.detail or EMPTY_MESSAGE
        return result

    # Each stream is independent: never pair switches from different cores.
    running: dict[object, SemanticEvent] = {}
    unknown_start: dict[object, int] = {}
    explicit: dict[tuple[object, str], tuple[str, SemanticEvent]] = {}
    unknown_thread: dict[tuple[object, str], SemanticEvent] = {}
    isr: dict[object, list[SemanticEvent]] = {}
    intervals: list[Interval] = []

    def append(start: int | None, end: int | None, name: str, priority: str, state: str):
        if start is not None and end is not None and 0 <= start < end:
            intervals.append(Interval(start, end, name, priority, state))

    def key(event: SemanticEvent):
        return event.thread_id if event.thread_id is not None else (
            ("named", event.thread_name) if event.thread_name else None)

    for event in trace.events:
        ts = event.timestamp_ns
        if ts is None:
            result.incomplete = True
            continue
        stream = event.stream
        identity = key(event)
        if identity is not None:
            ident = (stream, str(identity))
            if event.kind in THREAD_STATES or event.kind == EventKind.THREAD_SWITCHED_IN:
                uncertain = unknown_thread.pop(ident, None)
                if uncertain is not None:
                    append(uncertain.timestamp_ns, ts, uncertain.thread_name,
                           uncertain.thread_priority or "", "Unknown")
                prior = explicit.pop(ident, None)
                if prior is not None:
                    state, before = prior
                    append(before.timestamp_ns, ts, before.thread_name,
                           before.thread_priority or "", state)
                if event.kind in THREAD_STATES:
                    explicit[ident] = (THREAD_STATES[event.kind], event)
            elif event.kind == EventKind.UNKNOWN:
                # A future Zephyr event may change the thread state (e.g.
                # unmapped sleep-exit). Do not carry a known state across it.
                prior = explicit.pop(ident, None)
                if prior is not None:
                    state, before = prior
                    append(before.timestamp_ns, ts, before.thread_name,
                           before.thread_priority or "", state)
                    unknown_thread[ident] = event

        if event.kind == EventKind.THREAD_SWITCHED_IN:
            prior = running.pop(stream, None)
            if prior is not None:
                # There was no matching OUT: duration of preceding thread is unknowable.
                append(prior.timestamp_ns, ts, prior.thread_name, prior.thread_priority or "", "Unknown")
            gap = unknown_start.pop(stream, None)
            if prior is None:
                append(gap, ts, "", "", "Unknown")
            if identity is not None:
                running[stream] = event
            else:
                unknown_start[stream] = ts
                result.incomplete = True  # unnamed and unidentified switch cannot be paired

        elif event.kind == EventKind.THREAD_SWITCHED_OUT:
            prior = running.pop(stream, None)
            if prior is not None and key(prior) == identity and identity is not None:
                append(prior.timestamp_ns, ts,
                       prior.thread_name or event.thread_name,
                       prior.thread_priority or event.thread_priority or "", "Running")
            elif prior is not None:
                append(prior.timestamp_ns, ts, prior.thread_name,
                       prior.thread_priority or "", "Unknown")
            # No running thread proven between this OUT and the next IN.
            # A gap is exposed only if a later boundary is observed.
            unknown_start[stream] = ts

        elif event.kind == EventKind.ISR_ENTER:
            isr.setdefault(stream, []).append(event)
        elif event.kind == EventKind.ISR_EXIT:
            stack = isr.get(stream)
            if stack:
                begin = stack.pop()
                # Only emit outermost ISR intervals to avoid double representation.
                if not stack:
                    append(begin.timestamp_ns, ts, "", "", "ISR")

        if len(intervals) >= MAX_ROWS:
            result.incomplete = True
            result.detail = "Timeline row limit reached; remaining events not shown."
            break

    # Open scheduler and state intervals are intentionally *not* extended
    # to the last trace timestamp or into the future: no closing evidence.
    result.intervals = sorted(intervals, key=lambda row: (
        row.start_ns, row.end_ns, row.thread_name, row.state))
    if len(result.intervals) > MAX_ROWS:
        result.intervals = result.intervals[:MAX_ROWS]
        result.incomplete = True
        result.detail = "Timeline row limit reached; remaining intervals not shown."
    return result


def timeline_from_events(events: Iterable[dict]) -> Timeline:
    """Hand-written raw event fixtures are normalized through the shared parser."""
    return build_intervals(SemanticTraceParser().parse(events))


def _b64(value: str) -> str:
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


def serialize(result: Timeline) -> str:
    """Versioned UTF-8, tab-separated, base64-escaped names and diagnostics."""
    lines = ["\t".join(("ZEW_THREAD_TIMELINE", "1", str(int(result.incomplete)),
                        str(result.unknown_events), str(result.scheduler_events),
                        _b64(result.detail), str(int(result.decoder_unavailable))))]
    for interval in result.intervals:
        lines.append("\t".join(("I", str(interval.start_ns), str(interval.end_ns),
                                _b64(interval.thread_name), _b64(interval.priority),
                                interval.state)))
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ctf", type=Path, required=True, help="Saved local CTF directory only")
    args = parser.parse_args()
    path = args.ctf
    if path.is_symlink() or not path.is_dir():
        parser.error("Select an existing non-symlink saved CTF directory")
    result = build_intervals(SemanticTraceParser().parse(path))
    print(serialize(result), end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
