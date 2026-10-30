# SPDX-License-Identifier: Apache-2.0
"""Read-only observations of CMake compile_commands.json with exact-file identity.

A recorded command does not prove compiler execution, object production,
linker contribution, or firmware flashing.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

from .collector import InputError, SCHEMA_VERSION, _images, _regular, collect
from .inclusion import _query

MAX_BYTES = 48 * 1024 * 1024
MAX_ENTRIES = 100000


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise InputError("Duplicate compiler-database JSON key")
        result[key] = value
    return result


def _snapshot(path):
    before = path.stat()
    if before.st_size > MAX_BYTES:
        raise InputError("Compiler database exceeds 48 MiB limit")
    with path.open("rb") as handle:
        raw = handle.read(MAX_BYTES + 1)
    after = path.stat()
    if len(raw) > MAX_BYTES:
        raise InputError("Compiler database exceeds 48 MiB limit")
    if (before.st_size, before.st_mtime_ns, before.st_ino) != (after.st_size, after.st_mtime_ns, after.st_ino):
        raise InputError("Compiler database changed during collection")
    try:
        rows = json.loads(raw.decode("utf-8", "strict"), object_pairs_hook=_pairs)
    except (UnicodeError, ValueError, RecursionError) as exc:
        raise InputError("Invalid compiler-database JSON") from exc
    if not isinstance(rows, list) or len(rows) > MAX_ENTRIES:
        raise InputError("Expected bounded compiler-database array")
    return rows, hashlib.sha256(raw).hexdigest()


def _source(root, value):
    name = _query("source", value)
    directory = Path(root)
    if directory.is_symlink() or not directory.is_dir():
        raise InputError("Source root must be an existing non-symlink directory")
    directory = directory.resolve(strict=True)
    file = directory / name
    if file.is_symlink() or not file.is_file():
        raise InputError("Requested source must exist and be a regular file")
    target = file.resolve(strict=True)
    if not target.is_relative_to(directory):
        raise InputError("Source escapes the selected source root")
    return target


def _candidate(row):
    if not isinstance(row, dict):
        raise InputError("Compiler-database row must be an object")
    directory, name = row.get("directory"), row.get("file")
    if not all(isinstance(x, str) and 0 < len(x) <= 4096 and "\\x00" not in x
               for x in (directory, name)):
        raise InputError("Compiler-database row has invalid paths")
    if not (isinstance(row.get("command"), str)
            or isinstance(row.get("arguments"), list)
            and bool(row["arguments"])
            and all(isinstance(v, str) for v in row["arguments"])):
        raise InputError("Compiler-database row has no command/arguments")
    try:
        base, file = Path(directory), Path(name)
        if not base.is_absolute():
            return None
        return (file if file.is_absolute() else base / file).resolve(strict=False)
    except (OSError, ValueError):
        return None
