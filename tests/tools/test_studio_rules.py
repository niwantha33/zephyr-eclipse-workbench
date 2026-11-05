# SPDX-License-Identifier: Apache-2.0
import copy
import unittest

from zew_evidence.collector import InputError
from zew_evidence.studio_rules import normalize, canonical_hash


class StudioRulesTests(unittest.TestCase):
    def report(self):
        return {"schema_version": "fixture/1", "status": "evaluated", "findings": [
            {"rule_id": "CS-KCONFIG-UNDEFINED", "image": "app", "symbol": "CONFIG_X",
             "message": "Undefined in selected tree", "severity": "warning", "confidence": "derived",
             "evidence": [{"path": "prj.conf", "sha256": "a" * 64, "line": 2}],
             "next_action": "Check upstream symbol definitions."}], "limitations": ["Fixture only"]}

    def test_order_and_dedup_are_deterministic(self):
        a = self.report()
        b = copy.deepcopy(a)
        b["findings"][0]["symbol"] = "CONFIG_Y"
        self.assertEqual(normalize([a, b]), normalize([b, a]))
        self.assertEqual(len(normalize([a, a])["findings"]), 1)
        self.assertEqual(canonical_hash(a), canonical_hash(dict(reversed(list(a.items())))))

    def test_same_id_with_different_condition_rejected(self):
        a, b = self.report(), self.report()
        b["findings"][0]["message"] = "Conflicting result"
        with self.assertRaises(InputError):
            normalize([a, b])

    def test_missing_evidence_cannot_be_confirmed(self):
        a = self.report()
        a["findings"][0].update(confidence="confirmed", evidence=[])
        self.assertEqual(normalize([a])["findings"][0]["confidence"], "unknown")

    def test_unsafe_sources_enums_and_ids_are_rejected(self):
        changes = [lambda f: f.update(rule_id="invalid rule"),
                   lambda f: f.update(severity="fatal"),
                   lambda f: f["evidence"][0].update(sha256="not-a-digest"),
                   lambda f: f["evidence"][0].update(path="bad\npath"),
                   lambda f: f["evidence"][0].update(line=True)]
        for change in changes:
            report = self.report()
            change(report["findings"][0])
            with self.subTest(change=change), self.assertRaises(InputError):
                normalize([report])

    def test_malformed_json_types_and_control_characters_fail_closed(self):
        for key, value in (("confidence", []), ("severity", {}),
                           ("image", "app\nforged"), ("symbol", "node\x1b[31m"),
                           ("message", "message\x00hidden"), ("next_action", "action\x7f")):
            report = self.report()
            report["findings"][0][key] = value
            with self.subTest(key=key), self.assertRaises(InputError):
                normalize([report])
        for key, value in (("limitations", "not-an-array"), ("limitations", {}),
                           ("schema_version", []), ("status", {})):
            report = self.report()
            report[key] = value
            with self.subTest(key=key), self.assertRaises(InputError):
                normalize([report])
        report = self.report()
        report["findings"][0]["evidence"][0]["kind"] = []
        with self.assertRaises(InputError):
            normalize([report])
        with self.assertRaises(InputError):
            normalize([{"findings": [], "unserializable": set()}])

    def test_empty_is_limited_coverage_not_pass(self):
        report = normalize([])
        self.assertEqual(report["status"], "no-findings-in-supplied-evidence")
        self.assertEqual(report["freshness"], "unverified")


if __name__ == "__main__":
    unittest.main()
