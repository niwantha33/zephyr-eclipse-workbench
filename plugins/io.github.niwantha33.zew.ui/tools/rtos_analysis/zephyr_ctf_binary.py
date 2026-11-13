# SPDX-License-Identifier: Apache-2.0
"""Bounded decoder for the packed little-endian RAM CTF layout in Zephyr 4.4.

Zephyr 4.4 ctf_top.h writes uint32_t nanosecond timestamp followed by uint16_t
event ID, then fields in metadata order, without CTF packet framing.
The timestamp is the low 32 bits of k_cyc_to_ns_floor64(k_cycle_get_32()).
Recognition is strict; other CTF layouts stay with the existing bt2 decoder.

Structural decoding does not establish loss-free capture, independently
calibrated timestamps, or flashed-ELF identity.
"""
from __future__ import annotations

from pathlib import Path
import hashlib
import json
import re
import struct

MAX_METADATA = 2 * 1024 * 1024
MAX_STREAM = 64 * 1024 * 1024
MAX_EVENTS = 2_000_000
MAX_DEFINITIONS = 4096
MAX_FIELD_COUNT = 64
MAX_FIELD_ARRAY = 256

FORMATS = {
    'uint8_t': '<B', 'int8_t': '<b',
    'uint16_t': '<H', 'int16_t': '<h',
    'uint32_t': '<I', 'int32_t': '<i',
    'uint64_t': '<Q', 'int64_t': '<q',
}
EVENT = re.compile(r'\bevent\s*\{\s*(.*?)\n\};', re.S)
FIELD = re.compile(
    r'\s*(uint8_t|int8_t|uint16_t|int16_t|uint32_t|int32_t|uint64_t|int64_t|ctf_bounded_string_t)'
    r'\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:\[\s*(\d+)\s*\])?\s*;')
NAME = re.compile(r'\bname\s*=\s*([A-Za-z_][A-Za-z0-9_]*)\s*;')
IDENT = re.compile(r'\bid\s*=\s*(0x[0-9a-fA-F]+|[0-9]+)\s*;')


class NotZephyrPackedCtf(ValueError):
    """The metadata is not a supported packed Zephyr wire format."""


def is_packed_metadata(metadata: str) -> bool:
    return (metadata.startswith('/* CTF 1.8 */')
            and re.search(r'\bbyte_order\s*=\s*le\s*;', metadata) is not None
            and re.search(
                r'struct\s+event_header\s*\{\s*uint32_t\s+timestamp\s*;'
                r'\s*uint16_t\s+id\s*;\s*\}\s*;', metadata) is not None
            and re.search(r'\bevent\.header\s*:=\s*struct\s+event_header\s*;', metadata)
            is not None
            and 'packet.header' not in metadata and 'packet.context' not in metadata
            and 'event.context' not in metadata)


def _read_bounded(path: Path, maximum: int) -> bytes:
    if path.is_symlink() or not path.is_file():
        raise ValueError('Missing regular saved CTF file: ' + path.name)
    size = path.stat().st_size
    if not (0 < size <= maximum):
        raise ValueError(f'Invalid saved CTF file size: {path.name} ({size})')
    with path.open('rb') as stream:
        data = stream.read(maximum + 1)
    if len(data) > maximum or len(data) != size:
        raise ValueError('Saved CTF file changed during bounded read: ' + path.name)
    return data


def _layout(metadata: str):
    if not is_packed_metadata(metadata):
        raise NotZephyrPackedCtf('Unsupported packed Zephyr CTF metadata layout')
    events = EVENT.findall(metadata)
    if not (1 <= len(events) <= MAX_DEFINITIONS):
        raise ValueError('CTF event definition count outside supported bounds')
    layouts = {}
    for event in events:
        name = NAME.search(event)
        ident = IDENT.search(event)
        if name is None or ident is None:
            raise ValueError('Incomplete Zephyr CTF event definition')
        event_id = int(ident.group(1), 0)
        if not 0 <= event_id <= 65535 or event_id in layouts:
            raise ValueError('Duplicate or out-of-range CTF event ID')
        fields = re.search(r'\bfields\s*:=\s*struct\s*\{(.*?)\}\s*;', event, re.S)
        declarations = []
        if fields is not None:
            definition = fields.group(1)
            cursor = 0
            while cursor < len(definition):
                if not definition[cursor:].strip():
                    break
                match = FIELD.match(definition, cursor)
                if match is None:
                    raise ValueError('Unsupported CTF field declaration for ' + name.group(1))
                kind, label, count_text = match.groups()
                count = int(count_text) if count_text is not None else 1
                if not 1 <= count <= MAX_FIELD_ARRAY or len(declarations) >= MAX_FIELD_COUNT:
                    raise ValueError('CTF field array or count exceeds limit')
                declarations.append((kind, label, count, count_text is not None))
                cursor = match.end()
        layouts[event_id] = name.group(1), declarations
    return layouts


def decode_packed(path: Path):
    """Yield observed Zephyr records; never skip unknown IDs or partial bytes."""
    path = Path(path)
    if path.is_symlink() or not path.is_dir():
        raise ValueError('Select a real saved CTF directory')
    meta = _read_bounded(path / 'metadata', MAX_METADATA).decode('utf-8', 'strict')
    layouts = _layout(meta)
    binary = _read_bounded(path / 'channel0_0', MAX_STREAM)
    offset = 0
    wrap = 0
    last = None
    total = 0
    while offset < len(binary):
        if total >= MAX_EVENTS:
            raise ValueError('CTF event limit reached; only observed prefix decoded')
        start = offset
        if len(binary) - offset < 6:
            raise ValueError(f'Truncated Zephyr CTF header at byte {start}')
        ts, event_id = struct.unpack_from('<IH', binary, offset)
        offset += 6
        if event_id not in layouts:
            raise ValueError(f'Unknown Zephyr CTF event ID 0x{event_id:x} at byte {start}')
        event_name, fields = layouts[event_id]
        values = {}
        for kind, label, count, is_array in fields:
            fmt = '<B' if kind == 'ctf_bounded_string_t' else FORMATS[kind]
            width = struct.calcsize(fmt)
            size = width * count
            if offset + size > len(binary):
                raise ValueError(f'Truncated {event_name}.{label} at byte {offset}')
            payload = binary[offset:offset + size]
            offset += size
            if kind == 'ctf_bounded_string_t':
                value = payload.split(b'\0', 1)[0].decode('ascii', 'replace')
            elif is_array:
                value = [struct.unpack_from(fmt, payload, i * width)[0]
                         for i in range(count)]
            else:
                value = struct.unpack_from(fmt, payload)[0]
            values[label] = value
        if last is not None and ts < last:
            # 32-bit nanosecond rollover; preserve ordering only.
            wrap += 1 << 32
        last = ts
        total += 1
        yield {
            'kind': 'event', 'stream': 0, 'name': event_name,
            'timestamp_ns': wrap + ts,
            'thread_id': values.get('thread_id'),
            'thread_name': values.get('name') if event_name != 'named_event' else None,
            'named_event_name': values.get('name') if event_name == 'named_event' else None,
            'priority': values.get('prio'), 'core_id': values.get('cpu'),
            'object_id': values.get('id'), 'ret': values.get('ret'),
            'arg0': values.get('arg0'), 'arg1': values.get('arg1'),
            'size': values.get('size'), 'value': values.get('value'),
            'wire_offset': start,
        }


def supports(path: Path) -> bool:
    """Only select the raw decoder on explicitly recognized metadata."""
    path = Path(path)
    if path.is_symlink() or not path.is_dir():
        return False
    meta = path / 'metadata'
    if meta.is_symlink() or not meta.is_file() or meta.stat().st_size > MAX_METADATA:
        return False
    with meta.open('rb') as handle:
        data = handle.read(MAX_METADATA + 1)
    try:
        return is_packed_metadata(data.decode('utf-8', 'strict'))
    except UnicodeError:
        return False


def buffer_full_evidence(ctf: Path) -> bool:
    """Use only hash-matched capture receipts; a missing receipt means unknown."""
    ctf = Path(ctf)
    receipt = ctf.parent / 'capture.json'
    if receipt.is_symlink() or not receipt.is_file():
        return False
    try:
        info = json.loads(_read_bounded(receipt, 256 * 1024))
        if not isinstance(info, dict) or info.get('capture_full') is not True:
            return False
        stream = _read_bounded(ctf / 'channel0_0', MAX_STREAM)
        metadata = _read_bounded(ctf / 'metadata', MAX_METADATA)
        return (info.get('trace_bytes') == len(stream)
                and info.get('trace_sha256') == hashlib.sha256(stream).hexdigest()
                and info.get('metadata_sha256') == hashlib.sha256(metadata).hexdigest())
    except (OSError, ValueError, UnicodeError, TypeError):
        return False
