# SPDX-License-Identifier: Apache-2.0
"""Step 10: real subprocess tests for source-linked YAML diagnostics.

The selected Twister scl fixture implements only its Draft 2020-12 validation
contract. Real-checkout acceptance remains a separate release gate.
"""
from __future__ import annotations

import base64
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = (Path(__file__).resolve().parents[2]
          / "plugins/io.github.niwantha33.zew.ui/scripts/yaml_diagnostic_inspect.py")

SCL = '''\
from jsonschema import Draft202012Validator
def _yaml_validate(source, schema):
    for error in Draft202012Validator(schema).iter_errors(source):
        raise error
'''

SUITE_SCHEMA = '''\
type: object
required: [tests]
additionalProperties: false
properties:
  tests:
    type: object
    additionalProperties:
      type: object
      properties:
        timeout: {type: integer}
        harness: {type: string}
        tags: {type: array}
      additionalProperties: false
  sample:
    type: object
  common:
    type: object
'''

CONFIG_SCHEMA = '''\
type: object
additionalProperties: false
properties:
  platforms:
    type: object
    properties:
      default_platforms: {type: array, items: {type: string}}
    additionalProperties: false
  levels:
    type: array
    items:
      type: object
      required: [name]
      properties:
        name: {type: string}
        inherits: {type: array, items: {type: string}}
      additionalProperties: false
'''


def parsed(text):
    rows = text.strip().splitlines()
    if not rows or rows[0] != "ZEW-YAMLDIAG-1":
        raise AssertionError(text[:200])
    return [tuple([row.split("\t")[0]] + [
        base64.b64decode(x).decode("utf-8") for x in row.split("\t")[1:]])
        for row in rows[1:]]


class YamlDiagnosticInspectorTests(unittest.TestCase):
    def setUp(self):
        data = tempfile.TemporaryDirectory()
        self.addCleanup(data.cleanup)
        self.root = Path(data.name)
        self.zephyr = self.root / "zephyr"
        self.app = self.root / "app"
        self.app.mkdir()
        self.scl_dir = self.zephyr / "scripts/pylib/twister"
        self.scl_dir.mkdir(parents=True)
        (self.scl_dir / "scl.py").write_text(SCL, encoding="utf-8")
        schema_dir = self.zephyr / "scripts/schemas/twister"
        schema_dir.mkdir(parents=True)
        (schema_dir / "testsuite-schema.yaml").write_text(SUITE_SCHEMA, encoding="utf-8")
        (schema_dir / "test-config-schema.yaml").write_text(CONFIG_SCHEMA, encoding="utf-8")
        self.source = self.zephyr / "tests" / "demo" / "testcase.yaml"
        self.source.parent.mkdir(parents=True)
        self.source.write_text("tests:\n  demo:\n    timeout: 5\n", encoding="utf-8")

    def run_inspector(self, relative="tests/demo/testcase.yaml", *,
                      root=None, source_root="zephyr", zephyr=None, app=None):
        result = subprocess.run([sys.executable, str(SCRIPT),
                                 "--root", str(root or self.zephyr),
                                 "--relative", relative,
                                 "--source-root", source_root,
                                 "--zephyr", str(zephyr or self.zephyr),
                                 "--application", str(app or self.app)],
                                capture_output=True, text=True, timeout=20, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        return parsed(result.stdout), result

    def test_schema_valid_source_is_checked_without_fabricated_findings(self):
        rows, _ = self.run_inspector()
        self.assertEqual(rows[0][1:3], ("checked", "twister-schema-valid"))
        self.assertEqual(rows[0][4], "twister")
        self.assertEqual(rows[0][3], hashlib.sha256(self.source.read_bytes()).hexdigest())
        self.assertEqual(len(rows), 1)

    def test_type_failure_points_to_exact_yaml_property_key(self):
        self.source.write_text(
            "tests:\n  demo:\n    timeout: WRONG_PRIVATE_VALUE\n", encoding="utf-8")
        rows, result = self.run_inspector()
        self.assertEqual(rows[0][1:3], ("invalid", "twister-schema-rejected"))
        self.assertEqual(rows[1][:5], ("D", "error", "schema-type", "3", "5"))
        self.assertNotIn("WRONG_PRIVATE_VALUE", result.stdout + result.stderr)
        self.assertIn("selected Twister schema", rows[1][5])

    def test_additional_property_never_invents_unknown_field_position(self):
        self.source.write_text("tests:\n  demo:\n    secret: PRIVATE\n", encoding="utf-8")
        rows, result = self.run_inspector()
        self.assertEqual(rows[1][2], "schema-additionalproperties")
        self.assertEqual(rows[1][3:5], ("2", "3"))
        self.assertNotIn("PRIVATE", result.stdout + result.stderr)

    def test_required_field_points_to_containing_mapping(self):
        self.source.write_text("sample: {name: example}\n", encoding="utf-8")
        rows, _ = self.run_inspector()
        self.assertEqual(rows[1][2:5], ("schema-required", "1", "1"))

    def test_bad_yaml_syntax_is_location_linked(self):
        self.source.write_text("tests: [unterminated\n", encoding="utf-8")
        rows, result = self.run_inspector()
        self.assertEqual(rows[0][1:3], ("invalid", "yaml-syntax-invalid"))
        self.assertEqual(rows[0][4], "syntax")
        self.assertEqual(rows[1][2], "syntax-yaml-syntax-invalid")
        self.assertGreater(int(rows[1][3]), 0)
        self.assertNotIn("unterminated", result.stdout)

    def test_duplicate_key_locates_second_key(self):
        self.source.write_text("tests:\n  demo: {}\n  demo: {}\n", encoding="utf-8")
        rows, _ = self.run_inspector()
        self.assertEqual(rows[0][2], "duplicate-mapping-key")
        self.assertEqual(rows[1][2:5], ("syntax-duplicate-mapping-key", "3", "3"))

    def test_anchor_is_rejected_with_location(self):
        self.source.write_text("tests:\n  demo: &shared {}\n", encoding="utf-8")
        rows, _ = self.run_inspector()
        self.assertEqual(rows[0][2], "aliases-or-anchors-unsupported")
        self.assertGreater(int(rows[1][3]), 0)

    def test_safe_generic_yaml_never_claims_zephyr_schema(self):
        generic = self.app / "config" / "settings.yml"
        generic.parent.mkdir()
        generic.write_text("settings:\n  hello: world\n", encoding="utf-8")
        rows, _ = self.run_inspector("config/settings.yml", root=self.app,
                                     source_root="application")
        self.assertEqual(rows[0][1:3], ("checked", "yaml-syntax-valid"))
        self.assertEqual(rows[0][4], "syntax")
        self.assertEqual(len(rows), 1)

    def test_test_config_uses_selected_schema(self):
        config = self.zephyr / "tests" / "test_config.yaml"
        config.write_text("platforms:\n  default_platforms: [qemu_x86]\n", encoding="utf-8")
        rows, _ = self.run_inspector("tests/test_config.yaml")
        self.assertEqual(rows[0][1:3], ("checked", "twister-schema-valid"))
        config.write_text("platforms:\n  default_platforms: false\n", encoding="utf-8")
        rows, _ = self.run_inspector("tests/test_config.yaml")
        self.assertEqual(rows[1][2:5], ("schema-type", "2", "3"))

    def test_missing_upstream_schema_is_unavailable_not_valid(self):
        (self.zephyr / "scripts/schemas/twister/testsuite-schema.yaml").unlink()
        rows, _ = self.run_inspector()
        self.assertEqual(rows[0][1], "unavailable")
        self.assertEqual(rows[0][4], "none")
        self.assertEqual(len(rows), 1)

    def test_validator_crash_sanitized_and_not_validation_error(self):
        (self.scl_dir / "scl.py").write_text(
            "def _yaml_validate(*args): raise RuntimeError('SECRET')\n", encoding="utf-8")
        rows, result = self.run_inspector()
        self.assertEqual(rows[0][1:3],
                         ("unavailable", "selected-twister-validation-unavailable"))
        self.assertNotIn("SECRET", result.stdout + result.stderr)
        self.assertEqual(len(rows), 1)

    def test_path_traversal_and_wrong_root_have_no_diagnostics(self):
        for kwargs in ({"relative": "../unsafe/testcase.yaml"},
                       {"root": self.app},
                       {"relative": "/etc/passwd"}):
            with self.subTest(kwargs=kwargs):
                kw = dict(kwargs)
                rel = kw.pop("relative", "tests/demo/testcase.yaml")
                rows, _ = self.run_inspector(rel, **kw)
                self.assertEqual(rows[0][1], "unavailable")
                self.assertEqual(len(rows), 1)

    def test_symlinked_yaml_rejected_when_supported(self):
        original = self.source.parent / "saved.yaml"
        self.source.rename(original)
        try:
            self.source.symlink_to(original)
        except (OSError, NotImplementedError):
            self.skipTest("Symlink privileges not available")
        rows, _ = self.run_inspector()
        self.assertEqual(rows[0][1], "unavailable")
        self.assertEqual(len(rows), 1)

    def test_source_remains_unchanged_and_sha_matches(self):
        before = self.source.read_bytes()
        rows, _ = self.run_inspector()
        self.assertEqual(before, self.source.read_bytes())
        self.assertEqual(rows[0][3], hashlib.sha256(before).hexdigest())

    def test_no_synthetic_dts_schema_failure_from_generic_binding(self):
        binding = self.zephyr / "dts/bindings/vendor/dummy.yaml"
        binding.parent.mkdir(parents=True)
        binding.write_text("compatible: vendor,dummy\n", encoding="utf-8")
        rows, _ = self.run_inspector("dts/bindings/vendor/dummy.yaml")
        self.assertEqual(rows[0][1:3], ("checked", "yaml-syntax-valid"))
        self.assertEqual(rows[0][4], "syntax")
        self.assertEqual(len(rows), 1)


if __name__ == "__main__":
    unittest.main()
