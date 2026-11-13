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
import math
import struct
from typing import Iterable, Mapping
from zephyr_ctf_binary import decode_packed, supports, buffer_full_evidence


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
    MSGQ_PUT = "MSGQ_PUT"
    MSGQ_GET = "MSGQ_GET"
    MEM_ALLOC = "MEM_ALLOC"
    MEM_FREE = "MEM_FREE"
    VALUE_SAMPLE = "VALUE_SAMPLE"
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
    tracepoint_name: str = ""
    value: int | float | None = None
    # Resource identifiers and phases remain separate from human-readable names.
    object_id: str | None = None
    resource_phase: str = ""
    resource_result_code: int | None = None
    # Memory evidence is stored separately from generic object/tracepoint
    # semantics. None means the CTF event did not supply a usable field.
    memory_phase: str = ""  # attempt | allocated | freed | failed | ignored
    memory_family: str = ""  # heap | slab
    memory_pool: str = ""
    allocation_id: str | None = None
    allocation_size_bytes: int | None = None


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
    # Loss notifications are evidence for memory-profile completeness, but
    # must not silently change existing RTOS analysis output contracts.
    loss_reported: bool = False
    tracepoint_sample_counts: dict[str, int] = field(default_factory=dict)
    missing_tracepoints: list[str] = field(default_factory=list)
    sample_errors: list[str] = field(default_factory=list)

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
    "thread_sched_ready": EventKind.THREAD_READY,
    "thread_pend": EventKind.THREAD_BLOCKED,
    "thread_pending": EventKind.THREAD_BLOCKED,
    "thread_sched_pend": EventKind.THREAD_BLOCKED,
    "thread_sleep_enter": EventKind.THREAD_SLEEPING,
    "isr_enter": EventKind.ISR_ENTER,
    "isr_enter_user": EventKind.ISR_ENTER,
    "isr_exit": EventKind.ISR_EXIT,
    "isr_exit_user": EventKind.ISR_EXIT,
    "isr_exit_to_scheduler": EventKind.ISR_EXIT,
    "mutex_lock": EventKind.MUTEX_TAKE,
    "mutex_lock_enter": EventKind.MUTEX_TAKE,
    "mutex_lock_blocking": EventKind.MUTEX_TAKE,
    "mutex_lock_exit": EventKind.MUTEX_TAKE,
    "mutex_take": EventKind.MUTEX_TAKE,
    "mutex_unlock": EventKind.MUTEX_GIVE,
    "mutex_unlock_enter": EventKind.MUTEX_GIVE,
    "mutex_unlock_exit": EventKind.MUTEX_GIVE,
    "mutex_give": EventKind.MUTEX_GIVE,
    "semaphore_take": EventKind.SEM_TAKE,
    "semaphore_take_enter": EventKind.SEM_TAKE,
    "semaphore_take_blocking": EventKind.SEM_TAKE,
    "semaphore_take_exit": EventKind.SEM_TAKE,
    "sem_take": EventKind.SEM_TAKE,
    "semaphore_give": EventKind.SEM_GIVE,
    "semaphore_give_enter": EventKind.SEM_GIVE,
    "semaphore_give_exit": EventKind.SEM_GIVE,
    "sem_give": EventKind.SEM_GIVE,
    # Exact Zephyr 4.4 CTF metadata names; no generic queue/FIFO assumption.
    "msgq_put_enter": EventKind.MSGQ_PUT,
    "msgq_put_blocking": EventKind.MSGQ_PUT,
    "msgq_put_exit": EventKind.MSGQ_PUT,
    "msgq_put_front_enter": EventKind.MSGQ_PUT,
    "msgq_put_front_blocking": EventKind.MSGQ_PUT,
    "msgq_put_front_exit": EventKind.MSGQ_PUT,
    "msgq_get_enter": EventKind.MSGQ_GET,
    "msgq_get_blocking": EventKind.MSGQ_GET,
    "msgq_get_exit": EventKind.MSGQ_GET,
    # Only names verified in Zephyr CTF metadata, not speculative API names:
    "heap_sys_k_malloc_enter": EventKind.MEM_ALLOC,
    "heap_sys_k_malloc_exit": EventKind.MEM_ALLOC,
    "heap_sys_k_free_enter": EventKind.MEM_FREE,
    "heap_sys_k_free_exit": EventKind.MEM_FREE,
    "mem_slab_alloc_enter": EventKind.MEM_ALLOC,
    "mem_slab_alloc_exit": EventKind.MEM_ALLOC,
    "mem_slab_free_enter": EventKind.MEM_FREE,
    "mem_slab_free_exit": EventKind.MEM_FREE,
}


# Resource phases are explicit in Zephyr CTF's lock/take enter/blocking/exit
# event names. An "enter" does NOT prove successful acquisition. For mutex
# operations with a ret field, non-zero or missing ret never establishes a hold.
RESOURCE_EVENT_PHASES = {
    # Generic lock/take aliases lack an explicit success return: attempt only.
    "mutex_lock": "attempt", "mutex_take": "attempt",
    "mutex_lock_enter": "attempt", "mutex_lock_blocking": "blocking",
    "mutex_lock_exit": "result",
    "mutex_unlock": "released", "mutex_give": "released",
    "mutex_unlock_enter": "release_attempt", "mutex_unlock_exit": "release_result",
    "semaphore_take": "acquired", "sem_take": "acquired",
    "semaphore_take_enter": "attempt", "semaphore_take_blocking": "blocking",
    "semaphore_take_exit": "result",
    "semaphore_give": "released", "sem_give": "released",
    "semaphore_give_enter": "release_attempt", "semaphore_give_exit": "released",
    "msgq_put_enter": "attempt", "msgq_put_blocking": "blocking",
    "msgq_put_exit": "queue_result",
    "msgq_put_front_enter": "attempt", "msgq_put_front_blocking": "blocking",
    "msgq_put_front_exit": "queue_result",
    "msgq_get_enter": "attempt", "msgq_get_blocking": "blocking",
    "msgq_get_exit": "queue_result",
}


def _resource_phase(name: str, raw: Mapping) -> str:
    phase = RESOURCE_EVENT_PHASES.get(name, "")
    if phase == "queue_result":
        code = _memory_int(raw.get("ret"))
        return "unknown_result" if code is None else ("completed" if code == 0 else "failed")
    if phase in ("result", "release_result"):
        result = raw.get("ret")
        try:
            code = int(result, 0) if isinstance(result, str) else int(result)
        except (TypeError, ValueError, OverflowError):
            return "failed"  # no successful resource transition is established
        if code != 0:
            return "failed"
        return "acquired" if phase == "result" else "released"
    return phase


# Verified against Zephyr subsys/tracing/ctf/tsdl/metadata. The
# heap_sys_k_malloc_exit event contains id and ret (the returned pointer)
# but NO requested size; slab_alloc_exit contains id, timeout and ret
# (status), with NO block pointer or block size. Free events similarly
# omit a size. Do not assume size from a pointer, pool or slab identity.
MEMORY_NATIVE_EVENT_PHASES = {
    "heap_sys_k_malloc_enter": ("heap", "attempt"),
    "heap_sys_k_malloc_exit": ("heap", "alloc_result"),
    "heap_sys_k_free_enter": ("heap", "attempt"),
    "heap_sys_k_free_exit": ("heap", "freed"),
    "mem_slab_alloc_enter": ("slab", "attempt"),
    "mem_slab_alloc_exit": ("slab", "alloc_result"),
    "mem_slab_free_enter": ("slab", "attempt"),
    "mem_slab_free_exit": ("slab", "freed"),
}

# Intentionally not mapped: literal k_malloc/k_free,
# k_mem_slab_alloc/k_mem_slab_free, mem_alloc/mem_free — none were found
# as CTF event names in Zephyr's versioned metadata.
UNVERIFIED_MEMORY_NAMES = (
    "k_malloc", "k_free", "k_mem_slab_alloc", "k_mem_slab_free",
    "mem_alloc", "mem_free",
)


def _memory_int(value):
    if type(value) is int:
        return value
    if isinstance(value, str):
        try:
            return int(value, 0) if value.startswith(("0x", "-0x", "+0x")) else int(value)
        except ValueError:
            return None
    return None


def _memory_ptr(value):
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, str):
        value = value.strip()
        if not value:
            return None
    number = _memory_int(value)
    if number is not None:
        return None if number == 0 else str(number)
    return str(value) if isinstance(value, str) else None


def _memory_evidence(name: str, raw: Mapping):
    """Return (phase,family,pool,allocation_id,size) with no inferred size."""
    spec = MEMORY_NATIVE_EVENT_PHASES.get(name)
    if spec is None:
        return "", "", "", None, None
    family, phase = spec
    pool_name = _first(raw, "pool_name", "slab_name", "heap_name", "object_name")
    pool_id = _first(raw, "object_id", "id")
    pool = (str(pool_name) if pool_name is not None else
            f"{family} ID {pool_id} (name unavailable)" if pool_id is not None else
            f"unattributed {family}")
    allocation_id = None
    if name == "heap_sys_k_malloc_exit":
        allocation_id = _memory_ptr(_first(raw, "allocation_id", "ptr", "ret"))
        phase = "allocated" if allocation_id is not None else "failed"
    elif name == "heap_sys_k_free_exit":
        allocation_id = _memory_ptr(_first(raw, "allocation_id", "ptr", "heap_ref"))
        phase = "freed" if allocation_id is not None else "ignored"
    elif name == "mem_slab_alloc_exit":
        result = _memory_int(raw.get("ret"))
        phase = "allocated" if result == 0 else "failed"
        allocation_id = _memory_ptr(_first(raw, "allocation_id", "ptr"))
    elif name == "mem_slab_free_exit":
        phase = "freed"
        allocation_id = _memory_ptr(_first(raw, "allocation_id", "ptr"))
    size = None
    if phase == "allocated":
        # Optional richer CTF metadata/explicit normalized source may carry
        # sizes. Stock Zephyr CTF events listed above never do.
        for field in ("size_bytes", "allocation_size", "size"):
            if raw.get(field) is not None:
                candidate = _memory_int(raw[field])
                if candidate is not None and 0 <= candidate <= (1 << 63) - 1:
                    size = candidate
                break
    return phase, family, pool, allocation_id, size


def _text(value) -> str:
    return "" if value is None else str(value)


def _first(record: Mapping, *keys: str):
    for key in keys:
        value = record.get(key)
        if value is not None and value != "":
            return value
    return None


def _named_u32(value) -> int:
    """Accept CTF unsigned 32-bit integer or a signed int32 bit pattern."""
    if isinstance(value, bool):
        raise ValueError("boolean is not a trace sample")
    number = int(value, 0) if isinstance(value, str) else int(value)
    if number < -0x80000000 or number > 0xFFFFFFFF:
        raise ValueError("arg0 is outside its 32-bit range")
    return number & 0xFFFFFFFF


def _tracepoint_value(raw: Mapping, spec) -> int | float:
    # Current Zephyr CTF named_event carries a 32-bit unsigned bit pattern
    # in arg0. arg1 is reserved, NOT an additional sample.
    raw_name = _text(raw.get("name")).rsplit(":", 1)[-1]
    encoded = raw.get("arg0") if raw_name == "named_event" else raw.get("value")
    if encoded is None:
        raise ValueError("missing arg0/value payload")
    if raw_name == "named_event":
        bits = _named_u32(encoded)
        if spec.type == "integer":
            return bits if bits < 0x80000000 else bits - 0x100000000
        number = struct.unpack("<f", struct.pack("<I", bits))[0]
    elif spec.type == "integer":
        if isinstance(encoded, bool) or isinstance(encoded, float):
            raise ValueError("integer sample must be integral")
        number = int(encoded, 0) if isinstance(encoded, str) else int(encoded)
        if not -(2 ** 31) <= number <= 2 ** 31 - 1:
            raise ValueError("signed integer sample outside int32 range")
    else:
        number = float(encoded)
    if isinstance(number, float) and not math.isfinite(number):
        raise ValueError("float sample is not finite")
    return number


def decode_ctf(path: Path):
    """Use the native Zephyr 4.4 wire layout when recognized; else Babeltrace.

    Zephyr's raw RAM stream is not a packetized CTF trace with a clock
    declaration, so default_clock_snapshot is not reliable for that format.
    """
    if supports(path):
        yield from decode_packed(path)
        if buffer_full_evidence(path):
            # This is a verified RAM-backend flag, NOT a known dropped count.
            yield {"kind": "ram_buffer_full", "count": None}
        return
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

            def bounded_text(name):
                if fields is None or name not in fields:
                    return None
                value = fields[name]
                if isinstance(value, str):
                    return value.split("\0", 1)[0]
                if isinstance(value, bytes):
                    return value.split(b"\0", 1)[0].decode("ascii", "replace")
                try:
                    values = bytes(int(char) for char in value)
                    return values.split(b"\0", 1)[0].decode("ascii", "replace")
                except (TypeError, ValueError, OverflowError):
                    return str(value).split("\0", 1)[0]

            try:
                timestamp = message.default_clock_snapshot.ns_from_origin
            except (AttributeError, ValueError):
                timestamp = None
            yield {
                "kind": "event", "stream": streams[stream],
                "name": event.name, "timestamp_ns": timestamp,
                "thread_id": get("thread_id"),
                "thread_name": None if event.name.rsplit(":", 1)[-1] == "named_event" else bounded_text("name"),
                "named_event_name": bounded_text("name") if event.name.rsplit(":", 1)[-1] == "named_event" else None,
                "priority": get("prio"),
                "core_id": get("cpu"),
                "object_id": get("id"),
                "ret": get("ret"),
                "object_name": get("object_name"),
                "mutex_name": get("mutex_name"),
                "semaphore_name": get("semaphore_name"),
                "pool_name": get("pool_name"),
                "slab_name": get("slab_name"), "heap_name": get("heap_name"),
                "allocation_id": get("allocation_id"), "ptr": get("ptr"),
                "heap_ref": get("heap_ref"),
                "size_bytes": get("size_bytes"),
                "allocation_size": get("allocation_size"), "size": get("size"),
                "next_tid": get("next_tid"),
                "next_comm": get("next_comm"),
                "next_prio": get("next_prio"),
                "arg0": get("arg0"), "arg1": get("arg1"),
                "value": get("value"),
            }


class SemanticTraceParser:
    """Normalize raw records or a saved CTF path into stable time order.

    A decoder error (including a truncated final packet) returns the complete
    events observed *before* the error, with incomplete=True. Missing timestamps
    remain None and sort last; no timestamp is invented.
    """

    MAX_EVENTS = 2_000_000

    def parse(self, source: Iterable[Mapping] | str | Path, tracepoints=None) -> SemanticTrace:
        trace = SemanticTrace()
        # Optional project configuration: without it all named events follow
        # the existing UNKNOWN preservation path. No CTF name is presumed.
        specs = {spec.name: spec for spec in
                 (tracepoints.specs if tracepoints is not None
                  and tracepoints.status == "active" else ())}
        trace.tracepoint_sample_counts = {name: 0 for name in specs}
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
                    if raw.get("kind") in ("discarded_events", "discarded_packets",
                                            "ram_buffer_full"):
                        # Backend full is verified via the saved receipt's hashes;
                        # it is loss *risk*, not an invented dropped-event count.
                        trace.loss_reported = True
                        if raw.get("kind") == "ram_buffer_full":
                            trace.incomplete = True
                        continue
                    name = _text(raw.get("name"))
                    simple_name = name.rsplit(":", 1)[-1]
                    kind = RAW_EVENT_KINDS.get(simple_name, EventKind.UNKNOWN)
                    # Zephyr CTF's wire event name is always "named_event";
                    # the declared tracepoint is in its bounded "name" field.
                    declared_name = _text(raw.get("named_event_name")) if simple_name == "named_event" else simple_name
                    spec = specs.get(declared_name)
                    sample_value = None
                    if spec is not None:
                        try:
                            sample_value = _tracepoint_value(raw, spec)
                            kind = EventKind.VALUE_SAMPLE
                        except (TypeError, ValueError, OverflowError) as exc:
                            kind = EventKind.UNKNOWN
                            if len(trace.sample_errors) < 100:
                                trace.sample_errors.append(f"{declared_name}: {exc}")
                    timestamp = raw.get("timestamp_ns")
                    if type(timestamp) is not int or timestamp < 0:
                        timestamp = None
                        trace.incomplete = True
                    if timestamp is not None and previous_timestamp is not None and timestamp < previous_timestamp:
                        trace.input_out_of_order = True
                    if timestamp is not None:
                        previous_timestamp = timestamp
                    if kind == EventKind.VALUE_SAMPLE and timestamp is None:
                        kind = EventKind.UNKNOWN
                        if len(trace.sample_errors) < 100:
                            trace.sample_errors.append(f"{declared_name}: missing timestamp")
                    if kind == EventKind.VALUE_SAMPLE:
                        trace.tracepoint_sample_counts[declared_name] += 1
                    sched = simple_name == "sched_switch"
                    thread_id = _first(raw, "next_tid", "thread_id") if sched else raw.get("thread_id")
                    thread_name = _first(raw, "next_comm", "thread_name") if sched else raw.get("thread_name")
                    priority = _first(raw, "next_prio", "priority", "prio") if sched else _first(raw, "priority", "prio")
                    object_name = _first(raw, "object_name", "mutex_name", "semaphore_name",
                                         "pool_name", "heap_name")
                    memory_phase, memory_family, memory_pool, allocation_id, allocation_size = (
                        _memory_evidence(simple_name, raw))
                    event = SemanticEvent(
                        timestamp_ns=timestamp, kind=kind, thread_name=_text(thread_name),
                        thread_priority=None if priority is None else str(priority),
                        object_name=_text(object_name), raw_event_name=name,
                        thread_id=None if thread_id is None else str(thread_id),
                        stream=raw.get("stream"),
                        object_id=None if raw.get("object_id") is None else str(raw["object_id"]),
                        resource_phase=_resource_phase(simple_name, raw),
                        resource_result_code=_memory_int(raw.get("ret")),
                        tracepoint_name=declared_name if kind == EventKind.VALUE_SAMPLE else "",
                        value=sample_value if kind == EventKind.VALUE_SAMPLE else None,
                        memory_phase=memory_phase, memory_family=memory_family,
                        memory_pool=memory_pool, allocation_id=allocation_id,
                        allocation_size_bytes=allocation_size)
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
        trace.missing_tracepoints = [
            name for name, count in trace.tracepoint_sample_counts.items() if count == 0]
        return trace
