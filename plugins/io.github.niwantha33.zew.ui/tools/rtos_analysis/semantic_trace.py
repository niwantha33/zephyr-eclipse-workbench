#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Shared, offline Zephyr CTF -> semantic event normalization.

The source may be Babeltrace-normalized dictionaries or a saved CTF directory.
No target/probe access occurs. Unknown events are retained, not discarded.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Iterable, Mapping


class EventKind(str, Enum):
    THREAD_SWITCHED_IN = "THREAD_SWITCHED_IN"
    THREAD_SWITCHED_OUT = "THREAD_SWITCHED_OUT"
    THREAD_READY = "THREAD_READY"
    THREAD_BLOCKED = "THREAD_BLOCKED"
    THREAD_SLEEPING = "THREAD_SLEEPING"
    ISR_ENTER = "ISR_ENTER"
    ISR_EXIT = "ISR_EXIT"
    MUTEX_TAKE = "MUTEX_TAKE"
    MUTEX_GIVE = "MUTEX_GIVE"
    SEM_TAKE = "SEM_TAKE"
    SEM_GIVE = "SEM_GIVE"
    MEM_ALLOC = "MEM_ALLOC"
    MEM_FREE = "MEM_FREE"
    UNKNOWN = "UNKNOWN"


@dataclass(frozen=True)
class SemanticEvent:
    timestamp_ns: int | None
    kind: EventKind
    thread_name: str = ""
    thread_priority: str | None = None
    object_name: str = ""
    raw_event_name: str = ""
    # Source identifiers are retained for existing timing analysis. Neither an
    # ID nor an address is substituted for a human-readable thread/object name.
    thread_id: str | None = None
    stream: object | None = None


@dataclass
class SemanticTrace:
    events: list[SemanticEvent] = field(default_factory=list)
    unknown_count: int = 0
    incomplete: bool = False
    error: str = ""
    decoder_unavailable: bool = False
    input_out_of_order: bool = False
    # Preserves non-event decoder messages (including reported loss) for
    # existing evidence-qualified analysis without changing its API.
    raw_records: list[dict] = field(default_factory=list)

    @property
    def unknown_message(self) -> str:
        return f"{self.unknown_count} events were not understood"


# SINGLE SOURCE OF TRUTH for raw Zephyr/CTF event-name -> semantic-kind mappings.
# The CTF event namespace prefix is removed before lookup. These names are
# explicit aliases, not guesses about arbitrary Zephyr revisions. sched_switch
# is a *single* transition: represent its incoming thread, not an invented
# second switch-out event. Unmodeled lifecycle/priority events stay UNKNOWN.
RAW_EVENT_KINDS: dict[str, EventKind] = {
    "sched_switch": EventKind.THREAD_SWITCHED_IN,
    "thread_switched_in": EventKind.THREAD_SWITCHED_IN,
    "thread_switched_out": EventKind.THREAD_SWITCHED_OUT,
    "thread_ready": EventKind.THREAD_READY,
    "thread_pend": EventKind.THREAD_BLOCKED,
    "thread_pending": EventKind.THREAD_BLOCKED,
    "thread_sleep_enter": EventKind.THREAD_SLEEPING,
    "isr_enter": EventKind.ISR_ENTER,
    "isr_enter_user": EventKind.ISR_ENTER,
    "isr_exit": EventKind.ISR_EXIT,
    "isr_exit_user": EventKind.ISR_EXIT,
    "isr_exit_to_scheduler": EventKind.ISR_EXIT,
    "mutex_lock": EventKind.MUTEX_TAKE,
    "mutex_take": EventKind.MUTEX_TAKE,
    "mutex_unlock": EventKind.MUTEX_GIVE,
    "mutex_give": EventKind.MUTEX_GIVE,
    "semaphore_take": EventKind.SEM_TAKE,
    "sem_take": EventKind.SEM_TAKE,
    "semaphore_give": EventKind.SEM_GIVE,
    "sem_give": EventKind.SEM_GIVE,
    "mem_alloc": EventKind.MEM_ALLOC,
    "mem_free": EventKind.MEM_FREE,
}


def _text(value) -> str:
    return "" if value is None else str(value)


def _first(record: Mapping, *keys: str):
    for key in keys:
        value = record.get(key)
        if value is not None and value != "":
            return value
    return None


def decode_ctf(path: Path):
    """Yield Babeltrace records from a saved CTF trace; errors reach the parser.

    Import bt2 lazily so list-based normalization works without Babeltrace.
    """
    import bt2

    streams = {}
    for message in bt2.TraceCollectionMessageIterator(str(path)):
        if isinstance(message, bt2._DiscardedEventsMessageConst):
            yield {"kind": "discarded_events", "count": message.count}
        elif isinstance(message, bt2._DiscardedPacketsMessageConst):
            yield {"kind": "discarded_packets", "count": message.count}
        elif isinstance(message, bt2._EventMessageConst):
            event = message.event
            stream = event.stream.addr
            streams.setdefault(stream, len(streams))
            fields = event.payload_field

            def get(name):
                return str(fields[name]) if fields is not None and name in fields else None

            try:
                timestamp = message.default_clock_snapshot.ns_from_origin
            except (AttributeError, ValueError):
                timestamp = None
            yield {
                "kind": "event", "stream": streams[stream],
                "name": event.name, "timestamp_ns": timestamp,
                "thread_id": get("thread_id"),
                "thread_name": get("name"),
                "priority": get("prio"),
                "core_id": get("cpu"),
                "object_id": get("id"),
                "object_name": get("object_name"),
                "mutex_name": get("mutex_name"),
                "semaphore_name": get("semaphore_name"),
                "pool_name": get("pool_name"),
                "next_tid": get("next_tid"),
                "next_comm": get("next_comm"),
                "next_prio": get("next_prio"),
                "arg0": get("arg0"), "arg1": get("arg1"),
            }


class SemanticTraceParser:
    """Normalize raw records or a saved CTF path into stable time order.

    A decoder error (including a truncated final packet) returns the complete
    events observed *before* the error, with incomplete=True. Missing timestamps
    remain None and sort last; no timestamp is invented.
    """

    MAX_EVENTS = 2_000_000

    def parse(self, source: Iterable[Mapping] | str | Path) -> SemanticTrace:
        trace = SemanticTrace()
        previous_timestamp = None
        records = decode_ctf(Path(source)) if isinstance(source, (str, Path)) else source
        try:
            for raw in records:
                if not isinstance(raw, Mapping):
                    trace.incomplete = True
                    trace.raw_records.append({"kind": "event", "name": "", "timestamp_ns": None})
                    event = SemanticEvent(None, EventKind.UNKNOWN)
                else:
                    raw = dict(raw)
                    trace.raw_records.append(raw)
                    if raw.get("kind") in ("discarded_events", "discarded_packets"):
                        # These are loss notifications, not CTF events.
                        continue
                    name = _text(raw.get("name"))
                    simple_name = name.rsplit(":", 1)[-1]
                    kind = RAW_EVENT_KINDS.get(simple_name, EventKind.UNKNOWN)
                    timestamp = raw.get("timestamp_ns")
                    if type(timestamp) is not int or timestamp < 0:
                        timestamp = None
                        trace.incomplete = True
                    if timestamp is not None and previous_timestamp is not None and timestamp < previous_timestamp:
                        trace.input_out_of_order = True
                    if timestamp is not None:
                        previous_timestamp = timestamp
                    sched = simple_name == "sched_switch"
                    thread_id = _first(raw, "next_tid", "thread_id") if sched else raw.get("thread_id")
                    thread_name = _first(raw, "next_comm", "thread_name") if sched else raw.get("thread_name")
                    priority = _first(raw, "next_prio", "priority", "prio") if sched else _first(raw, "priority", "prio")
                    object_name = _first(raw, "object_name", "mutex_name", "semaphore_name",
                                         "pool_name", "heap_name")
                    event = SemanticEvent(
                        timestamp_ns=timestamp, kind=kind, thread_name=_text(thread_name),
                        thread_priority=None if priority is None else str(priority),
                        object_name=_text(object_name), raw_event_name=name,
                        thread_id=None if thread_id is None else str(thread_id),
                        stream=raw.get("stream"))
                    if not name:
                        trace.incomplete = True
                trace.events.append(event)
                if event.kind == EventKind.UNKNOWN:
                    trace.unknown_count += 1
                if len(trace.events) >= self.MAX_EVENTS:
                    trace.incomplete = True
                    trace.error = "Bounded semantic event limit reached"
                    break
        except ImportError as exc:
            trace.decoder_unavailable = True
            trace.incomplete = True
            trace.error = str(exc)
        except (Exception) as exc:
            # Incomplete CTF packets may fail during iterator advancement or
            # field access; keep previously decoded events, never fabricate any.
            trace.incomplete = True
            trace.error = f"{type(exc).__name__}: {exc}"
        trace.events.sort(key=lambda event: (event.timestamp_ns is None,
                                            event.timestamp_ns if event.timestamp_ns is not None else 0))
        return trace
