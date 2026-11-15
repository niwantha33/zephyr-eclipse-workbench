#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Local CI-only fake RTT TCP endpoint. No probe and no real hardware support.

Replay a deterministic test fixture via a bound loopback socket. Never use
this helper to qualify a hardware trace or open a public network listener.
"""
from __future__ import annotations

import socket
import threading

# A test imports the same wire encoder as the host framing verifier.
from ctf_framing import DATA, END, HELLO, MAX_PAYLOAD, encode_fixture_frame


def fixture_wire(payload: bytes, elf_sha: str, metadata_sha: str, epoch: bytes,
                 *, piece_size: int = 17, source_losses: int = 0) -> bytes:
    if not isinstance(payload, bytes) or not payload:
        raise ValueError("Nonempty deterministic fake CTF input required")
    if type(piece_size) is not int or not 1 <= piece_size <= MAX_PAYLOAD:
        raise ValueError("Invalid fake payload slice bound")
    pieces = [
        encode_fixture_frame(HELLO, 0, epoch, elf_sha, metadata_sha)
    ]
    sequence = 1
    for i in range(0, len(payload), piece_size):
        pieces.append(encode_fixture_frame(DATA, sequence, epoch, elf_sha,
                          metadata_sha, payload[i:i + piece_size],
                          source_losses=source_losses))
        sequence += 1
    pieces.append(encode_fixture_frame(END, sequence, epoch, elf_sha, metadata_sha,
                                       source_losses=source_losses))
    return b"".join(pieces)


class FakeRttServer:
    """One-shot loopback server with bounded sends; explicitly test only."""
    def __init__(self, wire: bytes, *, chunk_bytes: int = 3):
        if not isinstance(wire, bytes) or not 0 < len(wire) <= 12 * 1024 * 1024:
            raise ValueError("Fake wire data exceeds test bound")
        if type(chunk_bytes) is not int or not 1 <= chunk_bytes <= 4096:
            raise ValueError("Invalid fake TCP chunk bound")
        self.wire = wire
        self.chunk_bytes = chunk_bytes
        self.error = None
        self._socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._socket.settimeout(5)
        self._socket.bind(("127.0.0.1", 0))
        self._socket.listen(1)
        self.port = self._socket.getsockname()[1]
        self._thread = threading.Thread(target=self._serve, name="fake-rtt-ctf-test", daemon=True)

    def start(self):
        self._thread.start()
        return self

    def _serve(self):
        try:
            with self._socket:
                client, _ = self._socket.accept()
                with client:
                    client.settimeout(5)
                    for off in range(0, len(self.wire), self.chunk_bytes):
                        client.sendall(self.wire[off:off + self.chunk_bytes])
        except OSError as failure:
            # An intentionally corrupt trace may cause the strict consumer
            # to disconnect early. Tests inspect the consumer receipt.
            self.error = str(failure)[:160]

    def join(self):
        self._thread.join(timeout=6)
        if self._thread.is_alive():
            raise TimeoutError("Fake RTT server did not finish within 6 seconds")
