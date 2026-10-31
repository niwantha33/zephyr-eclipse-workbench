#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read-only, offline guided diagnostics for one saved Zephyr RAM CTF capture.

Evidence is provenance-checked. Neither a matching ELF checksum nor CTF file
integrity proves that a specific firmware image was flashed onto the MCU.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path

MAX_RECEIPT = 256 * 1024
MAX_TRACE = 64 * 1024 * 1024
MAX_METADATA = 2 * 1024 * 1024
MAX_FAULT = 4 * 1024 * 1024


def _file(path, cap):
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise ValueError("Missing or symlinked investigation artifact: " + path.name)
    if path.stat().st_size > cap:
        raise ValueError("Investigation artifact exceeds safe size limit: " + path.name)
    with path.open("rb") as src:
        raw = src.read(cap + 1)
    if len(raw) > cap:
        raise ValueError("Investigation artifact exceeds bounded read limit: " + path.name)
    return raw


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def review_receipt(receipt, *, trace=None, metadata=None, build_elf=None,
                   build_config=None, fault_log=None):
    """Produce sorted evidence-backed findings without mutating any inputs."""
    findings = []
    def finding(code, severity, evidence, action):
        findings.append(dict(code=code, severity=severity, evidence=evidence, action=action))

    if not isinstance(receipt, dict):
        raise ValueError("Capture receipt must be a JSON object")
    if trace is None or metadata is None:
        finding("CAPTURE_BYTES_UNVERIFIED", "blocking", "Raw CTF bytes unavailable",
                "Open the saved capture directory containing ctf/channel0_0 and ctf/metadata.")
    else:
        for name, contents, field in (
                ("CTF stream", trace, "trace_sha256"),
                ("CTF metadata", metadata, "metadata_sha256")):
            if not isinstance(receipt.get(field), str) or receipt[field] != _sha(contents):
                finding("ARTIFACT_HASH_MISMATCH", "blocking", name + " differs from saved receipt",
                        "Preserve the original files and recapture; do not trust this analysis.")
        if receipt.get("trace_bytes") != len(trace):
            finding("TRACE_LENGTH_MISMATCH", "blocking", "Stream length differs from capture receipt",
                    "Recapture the full memory range and inspect read/transfer errors.")
    cap = receipt.get("buffer_capacity")
    if isinstance(cap, int) and cap > 0 and isinstance(receipt.get("trace_bytes"), int):
        if receipt["trace_bytes"] > cap:
            finding("TRACE_EXCEEDS_CAPACITY", "blocking", "Captured bytes exceed the declared RAM buffer",
                    "Check the capture backend layout and selected ELF before decoding.")
    if receipt.get("capture_full") is True:
        finding("TRACE_BUFFER_FULL", "warning", "Backend reported RAM tracing buffer full",
                "Increase CONFIG_RAM_TRACING_BUFFER_SIZE or shorten capture; lost event count is unknown.")
    elif receipt.get("capture_full") == "unknown":
        finding("BUFFER_FULL_UNKNOWN", "warning", "Backend fullness symbol could not be read",
                "Treat loss as unknown and inspect the backend used by this Zephyr version.")
    if receipt.get("transport_read_complete") is not True:
        finding("TRANSPORT_INCOMPLETE", "blocking", "Complete memory read not established",
                "Inspect the halted GDB memory ranges, then retry the capture.")

    analysis = receipt.get("ctf_analysis", {})
    if not isinstance(analysis, dict):
        analysis = {}
    state = analysis.get("ctf_decode", "not checked")
    if state != "validated":
        finding("CTF_NOT_VALIDATED", "blocking" if state == "invalid" else "warning",
                "CTF decoder: " + str(state),
                "Install Babeltrace 2 bt2 for this interpreter or open CTF in Trace Compass.")
    loss = analysis.get("discarded_events_reported", 0)
    dropped = analysis.get("discarded_packets_reported", 0)
    if (isinstance(loss, int) and loss > 0) or (isinstance(dropped, int) and dropped > 0) \
            or analysis.get("discard_count_unknown") is True:
        finding("CTF_REPORTED_LOSS", "blocking", "CTF decoder reported dropped packets/events",
                "CPU/timing metrics must not be used; obtain a complete capture.")
    cpu = analysis.get("cpu", {})
    if not isinstance(cpu, dict):
        cpu = {}
    cpu_status = cpu.get("status", "unavailable")
    if cpu_status != "measured":
        finding("CPU_METRICS_PARTIAL", "warning",
                "Exclusive CPU accounting: " + str(cpu_status) + " (" + str(cpu.get("reason", "not enough evidence")) + ")",
                "Supply the observed idle thread ID and loss-free single-stream CTF switch/ISR events.")
    elif cpu.get("cpu_busy_percent") is None:
        finding("CPU_BUSY_UNKNOWN", "warning", "CPU busy percentage is not established",
                "Identify the idle thread explicitly before claiming utilisation.")
    if receipt.get("firmware_matches_elf") != "verified":
        finding("FLASH_IDENTITY_UNKNOWN", "warning", "Capture does not authenticate flashed firmware",
                "Use a separately verified flashed-image readback; ELF path/hash alone is not enough.")

    selected_build = "not supplied"
    if build_elf is not None:
        selected_build = "hash-matched" if _sha(build_elf) == receipt.get("elf_sha256") else "hash-mismatch"
        if selected_build == "hash-mismatch":
            finding("SELECTED_ELF_MISMATCH", "blocking", "Selected build ELF hash differs from the capture receipt",
                    "Select the exact unstripped ELF used to make the capture.")
    if build_config is not None and receipt.get("build_config_sha256"):
        if _sha(build_config) != receipt["build_config_sha256"]:
            finding("CONFIG_SNAPSHOT_MISMATCH", "blocking", "Selected generated .config hash differs",
                    "Select the original build configuration; rebuild changes invalidate comparisons.")
    fault = None
    if fault_log is not None:
        text = fault_log.decode("utf-8", "strict")
        markers = text.count("ZEPHYR FATAL ERROR")
        fault = {"sha256": _sha(fault_log), "fatal_markers": markers,
                 "core_dump_marker": "#CD:BEGIN#" in text,
                 "matched_to_trace": "not verified"}
        if markers != 1:
            finding("FAULT_TEXT_AMBIGUOUS", "warning", str(markers) + " fatal summary markers",
                    "Use a single failure excerpt in Zephyr Fault Investigation.")
        finding("FAULT_CORRELATION_UNVERIFIED", "information",
                "Fault log and trace have no authenticated common boot/session clock",
                "Investigate PC/LR/SP and status registers in Zephyr Fault Investigation; confirm matching ELF.")
        if fault["core_dump_marker"]:
            finding("COREDUMP_REQUIRES_DECODER", "information", "Zephyr core-dump marker present",
                    "Use matching Zephyr scripts/coredump and GDB; text analysis cannot unwind core dumps.")

    blocking = [x["code"] for x in findings if x["severity"] == "blocking"]
    return {
        "schema_version": "zew-investigation-1",
        "status": "blocked" if blocking else "review-with-limits",
        "capture": {"trace_bytes": receipt.get("trace_bytes"), "buffer_capacity": cap,
                    "ctf_decode": state, "cpu_status": cpu_status,
                    "cpu_busy_percent": cpu.get("cpu_busy_percent"),
                    "thread_exclusive_ns": cpu.get("thread_exclusive_ns"),
                    "isr_ns": cpu.get("isr_ns"), "unknown_ns": cpu.get("unknown_ns"),
                    "selected_build_elf": selected_build,
                    "flashed_firmware": "not independently authenticated"},
        "fault": fault,
        "findings": findings,
        "limits": [
            "Capture identity, target timestamp accuracy and unreported loss are not independently established.",
            "Observed maxima are not proven WCET, deadlines, or CPU capacity on all cores.",
            "No MCU, probe, debug state, application source or build file was changed.",
        ],
    }


def review(path, *, build=None, fault_log=None):
    folder = Path(path)
    if folder.is_symlink() or not folder.is_dir():
        raise ValueError("Select a real capture directory, not a symlink")
    receipt = json.loads(_file(folder / "capture.json", MAX_RECEIPT).decode("utf-8"))
    trace = _file(folder / "ctf" / "channel0_0", MAX_TRACE)
    metadata = _file(folder / "ctf" / "metadata", MAX_METADATA)
    elf = config = None
    if build is not None:
        build = Path(build)
        elf = _file(build / "zephyr" / "zephyr.elf", MAX_TRACE)
        config = _file(build / "zephyr" / ".config", MAX_METADATA)
    fatal = _file(fault_log, MAX_FAULT) if fault_log is not None else None
    return review_receipt(receipt, trace=trace, metadata=metadata,
                          build_elf=elf, build_config=config, fault_log=fatal)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture", type=Path, required=True)
    parser.add_argument("--build", type=Path)
    parser.add_argument("--fault-log", type=Path)
    args = parser.parse_args()
    try:
        result = review(args.capture, build=args.build, fault_log=args.fault_log)
    except (OSError, ValueError, UnicodeError, json.JSONDecodeError) as error:
        print(json.dumps({"status": "blocked", "reason": str(error)}))
        return 2
    print(json.dumps(result, indent=2, sort_keys=True))
    return 1 if result["status"] == "blocked" else 0


if __name__ == "__main__":
    raise SystemExit(main())
