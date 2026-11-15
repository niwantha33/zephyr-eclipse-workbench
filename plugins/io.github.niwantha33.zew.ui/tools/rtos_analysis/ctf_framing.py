# SPDX-License-Identifier: Apache-2.0
"""Test-only, strict CTF transport-frame verifier for a future Zephyr backend.

Not a firmware implementation and not a hardware authenticity mechanism.
The pinned Zephyr 4.4.0 checkout does NOT ship a CTF RTT backend.
CRC32 detects accidental frame corruption; it does not authenticate a probe.
Only single-core, single-stream, explicit-epoch byte sequences are accepted.
No scanning past damaged bytes or implicit resynchronisation.
"""
from __future__ import annotations

import hashlib
import re
import struct
import zlib

MAGIC = b"ZEWF"
VERSION = 1
HELLO = 1
DATA = 2
END = 3
MAX_PAYLOAD = 4096
MAX_CHUNK = 16384
MAX_FRAMES = 300000
MAX_RAW = 8 * 1024 * 1024
# magic, version, kind, reserved, length, sequence, epoch,
# ELF SHA256, metadata SHA256, core, stream, cumulative source losses, CRC32.
HEADER = struct.Struct("<4sBBHIQ16s32s32sHHQI")
MAX_PENDING = HEADER.size + MAX_PAYLOAD + MAX_CHUNK


class FrameError(ValueError):
    """An epoch is invalid. No later frame can silently recover it."""


def digest_bytes(value: str, label: str) -> bytes:
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ValueError("Expected exact lowercase " + label + " SHA256 digest")
    return bytes.fromhex(value)


def encode_fixture_frame(kind: int, sequence: int, epoch: bytes, elf_sha: str,
                         metadata_sha: str, payload: bytes = b"",
                         source_losses: int = 0, *, core: int = 0,
                         stream: int = 0) -> bytes:
    """Deterministic *test fixture* encoder; never a target-side transport."""
    if kind not in (HELLO, DATA, END) or type(sequence) is not int or sequence < 0:
        raise ValueError("Invalid fixture frame kind/sequence")
    if not isinstance(epoch, bytes) or len(epoch) != 16 or epoch == bytes(16):
        raise ValueError("Invalid fixture epoch")
    if not isinstance(payload, bytes) or len(payload) > MAX_PAYLOAD:
        raise ValueError("Invalid fixture payload")
    if not 0 <= source_losses < (1 << 64):
        raise ValueError("Invalid source-loss count")
    header = HEADER.pack(MAGIC, VERSION, kind, 0, len(payload), sequence,
                         epoch, digest_bytes(elf_sha, "ELF"),
                         digest_bytes(metadata_sha, "metadata"),
                         core, stream, source_losses, 0)
    crc = zlib.crc32(header[:-4] + payload) & 0xFFFFFFFF
    return header[:-4] + struct.pack("<I", crc) + payload


class FramedCtfGate:
    """Verify a complete transport before handing each payload to CTF decode.

    The expected ELF digest and epoch come from the caller. Matching header
    fields does NOT prove the actual flashed image or boot epoch; production
    capture must independently attest these and prove a trusted CTF sender.
    """

    def __init__(self, elf_sha: str, metadata_sha: str, expected_epoch: bytes,
                 *, max_raw_bytes: int = MAX_RAW):
        self._elf = digest_bytes(elf_sha, "ELF")
        self._meta = digest_bytes(metadata_sha, "metadata")
        if (not isinstance(expected_epoch, bytes) or len(expected_epoch) != 16
                or expected_epoch == bytes(16)):
            raise ValueError("Expected a nonzero 16-byte boot/session epoch")
        if type(max_raw_bytes) is not int or not 1 <= max_raw_bytes <= MAX_RAW:
            raise ValueError("Invalid raw-byte budget")
        self._epoch = expected_epoch
        self._budget = max_raw_bytes
        self._pending = bytearray()
        self._sequence = 0
        self._raw_bytes = 0
        self._frame_count = 0
        self._started = False
        self._ended = False
        self._invalid = False
        self._source_losses = None

    def _reject(self, reason: str):
        self._invalid = True
        raise FrameError(reason)

    def feed(self, chunk: bytes) -> list[bytes]:
        if self._invalid or self._ended:
            raise FrameError("Epoch is invalid or ended; start a new verified epoch")
        if not isinstance(chunk, (bytes, bytearray)) or len(chunk) > MAX_CHUNK:
            self._reject("Transport chunk exceeds fixed 16 KiB bound")
        self._pending.extend(chunk)
        if len(self._pending) > MAX_PENDING:
            self._reject("Unconsumed transport input exceeds fixed buffer")
        payloads = []
        while len(self._pending) >= HEADER.size:
            fields = HEADER.unpack_from(self._pending)
            (magic, version, kind, reserved, length, sequence, epoch,
             elf, metadata, core, stream, losses, crc) = fields
            if magic != MAGIC or version != VERSION or reserved != 0:
                self._reject("Invalid framing marker, protocol version or reserved flags")
            if length > MAX_PAYLOAD:
                self._reject("Declared frame length exceeds 4096 bytes")
            if len(self._pending) < HEADER.size + length:
                break  # Never decode a partial frame.
            payload = bytes(self._pending[HEADER.size:HEADER.size + length])
            computed = zlib.crc32(self._pending[:HEADER.size - 4] + payload) & 0xFFFFFFFF
            if computed != crc:
                self._reject("CTF transport CRC mismatch; no safe resynchronisation")
            if epoch != self._epoch or elf != self._elf or metadata != self._meta:
                self._reject("Frame boot epoch, ELF or metadata digest mismatch")
            if core != 0 or stream != 0:
                self._reject("Multi-core/stream clock mapping is not yet supported")
            if sequence != self._sequence:
                self._reject("Transport sequence gap, duplicate or reordering")
            if losses != 0:
                self._source_losses = losses
                self._reject("Firmware reported CTF source loss; interval unqualified")
            if self._frame_count >= MAX_FRAMES:
                self._reject("Frame-count budget exceeded")
            if not self._started:
                if kind != HELLO or sequence != 0 or length != 0:
                    self._reject("First frame must be empty HELLO sequence zero")
                self._started = True
            elif kind == DATA:
                if length == 0:
                    self._reject("Empty DATA frame is forbidden")
                if length > self._budget - self._raw_bytes:
                    self._reject("Raw CTF byte budget exhausted")
                self._raw_bytes += length
                payloads.append(payload)
            elif kind == END:
                if length != 0 or self._raw_bytes == 0:
                    self._reject("END must be empty and follow real data")
                self._ended = True
            else:
                self._reject("Unexpected HELLO or unknown frame kind")
            self._frame_count += 1
            self._sequence += 1
            del self._pending[:HEADER.size + length]
            if self._ended and self._pending:
                self._reject("Trailing bytes after END are not a new epoch")
        return payloads

    def finish(self) -> dict:
        if self._invalid or not self._started or not self._ended or self._pending:
            self._reject("Missing END, corrupt epoch or truncated transport tail")
        return self.stats()

    def stats(self) -> dict:
        return {
            "protocol": "zew-framed-ctf-test-v1",
            "frames": self._frame_count,
            "accepted_raw_bytes": self._raw_bytes,
            "remaining_transport_bytes": len(self._pending),
            "framing_complete": bool(self._ended and not self._pending and not self._invalid),
            "source_losses_reported": self._source_losses,
            "epoch_invalid": self._invalid,
            "flashed_image_attested": False,
            "live_stream_approved": False,
        }
