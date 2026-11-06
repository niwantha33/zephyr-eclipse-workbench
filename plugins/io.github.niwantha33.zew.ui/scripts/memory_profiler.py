#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read-only, evidence-labelled memory-layout observations from a GNU ld Zephyr map.

The linker map describes linker MEMORY ranges and output-section placement,
NOT the MPU registers programmed at boot or during thread switches.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

MAX_MAP_BYTES = 64 * 1024 * 1024
MAX_REGIONS = 1024
MAX_SECTIONS = 20000

MEMORY_ROW = re.compile(
    r"^\s*(?P<name>\S+)\s+0[xX](?P<origin>[0-9a-fA-F]+)\s+"
    r"0[xX](?P<length>[0-9a-fA-F]+)(?:\s+(?P<attributes>\S+))?\s*$"
)
OUTPUT = re.compile(
    r"^ ?(?P<name>\.[^\s]+)\s+0[xX](?P<address>[0-9a-fA-F]+)\s+"
    r"0[xX](?P<length>[0-9a-fA-F]+)"
    r"(?:\s+load address\s+0[xX](?P<load>[0-9a-fA-F]+))?\s*$"
)
OUTPUT_NAME = re.compile(r"^ ?(\.[^\s]+)\s*$")
OUTPUT_CONTINUATION = re.compile(
    r"^\s+0[xX](?P<address>[0-9a-fA-F]+)\s+0[xX](?P<length>[0-9a-fA-F]+)"
    r"(?:\s+load address\s+0[xX](?P<load>[0-9a-fA-F]+))?\s*$"
)


class MapFormatError(ValueError):
    """A map could not be safely interpreted as the supported GNU ld format."""


def _kind(name: str) -> str:
    """Classification is a label-based hint, not an MPU or device-property fact."""
    upper = name.upper()
    if "FLASH" in upper or "ROM" in upper:
        return "flash"
    if any(token in upper for token in ("RAM", "TCM")):
        return "ram"
    return "other"


def _region_for(regions: list[dict], start: int, end: int) -> str | None:
    matches = [r["name"] for r in regions
               if r["origin"] <= start and end <= r["end_exclusive"]]
    return matches[0] if len(matches) == 1 else None


def _section(name: str, address: str, length: str, load: str | None,
             regions: list[dict]) -> dict | None:
    size = int(length, 16)
    if size == 0:
        return None
    start = int(address, 16)
    load_at = int(load, 16) if load is not None else None
    return {
        "name": name,
        "start": start,
        "end_exclusive": start + size,
        "size_bytes": size,
        "vma_region": _region_for(regions, start, start + size),
        "load_address": load_at,
        "load_end_exclusive": load_at + size if load_at is not None else None,
        "lma_region": (_region_for(regions, load_at, load_at + size)
                       if load_at is not None else None),
        "confidence": "derived",
        "elf_allocation": "unknown-without-elf",
    }


def parse_map(text: str) -> dict:
    """Parse explicitly represented MEMORY rows and GNU output-section headers."""
    regions: list[dict] = []
    sections: list[dict] = []
    memory = False
    header = False
    layout = False
    wrapped_name: str | None = None

    for line in text.splitlines():
        stripped = line.strip()
        if stripped == "Memory Configuration":
            memory, header, layout, wrapped_name = True, False, False, None
            continue
        if stripped == "Linker script and memory map":
            memory, layout, wrapped_name = False, True, None
            continue
        if len(line) > 8192:
            raise MapFormatError("Map contains an overlong line")
        if memory:
            if not header:
                header = bool(re.fullmatch(r"Name\s+Origin\s+Length\s+Attributes?", stripped,
                                           flags=re.IGNORECASE))
                continue
            if not stripped:
                continue
            match = MEMORY_ROW.fullmatch(line)
            if match is None:
                raise MapFormatError("Unsupported memory-configuration table row")
            name = match["name"]
            if name == "*default*":
                continue  # GNU's fallback address range is not physical memory.
            origin = int(match["origin"], 16)
            size = int(match["length"], 16)
            if size == 0:
                continue
            regions.append({
                "name": name,
                "kind": _kind(name),
                "kind_basis": "region-name-heuristic",
                "origin": origin,
                "end_exclusive": origin + size,
                "size_bytes": size,
                "linker_attributes": match["attributes"] or "",
                "confidence": "derived",
            })
            if len(regions) > MAX_REGIONS:
                raise MapFormatError("Too many memory regions")
            continue
        if not layout:
            continue  # Ignore discarded input sections and other pre-map data.
        match = OUTPUT.fullmatch(line)
        name = match["name"] if match else None
        if match is None and wrapped_name is not None:
            cont = OUTPUT_CONTINUATION.fullmatch(line)
            if cont is not None:
                match, name = cont, wrapped_name
        wrapped_name = None
        if match is None:
            heading = OUTPUT_NAME.fullmatch(line)
            wrapped_name = heading[1] if heading else None
            continue
        section = _section(name, match["address"], match["length"], match["load"], regions)
        if section is not None:
            sections.append(section)
            if len(sections) > MAX_SECTIONS:
                raise MapFormatError("Too many output sections")

    if not layout:
        raise MapFormatError("GNU ld 'Linker script and memory map' marker missing")
    # Unexpectedly missing table is visible to callers, not silently called zero RAM.
    return {
        "status": "memory-regions-observed" if regions else "memory-regions-unavailable",
        "memory_regions": regions,
        "output_sections": sections,
        "mpu": {
            "status": "not-determinable-from-linker-map",
            "confidence": "unknown",
            "regions": [],
            "reason": "A linker map cannot establish boot-time or dynamic hardware MPU settings",
        },
    }


def inspect_map(path: Path) -> dict:
    """Read a bounded, unchanged input snapshot and attach SHA-256 provenance."""
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise MapFormatError("--map must be an existing regular, non-symlink file")
    before = path.stat()
    if before.st_size > MAX_MAP_BYTES:
        raise MapFormatError("Linker map exceeds 64 MiB parsing limit")
    with path.open("rb") as handle:
        raw = handle.read(MAX_MAP_BYTES + 1)
    after = path.stat()
    if len(raw) > MAX_MAP_BYTES or (before.st_size, before.st_mtime_ns, before.st_ino) != (
            after.st_size, after.st_mtime_ns, after.st_ino):
        raise MapFormatError("Linker map is too large or changed during inspection")
    try:
        body = parse_map(raw.decode("utf-8", "strict"))
    except UnicodeError as exc:
        raise MapFormatError("Linker map must be UTF-8") from exc
    return {
        "schema_version": 1,
        **body,
        "provenance": {
            "artifact": "zephyr/zephyr.map",
            "sha256": hashlib.sha256(raw).hexdigest(),
            "extraction": "GNU ld Memory Configuration and output-section headers",
            "linker_version": None,
        },
        "limitations": [
            "Addresses and section sizes come from linker text, not a verified ELF or target",
            "Memory kind is a name-based hint; attributes are linker attributes, not MPU permissions",
            "VMA/LMA records are linker-layout observations; neither proves flash usage or ELF allocation",
            "MPU configuration requires additional devicetree/configuration or runtime evidence",
        ],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--map", type=Path, required=True, help="Zephyr build/zephyr/zephyr.map")
    parser.add_argument("--output", type=Path, help="Create a JSON file (never replace existing files)")
    args = parser.parse_args(argv)
    try:
        report = json.dumps(inspect_map(args.map), indent=2, sort_keys=True) + "\n"
        if args.output is None:
            sys.stdout.write(report)
        else:
            with args.output.open("x", encoding="utf-8", newline="\n") as target:
                target.write(report)
        return 0
    except (MapFormatError, OSError) as exc:
        print(f"memory-profiler: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
