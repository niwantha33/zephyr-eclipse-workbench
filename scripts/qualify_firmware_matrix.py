#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Qualify *observed* firmware evidence separately from successful compilation.

This is a deterministic, read-only CI acceptance floor, not a proof that
source bytes reached the ELF or hardware. Unknown map/ELF coverage stays open.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re

EXPECTED = {
    "ti": "lp_mspm0g3507/mspm0g3507",
    "stm32": "nucleo_f446re/stm32f446xx",
    "nordic": "nrf52840dk/nrf52840",
    "adc-nrf": "nrf52840dk/nrf52840",
    "xtensa": "esp32s3_devkitc/esp32s3/procpu",
    "riscv": "esp32c3_devkitm/esp32c3",
    "mcuboot": "nrf52840dk/nrf52840",
}
REQUIRED_DIGESTS = ("zephyr/.config", "compile_commands.json",
                    "zephyr/zephyr.map", "zephyr/zephyr.elf")
SHA = re.compile(r"[a-f0-9]{64}\Z")
RANGE_STATUSES = {"map-elf-vma-ranges-consistent", "elf-range-conflict-or-unknown"}
SECTION_STATUSES = {"map-elf-output-layout-observed",
                    "map-elf-section-disagreement-or-unknown"}
RANGE_BUCKETS = ("file-backed-vma", "zero-fill-vma", "mixed-file-and-zero-fill-vma",
                 "uncovered", "ambiguous", "invalid")
SECTION_BUCKETS = ("exact-layout", "address-mismatch", "size-mismatch",
                   "missing-or-unallocated", "ambiguous-map-name", "ambiguous-elf-name")
FATAL_SECTION_BUCKETS = ("address-mismatch", "size-mismatch",
                         "ambiguous-map-name", "ambiguous-elf-name")
CLAIM = "Generated build artifact inspection only; no runtime or flashed-device verification"


class QualificationError(ValueError):
    """Fail closed without embedding private CI paths or raw artifact contents."""


def _number(record: dict, key: str, scenario: str) -> int:
    value = record.get(key)
    if type(value) is not int or not 0 <= value <= 10_000_000:
        raise QualificationError(f"{scenario}: missing or invalid {key} observation")
    return value


def _record(data: object, scenario: str, key: str) -> dict:
    if not isinstance(data, dict) or not isinstance(data.get(key), dict):
        raise QualificationError(f"{scenario}: missing {key} report")
    return data[key]


def qualify(results: object) -> dict:
    if not isinstance(results, list) or len(results) != len(EXPECTED):
        raise QualificationError("Seven complete, distinct real firmware scenarios required")
    by_scenario: dict[str, dict] = {}
    for raw in results:
        if not isinstance(raw, dict):
            raise QualificationError("Invalid firmware scenario report")
        scenario = raw.get("scenario")
        if not isinstance(scenario, str) or scenario not in EXPECTED or scenario in by_scenario:
            raise QualificationError("Unknown or duplicate firmware scenario")
        by_scenario[scenario] = raw
    if set(by_scenario) != set(EXPECTED):
        raise QualificationError("Missing mandatory firmware scenario")

    observations = []
    unresolved_scenarios = 0
    total_uncovered = 0
    total_unallocated = 0
    for scenario, target in EXPECTED.items():
        raw = by_scenario[scenario]
        if raw.get("target") != target:
            raise QualificationError(f"{scenario}: exact pinned board/core target changed")
        if raw.get("firmware_build_verified") is not True or raw.get("editor_metadata_verified") is not True:
            raise QualificationError(f"{scenario}: real build or editor verification incomplete")
        evidence = _record(raw, scenario, "evidence_engine")
        if evidence.get("claim_limit") != CLAIM:
            raise QualificationError(f"{scenario}: evidence boundaries changed")
        digests = _record(evidence, scenario, "digests")
        if any(not isinstance(digests.get(key), str)
               or not SHA.fullmatch(digests[key]) for key in REQUIRED_DIGESTS):
            raise QualificationError(f"{scenario}: missing or malformed graph artifact fingerprint")
        compiled = _record(evidence, scenario, "compilation")
        if (compiled.get("status") != "compile-command-observed"
                or _number(compiled, "matching_commands", scenario) != 1):
            raise QualificationError(f"{scenario}: exact CMake command evidence is not unique")
        link = _record(evidence, scenario, "link_range")
        if link.get("status") not in RANGE_STATUSES:
            raise QualificationError(f"{scenario}: missing or unsupported map/ELF range evidence")
        range_count = _number(link, "map_sections", scenario)
        if range_count == 0 or _number(link, "archive_references", scenario) == 0:
            raise QualificationError(f"{scenario}: real map section/archive coverage absent")
        ranges = _record(link, scenario, "elf_ranges")
        if ranges.get("coverage_type") != "virtual-address PT_LOAD containment only":
            raise QualificationError(f"{scenario}: invalid ELF range interpretation")
        if _number(ranges, "load_segments", scenario) == 0:
            raise QualificationError(f"{scenario}: missing ELF load segments")
        buckets = _record(ranges, scenario, "section_ranges")
        count = {key: _number(buckets, key, scenario) for key in RANGE_BUCKETS}
        if sum(count.values()) != range_count or count["file-backed-vma"] == 0:
            raise QualificationError(f"{scenario}: map range accounting inconsistent or no file-backed VMA")
        if bool(count["uncovered"] or count["ambiguous"] or count["invalid"]) != (
                link["status"] == "elf-range-conflict-or-unknown"):
            raise QualificationError(f"{scenario}: map range status obscures unresolved evidence")

        sections = _record(evidence, scenario, "elf_sections")
        if sections.get("status") not in SECTION_STATUSES:
            raise QualificationError(f"{scenario}: unsupported ELF output-section evidence")
        output_count = _number(sections, "map_output_sections", scenario)
        counts = _record(sections, scenario, "comparison")
        section_counts = {key: _number(counts, key, scenario) for key in SECTION_BUCKETS}
        if (output_count == 0 or sum(section_counts.values()) != output_count
                or section_counts["exact-layout"] == 0
                or _number(counts, "elf-section-byte-hashes", scenario) == 0):
            raise QualificationError(f"{scenario}: exact ELF output-section/byte evidence absent")
        if any(section_counts[key] > 0 for key in FATAL_SECTION_BUCKETS):
            raise QualificationError(f"{scenario}: actual address/size mismatch or ambiguous identity needs review")
        unresolved = (count["uncovered"] + count["ambiguous"] + count["invalid"]
                      + section_counts["missing-or-unallocated"])
        partial = unresolved > 0
        if partial != (sections["status"] == "map-elf-section-disagreement-or-unknown"
                       or link["status"] == "elf-range-conflict-or-unknown"):
            raise QualificationError(f"{scenario}: incomplete coverage was incorrectly labelled")
        unresolved_scenarios += int(partial)
        total_uncovered += count["uncovered"]
        total_unallocated += section_counts["missing-or-unallocated"]
        observations.append({
            "scenario": scenario, "target": target,
            "compiler_command_observation": "confirmed-in-database-only",
            "map_range_status": link["status"],
            "elf_section_status": sections["status"],
            "layout_coverage": "partial-unknown" if partial else "supported-records-consistent",
            "uncovered_map_input_ranges": count["uncovered"],
            "map_output_sections_without_allocated_elf_match": section_counts["missing-or-unallocated"],
            "file_backed_map_ranges": count["file-backed-vma"],
            "exact_elf_output_section_layouts": section_counts["exact-layout"],
            "sha256": {"map": digests["zephyr/zephyr.map"],
                       "elf": digests["zephyr/zephyr.elf"]},
            "source_to_final_elf_causality": "not-established",
            "flashed_hardware": "not-observed",
        })

    return {
        "schema_version": "0.1.0",
        "gate": "minimum-real-build-evidence-contract-passed",
        "full_evidence_coverage": False,
        "coverage_status": "partial-open" if unresolved_scenarios else "limited-to-supported-metadata",
        "scenario_count": len(observations),
        "unresolved_scenario_count": unresolved_scenarios,
        "uncovered_map_input_ranges": total_uncovered,
        "map_output_sections_without_allocated_elf_match": total_unallocated,
        "observations": observations,
        "limitations": [
            "CI passing proves the defined minimum observation contract, not complete coverage.",
            "Uncovered map input rows may represent non-loadable or non-allocated data; not firmware defects.",
            "A map output name without an allocated ELF section is unresolved, not proven linker failure.",
            "Source compilation command presence is not source-to-binary causality.",
            "No device, flash, probe, execution or physical measurement is verified.",
        ],
    }


def _unique_pairs(pairs):
    data = {}
    for key, value in pairs:
        if key in data:
            raise QualificationError("Duplicate qualification JSON field")
        data[key] = value
    return data


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    if args.output.exists() or args.output.is_symlink():
        raise QualificationError("Refusing to overwrite an existing qualification report")
    if args.input.is_symlink() or not args.input.is_file():
        raise QualificationError("Missing or symlinked matrix evidence input")
    if args.input.stat().st_size > 8 * 1024 * 1024:
        raise QualificationError("Matrix evidence exceeds 8 MiB bound")
    try:
        items = json.loads(args.input.read_text(encoding="utf-8"), object_pairs_hook=_unique_pairs)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise QualificationError("Invalid matrix evidence JSON") from error
    result = qualify(items)
    wire = json.dumps(result, ensure_ascii=True, sort_keys=True, indent=2) + "\n"
    # Output only the derived, allowlisted qualification facts.
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8", newline="\n") as output:
        output.write(wire)
    print(f"PASS: {result['scenario_count']} real-build minimum evidence contracts; "
          f"{result['unresolved_scenario_count']} with explicitly open map/ELF coverage")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
