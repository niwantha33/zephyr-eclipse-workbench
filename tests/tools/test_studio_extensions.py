# SPDX-License-Identifier: Apache-2.0
import copy
import json
from pathlib import Path
import tempfile
import unittest

from zew_evidence.collector import InputError
from zew_evidence.studio_extensions import load, evaluate


class StudioExtensionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "fixture-rulepack.json"
        self.rule = {"id": "FIXTURE-DISABLED-CONTROLLER", "title": "Fixture controller status",
            "when": [{"field": "status", "equals": "disabled"}],
            "required_evidence": ["generated-dts"], "severity": "warning", "confidence": "derived",
            "message": "Recorded controller disabled.", "next_action": "Review selected DTS source.",
            "documentation": "https://docs.zephyrproject.org/latest/build/dts/intro-syntax-structure.html",
            "scope": {"zephyr_min": "4.4.0", "zephyr_max_exclusive": "5.0.0"},
            "fixtures": {"passing": {"status": "okay"}, "failing": {"status": "disabled"}}}
        self.pack = {"schema_version": "zew.studio-rulepack/1", "pack_id": "fixture.dt",
                     "version": "1.0.0", "rules": [self.rule]}
        self.observation = {"id": "node-1", "image": "app", "subject": "/soc/adc@1",
            "facts": {"status": "disabled"},
            "evidence": [{"kind": "generated-dts", "path": "zephyr/zephyr.dts", "sha256": "a" * 64}]}

    def save(self):
        self.path.write_text(json.dumps(self.pack))

    def test_scoped_positive_negative_and_determinism(self):
        self.save()
        result = evaluate([self.path], [self.observation], "4.4.0")
        self.assertEqual(len(result["findings"]), 1)
        self.assertEqual(result, evaluate([self.path], [self.observation], "4.4.0"))
        self.observation["facts"]["status"] = "okay"
        self.assertEqual(evaluate([self.path], [self.observation], "4.4.0")["findings"], [])

    def test_missing_evidence_unknown_version_and_outside_version(self):
        self.save()
        for version, reason in [(None, "zephyr-version-unknown"), ("5.0.0", "outside-zephyr-version-scope")]:
            result = evaluate([self.path], [self.observation], version)
            self.assertFalse(result["findings"])
            self.assertEqual(result["skipped"][0]["reason"], reason)
        self.observation["evidence"] = []
        self.assertEqual(evaluate([self.path], [self.observation], "4.4.0")["skipped"][0]["reason"], "required-evidence-missing")

    def test_fixture_gate_and_confirmed_escalation_rejected(self):
        for key, value in [("confidence", "confirmed"), ("when", [{"field": "status", "equals": "okay"}]), ("fixtures", {})]:
            original = copy.deepcopy(self.rule)
            self.rule[key] = value
            self.save()
            with self.assertRaises(InputError):
                load(self.path)
            self.rule.clear()
            self.rule.update(original)

    def test_malformed_observation_fields_and_kinds_fail_closed(self):
        self.save()
        for key, value in (("id", []), ("image", "app\nforged"), ("subject", {})):
            original = self.observation[key]
            self.observation[key] = value
            with self.subTest(key=key), self.assertRaises(InputError):
                evaluate([self.path], [self.observation], "4.4.0")
            self.observation[key] = original
        self.observation["evidence"][0]["kind"] = []
        with self.assertRaises(InputError):
            evaluate([self.path], [self.observation], "4.4.0")

    def test_boolean_predicate_does_not_match_integer_fact(self):
        self.rule["when"] = [{"field": "active", "equals": True}]
        self.rule["fixtures"] = {"passing": {"active": False}, "failing": {"active": True}}
        self.observation["facts"] = {"active": 1}
        self.save()
        self.assertFalse(evaluate([self.path], [self.observation], "4.4.0")["findings"])
        self.observation["facts"] = {"active": True}
        self.assertEqual(len(evaluate([self.path], [self.observation], "4.4.0")["findings"]), 1)

    def test_code_unknown_schema_duplicate_keys_and_symlinks_rejected(self):
        self.save()
        self.pack["rules"][0]["execute"] = "do not execute"
        self.save()
        with self.assertRaises(InputError):
            load(self.path)
        self.path.write_text('{"schema_version":"x","schema_version":"y"}')
        with self.assertRaises(InputError):
            load(self.path)
        link = self.path.with_name("linked.json")
        try:
            link.symlink_to(self.path)
        except OSError:
            return
        with self.assertRaises(InputError):
            load(link)


if __name__ == "__main__":
    unittest.main()
