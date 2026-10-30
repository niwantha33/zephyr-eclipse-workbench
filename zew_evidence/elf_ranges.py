# SPDX-License-Identifier: Apache-2.0
"""Bounded ELF PT_LOAD virtual-range observations; not source/link verification."""
from __future__ import annotations

import struct
from pathlib import Path

from .collector import InputError


def load_segments(path: Path) -> list[tuple[int, int, int]]:
    size = path.stat().st_size
    with path.open("rb") as handle:
        ident = handle.read(16)
        if len(ident) != 16 or ident[:4] != b"\x7fELF":
            raise InputError("ELF identification invalid")
        klass, order = ident[4], ident[5]
        if klass not in (1, 2) or order not in (1, 2) or ident[6] != 1:
            raise InputError("Unsupported ELF class, byte order or identification version")
        endian = "<" if order == 1 else ">"
        fmt = ("HHIIIIIHHHHHH" if klass == 1 else "HHIQQQIHHHHHH")
        needed = struct.calcsize(endian + fmt)
        raw = handle.read(needed)
        if len(raw) != needed:
            raise InputError("ELF file header truncated")
        info = struct.unpack(endian + fmt, raw)
        e_type, e_version = info[0], info[2]
        phoff, ehsize, phentsize, phnum = info[4], info[7], info[8], info[9]
        if e_type not in (2, 3) or e_version != 1 or ehsize < needed + 16:
            raise InputError("Unsupported ELF executable header")
        phfmt = "IIIIIIII" if klass == 1 else "IIQQQQQQ"
        entry_size = struct.calcsize(endian + phfmt)
        if not (1 <= phnum <= 4096 and entry_size <= phentsize <= 256):
            raise InputError("Invalid or extended ELF program header count")
        if phoff < ehsize or phoff + phentsize * phnum > size:
            raise InputError("ELF program header table lies outside file")
        ranges = []
        limit = (1 << (32 if klass == 1 else 64))
        for index in range(phnum):
            handle.seek(phoff + index * phentsize)
            entry = handle.read(entry_size)
            if len(entry) != entry_size:
                raise InputError("Truncated ELF program header")
            values = struct.unpack(endian + phfmt, entry)
            if values[0] != 1:  # PT_LOAD only
                continue
            offset, virtual, file_bytes, memory_bytes = (
                (values[1], values[2], values[4], values[5]) if klass == 1
                else (values[2], values[3], values[5], values[6]))
            if (file_bytes > memory_bytes or offset + file_bytes > size
                    or virtual + memory_bytes > limit):
                raise InputError("Invalid ELF PT_LOAD bounds")
            if memory_bytes:
                ranges.append((virtual, file_bytes, memory_bytes))
        if not ranges:
            raise InputError("ELF contains no nonempty PT_LOAD segments")
        return sorted(ranges)


def coverage(segments: list[tuple[int, int, int]], address: int, length: int) -> str:
    """Only full containment in one unambiguous VMA segment counts."""
    if length <= 0 or address < 0:
        return "invalid"
    matches = [(v, f, m) for v, f, m in segments
               if v <= address and address + length <= v + m]
    if len(matches) != 1:
        return "uncovered" if not matches else "ambiguous"
    v, f, m = matches[0]
    if address + length <= v + f:
        return "file-backed-vma"
    if address >= v + f:
        return "zero-fill-vma"
    return "mixed-file-and-zero-fill-vma"
