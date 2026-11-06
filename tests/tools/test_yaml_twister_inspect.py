# SPDX-License-Identifier: Apache-2.0
"""Step 9: selected-checkout Twister scl/schema contract and security tests.

Fixtures test the adapter boundary using a minimal checkout-like scl API and
JSON Schema; they do not replace real Twister 4.4 semantic compatibility tests.
"""

import base64
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[2] / "plugins/io.github.niwantha33.zew.ui/scripts"
SCRIPT = SCRIPTS / "yaml_twister_inspect.py"

SCL_FIXTURE = '''\
from jsonschema import Draft202012Validator

def _yaml_validate(data, schema):
    validator = Draft202012Validator(schema)
    for error in validator.iter_errors(data):
        raise error
'''

SUITE_SCHEMA = '''\
type: object
required: [tests]
additionalProperties: false
properties:
  sample:
    type: object
    properties:
      name: {type: string}
      description: {type: string}
  common:
    type: object
  tests:
    type: object
    additionalProperties:
      type: object
      properties:
        harness: {type: string}
        tags: {}
        timeout: {type: integer}
        sysbuild: {type: boolean}
        platform_allow: {}
        toolchain_exclude: {}
        testcases: {type: array}
      additionalProperties: false
'''

CONFIG_SCHEMA = '''\
type: object
additionalProperties: false
properties:
  options:
    type: object
    properties:
      integration_mode: {type: array, items: {type: string}}
    additionalProperties: false
  platforms:
    type: object
    properties:
      default_platforms: {type: array, items: {type: string}}
      override_default_platforms: {type: boolean}
      increased_platform_scope: {type: boolean}
    additionalProperties: false
  levels:
    type: array
    items:
      type: object
      required: [name]
      additionalProperties: false
      properties:
        name: {type: string}
        description: {type: string}
        adds: {type: array, items: {type: string}}
        inherits: {type: array, items: {type: string}}
'''


def decode(wire):
    lines = wire.strip().splitlines()
    assert lines and lines[0] == "ZEW-TWISTER-1", wire[:200]
    return [tuple([line.split("\t")[0]] + [
        base64.b64decode(value).decode("utf-8")
        for value in line.split("\t")[1:]]) for line in lines[1:]]


class TwisterObservationTests(unittest.TestCase):
    def setUp(self):
        storage = tempfile.TemporaryDirectory(prefix="zew-twister-test-")
        self.addCleanup(storage.cleanup)
        self.root = Path(storage.name)
        self.zephyr = self.root / "zephyr"
        self.application = self.root / "application"
        self.application.mkdir()
        self.scriptdir = self.zephyr / "scripts/pylib/twister"
        self.scriptdir.mkdir(parents=True)
        (self.scriptdir / "scl.py").write_text(SCL_FIXTURE, encoding="utf-8")
        schemas = self.zephyr / "scripts/schemas/twister"
        schemas.mkdir(parents=True)
        (schemas / "testsuite-schema.yaml").write_text(SUITE_SCHEMA, encoding="utf-8")
        (schemas / "test-config-schema.yaml").write_text(CONFIG_SCHEMA, encoding="utf-8")
        self.testcase = self.zephyr / "tests/kernel/demo/testcase.yaml"
        self.testcase.parent.mkdir(parents=True)
        self.testcase.write_text(
            "sample:\n"
            "  name: Demo sample\n"
            "common:\n"
            "  tags: [rtos, smoke]\n"
            "tests:\n"
            "  kernel.basic:\n"
            "    harness: ztest\n"
            "    timeout: 10\n"
            "    platform_allow: [qemu_x86, native_sim]\n"
            "  kernel.special:\n"
            "    sysbuild: true\n"
            "    tags: [irq]\n", encoding="utf-8")

    def invoke(self, *, relative="tests/kernel/demo/testcase.yaml",
               source_root="zephyr", root=None, zephyr=None, application=None):
        return subprocess.run([sys.executable, str(SCRIPT),
                               "--root", str(root or self.zephyr),
                               "--relative", relative,
                               "--source-root", source_root,
                               "--zephyr", str(zephyr or self.zephyr),
                               "--application", str(application or self.application)],
                              capture_output=True, text=True, check=False, timeout=15)

    def test_scenarios_use_selected_schema_and_scl_validator(self):
        source = self.testcase.read_bytes()
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        rows = decode(result.stdout)
        self.assertEqual(rows[0][1:3], ("resolved", "selected-twister-schema-validated"))
        self.assertEqual(rows[0][6], "testsuite")
        self.assertEqual(rows[0][3], hashlib.sha256(source).hexdigest())
        self.assertRegex(rows[0][4], r"^[0-9a-f]{64}$")
        self.assertRegex(rows[0][5], r"^[0-9a-f]{64}$")
        self.assertIn(("M", "name", "Demo sample"), rows)
        self.assertIn(("C", "tags", '["rtos","smoke"]'), rows)
        self.assertIn(("T", "kernel.basic"), rows)
        self.assertIn(("A", "kernel.basic", "harness", "ztest"), rows)
        self.assertIn(("A", "kernel.special", "sysbuild", "true"), rows)
        self.assertEqual(self.testcase.read_bytes(), source)

    def test_config_options_platforms_and_level_inheritance(self):
        config = self.zephyr / "tests/test_config.yaml"
        config.write_text(
            "options:\n  integration_mode: [slow]\n"
            "platforms:\n"
            "  override_default_platforms: true\n"
            "  default_platforms: [qemu_x86, native_sim]\n"
            "levels:\n"
            "  - name: smoke\n"
            "    description: Quick CI\n"
            "    adds: [kernel.basic]\n"
            "  - name: release\n"
            "    inherits: [smoke]\n", encoding="utf-8")
        rows = decode(self.invoke(relative="tests/test_config.yaml").stdout)
        self.assertEqual(rows[0][1], "resolved")
        self.assertEqual(rows[0][6], "test-config")
        self.assertIn(("O", "platforms.override_default_platforms", "true"), rows)
        self.assertIn(("P", "qemu_x86"), rows)
        self.assertIn(("L", "release", "", "[]", '["smoke"]'), rows)
        self.assertFalse(any(r[0] == "T" for r in rows))

    def test_application_root_sample_supported(self):
        sample = self.application / "sample.yaml"
        sample.write_text(
            "sample: {name: Simple}\n"
            "tests: {sample.run: {harness: console}}\n", encoding="utf-8")
        rows = decode(self.invoke(relative="sample.yaml", source_root="application",
                                  root=self.application).stdout)
        self.assertEqual(rows[0][1], "resolved")
        self.assertIn(("T", "sample.run"), rows)

    def test_generic_tests_yaml_is_not_promoted(self):
        generic = self.zephyr / "tests/kernel/demo/tests.yaml"
        generic.write_text("tests: {generic: {}}\n", encoding="utf-8")
        rows = decode(self.invoke(relative="tests/kernel/demo/tests.yaml").stdout)
        self.assertEqual(rows[0][1:3], ("not-twister", "not-a-supported-twister-file"))
        self.assertEqual(len(rows), 1)

    def test_schema_rejection_is_distinct_from_execution(self):
        self.testcase.write_text(
            "tests:\n  kernel.basic:\n    unexpected_config: SECRET\n", encoding="utf-8")
        result = self.invoke()
        rows = decode(result.stdout)
        self.assertEqual(rows[0][1:3], ("unavailable", "twister-schema-rejected"))
        self.assertNotIn("SECRET", result.stdout + result.stderr)
        self.assertEqual(len(rows), 1)

    def test_duplicate_keys_rejected_before_schema(self):
        self.testcase.write_text(
            "tests: {one: {}}\ntests: {two: {}}\n", encoding="utf-8")
        rows = decode(self.invoke().stdout)
        self.assertEqual(rows[0][1:3], ("unavailable", "twister-source-syntax-or-safety-rejected"))

    def test_invalid_test_case_yaml_is_safe(self):
        self.testcase.write_text("tests: [not-closed\n", encoding="utf-8")
        rows = decode(self.invoke().stdout)
        self.assertEqual(rows[0][1], "unavailable")

    def test_schema_and_scl_missing_are_unavailable(self):
        (self.scriptdir / "scl.py").unlink()
        rows = decode(self.invoke().stdout)
        self.assertEqual(rows[0][1:3], ("unavailable", "selected-twister-api-or-schema-missing"))
        (self.scriptdir / "scl.py").write_text(SCL_FIXTURE, encoding="utf-8")
        (self.zephyr / "scripts/schemas/twister/testsuite-schema.yaml").unlink()
        rows = decode(self.invoke().stdout)
        self.assertEqual(rows[0][1:3], ("unavailable", "selected-twister-api-or-schema-missing"))

    def test_wrong_checkout_and_source_traversal_rejected(self):
        for change in ({"root": self.application},
                       {"relative": "../private/sample.yaml"},
                       {"relative": "/tmp/private/testcase.yaml"}):
            with self.subTest(change=change):
                rows = decode(self.invoke(**change).stdout)
                self.assertEqual(rows[0][1], "unavailable")

    def test_large_yaml_and_schema_are_rejected(self):
        self.testcase.write_bytes(b"a" * (256 * 1024 + 1))
        self.assertEqual(decode(self.invoke().stdout)[0][1], "unavailable")
        self.testcase.write_text("tests: {one: {}}\n", encoding="utf-8")
        (self.zephyr / "scripts/schemas/twister/testsuite-schema.yaml").write_bytes(
            b"a" * (128 * 1024 + 1))
        self.assertEqual(decode(self.invoke().stdout)[0][1], "unavailable")

    def test_source_symlink_rejected_if_supported(self):
        original = self.testcase.parent / "original.yaml"
        self.testcase.rename(original)
        try:
            self.testcase.symlink_to(original)
        except (OSError, NotImplementedError):
            self.skipTest("Symlink privilege unavailable")
        self.assertEqual(decode(self.invoke().stdout)[0][1], "unavailable")

    def test_upstream_exception_is_sanitized(self):
        (self.scriptdir / "scl.py").write_text(
            "raise RuntimeError('SECRET private path')\n", encoding="utf-8")
        result = self.invoke()
        rows = decode(result.stdout)
        self.assertEqual(rows[0][1], "unavailable")
        self.assertNotIn("SECRET", result.stdout + result.stderr)

    def test_unavailable_validator_never_claims_schema_validation(self):
        (self.scriptdir / "scl.py").write_text(
            "def not_validator(*args): return None\n", encoding="utf-8")
        rows = decode(self.invoke().stdout)
        self.assertEqual(rows[0][1:3], ("unavailable", "selected-twister-validator-unavailable"))


if __name__ == "__main__":
    unittest.main()
