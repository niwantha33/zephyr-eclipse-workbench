#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Evidence-only saved-CTF mutex, semaphore and message-queue object summaries.

Only completed, explicitly successful operations increment success counts.
Blocking durations require a matched BLOCKING -> exit pair for the *same*
observed thread and object. No queue depth, capacity, owner or deadlock is
reconstructed from operation counts. No live target is read.
"""
from __future__ import annotations

from dataclasses import dataclass, field

from semantic_trace import EventKind, SemanticTrace

MAX_OBJECTS = 1024


@dataclass
class ObjectActivity:
    kind: str
    object: str
    take_get: int = 0
    give_put: int = 0
    failures: int = 0
    blocking_events: int = 0
    paired_blocks: int = 0
    long_blocks: int = 0
    max_blocked_ns: int | None = None

    @property
    def unpaired_blocks(self) -> int:
        return self.blocking_events - self.paired_blocks


@dataclass
class SyncActivityReport:
    objects: list[ObjectActivity] = field(default_factory=list)
    resource_events: int = 0
    unattributed_events: int = 0
    incomplete: bool = False
    reason: str = ""


def summarize(trace: SemanticTrace, threshold_us: int = 1000) -> SyncActivityReport:
    """Count observed object operations; do not extrapolate trace boundaries."""
    if type(threshold_us) is not int or not 0 <= threshold_us <= 86_400_000_000:
        raise ValueError("Threshold must be 0..86400000000 microseconds.")
    incomplete = bool(trace.incomplete or trace.loss_reported or trace.input_out_of_order)
    result = SyncActivityReport(incomplete=incomplete,
                                reason=trace.error or ("Loss or unordered CTF was observed."
                                                       if incomplete else ""))
    objects: dict[tuple[str, str, str], ObjectActivity] = {}
    active: dict[object, str] = {}
    # (object identity, operation, thread id) -> observed BLOCKING timestamp
    pending: dict[tuple[tuple[str, str, str], str, str], int] = {}
    kinds = {
        EventKind.MUTEX_TAKE: ("Mutex", "take"),
        EventKind.MUTEX_GIVE: ("Mutex", "give"),
        EventKind.SEM_TAKE: ("Semaphore", "take"),
        EventKind.SEM_GIVE: ("Semaphore", "give"),
        EventKind.MSGQ_PUT: ("Message queue", "put"),
        EventKind.MSGQ_GET: ("Message queue", "get"),
    }
    threshold_ns = threshold_us * 1000
    for event in trace.events:
        if event.kind == EventKind.THREAD_SWITCHED_OUT and event.stream is not None:
            active.pop(event.stream, None)
        elif event.kind == EventKind.THREAD_SWITCHED_IN and event.stream is not None:
            if event.thread_id is not None:
                active[event.stream] = event.thread_id
            else:
                active.pop(event.stream, None)
        spec = kinds.get(event.kind)
        if spec is None:
            continue
        result.resource_events += 1
        family, operation = spec
        if event.object_id is not None and event.object_id != "":
            object_key = (family, "id", event.object_id)
            label = event.object_name or f"object ID {event.object_id} (name unavailable)"
        elif event.object_name:
            object_key = (family, "name", event.object_name)
            label = event.object_name
        else:
            result.unattributed_events += 1
            continue
        if object_key not in objects:
            if len(objects) >= MAX_OBJECTS:
                result.incomplete = True
                result.reason = "Object report truncated at 1024 identities."
                continue
            objects[object_key] = ObjectActivity(family, label)
        row = objects[object_key]
        if event.object_name and row.object.startswith("object ID "):
            row.object = event.object_name
        phase = event.resource_phase
        actor = event.thread_id
        if actor is None and event.stream is not None:
            actor = active.get(event.stream)
        request_key = (object_key, operation, actor) if actor is not None else None

        if phase == "blocking":
            row.blocking_events += 1
            if request_key is not None and event.timestamp_ns is not None:
                # Repeated blocking events do not create an invented start time.
                pending.setdefault(request_key, event.timestamp_ns)
            continue

        # Every outcome below comes from an explicit observed return/event.
        if phase in ("acquired", "completed"):
            if operation in ("take", "get"):
                row.take_get += 1
            else:
                row.give_put += 1
        elif phase == "released":
            if operation in ("give", "put"):
                row.give_put += 1
        elif phase == "failed" and event.resource_result_code is not None:
            # Missing/unparseable ret is NOT a proven failed operation.
            row.failures += 1

        # A matched return closes a BLOCKING interval even if the operation
        # timed out. Do not count the unblocked call overhead as blocked time.
        is_exit = phase in ("acquired", "completed", "released", "failed", "unknown_result")
        if is_exit and request_key is not None and request_key in pending:
            begin = pending.pop(request_key)
            end = event.timestamp_ns
            if end is not None and end >= begin:
                duration = end - begin
                row.paired_blocks += 1
                row.max_blocked_ns = max(row.max_blocked_ns or 0, duration)
                if duration > threshold_ns:
                    row.long_blocks += 1

    result.objects = sorted(objects.values(), key=lambda row: (row.kind, row.object))
    return result
