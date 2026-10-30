# SPDX-License-Identifier: Apache-2.0
"""Request/snapshot comparison: no invented Kconfig semantics and no secret export."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest

from zew import main
from zew_evidence.collector import InputError, collect
from zew_evidence.config_request_check import check


class ConfigRequestCheckTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="zew-requests-Ω ")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.build = self.root / "build"
        (self.build / "zephyr").mkdir(parents=True)
        self.config = self.build / "zephyr" / ".config"
        self.config.write_text(
            "CONFIG_GPIO=y\n# CONFIG_ADC is not set\nCONFIG_CAN=m\n"
            'CONFIG_WIFI_PASSWORD="private-generated"\n', encoding="utf-8")
        (self.build / "zephyr" / "zephyr.dts").write_text("/ { };\n", encoding="utf-8")
        self.snippet = self.root / "user.conf"

    def compare(self, request):
        self.snippet.write_text(request, encoding="utf-8")
        return check(collect(self.build), self.snippet)

    def test_matching_snapshot_is_repeatable_and_observed_only(self):
        report = self.compare("# example comment\nCONFIG_GPIO=y\n# CONFIG_ADC is not set\nCONFIG_CAN=m\n")
        self.assertEqual(report["status"], "matching-snapshot")
        self.assertEqual(report["counts"], {"matches-generated": 3})
        self.assertEqual(report["input"]["requests"], 3)
        self.assertEqual(report, check(collect(self.build), self.snippet))
        self.assertEqual([row["symbol"] for row in report["images"][0]["requests"]],
                         ["CONFIG_ADC", "CONFIG_CAN", "CONFIG_GPIO"])
        self.assertEqual(report["images"][0]["requests"][0]["confidence"], "confirmed")

    def test_mismatch_is_a_snapshot_difference_not_an_inferred_cause(self):
        report = self.compare("CONFIG_GPIO=n\nCONFIG_MISSING=y\n")
        self.assertEqual(report["status"], "partial-or-mismatched")
        self.assertEqual(report["counts"]["differs-from-generated"], 1)
        self.assertEqual(report["counts"]["symbol-not-observed"], 1)
        self.assertEqual(report["images"][0]["requests"][1]["confidence"], "unknown")
        self.assertTrue(any("does not prove" in limit for limit in report["limitations"]))

    def test_conflicts_and_identical_duplicates_are_distinguished(self):
        report = self.compare("CONFIG_GPIO=y\n# CONFIG_GPIO is not set\n")
        self.assertEqual(report["counts"], {"conflicting-requests": 1})
        self.assertEqual(report["images"][0]["requests"][0]["source_lines"], [1, 2])
        self.assertEqual(report["images"][0]["requests"][0]["requested"], "<redacted>")
        repeated = self.compare("CONFIG_GPIO=y\nCONFIG_GPIO=y\n")
        self.assertEqual(repeated["status"], "matching-snapshot")
        self.assertEqual(repeated["images"][0]["requests"][0]["source_lines"], [1, 2])

    def test_opaque_and_sensitive_values_never_escape(self):
        report = self.compare('CONFIG_WIFI_PASSWORD="secret-message"\n'
                              'CONFIG_DEVICE_SERIAL="private-id"\nCONFIG_GPIO=y\n')
        payload = json.dumps(report)
        self.assertNotIn("secret-message", payload)
        self.assertNotIn("private-id", payload)
        self.assertNotIn("private-generated", payload)
        self.assertNotIn(str(self.root), payload)
        self.assertEqual(report["counts"]["unsupported-value-type"], 2)
        self.assertEqual(report["counts"]["matches-generated"], 1)

    def test_invalid_line_never_echoes_input_text(self):
        report = self.compare("CONFIG_GPIO = sensitive text\n# CONFIG_CAN typo\nCONFIG_ADC=n\n")
        self.assertEqual(report["input"]["invalid_lines"], [1, 2])
        self.assertEqual(report["status"], "partial-or-mismatched")
        self.assertNotIn("sensitive text", json.dumps(report))

    def test_empty_and_missing_generated_config_cannot_pass(self):
        self.assertEqual(self.compare("# only a comment\n")["status"], "partial-or-mismatched")
        self.snippet.write_text("CONFIG_GPIO=y\n", encoding="utf-8")
        self.config.unlink()
        report = check(collect(self.build), self.snippet)
        self.assertEqual(report["counts"], {"no-generated-config": 1})
        self.assertEqual(report["images"][0]["generated_config"]["state"], "missing")

    def test_multi_image_uses_each_exact_snapshot(self):
        root = self.root / "sysbuild"
        for name, gpio in (("app", "y"), ("boot", "n")):
            zephyr = root / name / "zephyr"
            zephyr.mkdir(parents=True)
            (zephyr / ".config").write_text("CONFIG_GPIO=" + gpio + "\n", encoding="utf-8")
        self.snippet.write_text("CONFIG_GPIO=y\n", encoding="utf-8")
        report = check(collect(root, ["app=app", "boot=boot"]), self.snippet)
        self.assertEqual(report["counts"], {"differs-from-generated": 1, "matches-generated": 1})
        self.assertEqual([row["image"] for row in report["images"]], ["app", "boot"])

    def test_cli_exit_contract_and_unchanged_files(self):
        self.snippet.write_text("CONFIG_GPIO=y\n", encoding="utf-8")
        original = self.snippet.read_bytes(), self.config.read_bytes()
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            self.assertEqual(main(["config-check", "--build", str(self.build),
                                   "--snippet", str(self.snippet)]), 0)
        self.assertEqual(json.loads(stdout.getvalue())["status"], "matching-snapshot")
        self.snippet.write_text("CONFIG_GPIO=n\n", encoding="utf-8")
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(main(["config-check", "--build", str(self.build),
                                   "--snippet", str(self.snippet)]), 1)
        self.assertEqual(self.config.read_bytes(), original[1])

    def test_input_limits_and_invalid_encoding(self):
        self.snippet.write_bytes(b"CONFIG_GPIO=y\n" * 15000)
        with self.assertRaises(InputError):
            check(collect(self.build), self.snippet)
        self.snippet.write_bytes(b"CONFIG_GPIO=\xff\n")
        with self.assertRaises(InputError):
            check(collect(self.build), self.snippet)
        self.snippet.write_bytes(b"CONFIG_GPIO=y\0\n")
        with self.assertRaises(InputError):
            check(collect(self.build), self.snippet)

    def test_symlink_source_is_rejected(self):
        other = self.root / "actual.conf"
        other.write_text("CONFIG_GPIO=y\n", encoding="utf-8")
        try:
            self.snippet.symlink_to(other)
        except (OSError, NotImplementedError):
            self.skipTest("Symlinks not available on this host")
        with self.assertRaises(InputError):
            check(collect(self.build), self.snippet)


if __name__ == "__main__":
    unittest.main()
