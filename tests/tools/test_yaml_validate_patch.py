# SPDX-License-Identifier: Apache-2.0
"""Step 11 in-memory candidate parser contract: no source writes or unsafe YAML."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "plugins/io.github.niwantha33.zew.ui/scripts/yaml_validate_patch.py"


def observe(value: bytes, *, relative="tests/demo/sample.yaml"):
    process = subprocess.run([sys.executable, str(SCRIPT), "--relative", relative,
                              "--source-root", "application"],
                             input=value, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, timeout=15, check=False)
    return process.returncode, json.loads(process.stdout.decode("utf-8")), process.stderr


class SafeYamlEditCandidateTests(unittest.TestCase):
    def test_can_validate_candidate_without_accessing_any_original_file(self):
        source = b'compatible: "demo,device"\nproperties:\n  enabled: true\n'
        code, evidence, errors = observe(source)
        self.assertEqual(code, 0, errors)
        self.assertEqual(evidence["schema"], "zew.yaml-observation/1")
        self.assertEqual(evidence["status"], "parsed")
        self.assertEqual(evidence["provenance"]["sha256"], hashlib.sha256(source).hexdigest())
        self.assertEqual(evidence["provenance"]["size_bytes"], len(source))
        self.assertEqual(evidence["provenance"]["source_root"], "application")

    def test_invalid_yaml_returns_invalid_not_success(self):
        _, evidence, _ = observe(b"tests: [unclosed\n")
        self.assertEqual(evidence["status"], "invalid")
        self.assertEqual(evidence["reason"], "yaml-syntax-invalid")

    def test_duplicate_keys_rejected_and_secret_never_in_stderr(self):
        _, evidence, errors = observe(b"one: SECRET\none: PRIVATE\n")
        self.assertEqual(evidence["status"], "invalid")
        self.assertEqual(evidence["reason"], "duplicate-mapping-key")
        self.assertNotIn(b"SECRET", errors)

    def test_anchor_alias_and_unsafe_tag_rejected(self):
        for source in (b"a: &p {x: y}\nb: *p\n", b"a: !!python/object/apply:os.system ['echo hi']"):
            with self.subTest(source=source):
                _, evidence, _ = observe(source)
                self.assertNotEqual(evidence["status"], "parsed")

    def test_oversized_candidate_rejected(self):
        _, evidence, _ = observe(b"x" * (256 * 1024 + 1))
        self.assertEqual(evidence["status"], "invalid")
        self.assertEqual(evidence["reason"], "maximum-source-size-exceeded")

    def test_unsafe_relative_path_does_not_become_parsed(self):
        _, evidence, _ = observe(b"safe: true\n", relative="../outside.yaml")
        self.assertEqual(evidence["status"], "unsupported")
        self.assertEqual(evidence["reason"], "not-a-safe-root-relative-path")


if __name__ == "__main__":
    unittest.main()
