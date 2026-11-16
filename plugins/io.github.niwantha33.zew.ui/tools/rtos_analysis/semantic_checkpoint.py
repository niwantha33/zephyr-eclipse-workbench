# SPDX-License-Identifier: Apache-2.0
"""Bounded, test-only semantic checkpoints for verified packed CTF records.

This is an offline/loopback adapter, not a transport, hardware attestation,
live Eclipse view, CPU-load calculator, or new Zephyr recorder. Callers must
first validate ZEWF frame identity, loss, and complete CTF record boundaries.
No automatic reconnection, out-of-order sorting, or gap repair is permitted.
"""
from __future__ import annotations

from dataclasses import replace
import hashlib
import json
import re
from typing import Mapping

from semantic_trace import SemanticTrace, SemanticTraceParser

MAX_PREVIEW_EVENTS = 16000
MAX_CHECKPOINTS = 4096
MAX_RECORDS_PER_APPEND = 4096
MAX_COMMITTED_BYTES = 8 * 1024 * 1024


class SemanticEpochCheckpoint:
    """One explicit 16-byte epoch, exact metadata and single packed CTF stream.

    The source must be IncrementalPackedCtfDecoder.feed() or an equivalent
    *already verified* source of complete records with absolute wire offsets.
    Checkpoints are decoder committed byte offsets, not TCP chunk boundaries.
    Exact checkpoint retries are idempotent; conflicting retries poison the
    epoch. All preview output remains unqualified for live/hardware metrics.
    """

    def __init__(self, metadata: str, metadata_sha256: str, epoch: bytes,
                 *, max_events: int = MAX_PREVIEW_EVENTS):
        if not isinstance(metadata, str) or not metadata:
            raise ValueError("Exact CTF metadata is required")
        if not isinstance(metadata_sha256, str) or not re.fullmatch(
                r"[0-9a-f]{64}", metadata_sha256):
            raise ValueError("Exact lowercase metadata SHA256 is required")
        if hashlib.sha256(metadata.encode("utf-8")).hexdigest() != metadata_sha256:
            raise ValueError("CTF metadata digest mismatch")
        if type(epoch) is not bytes or len(epoch) != 16 or epoch == bytes(16):
            raise ValueError("Nonzero 16-byte epoch is required")
        if type(max_events) is not int or not 1 <= max_events <= MAX_PREVIEW_EVENTS:
            raise ValueError("Invalid semantic preview event budget")
        self._sha = metadata_sha256
        self._epoch = epoch
        self._budget = max_events
        self._offset = 0
        self._last_wire_offset = -1
        self._last_timestamp = None
        self._trace = SemanticTrace()
        self._checkpoints: dict[int, str] = {}
        self._closed = False
        self._poisoned = False
        self._sealed_test_format = False
        self._source_losses = None
        self._reason = ""

    def _reject(self, reason: str):
        self._poisoned = True
        self._reason = reason[:300]
        raise ValueError(reason)

    def append(self, records: list[Mapping], *, checkpoint: int,
               metadata_sha256: str, epoch: bytes) -> list:
        """Append each complete record at most once; return only new events."""
        if self._closed or self._poisoned:
            raise ValueError("Semantic epoch closed or invalid; use a new epoch")
        if metadata_sha256 != self._sha or epoch != self._epoch:
            self._reject("Semantic epoch or metadata identity drift")
        if type(checkpoint) is not int or not 0 <= checkpoint <= MAX_COMMITTED_BYTES:
            self._reject("Unbounded/invalid CTF committed-byte checkpoint")
        if not isinstance(records, (list, tuple)) or len(records) > MAX_RECORDS_PER_APPEND:
            self._reject("Unbounded semantic append batch")
        # A partial CTF event makes no decoder progress; it is not a retry.
        if not records and checkpoint == self._offset:
            return []
        try:
            source = [dict(item) for item in records if isinstance(item, Mapping)]
            if len(source) != len(records):
                self._reject("Non-mapping semantic source record")
            digest = hashlib.sha256(json.dumps(
                source, sort_keys=True, separators=(",", ":"), allow_nan=False
            ).encode("utf-8")).hexdigest()
        except (TypeError, ValueError, OverflowError) as exc:
            self._reject("Unserializable semantic source: " + str(exc))
        if checkpoint in self._checkpoints:
            if self._checkpoints[checkpoint] == digest:
                return []  # Exact replay, no new event or count.
            self._reject("Conflicting duplicate semantic checkpoint")
        if checkpoint <= self._offset or not source:
            self._reject("Stale, empty, or nonadvancing semantic checkpoint")
        if len(self._checkpoints) >= MAX_CHECKPOINTS:
            self._reject("Semantic checkpoint-count budget exceeded")
        if len(self._trace.events) + len(source) > self._budget:
            self._reject("Semantic preview event budget exceeded")

        last_offset = self._last_wire_offset
        last_time = self._last_timestamp
        for index, raw in enumerate(source):
            offset = raw.get("wire_offset")
            timestamp = raw.get("timestamp_ns")
            if (raw.get("kind") != "event" or raw.get("stream") != 0
                    or raw.get("core_id") not in (None, 0)):
                self._reject("Unsupported loss/multistream/non-event source record")
            if type(offset) is not int or not 0 <= offset < checkpoint:
                self._reject("Invalid semantic wire offset")
            if index == 0 and offset != self._offset:
                self._reject("Semantic checkpoint has a wire-offset gap")
            if offset <= last_offset:
                self._reject("Duplicate or out-of-order semantic wire offset")
            if type(timestamp) is not int or timestamp < 0:
                self._reject("Missing or invalid semantic timestamp")
            if last_time is not None and timestamp < last_time:
                self._reject("Out-of-order semantic timestamp; no implicit sorting")
            last_offset, last_time = offset, timestamp

        batch = SemanticTraceParser().parse(source)
        if (batch.incomplete or batch.decoder_unavailable or batch.input_out_of_order
                or batch.error or len(batch.events) != len(source)):
            self._reject("Semantic parser rejected complete checkpoint")
        self._trace.events.extend(batch.events)
        self._trace.raw_records.extend(batch.raw_records)
        self._trace.unknown_count += batch.unknown_count
        self._trace.loss_reported |= batch.loss_reported
        self._trace.sample_errors.extend(batch.sample_errors[:100 - len(self._trace.sample_errors)])
        self._offset = checkpoint
        self._last_wire_offset = last_offset
        self._last_timestamp = last_time
        self._checkpoints[checkpoint] = digest
        return list(batch.events)

    def finish(self, *, format_sealed: bool, source_losses: int | None) -> dict:
        """Seal test format only after independently completed frame+CTF gates.

        Even clean test framing does not verify a device, a flashed ELF, a
        trustworthy boot epoch, or continuous real-time scheduling.
        """
        if self._closed:
            raise ValueError("Semantic epoch already closed")
        self._closed = True
        self._source_losses = source_losses
        if (format_sealed is True and type(source_losses) is int
                and source_losses == 0 and not self._poisoned
                and self._offset > 0 and self._trace.events):
            self._sealed_test_format = True
        else:
            self._poisoned = True
            if not self._reason:
                self._reason = "Missing END, source-loss evidence or complete semantic records"
        return self.stats()

    def abort(self, reason: str) -> dict:
        """Retain a bounded observed prefix; never promote it to valid CTF."""
        if not self._closed:
            self._closed = True
            self._poisoned = True
            self._reason = str(reason)[:300]
        return self.stats()

    def snapshot(self) -> SemanticTrace:
        """Read-only-by-convention defensive copy for a non-authoritative view."""
        return replace(self._trace, events=list(self._trace.events),
                       raw_records=[dict(raw) for raw in self._trace.raw_records],
                       sample_errors=list(self._trace.sample_errors),
                       tracepoint_sample_counts=dict(self._trace.tracepoint_sample_counts),
                       missing_tracepoints=list(self._trace.missing_tracepoints),
                       incomplete=not self._sealed_test_format,
                       error=self._reason)

    def stats(self) -> dict:
        return {
            "schema": "zew-semantic-checkpoint-test-1",
            "committed_ctf_bytes": self._offset,
            "semantic_events": len(self._trace.events),
            "unknown_events": self._trace.unknown_count,
            "checkpoints": len(self._checkpoints),
            "poisoned": self._poisoned,
            "closed": self._closed,
            "format_complete_test_only": self._sealed_test_format,
            "source_losses_reported": self._source_losses,
            "reason": self._reason,
            "hardware_capture_verified": False,
            "flashed_image_verified": False,
            "metrics_qualified": False,
            "live_stream_approved": False,
        }
