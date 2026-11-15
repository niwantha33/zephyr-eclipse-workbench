#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Validate an opt-in halted Zephyr framed-RAM dump; NEVER claim live support.

Requires the matching generated .config, build ELF and pinned Zephyr metadata.
This checks a self-reported epoch and prebuild digest *for consistency only*.
It cannot establish that the selected ELF is flashed on physical hardware.
No OpenOCD, GDB or probe access is made.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis"))
from ctf_framing import HEADER, FramedCtfGate, MAX_PAYLOAD
from ctf_incremental import IncrementalPackedCtfDecoder

MAX_CAPTURE = 16384


def read_file(path: Path, maximum: int) -> bytes:
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= maximum:
        raise ValueError("Missing, symlinked, empty or oversized evidence file: " + path.name)
    return path.read_bytes()


def configured_hash(config: str, key: str) -> str:
    # Exact line matching after splitlines() handles both Windows CRLF and
    # Unix LF; a final carriage return must not invalidate valid Kconfig.
    pattern = re.compile(r'^CONFIG_' + re.escape(key) + r'="([0-9a-f]{64})"    if len(match) != 1:
        raise ValueError("Missing or duplicate 64-hex generated CONFIG_" + key)
    return match[0]


def inspect(wire_path: Path, build: Path, metadata_path: Path) -> dict:
    wire = read_file(wire_path, MAX_CAPTURE)
    elf = read_file(build / "zephyr/zephyr.elf", 512 * 1024 * 1024)
    if elf[:4] != b"\x7fELF":
        raise ValueError("Selected build output is not an ELF")
    config = read_file(build / "zephyr/.config", 4 * 1024 * 1024).decode("utf-8")
    for needed in ("CONFIG_TRACING_CTF=y", "CONFIG_TRACING_ASYNC=y",
                   "CONFIG_TRACING_BACKEND_ZEW_RAM=y"):
        if needed not in config.splitlines():
            raise ValueError("Generated build is not selected framed RAM backend")
    image_contract = configured_hash(config, "ZEW_TRACE_IMAGE_CONTRACT_SHA256")
    metadata_contract = configured_hash(config, "ZEW_TRACE_METADATA_SHA256")
    metadata_bytes = read_file(metadata_path, 65536)
    if hashlib.sha256(metadata_bytes).hexdigest() != metadata_contract:
        raise ValueError("Exact CTF metadata digest does not match generated config")
    if len(wire) < HEADER.size:
        raise ValueError("Truncated CTF frame header")
    fields = HEADER.unpack_from(wire)
    if fields[0] != b"ZEWF":
        raise ValueError("Not a ZEWF framed RAM dump")
    self_reported_epoch = fields[6]
    gate = FramedCtfGate(image_contract, metadata_contract, self_reported_epoch,
                         max_raw_bytes=MAX_CAPTURE)
    decoder = IncrementalPackedCtfDecoder(metadata_bytes.decode("utf-8"),
                                          max_stream_bytes=MAX_CAPTURE)
    observed = []
    for index in range(0, len(wire), 2048):
        for raw in gate.feed(wire[index:index + 2048]):
            observed.extend(decoder.feed(raw))
    frame_stats = gate.finish()
    decode_stats = decoder.seal()
    return {
        "schema": "zew-halted-framed-ctf-inspection-v1",
        "wire_format": "COMPLETE",
        "record_format": "COMPLETE",
        "frame_count": frame_stats["frames"],
        "observed_event_count": len(observed),
        "observed_event_names": list(dict.fromkeys(str(
            event.get("name", event.get("event", "UNKNOWN")))
            for event in observed))[:32],
        "wire_bytes": len(wire),
        "raw_ctf_bytes": decode_stats["received_bytes"],
        "elf_sha256_from_selected_build": hashlib.sha256(elf).hexdigest(),
        "metadata_sha256": metadata_contract,
        "prebuild_image_contract_sha256": image_contract,
        "self_reported_epoch": self_reported_epoch.hex(),
        "source_loss_receipt_verified": False,
        "target_halt_verified": False,
        "flashed_firmware_identity_verified": False,
        "hardware_capture_qualified": False,
        "live_stream_approved": False,
        "warning": "Format consistency only. Verify separate Zephyr RAM receipt, "
                   "exact flashed image, target state and physical capture.",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wire", type=Path, required=True)
    parser.add_argument("--build", type=Path, required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = inspect(args.wire, args.build, args.metadata)
    if args.output.exists() or args.output.is_symlink():
        raise ValueError("Refusing to overwrite an existing inspection report")
    with args.output.open("x", encoding="utf-8") as report:
        json.dump(result, report, indent=2)
        report.write("\n")
    print("FORMAT CHECK PASSED; physical target/build/loss verification PENDING")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, UnicodeError) as failure:
        print("RAM TRACE INSPECTION FAILED CLOSED:", str(failure)[:280],
              file=sys.stderr)
        sys.exit(2)
)
    match = [hit.group(1) for line in config.splitlines()
             if (hit := pattern.fullmatch(line)) is not None]
    if len(match) != 1:
        raise ValueError("Missing or duplicate 64-hex generated CONFIG_" + key)
    return match[0]


def inspect(wire_path: Path, build: Path, metadata_path: Path) -> dict:
    wire = read_file(wire_path, MAX_CAPTURE)
    elf = read_file(build / "zephyr/zephyr.elf", 512 * 1024 * 1024)
    if elf[:4] != b"\x7fELF":
        raise ValueError("Selected build output is not an ELF")
    config = read_file(build / "zephyr/.config", 4 * 1024 * 1024).decode("utf-8")
    for needed in ("CONFIG_TRACING_CTF=y", "CONFIG_TRACING_ASYNC=y",
                   "CONFIG_TRACING_BACKEND_ZEW_RAM=y"):
        if needed not in config.splitlines():
            raise ValueError("Generated build is not selected framed RAM backend")
    image_contract = configured_hash(config, "ZEW_TRACE_IMAGE_CONTRACT_SHA256")
    metadata_contract = configured_hash(config, "ZEW_TRACE_METADATA_SHA256")
    metadata_bytes = read_file(metadata_path, 65536)
    if hashlib.sha256(metadata_bytes).hexdigest() != metadata_contract:
        raise ValueError("Exact CTF metadata digest does not match generated config")
    if len(wire) < HEADER.size:
        raise ValueError("Truncated CTF frame header")
    fields = HEADER.unpack_from(wire)
    if fields[0] != b"ZEWF":
        raise ValueError("Not a ZEWF framed RAM dump")
    self_reported_epoch = fields[6]
    gate = FramedCtfGate(image_contract, metadata_contract, self_reported_epoch,
                         max_raw_bytes=MAX_CAPTURE)
    decoder = IncrementalPackedCtfDecoder(metadata_bytes.decode("utf-8"),
                                          max_stream_bytes=MAX_CAPTURE)
    observed = []
    for index in range(0, len(wire), 2048):
        for raw in gate.feed(wire[index:index + 2048]):
            observed.extend(decoder.feed(raw))
    frame_stats = gate.finish()
    decode_stats = decoder.seal()
    return {
        "schema": "zew-halted-framed-ctf-inspection-v1",
        "wire_format": "COMPLETE",
        "record_format": "COMPLETE",
        "frame_count": frame_stats["frames"],
        "observed_event_count": len(observed),
        "observed_event_names": list(dict.fromkeys(str(
            event.get("name", event.get("event", "UNKNOWN")))
            for event in observed))[:32],
        "wire_bytes": len(wire),
        "raw_ctf_bytes": decode_stats["received_bytes"],
        "elf_sha256_from_selected_build": hashlib.sha256(elf).hexdigest(),
        "metadata_sha256": metadata_contract,
        "prebuild_image_contract_sha256": image_contract,
        "self_reported_epoch": self_reported_epoch.hex(),
        "source_loss_receipt_verified": False,
        "target_halt_verified": False,
        "flashed_firmware_identity_verified": False,
        "hardware_capture_qualified": False,
        "live_stream_approved": False,
        "warning": "Format consistency only. Verify separate Zephyr RAM receipt, "
                   "exact flashed image, target state and physical capture.",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wire", type=Path, required=True)
    parser.add_argument("--build", type=Path, required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = inspect(args.wire, args.build, args.metadata)
    if args.output.exists() or args.output.is_symlink():
        raise ValueError("Refusing to overwrite an existing inspection report")
    with args.output.open("x", encoding="utf-8") as report:
        json.dump(result, report, indent=2)
        report.write("\n")
    print("FORMAT CHECK PASSED; physical target/build/loss verification PENDING")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, UnicodeError) as failure:
        print("RAM TRACE INSPECTION FAILED CLOSED:", str(failure)[:280],
              file=sys.stderr)
        sys.exit(2)
