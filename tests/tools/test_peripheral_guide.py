# SPDX-License-Identifier: Apache-2.0
"""Safe peripheral draft generation with authentic selected checkout edtlib."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

from zew import main
from zew_evidence.collector import InputError
from zew_evidence import peripheral_guide as guide


class FakeNode:
    def __init__(self, source):
        self.binding_path = None if "unmatched-binding" in source else "/vendor/gpio.yaml"
        self.status = "disabled" if "disabled-node" in source else "okay"
        self.buses = ["spi"]
        self.on_buses = []
        def value(kind, enum=None, const=None, deprecated=False):
            return SimpleNamespace(spec=SimpleNamespace(
                type=kind, enum=enum, const=const, deprecated=deprecated))
        self.props = {
            "gpio-controller": value("boolean"),
            "#io-channel-cells": value("int"),
            "zephyr,resolution": value("int"),
            "spi-max-frequency": value("int"),
            "clock-frequency": value("int"),
            "zephyr,reference": value("string", enum=["internal", "external"]),
            "deprecated-field": value("int", deprecated=True),
            "const-field": value("int", const=2),
            "gpios": value("phandle-array"),
        }


class FakeEDT:
    def __init__(self, dts, roots):
        self.source = Path(dts).read_text(encoding="utf-8")
    def get_node(self, path):
        if path != "/soc/gpio@1234":
            raise LookupError("Not a real selected node")
        return FakeNode(self.source)


class GuidedPeripheralTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory(prefix="zew-guide-Ω ")
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.app = self.root / "my Zephyr app"
        self.app.mkdir()
        self.overlay = self.app / "app.overlay"
        self.overlay.write_text("/* Owner's existing DTS source */\n", encoding="utf-8")
        self.zephyr = self.root / "zephyr"
        (self.zephyr / "dts/bindings").mkdir(parents=True)
        (self.zephyr / "scripts/dts/python-devicetree/src/devicetree").mkdir(parents=True)
        (self.zephyr / "scripts/dts/python-devicetree/src/devicetree/edtlib.py").write_text(
            "# synthetic fixture, not real zephyr\n", encoding="utf-8")
        self.build = self.root / "build"
        (self.build / "zephyr").mkdir(parents=True)
        (self.build / "CMakeCache.txt").write_text(
            "ZEPHYR_BASE:PATH=" + str(self.zephyr) + "\n", encoding="utf-8")
        self.dts = self.build / "zephyr/zephyr.dts"
        self.dts.write_text("/dts-v1/;\n/ { soc { gpio@1234 {}; }; };\n", encoding="utf-8")
        self.node = "/soc/gpio@1234"
        self.patch = mock.patch.object(guide.importlib, "import_module",
                                       return_value=SimpleNamespace(EDT=FakeEDT))
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def plan(self, **kwargs):
        return guide.guide(self.build, self.zephyr, self.node,
                          app=self.app, overlay=self.overlay, **kwargs)

    def test_real_binding_existing_integer_draft_and_private_source_protected(self):
        original = self.overlay.read_bytes(), self.dts.read_bytes()
        report = self.plan(integer="zephyr,resolution=12", kind="adc")
        self.assertEqual(report["status"], "draft-requires-real-configuration")
        self.assertEqual(report["source"]["binding"], "matched")
        self.assertEqual(report["source"]["property_type"], "int")
        self.assertIn("spi-controller-bus", report["source"]["interface_hints"])
        self.assertEqual(report["requested_peripheral_kind"], "adc")
        self.assertTrue(report["proposal"]["available"])
        self.assertIsNone(report["proposal"]["visible_diff"])
        self.assertEqual((self.overlay.read_bytes(), self.dts.read_bytes()), original)
        self.assertNotIn(str(self.root), json.dumps(report))
        self.assertNotIn("Owner's existing DTS", json.dumps(report))
        self.assertNotIn(self.node, json.dumps(report))
        self.assertEqual(len(report["proposal"]["post_change_overlay_sha256"]), 64)

    def test_opt_in_real_diff_shows_exact_reviewer_intent_and_no_writes(self):
        old = self.overlay.read_bytes()
        report = self.plan(status="okay", kind="gpio", show_diff=True)
        diff = report["proposal"]["visible_diff"]
        self.assertIn("status = \"okay\";", diff)
        self.assertIn("&{/soc/gpio@1234}", diff)
        self.assertIn("Owner's existing DTS source", diff)
        self.assertEqual(self.overlay.read_bytes(), old)
        self.assertEqual(report["source"]["confidence"], "confirmed")
        with contextlib.redirect_stdout(io.StringIO()):
            again = self.plan(status="okay", kind="gpio", show_diff=True)
        self.assertEqual(report, again)

    def test_enum_guidance_is_exact_constraint_not_synthetic(self):
        report = self.plan(enum="zephyr,reference=internal", kind="adc", show_diff=True)
        self.assertEqual(report["status"], "draft-requires-real-configuration")
        self.assertIn('zephyr,reference = "internal";', report["proposal"]["visible_diff"])
        rejected = self.plan(enum="zephyr,reference=imaginary")
        self.assertEqual(rejected["status"], "manual-review-required")
        self.assertFalse(rejected["proposal"]["available"])

    def test_spi_and_i2c_int_requests_require_existing_binding_types(self):
        spi = self.plan(integer="spi-max-frequency=1000000", kind="spi", show_diff=True)
        self.assertIn("spi-max-frequency = <1000000>;", spi["proposal"]["visible_diff"])
        i2c = self.plan(integer="clock-frequency=400000", kind="i2c")
        self.assertEqual(i2c["status"], "draft-requires-real-configuration")
        # The kind argument alone does not establish this is an I2C device:
        self.assertEqual(i2c["requested_peripheral_kind"], "i2c")
        self.assertNotIn("i2c-controller-bus", i2c["source"]["interface_hints"])

    def test_reject_structural_and_secret_or_unsafe_requests(self):
        for unsafe in ("gpios=7", "pinctrl-0=5", "reg=123",
                       "secret=100", "phandle=1", "gpio-controller=1",
                       "cs-gpios=3", "sda-gpios=5", "clock-frequency=-5",
                       "clock-frequency=4294967296", "clock-frequency=1;danger"):
            with self.subTest(unsafe=unsafe), self.assertRaises(InputError):
                self.plan(integer=unsafe)
        for unsafe in ("zephyr,reference=unknown", "zephyr,reference=internal};"):
            with self.subTest(unsafe=unsafe):
                result = self.plan(enum=unsafe) if unsafe.endswith("unknown") else None
                if result is not None:
                    self.assertEqual(result["status"], "manual-review-required")
                else:
                    with self.assertRaises(InputError):
                        self.plan(enum=unsafe)

    def test_binding_missing_or_deprecated_never_proposes(self):
        self.dts.write_text("unmatched-binding\n", encoding="utf-8")
        result = self.plan(status="disabled")
        self.assertEqual(result["status"], "manual-review-required")
        self.assertEqual(result["source"]["binding"], "unmatched")
        self.dts.write_text("normal\n", encoding="utf-8")
        result = self.plan(integer="deprecated-field=12")
        self.assertEqual(result["status"], "manual-review-required")
        self.assertTrue(result["source"]["selected_property_deprecated"])

    def test_missing_selected_property_rejects_and_const_must_match(self):
        self.assertEqual(self.plan(integer="not-a-property=8")["status"],
                         "manual-review-required")
        self.assertEqual(self.plan(integer="const-field=3")["status"],
                         "manual-review-required")
        self.assertEqual(self.plan(integer="const-field=2")["status"],
                         "draft-requires-real-configuration")
        self.assertEqual(self.plan(integer="zephyr,reference=3")["status"],
                         "manual-review-required")

    def test_exact_checkout_required_no_external_overlay_or_symlink(self):
        (self.build / "CMakeCache.txt").write_text(
            "ZEPHYR_BASE:PATH=/other\n", encoding="utf-8")
        with self.assertRaisesRegex(InputError, "checkout"):
            self.plan(status="okay")
        (self.build / "CMakeCache.txt").write_text(
            "ZEPHYR_BASE:PATH=" + str(self.zephyr) + "\n", encoding="utf-8")
        other = self.root / "other.overlay"
        other.write_text("private\n", encoding="utf-8")
        with self.assertRaisesRegex(InputError, "inside"):
            guide.guide(self.build, self.zephyr, self.node, status="okay",
                       app=self.app, overlay=other)
        link = self.app / "alias.overlay"
        try:
            link.symlink_to(self.overlay)
        except (OSError, NotImplementedError):
            self.skipTest("Symlinks not available")
        with self.assertRaisesRegex(InputError, "Symlink"):
            guide.guide(self.build, self.zephyr, self.node, status="okay",
                       app=self.app, overlay=link)

    def test_new_overlay_proposal_is_not_created(self):
        missing = self.app / "new.overlay"
        report = guide.guide(self.build, self.zephyr, self.node, status="disabled",
                             app=self.app, overlay=missing, show_diff=True)
        self.assertEqual(report["overlay"]["mode"], "new-file-proposal")
        self.assertFalse(missing.exists())
        self.assertIn("status = \"disabled\"", report["proposal"]["visible_diff"])

    def test_explicit_one_image_sysbuild_and_no_guess(self):
        root = self.root / "sysbuild"
        for name in ("app", "boot"):
            folder = root / name
            (folder / "zephyr").mkdir(parents=True)
            (folder / "zephyr/zephyr.dts").write_bytes(self.dts.read_bytes())
            (folder / "CMakeCache.txt").write_text(
                "ZEPHYR_BASE:PATH=" + str(self.zephyr) + "\n", encoding="utf-8")
        with self.assertRaisesRegex(InputError, "exactly one"):
            guide.guide(root, self.zephyr, self.node, status="okay",
                       images=["app=app", "boot=boot"])
        selected = guide.guide(root, self.zephyr, self.node, status="okay",
                               images=["app=app"])
        self.assertEqual(selected["image"], "app")
        self.assertTrue(selected["proposal"]["available"])

    def test_cli_exit_and_diff_opt_in(self):
        args = ["peripheral-guide", "--build", str(self.build), "--zephyr",
                str(self.zephyr), "--node", self.node, "--kind", "gpio",
                "--application", str(self.app), "--overlay", str(self.overlay),
                "--status", "okay"]
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(main(args), 0)
        self.assertIsNone(json.loads(output.getvalue())["proposal"]["visible_diff"])
        with contextlib.redirect_stdout(output := io.StringIO()):
            self.assertEqual(main(args + ["--show-diff"]), 0)
        self.assertIn("status = ", json.loads(output.getvalue())["proposal"]["visible_diff"])
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(main(args[:-1] + ["bogus"]), 2)


if __name__ == "__main__":
    unittest.main()
