# SPDX-License-Identifier: Apache-2.0
"""Real upstream DT origin tests; select current dtlib with ZEW_TEST_DTLIB.

Old upstream DT libraries lack property origin attributes and must report
unknown. The safety/unavailability tests run without any external dependency.
"""
import contextlib
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest import mock

from zew_evidence import studio_devicetree as dt
from zew_evidence.collector import InputError


class StudioDevicetreeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="zew-origin-Ω ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.build, self.zephyr, self.sources = [self.root / p for p in ("build", "zephyr", "sources")]
        (self.build / "zephyr").mkdir(parents=True)
        self.zephyr.mkdir()
        self.sources.mkdir()
        (self.build / "CMakeCache.txt").write_text(f"ZEPHYR_BASE:PATH={self.zephyr}\n")
        self.base = self.sources / "board.dts"
        self.overlay = self.sources / "app.overlay"
        self.base.write_text('/dts-v1/; / { soc { adc: adc@1 { status="disabled"; }; }; };\n')
        self.overlay.write_text('&adc { status="okay"; };\n')
        self.pre = self.build / "zephyr/zephyr.dts.pre"
        self.pre.write_text('/dts-v1/;\n' + f'# 1 "{self.base}"\n' +
                            '/ { soc { adc: adc@1 { status="disabled"; }; }; };\n' +
                            f'# 1 "{self.overlay}"\n&adc {{ status="okay"; }};\n')
        self.final = self.build / "zephyr/zephyr.dts"
        self.final.write_text('/dts-v1/; / { soc { adc@1 { status="okay"; }; }; };\n')

    def scan(self, **kwargs):
        return dt.inspect(self.build, self.zephyr, "/soc/adc@1", "status", [self.sources], **kwargs)

    def real_library(self):
        source = os.environ.get("ZEW_TEST_DTLIB")
        if not source:
            self.skipTest("Set ZEW_TEST_DTLIB to selected upstream dtlib.py for semantic integration")
        lib = self.zephyr / "scripts/dts/python-devicetree/src/devicetree"
        lib.mkdir(parents=True)
        shutil.copyfile(source, lib / "dtlib.py")
        (lib / "__init__.py").write_text("")

    def test_missing_preprocessor_evidence_is_explicit(self):
        self.pre.unlink()
        report = self.scan()["images"][0]
        self.assertEqual(report["reason"], "generated-dts-and-preprocessed-input-required")
        self.assertIsNone(report["origin"])

    def test_wrong_checkout_cannot_claim_origin(self):
        (self.build / "CMakeCache.txt").write_text("ZEPHYR_BASE:PATH=/not-selected\n")
        self.assertEqual(self.scan()["images"][0]["reason"], "selected-checkout-unverified")

    def test_external_include_and_incbin_reads_blocked_before_parser(self):
        for directive in ('/include/ "secret.dtsi"', '/incbin/("/etc/passwd")'):
            self.pre.write_text(directive)
            with mock.patch.object(dt, "_library", side_effect=AssertionError("must not import")):
                self.assertEqual(self.scan()["images"][0]["reason"], "external-parser-reads-not-permitted")

    def test_invalid_selection_and_symlink_roots_rejected(self):
        with self.assertRaises(InputError):
            dt.inspect(self.build, self.zephyr, "../bad", "status", [self.sources])
        link = self.root / "alias"
        try:
            link.symlink_to(self.sources, target_is_directory=True)
        except OSError:
            self.skipTest("symlinks unavailable")
        with self.assertRaises(InputError):
            dt.inspect(self.build, self.zephyr, "/soc/adc@1", "status", [link])

    def test_source_hashing_has_global_io_budget(self):
        budget = [1]
        row = dt._source(str(self.overlay), self.build, [self.sources], budget)
        self.assertEqual(row["state"], "source-io-budget-exceeded")
        self.assertEqual(budget, [1])

    def test_upstream_override_is_exact_final_source_and_no_write(self):
        self.real_library()
        before = {p: p.read_bytes() for p in (self.pre, self.final, self.base, self.overlay)}
        report = self.scan()
        row = report["images"][0]
        self.assertEqual(row["state"], "property-origin-observed")
        self.assertEqual(row["origin"]["path"], "app.overlay")
        self.assertEqual(row["origin"]["line"], 1)
        self.assertEqual(row["origin"]["sha256"], hashlib.sha256(self.overlay.read_bytes()).hexdigest())
        self.assertEqual(row["final_property"]["value_sha256"], hashlib.sha256(b"okay\0").hexdigest())
        self.assertNotIn("value_hex", row["final_property"])
        self.assertNotIn(str(self.root), json.dumps(report))
        self.assertEqual(report, self.scan())
        self.assertEqual(before, {p: p.read_bytes() for p in before})
        self.assertEqual(self.scan(include_values=True)["images"][0]["final_property"]["value_hex"], b"okay\0".hex())

    def test_upstream_mismatch_never_promotes_preprocessed_origin(self):
        self.real_library()
        self.final.write_text('/dts-v1/; / { soc { adc@1 { status="disabled"; }; }; };')
        row = self.scan()["images"][0]
        self.assertEqual(row["reason"], "preprocessed-and-final-property-differ")
        self.assertIsNone(row["origin"])

    def test_upstream_deletion_has_no_winning_origin(self):
        self.real_library()
        self.pre.write_text(self.pre.read_text() + '\n&adc { /delete-property/ status; };\n')
        self.final.write_text('/dts-v1/; / { soc { adc@1 { }; }; };')
        row = self.scan()["images"][0]
        self.assertEqual(row["state"], "property-not-present")
        self.assertIsNone(row["origin"])

    def test_marker_without_source_file_is_unknown(self):
        self.real_library()
        self.overlay.unlink()
        row = self.scan()["images"][0]
        self.assertEqual(row["origin"]["state"], "source-missing")
        self.assertEqual(row["confidence"], "unknown")

    def test_marker_participation_is_not_property_origin(self):
        self.real_library()
        inactive = self.sources / "unused.overlay"
        inactive.write_text('/* participates but assigns nothing */\n')
        self.pre.write_text(self.pre.read_text() + f'\n# 1 "{inactive}"\n')
        row = self.scan()["images"][0]
        self.assertEqual(row["origin"]["path"], "app.overlay")
        self.assertEqual([s["path"] for s in row["active_sources"]],
                         ["app.overlay", "board.dts", "unused.overlay"])


if __name__ == "__main__":
    unittest.main()
