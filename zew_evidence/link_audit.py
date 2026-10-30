# SPDX-License-Identifier: Apache-2.0
"""Cross-check GNU-ld section text with ELF load ranges and GNU ar contents.

These are independent, read-only *observations*. A map and an ELF can agree
yet be stale, forged, or unrelated to any current source tree or device.
"""
from __future__ import annotations

import re
from pathlib import Path

from .collector import InputError, SCHEMA_VERSION, _digest, _images, _regular, collect
from .elf_ranges import coverage, load_segments
from .gnu_archive import observe_member
from .linker import _map_snapshot

SECTION = re.compile(r"^[ \t]+\.[^\s]+[ \t]+0x([0-9a-fA-F]+)[ \t]+0x([0-9a-fA-F]+)[ \t]+(.+?)\s*$")
ARCHIVE = re.compile(r"^(.+\.a)\(([^()]+\.(?:o|obj))\)$")
MAX_SECTIONS = 150000
MAX_ARCHIVE_REFS = 2000
MAX_DIGEST_EXPORT = 32
MAX_TOTAL_ARCHIVE_IO = 512 * 1024 * 1024


def _archive_path(directory: Path, value: str) -> Path | None:
    try:
        archive = Path(value)
        path = archive if archive.is_absolute() else directory / archive
        if not path.is_relative_to(directory):
            return None
        # Reject symlinked artifact or parents and never traverse outside image.
        return _regular(directory, path.relative_to(directory).as_posix())
    except (ValueError, OSError):
        return None


def _parse(text: str):
    if "Linker script and memory map" not in text:
        return None
    in_layout = False
    rows = []
    for line in text.splitlines():
        if line.strip() == "Linker script and memory map":
            in_layout = True
            continue
        if not in_layout:
            continue
        if len(line) > 8192:
            continue
        matched = SECTION.fullmatch(line)
        if matched is None:
            continue
        token = matched[3].strip()
        is_archive = ARCHIVE.fullmatch(token)
        if not is_archive and not token.endswith((".o", ".obj")):
            continue
        size = int(matched[2], 16)
        if size == 0:
            continue
        rows.append((int(matched[1], 16), size,
                     (is_archive[1], is_archive[2]) if is_archive else None))
        if len(rows) > MAX_SECTIONS:
            raise InputError("GNU linker map contains too many source input sections")
    return rows


def audit(build: Path, images: list[str] | None = None, graph: dict | None = None) -> dict:
    root = Path(build)
    if root.is_symlink() or not root.is_dir():
        raise InputError("--build must be an existing, non-symlink directory")
    root = root.resolve(strict=True)
    graph = graph if graph is not None else collect(root, images or [])
    proofs = {n["id"]: n["provenance"]["sha256"] for n in graph["nodes"]
              if n["type"] == "artifact" and n["data"]["kind"] in ("map", "elf")}
    images_out = []
    for name, directory in _images(root, images or []):
        doc_id, elf_id = f"artifact:{name}:map", f"artifact:{name}:elf"
        report = {
            "image": name, "status": "unknown", "confidence": "unknown",
            "map_sections": 0, "elf_ranges": None, "archive": None,
            "provenance": {"map": None, "elf": None}, "missing_evidence": [],
        }
        map_path = _regular(directory, "zephyr/zephyr.map")
        elf_path = _regular(directory, "zephyr/zephyr.elf")
        if map_path is None:
            if doc_id in proofs:
                raise InputError("GNU linker map disappeared after Evidence Graph snapshot")
            report["missing_evidence"].append("zephyr/zephyr.map")
            images_out.append(report)
            continue
        expected_map = proofs.get(doc_id)
        if expected_map is None:
            raise InputError("GNU linker map lacks Evidence Graph provenance")
        text = _map_snapshot(map_path, expected_map)
        report["provenance"]["map"] = {"node_id": doc_id, "sha256": expected_map}
        rows = _parse(text)
        if rows is None:
            report["status"] = "unsupported-map-format"
            images_out.append(report)
            continue
        report["map_sections"] = len(rows)
        if elf_path is None:
            if elf_id in proofs:
                raise InputError("ELF disappeared after Evidence Graph snapshot")
            report["status"] = "missing-elf"
            report["missing_evidence"].append("zephyr/zephyr.elf")
            images_out.append(report)
            continue
        elf_sha, elf_size = _digest(elf_path)
        if elf_sha != proofs.get(elf_id):
            raise InputError("ELF differs from Evidence Graph snapshot")
        try:
            segments = load_segments(elf_path)
        except InputError:
            report["status"] = "unsupported-or-invalid-elf"
            report["missing_evidence"].append("Supported, bounded ELF PT_LOAD program headers")
            images_out.append(report)
            continue
        # Reject a race between the hashed ELF observation and header parsing.
        verified_sha, verified_size = _digest(elf_path)
        if (verified_sha, verified_size) != (elf_sha, elf_size):
            raise InputError("ELF changed after program-header inspection")
        report["provenance"]["elf"] = {"node_id": elf_id, "sha256": elf_sha, "bytes": elf_size}
        counts = {key: 0 for key in (
            "file-backed-vma", "zero-fill-vma", "mixed-file-and-zero-fill-vma",
            "uncovered", "ambiguous", "invalid")}
        for address, size, _ in rows:
            counts[coverage(segments, address, size)] += 1
        report["elf_ranges"] = {"load_segments": len(segments), "section_ranges": counts,
                                 "coverage_type": "virtual-address PT_LOAD containment only"}
        # Parse archive identities as a separate independent claim. Never
        # equate a basename-matched member with a CMake compilation output.
        references = sorted({archive for _, _, archive in rows if archive is not None})
        if len(references) > MAX_ARCHIVE_REFS:
            raise InputError("Too many distinct GNU archive-member references")
        archive_counts = {status: 0 for status in (
            "exact-archive-member-observed", "missing-member", "ambiguous-member",
            "unsupported-thin-archive", "unsupported-archive-format",
            "unavailable-archive", "inspection-budget-exceeded")}
        digests = []
        archive_io = 0
        for archive_name, member_name in references:
            file = _archive_path(directory, archive_name)
            if file is None:
                archive_counts["unavailable-archive"] += 1
                continue
            # Bound aggregate worst-case I/O across independently referenced
            # archive members. Some generated maps contain thousands of them.
            # Stop inspecting when budget is exhausted; never infer success.
            archive_size = file.stat().st_size
            cost = archive_size * 2  # scan/member read + optional digest
            if archive_io + cost > MAX_TOTAL_ARCHIVE_IO:
                archive_counts["inspection-budget-exceeded"] += 1
                continue
            archive_io += cost
            result = observe_member(file, member_name)
            archive_counts[result["status"]] += 1
            if result["status"] == "exact-archive-member-observed":
                if len(digests) < MAX_DIGEST_EXPORT:
                    sha, _ = _digest(file)
                    digests.append({"archive_sha256": sha,
                                    "member_sha256": result["member_sha256"],
                                    "member_bytes": result["member_bytes"]})
        report["archive"] = {"referenced_unique_members": len(references),
                              "status_counts": archive_counts,
                              "member_hash_evidence": digests,
                              "hash_evidence_limit": MAX_DIGEST_EXPORT,
                              "inspection_io_budget_bytes": MAX_TOTAL_ARCHIVE_IO}
        if counts["uncovered"] or counts["ambiguous"] or counts["invalid"]:
            report["status"] = "elf-range-conflict-or-unknown"
        elif rows:
            report["status"] = "map-elf-vma-ranges-consistent"
            report["confidence"] = "derived"
        else:
            report["status"] = "no-nonzero-map-input-sections"
        images_out.append(report)
    return {
        "schema_version": SCHEMA_VERSION,
        "status": "gnu-map-archive-elf-range-audit",
        "images": images_out,
        "limitations": [
            "ELF PT_LOAD VMA range containment does not verify map source membership or section bytes.",
            "LMA/physical addresses, linker relocations, overlay sections and vendor maps are not interpreted.",
            "GNU archive-member bytes are observed only for exact unambiguous regular archive identities.",
            "Archive-inspection I/O is bounded; budget-exceeded references remain unknown.",
            "A recognised map input section does not prove object or archive contents were linked into the ELF.",
            "Neither matched ELF/map hashes nor archive member checks prove source freshness or device flashing.",
            "No section names, host paths, archive names, symbols or raw evidence contents are exported.",
        ],
    }
