# SPDX-License-Identifier: Apache-2.0
"""Conservative GNU ld input-to-output section *context* observations.

Uses exactly named output headers and ELF section-header allocation flags.
This is NOT a full GNU linker script interpreter, proof of source inclusion,
or explanation of every record outside ELF PT_LOAD segments.
"""
from __future__ import annotations

import re
from pathlib import Path

from .collector import InputError, SCHEMA_VERSION, _digest, _images, _regular, collect
from .elf_sections import inspect_sections
from .linker import _map_snapshot

MAX_HEADERS = 20000
MAX_INPUT_ROWS = 150000
# Strictly column-zero GNU output declarations. Indented input sections
# cannot accidentally become parents. Unknown map variants stay unknown.
OUTPUT = re.compile(
    r"^(\.[^\s]+)\s+0x([0-9a-fA-F]+)\s+0x([0-9a-fA-F]+)"
    r"(?:\s+load address\s+0x[0-9a-fA-F]+)?\s*$")
INPUT = re.compile(
    r"^[ \t]+\.[^\s]+\s+0x([0-9a-fA-F]+)\s+0x([0-9a-fA-F]+)\s+(.+?)\s*$")
INPUT_NAME = re.compile(r"^[ \t]+\.[^\s]+\s*$")
INPUT_CONTINUATION = re.compile(
    r"^[ \t]+0x([0-9a-fA-F]+)\s+0x([0-9a-fA-F]+)\s+(.+?)\s*$")
OBJECT = re.compile(r"^(?:[^()]+\.(?:o|obj)|[^()]+\.a\([^()]+\.(?:o|obj)\))$")


def _input_record(address: str, length: str, object_token: str) -> tuple[int, int] | None:
    # The object path is used only for syntactic identity, never exported,
    # resolved on disk, or inferred from a matching basename.
    if not OBJECT.fullmatch(object_token):
        return None
    count = int(length, 16)
    return (int(address, 16), count) if count else None


def _records(text: str) -> tuple[list[tuple[str, int, int]],
                                 list[tuple[tuple[str, int, int] | None, int, int]]] | None:
    if "Linker script and memory map" not in text:
        return None
    active = False
    parent = None
    pending = False
    outputs = []
    inputs = []
    for line in text.splitlines():
        stripped = line.strip()
        if stripped == "Linker script and memory map":
            active, parent, pending = True, None, False
            continue
        if not active or len(line) > 8192:
            pending = False
            continue
        if not line or line[0] not in (" ", "\t"):
            # LOAD, PROVIDE, Memory Configuration, and all unknown
            # column-zero lines terminate parent context.
            pending = False
            matched = OUTPUT.fullmatch(line)
            if matched:
                length = int(matched[3], 16)
                parent = (matched[1], int(matched[2], 16), length) if length else None
                if parent:
                    outputs.append(parent)
                    if len(outputs) > MAX_HEADERS:
                        raise InputError("Too many supported GNU map output headers")
            else:
                parent = None
            continue
        single = INPUT.fullmatch(line)
        if single:
            pending = False
            entry = _input_record(single[1], single[2], single[3].strip())
            if entry:
                inputs.append((parent, *entry))
        elif pending:
            continuation = INPUT_CONTINUATION.fullmatch(line)
            pending = False
            if continuation:
                entry = _input_record(continuation[1], continuation[2],
                                      continuation[3].strip())
                if entry:
                    inputs.append((parent, *entry))
        else:
            pending = bool(INPUT_NAME.fullmatch(line))
        if len(inputs) > MAX_INPUT_ROWS:
            raise InputError("Too many supported GNU map input-section records")
    return outputs, inputs


def _classify(outputs: list[tuple[str, int, int]],
              inputs: list[tuple[tuple[str, int, int] | None, int, int]],
              elf: list[dict]) -> dict:
    names = {}
    for output in outputs:
        names[output[0]] = names.get(output[0], 0) + 1
    elf_names = {}
    for section in elf:
        elf_names.setdefault(section["name"], []).append(section)
    output_counts = {key: 0 for key in (
        "allocated-exact-output", "nonallocated-exact-output", "absent-elf-output",
        "ambiguous-output-name", "ambiguous-elf-name", "output-layout-mismatch")}
    states = {}
    for output in outputs:
        name, address, length = output
        if names[name] != 1:
            state = "ambiguous-output-name"
        elif not elf_names.get(name):
            state = "absent-elf-output"
        elif len(elf_names[name]) != 1:
            state = "ambiguous-elf-name"
        else:
            section = elf_names[name][0]
            if section["address"] != address or section["bytes"] != length:
                state = "output-layout-mismatch"
            elif section["allocated"]:
                state = "allocated-exact-output"
            else:
                state = "nonallocated-exact-output"
        output_counts[state] += 1
        states[output] = state
    input_counts = {key: 0 for key in (
        "within-allocated-output", "within-nonallocated-output",
        "outside-parent-bounds", "parent-unobserved",
        "parent-metadata-unresolved")}
    for parent, address, length in inputs:
        if parent is None:
            key = "parent-unobserved"
        elif not (parent[1] <= address and address + length <= parent[1] + parent[2]):
            key = "outside-parent-bounds"
        elif states[parent] == "allocated-exact-output":
            key = "within-allocated-output"
        elif states[parent] == "nonallocated-exact-output":
            key = "within-nonallocated-output"
        else:
            key = "parent-metadata-unresolved"
        input_counts[key] += 1
    return {"output_headers": len(outputs), "supported_input_rows": len(inputs),
            "output_header_classes": output_counts, "input_context_classes": input_counts}


def audit(build: Path, images: list[str] | None = None, graph: dict | None = None) -> dict:
    root = Path(build)
    if root.is_symlink() or not root.is_dir():
        raise InputError("--build must be an existing non-symlink directory")
    root = root.resolve(strict=True)
    graph = graph if graph is not None else collect(root, images or [])
    hashes = {node["id"]: node["provenance"]["sha256"] for node in graph["nodes"]
              if node["type"] == "artifact" and node["data"]["kind"] in ("elf", "map")}
    result = []
    for image, sub in _images(root, images or []):
        map_id, elf_id = f"artifact:{image}:map", f"artifact:{image}:elf"
        row = {"image": image, "status": "unknown", "confidence": "unknown",
               "context": None, "provenance": {"map": None, "elf": None},
               "missing_evidence": []}
        map_path = _regular(sub, "zephyr/zephyr.map")
        elf_path = _regular(sub, "zephyr/zephyr.elf")
        if map_path is None:
            if map_id in hashes:
                raise InputError("GNU map disappeared after graph capture")
            row["missing_evidence"].append("zephyr/zephyr.map")
            result.append(row)
            continue
        expected_map = hashes.get(map_id)
        if not expected_map:
            raise InputError("GNU map missing from Evidence Graph")
        records = _records(_map_snapshot(map_path, expected_map))
        row["provenance"]["map"] = {"node_id": map_id, "sha256": expected_map}
        if records is None:
            row["status"] = "unsupported-map-format"
            result.append(row)
            continue
        if elf_path is None:
            if elf_id in hashes:
                raise InputError("ELF disappeared after graph capture")
            row["status"] = "missing-elf"
            row["missing_evidence"].append("zephyr/zephyr.elf")
            result.append(row)
            continue
        elf_hash, size = _digest(elf_path)
        if elf_hash != hashes.get(elf_id):
            raise InputError("ELF differs from Evidence Graph snapshot")
        try:
            # Include nonallocated ELF section *metadata*, without hashing
            # their file bytes or treating them as loadable.
            sections = inspect_sections(elf_path, include_unallocated=True)
        except InputError:
            row["status"] = "unsupported-or-invalid-elf-sections"
            row["missing_evidence"].append("Supported ELF section table")
            result.append(row)
            continue
        if _digest(elf_path) != (elf_hash, size):
            raise InputError("ELF changed during GNU map context inspection")
        row["provenance"]["elf"] = {"node_id": elf_id, "sha256": elf_hash}
        row["context"] = _classify(records[0], records[1], sections)
        counts = row["context"]["input_context_classes"]
        if row["context"]["output_headers"] == 0 or row["context"]["supported_input_rows"] == 0:
            row["status"] = "no-supported-gnu-output-or-input-records"
        elif counts["within-allocated-output"] or counts["within-nonallocated-output"]:
            row["status"] = "gnu-map-output-context-partially-observed"
            row["confidence"] = "derived"
        else:
            row["status"] = "gnu-map-context-unresolved"
        result.append(row)
    return {"schema_version": SCHEMA_VERSION,
            "status": "gnu-map-elf-output-context-audit", "images": result,
            "limitations": [
                "Only column-zero GNU output headers and selected input records are parsed; not a linker-script interpreter.",
                "Indented, multi-line and vendor-specific output headers may remain unrecognised.",
                "A within-output section is a textual/layout observation, never proof its input bytes reached final ELF.",
                "Nonallocated ELF sections are reported as metadata only and never counted as flashed or loadable.",
                "A map row outside PT_LOAD does not automatically imply a linker or firmware error.",
                "Results omit all section names, paths, addresses, compiler commands and raw linker text.",
                "Evidence Graph hashes identify local bytes; freshness, device identity and flashed firmware remain unknown.",
            ]}
