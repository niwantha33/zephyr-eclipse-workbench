# SPDX-License-Identifier: Apache-2.0
"""Bounded incremental decoder for *verified* Zephyr 4.4 packed CTF bytes.

This is a format adapter, NOT a live transport or resynchronization protocol.
A future producer/host session MUST validate frame checksum, build/metadata
identity, sequence, boot epoch and loss receipts before feeding any bytes.
Unframed Zephyr CTF has no corruption marker: fail closed, never scan forward.
"""
from __future__ import annotations

import struct
from zephyr_ctf_binary import (
    MAX_EVENTS, MAX_METADATA, MAX_STREAM, _layout, _record_size,
    _decode_one, _event_record,
)

MAX_CHUNK = 16 * 1024
MAX_PENDING = 64 * 1024


class IncrementalPackedCtfDecoder:
    """One stream and boot epoch; caller owns bounded persistent raw evidence.

    feed() returns complete observed records in the same dictionary schema
    as zephyr_ctf_binary.decode_packed(). Neither timestamp gaps nor switch
    pairing can prove a *count* of missing events without source loss counters.
    """

    def __init__(self, metadata: str, *, max_stream_bytes: int = MAX_STREAM):
        if not isinstance(metadata, str) or not 0 < len(metadata.encode('utf-8')) <= MAX_METADATA:
            raise ValueError('Missing or oversized exact CTF metadata')
        if not isinstance(max_stream_bytes, int) or not 1 <= max_stream_bytes <= MAX_STREAM:
            raise ValueError('Invalid bounded stream budget')
        self._layouts = _layout(metadata)
        for _, fields in self._layouts.values():
            if _record_size(fields) > MAX_PENDING:
                raise ValueError('Encoded CTF event exceeds pending-buffer limit')
        self._maximum = max_stream_bytes
        self._pending = bytearray()
        self._received = 0
        self._consumed = 0
        self._events = 0
        self._last = None
        self._wrap = 0
        self._poisoned = False
        self._sealed = False

    def feed(self, chunk: bytes) -> list[dict]:
        if self._sealed or self._poisoned:
            raise ValueError('CTF epoch sealed or invalid; a new verified epoch is required')
        if not isinstance(chunk, (bytes, bytearray)) or len(chunk) > MAX_CHUNK:
            self._poisoned = True
            raise ValueError('Invalid/oversized CTF transport chunk')
        if len(chunk) > self._maximum - self._received:
            self._poisoned = True
            raise ValueError('CTF raw input budget exceeded; do not hide host overflow')
        self._received += len(chunk)
        self._pending.extend(chunk)
        if len(self._pending) > MAX_PENDING:
            self._poisoned = True
            raise ValueError('Incomplete CTF pending buffer exceeds fixed limit')
        complete = []
        try:
            while len(self._pending) >= 6:
                ident = struct.unpack_from('<H', self._pending, 4)[0]
                definition = self._layouts.get(ident)
                if definition is None:
                    raise ValueError(f'Unknown Zephyr CTF event ID 0x{ident:x} at byte {self._consumed}')
                needed = _record_size(definition[1])
                if len(self._pending) < needed:
                    break
                if self._events >= MAX_EVENTS:
                    raise ValueError('CTF event count exceeded')
                ts, name, values, end = _decode_one(self._pending, 0, self._layouts)
                if end != needed:
                    raise ValueError('CTF record decoder checkpoint mismatch')
                if self._last is not None and ts < self._last:
                    if self._last < 0xF0000000 or ts > 0x0FFFFFFF:
                        raise ValueError('Nonmonotonic CTF timestamp without proven 32-bit wrap')
                    self._wrap += 1 << 32
                self._last = ts
                complete.append(_event_record(self._wrap + ts, name, values, self._consumed))
                del self._pending[:needed]
                self._consumed += needed
                self._events += 1
            return complete
        except (ValueError, struct.error) as invalid:
            self._poisoned = True
            raise ValueError('Unframed CTF invalid; no safe resynchronization: ' + str(invalid)) from invalid

    def seal(self) -> dict:
        """Only a byte-complete format prefix; NOT a verified offline capture."""
        self._sealed = True
        if self._poisoned:
            raise ValueError('Poisoned CTF epoch cannot be sealed as complete')
        if self._pending:
            raise ValueError(f'Truncated CTF tail: {len(self._pending)} uncommitted bytes')
        return self.stats()

    def stats(self) -> dict:
        return {
            'received_bytes': self._received,
            'committed_bytes': self._consumed,
            'pending_bytes': len(self._pending),
            'events': self._events,
            'poisoned': self._poisoned,
            'sealed': self._sealed,
            'source_drops': None,  # unknown without verified firmware counters
            'transport_gaps': None,  # unknown without framed sequence evidence
            'firmware_identity_verified': False,
            'live_stream_approved': False,
        }
