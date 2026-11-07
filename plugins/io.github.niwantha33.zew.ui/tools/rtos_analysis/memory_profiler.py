#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Evidence-only allocation balances from normalized semantic events.

This is a *trace-observed* balance, not a heap census or leak detector.
Each confirmed allocation/free outcome changes the step function at its
observed timestamp. Identity is required to match heap frees to allocations.
Slab alloc/free outcomes without block pointers can establish a per-slab
count balance, but not byte sizes. Never infer sizes from heap IDs, pointers,
slab IDs, or CONFIG values. If any event makes exact byte accounting
impossible, byte totals/peaks are unavailable, NOT zero.

A truncated/lossy trace cannot support an end-of-trace imbalance statement.
"""
from __future__ import annotations

from dataclasses import dataclass, field

from semantic_trace import EventKind, SemanticTrace

NO_EVENTS = (
    "No memory allocation events found. Enable CONFIG_TRACING_HEAP or the equivalent "
    "memory tracing option in the Zephyr build to use this view."
)
INCOMPLETE = "Trace is incomplete; outstanding allocation imbalance cannot be assessed."
BYTES_UNAVAILABLE = (
    "Byte totals and peak are unavailable: the observed memory events do not "
    "provide complete allocation sizes and identities. No sizes were estimated."
)


@dataclass(frozen=True)
class MemoryStep:
    timestamp_ns: int
    outstanding_count: int | None
    outstanding_bytes: int | None


@dataclass(frozen=True)
class PoolProfile:
    name: str
    alloc_count: int
    free_count: int
    current_count: int | None
    current_bytes: int | None
    peak_bytes: int | None


@dataclass(frozen=True)
class MemoryProfile:
    steps: list[MemoryStep]
    pools: list[PoolProfile]
    memory_events: int
    current_count: int | None
    current_bytes: int | None
    peak_bytes: int | None
    peak_timestamp_ns: int | None
    incomplete: bool
    count_complete: bool
    bytes_complete: bool
    decoder_unavailable: bool
    diagnostics: list[str]

    @property
    def no_events(self) -> bool:
        return self.memory_events == 0

    @property
    def imbalance_line(self) -> str:
        if self.incomplete or self.no_events or not self.count_complete \
                or not self.bytes_complete or self.current_count is None \
                or self.current_bytes is None or self.current_count <= 0:
            return ""
        return (f"Trace ends with {self.current_count} outstanding allocations "
                f"({self.current_bytes} bytes). This may indicate a leak or may simply "
                "mean the allocations are long-lived. The trace alone cannot distinguish these.")

    @property
    def message(self) -> str:
        if self.no_events:
            return NO_EVENTS
        if self.incomplete:
            return INCOMPLETE
        if not self.count_complete:
            return "Allocation balance unavailable: the trace has unmatched or ambiguous allocation/free outcomes."
        if not self.bytes_complete:
            return BYTES_UNAVAILABLE
        return self.imbalance_line or "No positive outstanding allocation imbalance was observed."


@dataclass
class _Pool:
    alloc_count: int = 0
    free_count: int = 0
    current_count: int = 0
    current_known_bytes: int = 0
    peak_known_bytes: int = 0
    bytes_complete: bool = True
    # Stock slab CTF does not identify allocated/free block pointers.
    anonymous_slab_count: int = 0


class MemoryProfiler:
    def calculate(self, trace: SemanticTrace) -> MemoryProfile:
        pools: dict[str, _Pool] = {}
        # A pointer identity must be paired within the same pool/family.
        # No synthetic allocation ID or name is generated.
        live: dict[tuple[str, str, str], int | None] = {}
        steps: list[MemoryStep] = []
        notes: list[str] = []
        count_complete = True
        bytes_complete = True
        count = 0
        known_bytes = 0
        peak = 0
        peak_time: int | None = None
        events = 0
        prior_timestamp: int | None = None

        def warn(message: str) -> None:
            if message not in notes and len(notes) < 12:
                notes.append(message)

        for event in trace.events:
            if event.kind not in (EventKind.MEM_ALLOC, EventKind.MEM_FREE):
                continue
            events += 1
            timestamp = event.timestamp_ns
            if timestamp is None:
                count_complete = False
                bytes_complete = False
                warn("Memory event without an observed timestamp was not included.")
                continue
            if prior_timestamp is not None and timestamp < prior_timestamp:
                count_complete = False
                bytes_complete = False
                warn("Memory event timestamps are not monotonic.")
                continue
            prior_timestamp = timestamp
            phase = event.memory_phase or (
                "allocated" if event.kind == EventKind.MEM_ALLOC else "freed")
            if phase not in ("allocated", "freed"):
                # Entry, blocking and failure events are not allocations.
                continue
            family = event.memory_family or "heap"
            pool = event.memory_pool or event.object_name or "Unattributed pool"
            state = pools.setdefault(pool, _Pool())
            ident = event.allocation_id
            key = (family, pool, ident) if ident else None
            size = event.allocation_size_bytes
            if size is not None and (type(size) is not int or size < 0):
                size = None

            if phase == "allocated":
                state.alloc_count += 1
                if family == "slab" and key is None:
                    state.anonymous_slab_count += 1
                    state.current_count += 1
                    count += 1
                    # A slab with no pointer cannot pair individual sizes on free.
                    bytes_complete = False
                    state.bytes_complete = False
                elif key is None:
                    count_complete = False
                    bytes_complete = False
                    state.bytes_complete = False
                    warn("Heap allocation without a pointer cannot be paired with a free.")
                elif key in live:
                    count_complete = False
                    bytes_complete = False
                    state.bytes_complete = False
                    warn("Duplicate live allocation pointer observed without an intervening free.")
                else:
                    live[key] = size
                    count += 1
                    state.current_count += 1
                    if size is None:
                        bytes_complete = False
                        state.bytes_complete = False
                    else:
                        known_bytes += size
                        state.current_known_bytes += size
            else:
                state.free_count += 1
                if family == "slab" and key is None:
                    if state.anonymous_slab_count > 0:
                        state.anonymous_slab_count -= 1
                        state.current_count -= 1
                        count -= 1
                        bytes_complete = False
                        state.bytes_complete = False
                    else:
                        count_complete = False
                        bytes_complete = False
                        state.bytes_complete = False
                        warn("A slab free had no corresponding observed allocation in its pool.")
                elif key is None or key not in live:
                    count_complete = False
                    bytes_complete = False
                    state.bytes_complete = False
                    warn("A free had no matching observed allocation identity.")
                else:
                    allocated_size = live.pop(key)
                    count -= 1
                    state.current_count -= 1
                    if allocated_size is not None:
                        known_bytes -= allocated_size
                        state.current_known_bytes -= allocated_size
                    else:
                        bytes_complete = False
                        state.bytes_complete = False

            if not bytes_complete:
                # A subtotal is never mislabelled as the whole-heap byte total.
                steps.append(MemoryStep(timestamp, count if count_complete else None, None))
            else:
                steps.append(MemoryStep(timestamp, count if count_complete else None,
                                        known_bytes if count_complete else None))
                if known_bytes > peak:
                    peak = known_bytes
                    peak_time = timestamp
            if state.bytes_complete and count_complete:
                state.peak_known_bytes = max(state.peak_known_bytes, state.current_known_bytes)

        # Once the history has any unsized/ambiguous operation, the WHOLE
        # timeline's byte peak is unproven, including its earlier steps.
        if not bytes_complete or not count_complete:
            steps = [MemoryStep(s.timestamp_ns, s.outstanding_count
                                if count_complete else None, None) for s in steps]
        pool_list = [
            PoolProfile(name, s.alloc_count, s.free_count,
                        s.current_count if count_complete else None,
                        s.current_known_bytes if s.bytes_complete and count_complete
                        and bytes_complete else None,
                        s.peak_known_bytes if s.bytes_complete and count_complete
                        and bytes_complete else None)
            for name, s in sorted(pools.items())
        ]
        return MemoryProfile(
            steps=steps,
            pools=pool_list,
            memory_events=events,
            current_count=count if count_complete else None,
            current_bytes=known_bytes if bytes_complete and count_complete else None,
            peak_bytes=peak if bytes_complete and count_complete and events > 0 else None,
            peak_timestamp_ns=peak_time if bytes_complete and count_complete else None,
            incomplete=trace.incomplete,
            count_complete=count_complete,
            bytes_complete=bytes_complete and count_complete,
            decoder_unavailable=trace.decoder_unavailable,
            diagnostics=([trace.error] if trace.error else []) + notes,
        )
