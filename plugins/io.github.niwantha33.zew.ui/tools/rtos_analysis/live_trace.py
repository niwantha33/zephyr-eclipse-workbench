# SPDX-License-Identifier: Apache-2.0
"""Hardware-free, loopback-only test collector for framed packed Zephyr 4.4 CTF.

Not a production RTT receiver. No OpenOCD, GDB, COM device, workbench socket
or probe is opened. A matching header is NOT a flashed firmware attestation.
The existing saved CTF capture/analysis path is not modified.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import socket
import tempfile
import time

from ctf_framing import FramedCtfGate, MAX_RAW
from ctf_incremental import IncrementalPackedCtfDecoder
from zephyr_ctf_binary import _read_bounded, MAX_METADATA

MAX_WIRE_BYTES = 12 * 1024 * 1024
MAX_JSONL_BYTES = 8 * 1024 * 1024
MAX_INDEX_EVENTS = 16000


def capture_loopback_test(port: int, metadata_file: Path, elf_sha: str,
                          epoch: bytes, output: Path, *,
                          test_only: bool = False,
                          seconds: float = 5.0, max_raw_bytes: int = MAX_RAW) -> Path:
    """Capture a single synthetic replay; always retain an honest receipt.

    A production session must use independently attested target/build identity,
    prove firmware loss counts and negotiate transport ownership. Until then
    there is deliberately no generic public host or target connection.
    """
    if test_only is not True:
        raise PermissionError("Hardware/live capture disabled; explicit test_only required")
    if type(port) is not int or not 1024 <= port <= 65535:
        raise ValueError("Test replay port must be 1024..65535")
    if not isinstance(seconds, (int, float)) or not 0.1 <= seconds <= 30:
        raise ValueError("Test capture duration outside 0.1..30 seconds")
    if type(max_raw_bytes) is not int or not 1 <= max_raw_bytes <= MAX_RAW:
        raise ValueError("Invalid raw capture limit")
    metadata_raw = _read_bounded(Path(metadata_file), MAX_METADATA)
    metadata = metadata_raw.decode("utf-8", "strict")
    metadata_hash = hashlib.sha256(metadata_raw).hexdigest()
    gate = FramedCtfGate(elf_sha, metadata_hash, epoch,
                         max_raw_bytes=max_raw_bytes)
    decoder = IncrementalPackedCtfDecoder(metadata, max_stream_bytes=max_raw_bytes)
    parent = Path(output)
    if parent.is_symlink():
        raise ValueError("Test output cannot be a symlink")
    parent.mkdir(parents=True, exist_ok=True)
    folder = Path(tempfile.mkdtemp(prefix="ctf-loopback-test-", dir=parent))
    raw_wire_bytes = 0
    emitted = 0
    json_bytes = 0
    completed = False
    reason = "no transport data"
    started = time.monotonic()
    try:
        # These files are deliberately NOT a ctf/channel0_0 folder: a synthetic
        # host replay is not a hardware-qualified offline capture.
        with (folder / "transport.bin").open("xb") as wire, \
             (folder / "committed_ctf.bin").open("xb") as ctf, \
             (folder / "events.jsonl").open("x", encoding="utf-8") as jsonl:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as client:
                client.settimeout(min(2.0, seconds))
                client.connect(("127.0.0.1", port))
                client.settimeout(0.25)
                deadline = time.monotonic() + seconds
                while time.monotonic() < deadline:
                    try:
                        chunk = client.recv(4096)
                    except socket.timeout:
                        continue
                    if not chunk:
                        break
                    if len(chunk) > MAX_WIRE_BYTES - raw_wire_bytes:
                        raise ValueError("Test transport file budget exhausted")
                    wire.write(chunk)
                    raw_wire_bytes += len(chunk)
                    for payload in gate.feed(chunk):
                        ctf.write(payload)
                        for event in decoder.feed(payload):
                            if emitted >= MAX_INDEX_EVENTS:
                                raise ValueError("JSONL event-index bound reached")
                            line = json.dumps(event, sort_keys=True, separators=(",", ":"),
                                              ensure_ascii=True) + "\n"
                            encoded = line.encode("utf-8")
                            if len(encoded) > MAX_JSONL_BYTES - json_bytes:
                                raise ValueError("JSONL byte bound reached")
                            jsonl.write(line)
                            emitted += 1
                            json_bytes += len(encoded)
                else:
                    raise TimeoutError("Test capture duration elapsed before a sealed END")
        gate.finish()
        decoder.seal()
        completed = True
        reason = "all synthetic frames and CTF records complete"
    except (OSError, ValueError, TimeoutError) as problem:
        # The already received wire and complete prefix are preserved. A
        # malformed interval is NEVER promoted to a validated offline trace.
        reason = (type(problem).__name__ + ": " + str(problem))[:300]
    finally:
        elapsed = max(0.001, time.monotonic() - started)
        receipt = {
            "schema": "zew-ctf-loopback-test-1",
            "status": "TEST_ONLY_COMPLETE" if completed else "INCOMPLETE",
            "reason": reason,
            "protocol": gate.stats(),
            "decoder": decoder.stats(),
            "wire_bytes": raw_wire_bytes,
            "events_written": emitted,
            "events_jsonl_bytes": json_bytes,
            "host_events_per_sec": round(emitted / elapsed, 2),
            "target_state": "UNKNOWN",
            "loss_evidence": "UNKNOWN: no independently trusted firmware counters",
            "hardware_capture": False,
            "flashed_firmware_identity_verified": False,
            "safe_for_offline_analysis": False,
            "live_stream_approved": False,
        }
        (folder / "receipt.json").write_text(
            json.dumps(receipt, indent=2, sort_keys=True) + "\n",
            encoding="utf-8")
    return folder
