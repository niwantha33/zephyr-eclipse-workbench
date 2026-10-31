#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Bounded Zephyr UART application console capture; explicit port ownership.

Host timestamps mean reception time, not firmware event time. Binary tracing
requires a different UART/USB endpoint; never parse unframed CTF as text.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import tempfile
import time


def capture(port, baud, directory, seconds, *, serial_factory=None, max_bytes=8 * 1024 * 1024,
            read_clock=time.monotonic, timeout=0.2, show=None):
    if not port or len(port) > 128 or any(c in port for c in "\r\n\0"):
        raise ValueError("Explicit serial port required (for example COM8 or /dev/ttyACM0)")
    if not 1200 <= baud <= 4_000_000:
        raise ValueError("Baud rate outside supported validation range")
    if not 0.1 <= seconds <= 3600 or not 1 <= max_bytes <= 64 * 1024 * 1024:
        raise ValueError("Capture duration or maximum bytes out of bounds")
    if serial_factory is None:
        try:
            import serial
        except ImportError as error:
            raise RuntimeError("PySerial missing in managed Python; install pyserial for this interpreter") from error
        serial_factory = serial.Serial
    output = Path(directory)
    output.mkdir(parents=True, exist_ok=True)
    session_dir = Path(tempfile.mkdtemp(prefix="serial-", dir=output))
    lines = 0
    raw_length = 0
    truncated_lines = 0
    pending = bytearray()
    started = datetime.now(timezone.utc).isoformat()
    digest = hashlib.sha256()
    port_handle = None
    stop = "duration"
    index_limit = False
    max_json_bytes = 8 * 1024 * 1024
    max_lines = 32000
    def emit(blob, *, truncated=False, records=None):
        nonlocal lines, truncated_lines, index_limit
        if not blob:
            return
        lines += 1
        truncated_lines += int(truncated)
        text = blob.decode("utf-8", "replace")
        timestamp = datetime.now(timezone.utc).isoformat()
        item = {"host_received_utc": timestamp, "source": "host UART read",
                "target_timestamp": None, "thread": None, "severity": None,
                "line": text, "contains_replacement": "\ufffd" in text,
                "truncated": bool(truncated)}
        records.write(json.dumps(item, ensure_ascii=True) + "\n")
        if lines >= max_lines or records.tell() >= max_json_bytes:
            index_limit = True
        if show is not None:
            show(f"[Host receive {timestamp}] {text}")
    try:
        kwargs = {"port": port, "baudrate": baud, "timeout": timeout}
        if os.name != "nt":
            kwargs["exclusive"] = True
        port_handle = serial_factory(**kwargs)
        deadline = read_clock() + seconds
        with (session_dir / "raw.bin").open("wb") as raw, \
             (session_dir / "messages.jsonl").open("w", encoding="utf-8") as records:
            while read_clock() < deadline and raw_length < max_bytes and not index_limit:
                chunk = port_handle.read(min(512, max_bytes - raw_length))
                if not chunk:
                    continue
                if not isinstance(chunk, (bytes, bytearray)):
                    raise RuntimeError("Serial driver returned nonbinary data")
                digest.update(chunk)
                raw.write(chunk)
                raw_length += len(chunk)
                pending.extend(chunk)
                while b"\n" in pending and not index_limit:
                    before, _, after = pending.partition(b"\n")
                    pending = bytearray(after)
                    emit(before.rstrip(b"\r"), records=records)
                if len(pending) > 4096 and not index_limit:
                    emit(bytes(pending[:4096]), truncated=True, records=records)
                    del pending[:4096]
            if pending and not index_limit:
                emit(bytes(pending), truncated=True, records=records)
        if index_limit:
            stop = "message_index_limit"
        elif raw_length >= max_bytes:
            stop = "byte_limit"
        receipt = {
            "schema": "zew-uart-1", "port": port, "baud": baud,
            "started_host_utc": started, "ended_host_utc": datetime.now(timezone.utc).isoformat(),
            "bytes": raw_length, "sha256": digest.hexdigest(), "lines": lines,
            "truncated_lines": truncated_lines, "stop_reason": stop,
            "message_index_complete": not index_limit,
            "time_basis": "host receive UTC only; not target event time",
            "quality": "UTF-8 replacement and truncation flags preserved per line",
            "safety": "Only the named port was opened; no reset, flash, GDB or OpenOCD operations."
        }
        (session_dir / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n",
                                                  encoding="utf-8")
        return session_dir
    finally:
        if port_handle is not None:
            port_handle.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", required=True)
    parser.add_argument("--baud", type=int, default=115200)
    parser.add_argument("--seconds", type=float, default=30)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        folder = capture(args.port, args.baud, args.output, args.seconds, show=print)
        print("Saved read-only application console in " + str(folder)
              + "; timestamps are host receive time, not target emission time.")
        return 0
    except (RuntimeError, OSError, ValueError) as error:
        print("Serial capture failed: " + str(error), file=__import__("sys").stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
