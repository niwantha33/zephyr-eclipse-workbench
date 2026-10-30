# SPDX-License-Identifier: Apache-2.0
"""Minimum CI gate on captured real-firmware shape; unresolved means unresolved."""
import contextlib
import copy
import io
import json
from pathlib import Path
import tempfile
import unittest

from scripts.qualify_firmware_matrix import CLAIM, EXPECTED, QualificationError, main, qualify

ROOT = Path(__file__).resolve().parents[2]
BASELINE = ROOT / "docs/evidence/real_firmware_baseline_20261030.json"
KEYS = ("zephyr/.config", "compile_commands.json", "zephyr/zephyr.map",
        "zephyr/zephyr.elf")


def sample_matrix():
    measured = json.loads(BASELINE.read_text(encoding="utf-8"))["observations"]
    records = []
    for item in measured:
        names = item["map_output_sections"]
        exact, unresolved = (item["exact_allocated_elf_sections"],
                             item["output_names_without_allocated_elf_match"])
        mapped, covered, unknown = (item["map_input_sections"],
                                   item["file_backed_map_inputs"],
                                   item["uncovered_map_inputs"])
        assert exact + unresolved == names
        assert mapped >= covered + unknown
        output = {
            "scenario": item["scenario"], "target": item["target"],
            "firmware_build_verified": True, "editor_metadata_verified": True,
            "evidence_engine": {
                "claim_limit": CLAIM,
                "compilation": {"status": "compile-command-observed", "matching_commands": 1},
                "digests": {key: "a" * 64 for key in KEYS},
                "link_range": {
                    "status": item["link_status"], "map_sections": mapped,
                    "archive_references": 12,
                    "elf_ranges": {
                        "coverage_type": "virtual-address PT_LOAD containment only",
                        "load_segments": 3,
                        "section_ranges": {
                            "file-backed-vma": covered,
                            "zero-fill-vma": mapped - covered - unknown,
                            "mixed-file-and-zero-fill-vma": 0,
                            "uncovered": unknown, "ambiguous": 0, "invalid": 0,
                        },
                    },
                },
                "elf_sections": {
                    "status": item["elf_section_status"],
                    "map_output_sections": names,
                    "comparison": {
                        "exact-layout": exact, "missing-or-unallocated": unresolved,
                        "address-mismatch": 0, "size-mismatch": 0,
                        "ambiguous-map-name": 0, "ambiguous-elf-name": 0,
                        "elf-section-byte-hashes": 1,
                        "nonallocated-exact-metadata": 0,
                        "unexplained-map-outputs": unresolved,
                        "uncovered-vma": 0, "ambiguous-vma": 0,
                        "other-vma": 0,
                    },
                },
            },
        }
        records.append(output)
    return records


class QualifyRealFirmwareMatrixTests(unittest.TestCase):
    def test_recorded_baseline_is_seven_complete_real_build_observations(self):
        baseline = json.loads(BASELINE.read_text(encoding="utf-8"))
        self.assertEqual(baseline["source_sha"], "85f5d5b6201bdb6cca39bbf47db3d435b0142527")
        self.assertEqual(len(baseline["observations"]), 7)
        self.assertEqual(set(x["scenario"] for x in baseline["observations"]), set(EXPECTED))
        self.assertTrue(all(x["uncovered_map_inputs"] > 0 for x in baseline["observations"]))
        self.assertTrue(all(x["output_names_without_allocated_elf_match"] > 0
                            for x in baseline["observations"]))

    def test_all_seven_are_qualifying_partial_not_fully_verified(self):
        source = sample_matrix()
        result = qualify(source)
        self.assertEqual(result["gate"], "minimum-real-build-evidence-contract-passed")
        self.assertEqual(result["scenario_count"], 7)
        self.assertEqual(result["unresolved_scenario_count"], 7)
        self.assertEqual(result["coverage_status"], "partial-open")
        self.assertIs(result["full_evidence_coverage"], False)
        self.assertTrue(all(x["layout_coverage"] == "partial-unknown"
                            and x["source_to_final_elf_causality"] == "not-established"
                            and x["flashed_hardware"] == "not-observed"
                            for x in result["observations"]))
        self.assertEqual(result, qualify(list(reversed(source))))
        source[0]["evidence_engine"]["secret_host_file"] = "C:/Private/credentials.txt"
        text = json.dumps(qualify(source))
        self.assertNotIn("credentials", text)
        self.assertNotIn("C:/Private", text)

    def test_exactly_seven_pinned_targets_are_required(self):
        cases = sample_matrix()
        for change in ("missing", "duplicate", "unknown", "wrong-target", "not-built"):
            with self.subTest(change=change):
                data = copy.deepcopy(cases)
                if change == "missing":
                    data.pop()
                elif change == "duplicate":
                    data[0] = data[1]
                elif change == "unknown":
                    data[0]["scenario"] = "unknown-vendor"
                elif change == "wrong-target":
                    data[0]["target"] = "lp_mspm0g3507/wrongcore"
                else:
                    data[0]["firmware_build_verified"] = False
                with self.assertRaises(QualificationError):
                    qualify(data)

    def test_missing_source_evidence_or_bad_provenance_is_not_qualification(self):
        data = sample_matrix()
        for key, value in [
            ("status", "no-matching-command-observed"),
            ("matching_commands", 0), ("matching_commands", 2)]:
            invalid = copy.deepcopy(data)
            invalid[0]["evidence_engine"]["compilation"][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(QualificationError):
                qualify(invalid)
        for invalid_digest in ("invalid", "A" * 64, None, "b" * 63):
            invalid = copy.deepcopy(data)
            invalid[0]["evidence_engine"]["digests"][KEYS[-1]] = invalid_digest
            with self.subTest(digest=invalid_digest), self.assertRaises(QualificationError):
                qualify(invalid)

    def test_unsupported_linker_or_missing_validated_sections_fails(self):
        data = sample_matrix()
        for key, value in (
            ("link_range.status", "unsupported-map-format"),
            ("link_range.map_sections", 0),
            ("link_range.archive_references", 0),
            ("elf_sections.status", "unsupported-or-invalid-elf-sections"),
            ("elf_sections.comparison.exact-layout", 0),
            ("elf_sections.comparison.elf-section-byte-hashes", 0),
        ):
            value_copy = copy.deepcopy(data)
            field = value_copy[0]["evidence_engine"]
            parts = key.split(".")
            for part in parts[:-1]:
                field = field[part]
            field[parts[-1]] = value
            with self.subTest(key=key), self.assertRaises(QualificationError):
                qualify(value_copy)

    def test_uncovered_range_must_not_be_labelled_consistent(self):
        data = sample_matrix()
        data[0]["evidence_engine"]["link_range"]["status"] = "map-elf-vma-ranges-consistent"
        with self.assertRaisesRegex(QualificationError, "obscures"):
            qualify(data)

    def test_actual_layout_conflicts_are_not_silently_green(self):
        for bucket in ("address-mismatch", "size-mismatch", "ambiguous-map-name",
                       "ambiguous-elf-name"):
            data = sample_matrix()
            summary = data[0]["evidence_engine"]["elf_sections"]["comparison"]
            summary["missing-or-unallocated"] -= 1
            summary["unexplained-map-outputs"] -= 1
            summary[bucket] = 1
            with self.subTest(bucket=bucket), self.assertRaises(QualificationError):
                qualify(data)

    def test_agreement_in_supported_fields_never_proves_source_or_hardware(self):
        data = sample_matrix()
        for item in data:
            evidence = item["evidence_engine"]
            e = evidence["link_range"]
            counts = e["elf_ranges"]["section_ranges"]
            counts["file-backed-vma"] += counts["uncovered"]
            counts["uncovered"] = 0
            e["status"] = "map-elf-vma-ranges-consistent"
            section = evidence["elf_sections"]
            summary = section["comparison"]
            summary["exact-layout"] += summary["missing-or-unallocated"]
            summary["missing-or-unallocated"] = 0
            summary["unexplained-map-outputs"] = 0
            section["status"] = "map-elf-output-layout-observed"
        result = qualify(data)
        self.assertEqual(result["unresolved_scenario_count"], 0)
        self.assertEqual(result["coverage_status"], "limited-to-supported-metadata")
        self.assertFalse(result["full_evidence_coverage"])
        self.assertEqual(result["observations"][0]["flashed_hardware"], "not-observed")

    def test_independent_real_vendor_run_confirms_all_prior_nonallocated_metadata(self):
        evidence = json.loads((ROOT / "docs/evidence/nonallocated_elf_observations_20261030.json")
                              .read_text(encoding="utf-8"))
        self.assertEqual(evidence["source_head_sha"], "7c5d5c7e2a97e92984cfd458b80171928afff6f3")
        actual = evidence["observations"]
        self.assertEqual({item["scenario"] for item in actual}, set(EXPECTED))
        self.assertEqual(sum(item["exact_nonallocated_elf_metadata"] for item in actual), 66)
        self.assertTrue(all(
            item["previous_missing_or_unallocated"] == item["exact_nonallocated_elf_metadata"]
            and item["exact_allocated_elf_output"] > 0 for item in actual))

    def test_measured_nonallocated_elf_metadata_reconciliation_keeps_ranges_unknown(self):
        cases = sample_matrix()
        baseline = json.loads(BASELINE.read_text(encoding="utf-8"))["observations"]
        by_scenario = {row["scenario"]: row for row in baseline}
        for case in cases:
            scenario = case["scenario"]
            measured = by_scenario[scenario]["output_names_without_allocated_elf_match"]
            section = case["evidence_engine"]["elf_sections"]
            counts = section["comparison"]
            counts["nonallocated-exact-metadata"] = measured
            counts["unexplained-map-outputs"] = 0
            section["status"] = "map-elf-output-layout-observed"
        qualified = qualify(cases)
        self.assertEqual(qualified["unexplained_map_output_sections"], 0)
        self.assertEqual(qualified["nonallocated_elf_output_metadata_matched"], 66)
        self.assertEqual(qualified["unresolved_scenario_count"], 7)  # PT_LOAD map rows still unresolved.
        self.assertIs(qualified["full_evidence_coverage"], False)
        for row in qualified["observations"]:
            self.assertEqual(row["unexplained_map_output_sections"], 0)
            self.assertEqual(row["layout_coverage"], "partial-unknown")
            self.assertEqual(row["source_to_final_elf_causality"], "not-established")

    def test_false_reconciliation_and_status_mismatch_fail_closed(self):
        for mutation in ("too-many", "no-explanation", "wrong-status"):
            cases = sample_matrix()
            section = cases[0]["evidence_engine"]["elf_sections"]
            counts = section["comparison"]
            if mutation == "too-many":
                counts["nonallocated-exact-metadata"] = counts["missing-or-unallocated"] + 1
            elif mutation == "no-explanation":
                counts["nonallocated-exact-metadata"] = 1
            else:
                counts["unexplained-map-outputs"] = 0
                counts["nonallocated-exact-metadata"] = counts["missing-or-unallocated"]
                section["status"] = "map-elf-section-disagreement-or-unknown"
            with self.subTest(mutation=mutation), self.assertRaises(QualificationError):
                qualify(cases)

    def test_cli_emits_sanitized_deterministic_summary_without_overwriting(self):
        with tempfile.TemporaryDirectory(prefix="zew-qual-Ω ") as temp:
            root = Path(temp)
            raw = root / "matrix.json"
            output = root / "qualification.json"
            raw.write_text(json.dumps(sample_matrix()), encoding="utf-8")
            stream = io.StringIO()
            with contextlib.redirect_stdout(stream):
                self.assertEqual(main(["--input", str(raw), "--output", str(output)]), 0)
            self.assertIn("7 real-build", stream.getvalue())
            data = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(data, qualify(sample_matrix()))
            before = output.read_bytes()
            with self.assertRaisesRegex(QualificationError, "overwrite"):
                main(["--input", str(raw), "--output", str(output)])
            self.assertEqual(output.read_bytes(), before)

    def test_partial_or_invalid_matrix_writes_no_report(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            raw, report = root / "matrix.json", root / "qualification.json"
            raw.write_text(json.dumps(sample_matrix()[:-1]), encoding="utf-8")
            with self.assertRaises(QualificationError):
                main(["--input", str(raw), "--output", str(report)])
            self.assertFalse(report.exists())


if __name__ == "__main__":
    unittest.main()
