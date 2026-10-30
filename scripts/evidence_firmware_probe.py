#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read-only CI acceptance probe for *real* generated Zephyr build artifacts.

No build, flash, device access, source modification or background monitoring.
A generated ELF/map observation does not prove the target was programmed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))

from zew_evidence.collector import collect
from zew_evidence.compilation import inspect as compilation
from zew_evidence.link_audit import audit as range_audit
from zew_evidence.section_audit import audit as section_audit

ARTIFACTS = ("zephyr/.config", "compile_commands.json", "zephyr/zephyr.map",
             "zephyr/zephyr.elf")
ALLOWED_RANGE = {"map-elf-vma-ranges-consistent", "elf-range-conflict-or-unknown",
                 "no-nonzero-map-input-sections", "unsupported-map-format",
                 "unsupported-or-invalid-elf"}
ALLOWED_SECTION = {"map-elf-output-layout-observed",
                   "map-elf-section-disagreement-or-unknown",
                   "no-supported-map-output-section", "unsupported-map-format",
                   "unsupported-or-invalid-elf-sections"}


def fingerprints(build: Path) -> dict:
    out = {}
    for name in ARTIFACTS:
        path = build / name
        if not path.is_file() or path.is_symlink():
            raise ValueError("Missing mandatory generated build artifact: " + name)
        out[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    return out


def validate(build: Path, source_root: Path) -> dict:
    build = Path(build).resolve(strict=True)
    source_root = Path(source_root).resolve(strict=True)
    before = fingerprints(build)
    graph = collect(build)
    compiled = compilation(build, "src/main.c", source_root, graph=graph)["images"][0]
    linked = range_audit(build, graph=graph)["images"][0]
    sections = section_audit(build, graph=graph)["images"][0]
    after = fingerprints(build)
    if before != after:
        raise ValueError("Evidence inspection unexpectedly changed build artifact bytes")
    for kind, relative in (("compiler_database", "compile_commands.json"),
                           ("map", "zephyr/zephyr.map"), ("elf", "zephyr/zephyr.elf")):
        node = next((n for n in graph["nodes"] if n["id"] == f"artifact:default:{kind}"), None)
        if node is None or node["provenance"]["sha256"] != before[relative]:
            raise ValueError("Evidence Graph artifact digest does not match real generated file")
    if compiled["status"] not in {"compile-command-observed", "no-matching-command-observed"}:
        raise ValueError("Unexpected source compilation database observation")
    if linked["status"] not in ALLOWED_RANGE or sections["status"] not in ALLOWED_SECTION:
        raise ValueError("Real build analyzer returned unexpected status")
    if linked["provenance"]["map"] is None:
        raise ValueError("Real linker-map analysis lost its provenance")
    if sections["provenance"]["elf"] is None:
        raise ValueError("Real ELF section analysis lost its provenance")
    output = {
        "compilation": {"status": compiled["status"],
                        "matching_commands": compiled["matching_commands"]},
        "link_range": {"status": linked["status"],
                       "map_sections": linked["map_sections"],
                       "elf_ranges": linked["elf_ranges"],
                       "archive_references": (linked["archive"] or {}).get("referenced_unique_members", 0)},
        "elf_sections": {"status": sections["status"],
                         "map_output_sections": sections["map_output_sections"],
                         "comparison": sections["comparison"]},
        "digests": {key: before[key] for key in ARTIFACTS},
        "claim_limit": "Generated build artifact inspection only; no runtime or flashed-device verification",
    }
    wire = json.dumps(output, sort_keys=True, ensure_ascii=True)
    if str(source_root) in wire or str(build) in wire or "\\Users\\" in wire:
        raise ValueError("Host path leaked into evidence summary")
    return output


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", type=Path, required=True)
    parser.add_argument("--application", type=Path, required=True)
    args = parser.parse_args(argv)
    first = validate(args.build, args.application)
    second = validate(args.build, args.application)
    if first != second:
        raise ValueError("Evidence engine returned non-deterministic results")
    print(json.dumps(first, sort_keys=True, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
