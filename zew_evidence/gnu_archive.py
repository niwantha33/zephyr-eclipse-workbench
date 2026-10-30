# SPDX-License-Identifier: Apache-2.0
"""GNU ar member observations. No extraction, tools, or filesystem mutation."""
from __future__ import annotations

import hashlib
from pathlib import Path

from .collector import InputError

MAX_ARCHIVE = 256 * 1024 * 1024
MAX_NAMES = 4 * 1024 * 1024
MAX_MEMBERS = 100000


def _name(field: bytes, table: bytes) -> str | None:
    value = field.decode("ascii", "strict").strip()
    if value in ("/", "//") or value.startswith("__.SYMDEF") or value == "/SYM64/":
        return None
    if value.startswith("/") and value[1:].isdigit():
        index = int(value[1:])
        if index >= len(table):
            raise InputError("GNU archive long-name reference out of bounds")
        end = table.find(b"/\n", index)
        if end < 0:
            end = table.find(b"\n", index)
        if end < 0:
            raise InputError("GNU archive long-name terminator missing")
        return table[index:end].decode("utf-8", "strict")
    if value.endswith("/"):
        return value[:-1]
    return value


def observe_member(path: Path, wanted: str) -> dict:
    """Report only unique *exact* archive members; no basename-based inference."""
    if (not wanted or len(wanted) > 4096 or "\x00" in wanted
            or wanted.startswith(("/", "~"))):
        raise InputError("Invalid archive member identity")
    before = path.stat()
    if before.st_size > MAX_ARCHIVE:
        raise InputError("GNU archive exceeds 256 MiB bound")
    matches = []
    with path.open("rb") as handle:
        magic = handle.read(8)
        if magic == b"!<thin>\n":
            return {"status": "unsupported-thin-archive", "matching_members": 0,
                    "member_sha256": None}
        if magic != b"!<arch>\n":
            return {"status": "unsupported-archive-format", "matching_members": 0,
                    "member_sha256": None}
        offset, table, count = 8, b"", 0
        while offset < before.st_size:
            count += 1
            if count > MAX_MEMBERS:
                raise InputError("Too many GNU archive members")
            if offset + 60 > before.st_size:
                raise InputError("Truncated GNU archive member header")
            handle.seek(offset)
            header = handle.read(60)
            if header[58:60] != b"`\n":
                raise InputError("Invalid GNU archive member header")
            try:
                size_field = header[48:58].decode("ascii", "strict").strip()
                size = int(size_field)
            except (UnicodeError, ValueError) as error:
                raise InputError("Invalid GNU archive member length") from error
            payload = offset + 60
            if size < 0 or payload + size > before.st_size:
                raise InputError("GNU archive member exceeds archive")
            raw_name = header[:16]
            plain = raw_name.decode("ascii", "strict").strip()
            actual = None
            data_offset = payload
            data_size = size
            if plain == "//":
                if size > MAX_NAMES:
                    raise InputError("GNU long-name table exceeds 4 MiB")
                handle.seek(payload)
                table = handle.read(size)
            elif plain.startswith("#1/"):
                try:
                    name_len = int(plain[3:])
                except ValueError as exc:
                    raise InputError("Invalid BSD extended archive member name") from exc
                if name_len > min(4096, size):
                    raise InputError("Invalid extended archive name length")
                handle.seek(payload)
                actual = handle.read(name_len).decode("utf-8", "strict")
                data_offset += name_len
                data_size -= name_len
            else:
                actual = _name(raw_name, table)
            if actual == wanted:
                if len(matches) > 1:
                    raise InputError("Excess duplicate archive-member identities")
                digest = hashlib.sha256()
                handle.seek(data_offset)
                left = data_size
                while left:
                    block = handle.read(min(65536, left))
                    if not block:
                        raise InputError("Truncated GNU archive member contents")
                    digest.update(block)
                    left -= len(block)
                matches.append((data_size, digest.hexdigest()))
            offset = payload + size + (size & 1)
            if offset > before.st_size + 1:
                raise InputError("GNU archive padding outside file")
    after = path.stat()
    if ((before.st_size, before.st_mtime_ns, before.st_ino) !=
            (after.st_size, after.st_mtime_ns, after.st_ino)):
        raise InputError("GNU archive changed during inspection")
    if len(matches) != 1:
        return {"status": "ambiguous-member" if matches else "missing-member",
                "matching_members": len(matches), "member_sha256": None}
    size, digest = matches[0]
    return {"status": "exact-archive-member-observed", "matching_members": 1,
            "member_bytes": size, "member_sha256": digest}
