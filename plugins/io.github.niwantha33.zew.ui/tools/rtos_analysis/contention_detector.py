#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline, evidence-only priority inversion and resource wait detection.

Consumes SemanticTrace.events only, never a live target. The kernel's
NUMERICALLY SMALLER priority value denotes a higher Zephyr priority.
An attempted lock does not prove ownership; only an explicitly successful
acquisition does. A missing owner is never invented.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from semantic_trace import EventKind, SemanticEvent, SemanticTrace, SemanticTraceParser

DEFAULT_THRESHOLD_US = 1000
MAX_FINDINGS = 5000


@dataclass(frozen=True)
class Actor:
    key: tuple[str, str]
    name: str
    priority: int | None


@dataclass
class Owner:
    actor: Actor
    depth: int = 1


@dataclass
class Pending:
    kind: EventKind
    key: tuple[str, str, str]
    object_label: str
    waiter: Actor
    attempt_ns: int
    blocked_ns: int | None = None
    holder: Actor | None = None
    medium: Actor | None = None
    last_block_start: int | None = None


@dataclass(frozen=True)
class Finding:
    kind: str  # Inversion | Long Wait
    object_name: str
    waiter: str
    holder: str
    duration_ns: int
    explanation: str
    holder_unknown: bool
    waiter_priority: int | None = None
    holder_priority: int | None = None
    medium_thread: str = ""
    medium_priority: int | None = None

    @property
    def duration_us(self) -> str:
        return f"{self.duration_ns // 1000}.{self.duration_ns % 1000:03d}"


@dataclass
class ContentionReport:
    findings: list[Finding] = field(default_factory=list)
    inversion_enabled: bool = False
    incomplete: bool = False
    error: str = ""
    decoder_unavailable: bool = False
    observed_resource_events: int = 0


class ContentionDetector:
    """Recognize completed, explicitly bounded waits without guessing ownership.

    Pattern A: observed mutex acquisition by L, distinct H attempting that same
    mutex and explicitly blocked, observed scheduled-in M2 DURING that block
    while L still holds the mutex, with H.priority < M2.priority < L.priority,
    then an explicit READY or successful lock-return ending H's block.

    Pattern B: an observed take attempt followed by a successful take-return
    OR an explicit resource-blocking interval closed by READY/lock-return,
    strictly longer than threshold_us. If acquisition was not observed but a
    blocked->ready interval is, label it only as a bounded *wait*, not an
    acquired mutex. Missing holders are explicitly marked holder unknown.
    """

    def detect(self, trace: SemanticTrace, threshold_us: int = DEFAULT_THRESHOLD_US) -> ContentionReport:
        if type(threshold_us) is not int or threshold_us < 0 or threshold_us > 86_400_000_000:
            raise ValueError("Long-wait threshold must be a nonnegative integer in microseconds")
        result = ContentionReport(incomplete=trace.incomplete, error=trace.error,
                                  decoder_unavailable=trace.decoder_unavailable)
        threshold_ns = threshold_us * 1000
        # Active actor may be derived ONLY from explicit switched-in/out on
        # the same CTF stream. It is cleared at every switch-out or BLOCKED.
        active: dict[object, Actor] = {}
        known: dict[tuple[str, str], Actor] = {}
        holders: dict[tuple[str, str, str], Owner | None] = {}
        pending: dict[tuple[tuple[str, str, str], tuple[str, str]], Pending] = {}
        any_numeric_priority = False

        def actor_of(event: SemanticEvent, allow_current: bool) -> Actor | None:
            nonlocal any_numeric_priority
            key = (("id", str(event.thread_id)) if event.thread_id is not None else
                   ("name", event.thread_name) if event.thread_name else None)
            previous = known.get(key) if key is not None else None
            if key is None:
                return active.get(event.stream) if allow_current and event.stream is not None else None
            priority = previous.priority if previous is not None else None
            if event.thread_priority is not None:
                try:
                    priority = int(event.thread_priority)
                    any_numeric_priority = True
                except (TypeError, ValueError, OverflowError):
                    priority = None
            name = event.thread_name or (previous.name if previous is not None else "")
            actor = Actor(key, name, priority)
            known[key] = actor
            return actor

        def object_of(event: SemanticEvent, mutex: bool):
            category = "mutex" if mutex else "semaphore"
            if event.object_id is not None and event.object_id != "":
                identity = ("id", str(event.object_id))
            elif event.object_name:
                identity = ("name", event.object_name)
            else:
                return None, ""
            label = (event.object_name if event.object_name else
                     f"object ID {event.object_id} (name unavailable)")
            return (category, identity[0], identity[1]), label

        def finalize(request: Pending, finish_ns: int, blocked_end: bool) -> None:
            if finish_ns < request.attempt_ns:
                return
            begin = request.blocked_ns if blocked_end and request.blocked_ns is not None else request.attempt_ns
            if finish_ns < begin:
                return
            duration = finish_ns - begin
            waiter = request.waiter.name
            holder = request.holder.name if request.holder is not None else ""
            identifiable = bool(holder)
            if request.kind == EventKind.MUTEX_TAKE and request.medium is not None \
                    and request.holder is not None and request.blocked_ns is not None \
                    and request.waiter.priority is not None \
                    and request.holder.priority is not None and request.medium.priority is not None \
                    and request.waiter.priority < request.medium.priority < request.holder.priority \
                    and finish_ns > request.blocked_ns and waiter and holder \
                    and request.medium.name:
                result.findings.append(Finding(
                    "Inversion", request.object_label, waiter, holder,
                    finish_ns - request.blocked_ns,
                    f"Observed {holder} holding the mutex; {waiter} blocked while "
                    f"{request.medium.name} ran at intermediate priority. "
                    "No missed deadline is implied.",
                    False, request.waiter.priority, request.holder.priority,
                    request.medium.name, request.medium.priority))
            if duration > threshold_ns:
                description = (f"Observed {waiter or 'unnamed thread'} waiting for a "
                               f"{'mutex' if request.kind == EventKind.MUTEX_TAKE else 'semaphore'} "
                               f"for {duration / 1000:.3f} us; "
                               + (f"holder {holder} was observed." if identifiable else
                                  "holder unknown (no confirmed holder observed)."))
                result.findings.append(Finding(
                    "Long Wait", request.object_label, waiter, holder, duration,
                    description, not identifiable, request.waiter.priority,
                    request.holder.priority if request.holder else None))
            if len(result.findings) > MAX_FINDINGS:
                result.findings = result.findings[:MAX_FINDINGS]
                result.incomplete = True
                result.error = "Contention report truncated at 5000 findings"

        for event in trace.events:
            ts = event.timestamp_ns
            if ts is None:
                result.incomplete = True
                continue
            if len(result.findings) >= MAX_FINDINGS:
                result.incomplete = True
                result.error = "Contention report truncated at 5000 findings"
                break

            is_resource = event.kind in (EventKind.MUTEX_TAKE, EventKind.MUTEX_GIVE,
                                         EventKind.SEM_TAKE, EventKind.SEM_GIVE)
            actor = actor_of(event, allow_current=is_resource)
            if event.kind == EventKind.THREAD_SWITCHED_OUT:
                if event.stream is not None:
                    active.pop(event.stream, None)
            elif event.kind == EventKind.THREAD_SWITCHED_IN:
                if event.stream is not None:
                    if actor is not None:
                        active[event.stream] = actor
                    else:
                        active.pop(event.stream, None)
                if actor is not None and actor.priority is not None and actor.name:
                    # Only the *observed* switch-in can establish that M2 ran.
                    for request in pending.values():
                        if request.kind != EventKind.MUTEX_TAKE or request.blocked_ns is None \
                                or request.medium is not None or ts <= request.blocked_ns \
                                or actor.key in (request.waiter.key,
                                                 request.holder.key if request.holder else None):
                            continue
                        owner = holders.get(request.key)
                        if owner is None or request.holder is None \
                                or owner.actor.key != request.holder.key:
                            continue
                        if request.waiter.priority is not None \
                                and request.holder.priority is not None \
                                and request.waiter.priority < actor.priority < request.holder.priority:
                            request.medium = actor

            elif event.kind == EventKind.THREAD_BLOCKED:
                if actor is not None:
                    matches = [r for r in pending.values() if r.waiter.key == actor.key
                               and r.blocked_ns is None]
                    # A thread with multiple outstanding requests has no
                    # unambiguous relationship to this generic scheduler event.
                    if len(matches) == 1:
                        request = matches[0]
                        request.blocked_ns = ts
                        owner = holders.get(request.key)
                        request.holder = owner.actor if owner is not None else None
                    if event.stream is not None:
                        active.pop(event.stream, None)

            elif event.kind == EventKind.THREAD_READY:
                if actor is not None:
                    for key, request in list(pending.items()):
                        if request.waiter.key == actor.key and request.blocked_ns is not None:
                            finalize(request, ts, blocked_end=True)
                            del pending[key]

            if not is_resource:
                continue
            result.observed_resource_events += 1
            mutex = event.kind in (EventKind.MUTEX_TAKE, EventKind.MUTEX_GIVE)
            object_key, object_label = object_of(event, mutex)
            if object_key is None:
                continue  # object cannot be matched, no speculative association
            if event.kind in (EventKind.MUTEX_GIVE, EventKind.SEM_GIVE):
                if event.kind == EventKind.MUTEX_GIVE and event.resource_phase in ("released", ""):
                    owner = holders.get(object_key)
                    if owner is None or actor is None or owner.actor.key != actor.key:
                        holders[object_key] = None
                    elif owner.depth > 1:
                        owner.depth -= 1
                    else:
                        holders.pop(object_key, None)
                continue
            if actor is None:
                continue
            request_id = (object_key, actor.key)
            # An unqualified MUTEX_TAKE/SEM_TAKE kind alone does not prove
            # the enter/exit phase or ownership. Require explicit semantics.
            phase = event.resource_phase
            if phase in ("attempt", "blocking"):
                request = pending.get(request_id)
                if request is None:
                    request = Pending(event.kind, object_key, object_label, actor, ts)
                    pending[request_id] = request
                if phase == "blocking" and request.blocked_ns is None:
                    request.blocked_ns = ts
                    owner = holders.get(request.key)
                    request.holder = owner.actor if owner is not None else None
            elif phase == "acquired":
                request = pending.pop(request_id, None)
                if request is not None:
                    # A successful acquire ends an observed take attempt.
                    finalize(request, ts, blocked_end=request.blocked_ns is not None)
                if mutex:
                    owner = holders.get(object_key)
                    if owner is None:
                        holders[object_key] = Owner(actor)
                    elif owner.actor.key == actor.key:
                        owner.depth += 1
                    else:
                        # Conflicting acquisitions without an observed unlock
                        # destroy reliable ownership evidence.
                        holders[object_key] = None
            elif phase == "failed":
                # Failed or timed-out acquisition is not ownership evidence.
                pending.pop(request_id, None)

        result.inversion_enabled = any_numeric_priority
        if not any_numeric_priority:
            result.findings = [f for f in result.findings if f.kind != "Inversion"]
        result.findings.sort(key=lambda f: (0 if f.kind == "Inversion" else 1,
                                             -f.duration_ns, f.object_name,
                                             f.waiter, f.holder))
        return result


def detect_events(events, threshold_us: int = DEFAULT_THRESHOLD_US) -> ContentionReport:
    """Convenience entrypoint: normalize first, then analyze semantic events."""
    return ContentionDetector().detect(SemanticTraceParser().parse(events), threshold_us)
