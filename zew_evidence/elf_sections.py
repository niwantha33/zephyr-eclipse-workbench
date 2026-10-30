# SPDX-License-Identifier: Apache-2.0
"""Bounded ELF output-section metadata and bytes; never source attribution."""
from __future__ import annotations

import hashlib
import struct
from pathlib import Path

from .collector import InputError

MAX_HEADERS = 20000
MAX_STRINGS = 8 * 1024 * 1024
MAX_SECTION_HASH = 64 * 1024 * 1024


def inspect_sections(path: Path) -> list[dict]:
    """Read ELF32/64 section table, require explicit string table and valid offsets.

    Returns internal section names for exact same-file matching only. Callers
    must never export names, source paths or file offsets.
    """
    before = path.stat()
    size = before.st_size
    with path.open("rb") as f:
        ident = f.read(16)
        if len(ident) != 16 or ident[:4] != b"\x7fELF":
            raise InputError("ELF identification invalid")
        kind, byteorder = ident[4], ident[5]
        if kind not in (1, 2) or byteorder not in (1, 2) or ident[6] != 1:
            raise InputError("Unsupported ELF class or byte order")
        order = "<" if byteorder == 1 else ">"
        header_fmt = "HHIIIIIHHHHHH" if kind == 1 else "HHIQQQIHHHHHH"
        header_len = struct.calcsize(order + header_fmt)
        raw = f.read(header_len)
        if len(raw) != header_len:
            raise InputError("Truncated ELF header")
        hdr = struct.unpack(order + header_fmt, raw)
        shoff, ehsize, entsize, count, strindex = hdr[5], hdr[7], hdr[10], hdr[11], hdr[12]
        section_fmt = "IIIIIIIIII" if kind == 1 else "IIQQQQIIQQ"
        section_len = struct.calcsize(order + section_fmt)
        # ELF extended section numbering is explicitly unsupported, not guessed.
        if (hdr[2] != 1 or ehsize < 16 + header_len or not shoff
                or not 1 <= count <= MAX_HEADERS or not 1 <= strindex < count
                or not section_len <= entsize <= 256 or shoff < ehsize
                or shoff + count * entsize > size):
            raise InputError("Missing, extended or invalid ELF section table")
        table = []
        for i in range(count):
            f.seek(shoff + i * entsize)
            data = f.read(section_len)
            if len(data) != section_len:
                raise InputError("Truncated ELF section header")
            item = struct.unpack(order + section_fmt, data)
            name, typ, flags, addr, offset, length = item[:6]
            if offset > size and typ != 8:
                raise InputError("ELF section file offset outside file")
            if typ != 8 and (length > size or offset + length > size):
                raise InputError("ELF section contents outside file")
            table.append((name, typ, flags, addr, offset, length))
        str_name, str_type, _, _, str_offset, str_len = table[strindex]
        if str_type != 3 or str_len > MAX_STRINGS or str_offset + str_len > size:
            raise InputError("Missing or oversized ELF section string table")
        f.seek(str_offset)
        strings = f.read(str_len)
        if len(strings) != str_len or not strings or strings[0] != 0:
            raise InputError("ELF section string table invalid")
        rows = []
        for idx, (noff, typ, flags, addr, offset, length) in enumerate(table):
            if noff >= len(strings):
                raise InputError("ELF section-name offset outside string table")
            end = strings.find(b"\x00", noff)
            if end < 0:
                raise InputError("Unterminated ELF section name")
            try:
                name = strings[noff:end].decode("utf-8", "strict")
            except UnicodeError as error:
                raise InputError("Unsupported ELF section-name encoding") from error
            # Exclude synthetic, unallocated, zero length or symbol sections.
            if idx == 0 or not name or not flags & 2 or not length:
                continue
            digest = None
            if typ != 8 and length <= MAX_SECTION_HASH:
                f.seek(offset)
                sha = hashlib.sha256()
                left = length
                while left:
                    block = f.read(min(left, 65536))
                    if not block:
                        raise InputError("Truncated ELF section bytes")
                    sha.update(block)
                    left -= len(block)
                digest = sha.hexdigest()
            rows.append({"name": name, "address": addr, "bytes": length,
                         "data_kind": "zero-fill" if typ == 8 else
                         "file-backed" if digest else "file-backed-too-large",
                         "sha256": digest})
    after = path.stat()
    if (before.st_size, before.st_mtime_ns, before.st_ino) != (
            after.st_size, after.st_mtime_ns, after.st_ino):
        raise InputError("ELF changed while reading section evidence")
    return rows
