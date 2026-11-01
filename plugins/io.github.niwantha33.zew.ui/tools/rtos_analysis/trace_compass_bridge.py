#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Safe, optional handoff of a verified saved Zephyr CTF folder to Trace Compass.

The standalone Trace Compass RCP supports: tracecompass --cli --open <trace-path>.
Opening is a request to the viewer, NOT a claim it successfully decoded the trace.
No install, download, shell, probe access, or application-file mutation occurs.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

MAX_RECEIPT_BYTES = 256 * 1024
MAX_STREAM_BYTES = 64 * 1024 * 1024
MAX_METADATA_BYTES = 2 * 1024 * 1024


class HandoffError(ValueError):
    pass


def _read_artifact(path: Path, maximum: int) -> bytes:
    if path.is_symlink() or not path.is_file():
        raise HandoffError("Required capture artifact is missing or symlinked: " + path.name)
    if path.stat().st_size > maximum:
        raise HandoffError("Capture artifact exceeds the supported size: " + path.name)
    with path.open("rb") as handle:
        data = handle.read(maximum + 1)
    if len(data) > maximum:
        raise HandoffError("Capture artifact is too large: " + path.name)
    return data


def verify_capture(folder: Path) -> dict:
    """Validate saved capture identity before handing untrusted bytes to a viewer."""
    folder = Path(folder)
    if folder.is_symlink() or not folder.is_dir():
        raise HandoffError("Select a real saved trace-* capture directory")
    ctf = folder / "ctf"
    if ctf.is_symlink() or not ctf.is_dir():
        raise HandoffError("CTF directory is missing or symlinked")
    receipt = json.loads(_read_artifact(folder / "capture.json", MAX_RECEIPT_BYTES))
    if not isinstance(receipt, dict):
        raise HandoffError("Capture receipt is not a JSON object")
    stream = _read_artifact(ctf / "channel0_0", MAX_STREAM_BYTES)
    metadata = _read_artifact(ctf / "metadata", MAX_METADATA_BYTES)
    if not stream or not metadata:
        raise HandoffError("CTF stream and metadata must both be non-empty")
    if receipt.get("trace_bytes") != len(stream):
        raise HandoffError("Saved trace length does not match capture receipt")
    if receipt.get("trace_sha256") != hashlib.sha256(stream).hexdigest():
        raise HandoffError("Saved CTF stream SHA-256 differs from capture receipt")
    if receipt.get("metadata_sha256") != hashlib.sha256(metadata).hexdigest():
        raise HandoffError("Saved CTF metadata SHA-256 differs from capture receipt")
    if receipt.get("transport_read_complete") is not True:
        raise HandoffError("Capture memory read completeness was not established")
    analysis = receipt.get("ctf_analysis", {})
    if isinstance(analysis, dict) and analysis.get("ctf_decode") == "invalid":
        raise HandoffError("CTF decoder reported invalid data; inspect diagnostics.json")
    return {
        "ctf": str(ctf.resolve()),
        "decode": analysis.get("ctf_decode", "not checked") if isinstance(analysis, dict) else "not checked",
        "capture_full": receipt.get("capture_full", "unknown"),
        "metadata_provenance": receipt.get("metadata_provenance", "legacy capture: checkout unverified"),
    }


def find_launcher(explicit=None, *, environ=None, which=shutil.which) -> Path | None:
    """Use only an explicitly configured executable or an executable on PATH."""
    env = os.environ if environ is None else environ
    candidate = explicit or env.get("ZEW_TRACE_COMPASS")
    if candidate:
        path = Path(candidate).expanduser()
        if not path.is_file():
            raise HandoffError("Configured Trace Compass executable does not exist")
        if os.name != "nt" and not os.access(path, os.X_OK):
            raise HandoffError("Configured Trace Compass file is not executable")
        return path.resolve()
    for name in ("tracecompass", "tracecompass.exe"):
        resolved = which(name)
        if resolved and Path(resolved).is_file():
            return Path(resolved).resolve()
    return None


def handoff(capture: Path, *, launcher=None, open_viewer=False, environ=None, popen=subprocess.Popen):
    info = verify_capture(capture)
    program = find_launcher(launcher, environ=environ)
    if program is None:
        return {
            **info,
            "status": "viewer-unavailable",
            "hint": "Install standalone Trace Compass, add tracecompass to PATH, "
                    "or set ZEW_TRACE_COMPASS to the executable. Alternatively import ctf/ "
                    "as Generic CTF and use XML Analyses to import Zephyr_RTOS_Timeline.xml.",
        }
    if not open_viewer:
        return {**info, "status": "ready", "launcher": str(program)}
    try:
        # Never use shell=True. The CTF directory is kept as a single argument.
        popen([str(program), "--cli", "--open", info["ctf"]],
              cwd=str(Path(capture).resolve()), stdin=subprocess.DEVNULL,
              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except OSError as error:
        raise HandoffError("Trace Compass could not be started: " + str(error)) from error
    return {
        **info, "status": "launch-requested", "launcher": str(program),
        "hint": "Trace Compass was started with --cli --open. Verify its CTF import. "
                "For the Zephyr timeline, import Zephyr_RTOS_Timeline.xml once "
                "in Tracing > XML Analyses. Standalone launch does not auto-install XML analyses.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture", type=Path, required=True)
    parser.add_argument("--launcher", type=Path, help="Explicit standalone Trace Compass executable")
    parser.add_argument("--open", action="store_true", help="Request opening the trace in standalone Trace Compass")
    args = parser.parse_args()
    try:
        result = handoff(args.capture, launcher=args.launcher, open_viewer=args.open)
        print(json.dumps(result, indent=2, sort_keys=True))
        return 2 if result["status"] == "viewer-unavailable" else 0
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(json.dumps({"status": "blocked", "reason": str(error)}, sort_keys=True))
        return 3


if __name__ == "__main__":
    raise SystemExit(main())
