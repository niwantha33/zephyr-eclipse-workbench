#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline Zephyr binary coredump investigation through selected upstream GDB stub.

No TCP listening socket, MCU access, reset or flash. The user must explicitly
confirm that the selected unstripped ELF belongs to the dump.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

MAX_CORE = 64 * 1024 * 1024
MAX_ELF = 128 * 1024 * 1024
MAX_GDB_OUTPUT = 2 * 1024 * 1024


def checked(path, limit):
    p = Path(path).resolve(strict=True)
    if not p.is_file() or p.stat().st_size < 4 or p.stat().st_size > limit:
        raise ValueError("Required input file absent, empty or above bounded size: " + p.name)
    return p


def quote_pipe(value, platform=None):
    """Quote only path arguments passed to GDB's local command pipe."""
    platform = os.name if platform is None else platform
    data = str(value)
    if "\0" in data or "\n" in data or "\r" in data:
        raise ValueError("Unsupported control character in tool path")
    if platform == "nt":
        # GDB's command pipe invokes cmd.exe on Windows. Reject syntax that
        # can expand variables, escape quotes or run additional commands.
        if any(c in data for c in '"&|<>^%!'):
            raise ValueError("Unsafe shell metacharacter in Windows tool path")
        return '"' + data + '"'
    return shlex.quote(data)


def command(*, gdb, python, elf, dump, zephyr, platform=None):
    server = Path(zephyr) / "scripts" / "coredump" / "coredump_gdbserver.py"
    if not server.is_file():
        raise ValueError("Selected Zephyr checkout lacks scripts/coredump/coredump_gdbserver.py")
    # --pipe keeps the upstream GDB stub off TCP; quote each operand separately.
    operands = [quote_pipe(Path(p).resolve(), platform) for p in (python, server)]
    operands += ["--pipe"]
    operands += [quote_pipe(Path(p).resolve(), platform) for p in (elf, dump)]
    return [
        str(Path(gdb).resolve()), "--batch", "--nx",
        "-iex", "set auto-load off",
        "-ex", "set pagination off",
        "-ex", "set confirm off",
        "-ex", "set remotetimeout 5",
        "-ex", "target remote | " + " ".join(operands),
        "-ex", "info registers",
        "-ex", "bt 20",
        "-ex", "thread apply all bt 8",
        str(Path(elf).resolve()),
    ]


def investigate(*, gdb, python, elf, dump, zephyr, confirmed=False, timeout=40,
                runner=subprocess.run):
    if not confirmed:
        raise ValueError("Explicit --confirm-elf required: the dump's build identity is not verified")
    if not 5 <= timeout <= 120:
        raise ValueError("Offline analysis timeout must be 5–120 seconds")
    elf = checked(elf, MAX_ELF)
    dump = checked(dump, MAX_CORE)
    gdb = checked(gdb, MAX_ELF)
    python = checked(python, MAX_ELF)
    if elf.open("rb").read(4) != b"\x7fELF":
        raise ValueError("Selected firmware is not an ELF file")
    if dump.open("rb").read(2) != b"ZE":
        raise ValueError("Input is not a Zephyr raw binary core dump (ZE header)")
    args = command(gdb=gdb, python=python, elf=elf, dump=dump, zephyr=zephyr)
    with tempfile.TemporaryFile() as output:
        try:
            process = runner(args, cwd=str(Path(zephyr).resolve()), stdin=subprocess.DEVNULL,
                             stdout=output, stderr=subprocess.STDOUT,
                             timeout=timeout, check=False)
        except subprocess.TimeoutExpired as error:
            raise RuntimeError("Offline GDB core dump analysis timed out; no MCU was accessed") from error
        length = output.tell()
        if length > MAX_GDB_OUTPUT:
            raise RuntimeError("GDB response exceeds the bounded report size")
        output.seek(0)
        log = output.read().decode("utf-8", "replace")
    with elf.open("rb") as image:
        elf_hash = hashlib.file_digest(image, "sha256").hexdigest()
    with dump.open("rb") as snapshot:
        dump_hash = hashlib.file_digest(snapshot, "sha256").hexdigest()
    return {
        "schema": "zew-coredump-1",
        "status": "inspected" if process.returncode == 0 else "unavailable",
        "exit_code": process.returncode,
        "elf_sha256": elf_hash,
        "coredump_sha256": dump_hash,
        "elf_match": "USER CONFIRMED — NOT independently proven by dump bytes",
        "execution": "local GDB pipe to selected Zephyr checkout; no listening socket or physical target",
        "gdb_output": log,
        "limits": [
            "Availability/quality of backtrace depends on dumped memory, Zephyr version and architecture.",
            "Printed register frames are not necessarily the original exception frame.",
            "GDB success does not prove ELF matches firmware, complete memory or root cause.",
        ]
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gdb", required=True, type=Path)
    parser.add_argument("--python", required=True, type=Path)
    parser.add_argument("--elf", required=True, type=Path)
    parser.add_argument("--dump", required=True, type=Path)
    parser.add_argument("--zephyr", required=True, type=Path)
    parser.add_argument("--confirm-elf", action="store_true")
    parser.add_argument("--timeout", type=int, default=40)
    args = parser.parse_args()
    try:
        data = investigate(gdb=args.gdb, python=args.python, elf=args.elf,
                           dump=args.dump, zephyr=args.zephyr,
                           confirmed=args.confirm_elf, timeout=args.timeout)
    except (ValueError, OSError, RuntimeError) as error:
        print(json.dumps({"status": "unavailable", "reason": str(error)}))
        return 2
    print(json.dumps(data, indent=2))
    return 0 if data["status"] == "inspected" else 1


if __name__ == "__main__":
    raise SystemExit(main())
