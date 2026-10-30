# SPDX-License-Identifier: Apache-2.0
"""Exact GNU map output-section header versus ELF SHF_ALLOC section evidence.

The map cannot supply the bytes to compare. This reports agreement of
addresses/sizes and SHA-256 of bytes that exist in the ELF, not causal
source/object inclusion or an independently authenticated image.
"""
from __future__ import annotations

import re
from pathlib import Path

from .collector import InputError, SCHEMA_VERSION, _digest, _images, _regular, collect
from .elf_ranges import load_segments, coverage
from .elf_sections import inspect_sections
from .linker import _map_snapshot

MAX_MAP_OUTPUT_SECTIONS = 20000
MAX_EXPORTED_HASHES = 16
# A GNU-ld output-section line has no archive/object token. Input-section
# lines naming an .o/.a are excluded; ambiguous multi-line forms stay unknown.
OUTPUT = re.compile(
    r"^ ?(\.[^\s]+)\s+0x([0-9a-fA-F]+)\s+0x([0-9a-fA-F]+)"
    r"(?:\s+load address\s+0x[0-9a-fA-F]+)?\s*$")


def _outputs(text: str) -> list[tuple[str, int, int]] | None:
    if "Linker script and memory map" not in text:
        return None
    after = False
    rows = []
    for line in text.splitlines():
        if line.strip() == "Linker script and memory map":
            after = True
            continue
        if not after:
            continue
        if len(line) > 8192:
            continue
        matched = OUTPUT.fullmatch(line)
        if not matched:
            continue
        size = int(matched[3], 16)
        if size:
            rows.append((matched[1], int(matched[2], 16), size))
            if len(rows) > MAX_MAP_OUTPUT_SECTIONS:
                raise InputError("Too many recognised GNU map output-section records")
    return rows


def audit(build: Path, images: list[str] | None = None, graph: dict | None = None) -> dict:
    root = Path(build)
    if not root.is_dir() or root.is_symlink():
        raise InputError("--build must be an existing non-symlink directory")
    root = root.resolve(strict=True)
    graph = graph if graph is not None else collect(root, images or [])
    proofs = {n["id"]: n["provenance"]["sha256"] for n in graph["nodes"]
              if n["type"] == "artifact" and n["data"]["kind"] in ("map", "elf")}
    images_out = []
    for name, directory in _images(root, images or []):
        map_id, elf_id = f"artifact:{name}:map", f"artifact:{name}:elf"
        row = {"image": name, "status": "unknown", "confidence": "unknown",
               "map_output_sections": 0, "elf_allocated_sections": 0,
               "comparison": None, "observed_elf_section_hashes": [],
               "provenance": {"map": None, "elf": None}, "missing_evidence": []}
        map_path = _regular(directory, "zephyr/zephyr.map")
        elf_path = _regular(directory, "zephyr/zephyr.elf")
        if map_path is None:
            if map_id in proofs:
                raise InputError("Linker map disappeared since Evidence Graph capture")
            row["missing_evidence"].append("zephyr/zephyr.map")
            images_out.append(row)
            continue
        map_hash = proofs.get(map_id)
        if not map_hash:
            raise InputError("Linker map is missing its graph digest")
        rows = _outputs(_map_snapshot(map_path, map_hash))
        row["provenance"]["map"] = {"node_id": map_id, "sha256": map_hash}
        if rows is None:
            row["status"] = "unsupported-map-format"
            images_out.append(row)
            continue
        row["map_output_sections"] = len(rows)
        if elf_path is None:
            if elf_id in proofs:
                raise InputError("ELF disappeared since Evidence Graph capture")
            row["status"] = "missing-elf"
            row["missing_evidence"].append("zephyr/zephyr.elf")
            images_out.append(row)
            continue
        elf_hash, elf_size = _digest(elf_path)
        if elf_hash != proofs.get(elf_id):
            raise InputError("ELF differs from Evidence Graph snapshot")
        try:
            sections = inspect_sections(elf_path)
            segments = load_segments(elf_path)
        except InputError:
            row["status"] = "unsupported-or-invalid-elf-sections"
            row["missing_evidence"].append("Valid ELF section table and PT_LOAD headers")
            images_out.append(row)
            continue
        # Confirm the same bytes remained in place after all independent reads.
        new_hash, new_size = _digest(elf_path)
        if (new_hash, new_size) != (elf_hash, elf_size):
            raise InputError("ELF changed while inspecting output sections")
        row["provenance"]["elf"] = {"node_id": elf_id, "sha256": elf_hash, "bytes": elf_size}
        row["elf_allocated_sections"] = len(sections)
        by_name = {}
        for section in sections:
            by_name.setdefault(section["name"], []).append(section)
        counts = {kind: 0 for kind in (
            "exact-layout", "address-mismatch", "size-mismatch", "missing-or-unallocated",
            "ambiguous-map-name", "ambiguous-elf-name", "file-backed-vma",
            "zero-fill-vma", "mixed-file-and-zero-fill-vma", "uncovered-vma",
            "ambiguous-vma", "other-vma", "elf-section-byte-hashes",
            "file-backed-sections-unhashed")}
        names = {}
        for section_name, address, size in rows:
            names[section_name] = names.get(section_name, 0) + 1
        for section_name, address, size in rows:
            if names[section_name] != 1:
                counts["ambiguous-map-name"] += 1
                continue
            choices = by_name.get(section_name, [])
            if not choices:
                counts["missing-or-unallocated"] += 1
                continue
            if len(choices) != 1:
                counts["ambiguous-elf-name"] += 1
                continue
            sec = choices[0]
            if sec["address"] != address:
                counts["address-mismatch"] += 1
                continue
            if sec["bytes"] != size:
                counts["size-mismatch"] += 1
                continue
            counts["exact-layout"] += 1
            vma = coverage(segments, address, size)
            key = vma if vma in ("file-backed-vma", "zero-fill-vma",
                                 "mixed-file-and-zero-fill-vma") else (
                "uncovered-vma" if vma == "uncovered" else
                "ambiguous-vma" if vma == "ambiguous" else "other-vma")
            counts[key] += 1
            # Bytes are SHA-256-hashed from the exact ELF file section offsets.
            # Do not reveal section names, source filenames or address ranges.
            if sec["sha256"]:
                counts["elf-section-byte-hashes"] += 1
            elif sec["data_kind"] == "file-backed-unhashed":
                counts["file-backed-sections-unhashed"] += 1
            if len(row["observed_elf_section_hashes"]) < MAX_EXPORTED_HASHES and sec["sha256"]:
                row["observed_elf_section_hashes"].append({
                    "sha256": sec["sha256"], "bytes": sec["bytes"]})
        row["comparison"] = counts
        disagreement = sum(counts[k] for k in (
            "address-mismatch", "size-mismatch", "missing-or-unallocated",
            "ambiguous-map-name", "ambiguous-elf-name", "uncovered-vma",
            "ambiguous-vma", "other-vma"))
        if not rows:
            row["status"] = "no-supported-map-output-section"
        elif disagreement:
            row["status"] = "map-elf-section-disagreement-or-unknown"
        else:
            row["status"] = "map-elf-output-layout-observed"
            row["confidence"] = "derived"
        images_out.append(row)
    return {
        "schema_version": SCHEMA_VERSION,
        "status": "elf-output-section-observation",
        "images": images_out,
        "limitations": [
            "Checks only single-line GNU ld output-section headers with exact ELF SHF_ALLOC section names.",
            "An identical name, VMA and size is metadata agreement, not source-to-output causality.",
            "Section SHA-256 values are bytes read from ELF, not a comparison against map or object bytes.",
            "Section hashing has per-section and aggregate byte budgets; un-hashed sections remain explicit.",
            "ELF PT_LOAD VMA coverage is not a proof of LMA/flash address or runtime memory contents.",
            "Duplicate names, absent sections, extended ELF numbering and unsupported map formats stay unknown.",
            "Map/ELF may be stale or forged; no device target is read or verified.",
            "Raw section names, paths, symbols, addresses and compiler commands are not exported.",
        ],
    }
