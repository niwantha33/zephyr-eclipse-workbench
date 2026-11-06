# SPDX-License-Identifier: Apache-2.0
"""Step 6: packaged CLI source contract and safe root/file identity checks."""

import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "plugins/io.github.niwantha33.zew.ui/scripts"
SCRIPT = SCRIPTS / "yaml_observe_file.py"


def run_script(root, relative, kind="application"):
    return subprocess.run([sys.executable, str(SCRIPT), "--root", str(root),
                           "--relative", relative, "--source-root", kind],
                          capture_output=True, text=True, check=False, timeout=15)


class SelectedYamlSourceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "tests" / "demo" / "sample.yaml"
        self.source.parent.mkdir(parents=True)
        self.source.write_bytes(b"tests:\n  demo:\n    tags: [gpio, uart]\n")

    def test_end_to_end_managed_cli_output(self):
        result = run_script(self.root, "tests/demo/sample.yaml")
        self.assertEqual(result.returncode, 0, result.stderr)
        payload = json.loads(result.stdout)
        self.assertEqual(payload["schema"], "zew.yaml-observation/1")
        self.assertEqual(payload["status"], "parsed")
        self.assertEqual(payload["classification"]["kind"], "twister-test")
        self.assertEqual(payload["provenance"]["source_root"], "application")
        self.assertEqual(payload["provenance"]["relative_path"], "tests/demo/sample.yaml")
        self.assertEqual(payload["provenance"]["sha256"],
                         hashlib.sha256(self.source.read_bytes()).hexdigest())
        self.assertEqual(payload["documents"][0]["root"]["entries"][0]["key"], "tests")
        self.assertEqual(self.source.read_bytes(), b"tests:\n  demo:\n    tags: [gpio, uart]\n")

    def test_zephyr_checkout_classification(self):
        binding = self.root / "dts" / "bindings" / "gpio" / "demo.yaml"
        binding.parent.mkdir(parents=True)
        binding.write_text("compatible: test,unit\n", encoding="utf-8")
        result = run_script(self.root, "dts/bindings/gpio/demo.yaml", "zephyr")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["classification"]["kind"], "devicetree-binding")

    def test_malformed_yaml_is_a_report_not_crash(self):
        self.source.write_text("a: [not closed\n", encoding="utf-8")
        result = run_script(self.root, "tests/demo/sample.yaml")
        self.assertEqual(result.returncode, 0)
        payload = json.loads(result.stdout)
        self.assertEqual(payload["status"], "invalid")
        self.assertEqual(payload["reason"], "yaml-syntax-invalid")

    def test_duplicate_keys_are_invalid_without_source_echo(self):
        self.source.write_text("secret: SECRET123\nsecret: SECRET789\n", encoding="utf-8")
        result = run_script(self.root, "tests/demo/sample.yaml")
        self.assertEqual(result.returncode, 0)
        self.assertEqual(json.loads(result.stdout)["reason"], "duplicate-mapping-key")
        self.assertNotIn("SECRET", result.stderr)

    def test_parent_absolute_and_non_yaml_paths_rejected(self):
        for name in ("../unrelated.yml", "/etc/passwd", "tests/demo/sample.yaml/../../a.yml",
                     "tests/demo/missing.yaml", "tests/demo/not-yaml.txt", "tests//demo/sample.yaml"):
            with self.subTest(name=name):
                result = run_script(self.root, name)
                self.assertEqual(result.returncode, 2)
                self.assertFalse(result.stdout.strip())

    def test_oversized_source_not_read(self):
        self.source.write_bytes(b"a" * (256 * 1024 + 1))
        result = run_script(self.root, "tests/demo/sample.yaml")
        self.assertEqual(result.returncode, 2)
        self.assertIn("oversized", result.stderr)

    def test_symlinked_source_rejected(self):
        alias = self.root / "tests" / "demo" / "alias.yaml"
        try:
            alias.symlink_to(self.source)
        except (OSError, NotImplementedError):
            self.skipTest("Symlink privilege unavailable")
        result = run_script(self.root, "tests/demo/alias.yaml")
        self.assertEqual(result.returncode, 2)
        self.assertIn("symlinked", result.stderr)

    def test_parser_modules_match_packaged_versions_byte_for_byte(self):
        for filename in ("yaml_classification.py", "yaml_observation.py"):
            with self.subTest(filename=filename):
                self.assertEqual((ROOT / "zew_evidence" / filename).read_bytes(),
                                 (SCRIPTS / "zew_evidence" / filename).read_bytes())


if __name__ == "__main__":
    unittest.main()
