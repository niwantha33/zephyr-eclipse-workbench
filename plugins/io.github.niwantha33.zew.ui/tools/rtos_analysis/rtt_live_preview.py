#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Experimental ZEWF-v1 binary receiver for an EXISTING localhost RTT server.

This is a real byte-stream ingestion path, not fake RTT, but it is NOT a
production-qualified live trace feature. It does not configure a probe,
verify a flashed image, reserve OpenOCD ownership, or infer MCU clock time.
Only an explicit caller-approved, already running, dedicated *binary*
channel may be read. Never connect the ordinary RTT text console here.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import socket
import tempfile
import time

from ctf_framing import FramedCtfGate, HEADER, MAX_RAW
from ctf_incremental import IncrementalPackedCtfDecoder
from zephyr_ctf_binary import _read_bounded, MAX_METADATA

MAX_CONFIG = 4 * 1024 * 1024
MAX_WIRE = 12 * 1024 * 1024
MAX_JSONL = 8 * 1024 * 1024
MAX_EVENTS = 16000


def _config_value(lines: list[str], key: str) -> str | None:
    prefix = "CONFIG_" + key + "="
    matches = [line[len(prefix):] for line in lines if line.startswith(prefix)]
    if len(matches) > 1:
        raise ValueError("Duplicate generated CONFIG_" + key)
    return matches[0] if matches else None


def inspect_rtt_preflight(config_file: Path, metadata_file: Path) -> dict:
    """Check a GENERATED build configuration, never requested prj.conf."""
    config_file, metadata_file = Path(config_file), Path(metadata_file)
    for file, limit in ((config_file, MAX_CONFIG), (metadata_file, MAX_METADATA)):
        if file.is_symlink() or not file.is_file() or not 0 < file.stat().st_size <= limit:
            raise ValueError("Missing, symlinked, empty or oversized: " + file.name)
    lines = config_file.read_text(encoding="utf-8", errors="strict").splitlines()
    required = ("TRACING", "TRACING_CTF", "TRACING_ASYNC",
                "TRACING_BACKEND_ZEW_RTT", "USE_SEGGER_RTT")
    for key in required:
        if _config_value(lines, key) != "y":
            raise ValueError("Generated CONFIG_" + key + "=y is required")
    if _config_value(lines, "SMP") == "y":
        raise ValueError("Multi-core is not qualified for ZEWF v1")
    count = _config_value(lines, "SEGGER_RTT_MAX_NUM_UP_BUFFERS")
    if count is None or not re.fullmatch(r"[0-9]{1,3}", count) or int(count) < 2:
        raise ValueError("Generated RTT needs at least two up channels; binary channel 1 only")
    contract = _config_value(lines, "ZEW_TRACE_IMAGE_CONTRACT_SHA256")
    metadata_sha = _config_value(lines, "ZEW_TRACE_METADATA_SHA256")
    for name, value in (("image contract", contract), ("metadata", metadata_sha)):
        if value is None or not re.fullmatch(r'"[0-9a-f]{64}"', value):
            raise ValueError("Exact generated " + name + " SHA256 is missing")
    # The firmware sends a PREBUILD contract, NOT the finished ELF digest.
    # Matching this field is necessary for format consistency, not attestation.
    metadata_raw = _read_bounded(metadata_file, MAX_METADATA)
    if hashlib.sha256(metadata_raw).hexdigest() != metadata_sha[1:-1]:
        raise ValueError("Generated metadata digest differs from exact TSDL")
    return {
        "image_contract_sha256": contract[1:-1],
        "metadata_sha256": metadata_sha[1:-1],
        "metadata": metadata_raw.decode("utf-8", "strict"),
        "channel": 1,
        "flashed_image_attested": False,
        "probe_ownership_verified": False,
    }


def capture_existing_rtt_preview(
        port: int, config_file: Path, metadata_file: Path, output: Path, *,
        explicit_opt_in: bool = False, seconds: float = 5.0,
        max_raw_bytes: int = MAX_RAW) -> Path:
    """Read one bounded ZEWF epoch from an existing localhost binary RTT server.

    This path MUST remain a preview until target identity, RTT control-block
    ownership, loss, clock and physical-vendor validation are independently
    established. Even a complete HELLO/DATA/END is not proof of the device.
    """
    if explicit_opt_in is not True:
        raise PermissionError("Experimental binary RTT capture requires explicit opt-in")
    if type(port) is not int or not 1024 <= port <= 65535:
        raise ValueError("Existing localhost RTT TCP port must be 1024..65535")
    if type(seconds) not in (float, int) or not 0.1 <= seconds <= 30:
        raise ValueError("Preview duration must be 0.1..30 seconds")
    if type(max_raw_bytes) is not int or not 1 <= max_raw_bytes <= MAX_RAW:
        raise ValueError("Invalid CTF input bound")
    evidence = inspect_rtt_preflight(config_file, metadata_file)
    destination = Path(output)
    if destination.is_symlink():
        raise ValueError("Output directory may not be a symlink")
    destination.mkdir(parents=True, exist_ok=True)
    folder = Path(tempfile.mkdtemp(prefix="zew-rtt-preview-", dir=destination))
    gate = None
    decoder = IncrementalPackedCtfDecoder(evidence["metadata"],
                                           max_stream_bytes=max_raw_bytes)
    prefix = bytearray()
    wire_bytes = events = event_bytes = 0
    complete = False
    failure = "No complete HELLO/DATA/END received"
    start = time.monotonic()
    try:
        with (folder / "transport.bin").open("xb") as wire, \
             (folder / "committed_ctf.bin").open("xb") as ctf, \
             (folder / "events.jsonl").open("x", encoding="utf-8") as event_log:
            # No OpenOCD commands, target state changes, public network binding,
            # implicit port discovery, fallback to RTT channel zero or retries.
            with socket.create_connection(("127.0.0.1", port), timeout=2.0) as sock:
                sock.settimeout(0.25)
                deadline = time.monotonic() + seconds
                while time.monotonic() < deadline:
                    try:
                        chunk = sock.recv(4096)
                    except socket.timeout:
                        # A complete END can finish without requiring a TCP close.
                        if gate is not None and gate.stats()["framing_complete"]:
                            break
                        continue
                    if not chunk:
                        break
                    if len(chunk) > MAX_WIRE - wire_bytes:
                        raise ValueError("Host transport byte budget exhausted")
                    wire.write(chunk)
                    wire_bytes += len(chunk)
                    if gate is None:
                        prefix.extend(chunk)
                        if len(prefix) < HEADER.size:
                            continue
                        fields = HEADER.unpack_from(prefix)
                        if fields[0] != b"ZEWF" or fields[1] != 1 or fields[2] != 1:
                            raise ValueError("No valid initial ZEWF HELLO header")
                        # This epoch is self-reported by the peer: not trusted,
                        # authenticated, unique, or an independently proven boot.
                        gate = FramedCtfGate(evidence["image_contract_sha256"],
                                            evidence["metadata_sha256"], fields[6],
                                            max_raw_bytes=max_raw_bytes)
                        chunk = bytes(prefix)
                        prefix.clear()
                    for payload in gate.feed(chunk):
                        ctf.write(payload)
                        for record in decoder.feed(payload):
                            if events >= MAX_EVENTS:
                                raise ValueError("Live preview event-count budget exhausted")
                            line = json.dumps(record, sort_keys=True,
                                              separators=(",", ":"), ensure_ascii=True) + "\n"
                            n = len(line.encode("utf-8"))
                            if n > MAX_JSONL - event_bytes:
                                raise ValueError("Live preview JSONL byte budget exhausted")
                            event_log.write(line)
                            events += 1
                            event_bytes += n
                if gate is None:
                    raise ValueError("Missing or truncated ZEWF HELLO")
        gate.finish()
        decoder.seal()
        complete = True
        failure = "Format verified; physical hardware and firmware still UNVERIFIED"
    except (OSError, ValueError, TimeoutError, UnicodeError) as error:
        failure = (type(error).__name__ + ": " + str(error))[:300]
    finally:
        elapsed = max(0.001, time.monotonic() - start)
        receipt = {
            "schema": "zew-external-rtt-preview-v1",
            "status": "FORMAT_COMPLETE_UNVERIFIED" if complete else "INCOMPLETE",
            "reason": failure,
            "transport": "existing-localhost-dedicated-rtt-channel-1",
            "wire_bytes": wire_bytes,
            "events_written": events,
            "events_jsonl_bytes": event_bytes,
            "host_events_per_sec": round(events / elapsed, 2),
            "protocol": gate.stats() if gate is not None else None,
            "decoder": decoder.stats(),
            "prebuild_image_contract_sha256": evidence["image_contract_sha256"],
            "metadata_sha256": evidence["metadata_sha256"],
            "self_reported_epoch": gate._epoch.hex() if gate is not None else None,
            "hardware_capture_qualified": False,
            "flashed_firmware_identity_verified": False,
            "probe_ownership_verified": False,
            "loss_evidence_independently_verified": False,
            "safe_for_offline_analysis": False,
            "live_stream_approved": False,
        }
        (folder / "receipt.json").write_text(
            json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return folder
