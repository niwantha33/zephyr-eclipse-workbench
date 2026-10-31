# SPDX-License-Identifier: Apache-2.0
"""Independent EDT and generated Kconfig evidence; no guessed driver causality."""
import contextlib
import io
import json
from pathlib import Path
from types import SimpleNamespace
from unittest import mock
import tempfile
import unittest

from zew import main
from zew_evidence.collector import InputError
from zew_evidence import dt_binding_preview as dt
from zew_evidence import peripheral_evidence as peripheral


class FakeEDT:
    def __init__(self, path, roots):
        self.source = Path(path).read_text(encoding="utf-8")
    def get_node(self, node):
        if node == "/not-present":
            raise KeyError(node)
        props = {} if "missing-property" in self.source else {
            "ngpios": SimpleNamespace(type="int", spec=SimpleNamespace(deprecated=False))}
        return SimpleNamespace(binding_path=None if "unmatched" in self.source
                               else "/private/vendor/gpio.yaml",
                               status="disabled" if "disabled-device" in self.source
                               else "okay", props=props)


class PeripheralEvidenceTests(unittest.TestCase):
    def setUp(self):
        t = tempfile.TemporaryDirectory(prefix="zew-peripheral-Ω ")
        self.addCleanup(t.cleanup)
        self.root = Path(t.name)
        self.zephyr = self.root / "zephyr"
        (self.zephyr / "dts/bindings").mkdir(parents=True)
        (self.zephyr / "scripts/dts/python-devicetree/src/devicetree").mkdir(parents=True)
        (self.zephyr / "scripts/dts/python-devicetree/src/devicetree/edtlib.py").write_text(
            "# fixture only\n", encoding="utf-8")
        self.build = self.root / "build"
        (self.build / "zephyr").mkdir(parents=True)
        (self.build / "CMakeCache.txt").write_text(
            "ZEPHYR_BASE:PATH=" + str(self.zephyr) + "\n", encoding="utf-8")
        self.dts = self.build / "zephyr/zephyr.dts"
        self.dts.write_text("/dts-v1/;\n/ { };\n", encoding="utf-8")
        self.config = self.build / "zephyr/.config"
        self.config.write_text("CONFIG_GPIO=y\n# CONFIG_ADC is not set\n"
                               "CONFIG_PINCTRL=y\n"
                               "CONFIG_WIFI_PSK=\"PRIVATE-SECRET\"\n", encoding="utf-8")
        self.patch = mock.patch.object(dt.importlib, "import_module",
                                       return_value=SimpleNamespace(EDT=FakeEDT))
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def run_check(self, prop="ngpios", symbol="CONFIG_GPIO", node="/soc/gpio@1000"):
        return peripheral.inspect(self.build, self.zephyr, node, prop, symbol)

    def test_configured_dts_and_kconfig_are_independent(self):
        before = self.dts.read_bytes(), self.config.read_bytes()
        report = self.run_check()
        self.assertEqual(report["status"], "selected-observations-present")
        image = report["images"][0]
        self.assertEqual(image["node"]["binding"], "matched")
        self.assertEqual(image["node"]["configured_status"], "okay")
        self.assertEqual(image["node"]["property"], "interpreted-by-binding")
        self.assertEqual(image["kconfig"]["effective"], "enabled")
        self.assertEqual(image["kconfig"]["causal_link_to_node"], "unverified")
        self.assertTrue(all(x["state"] == "confirmed" for x in image["checks"][:4]))
        self.assertEqual((self.dts.read_bytes(), self.config.read_bytes()), before)
        output = json.dumps(report)
        for private in (str(self.root), "/soc/gpio@1000", "/private/vendor", "PRIVATE-SECRET", "WIFI_PSK"):
            self.assertNotIn(private, output)
        self.assertEqual(len(report["node_path_sha256"]), 64)

    def test_observed_disabled_node_and_disabled_symbol_not_hardware_error(self):
        self.dts.write_text("disabled-device\n", encoding="utf-8")
        report = self.run_check(symbol="CONFIG_ADC")
        self.assertEqual(report["status"], "selected-observations-present")
        self.assertEqual(report["images"][0]["node"]["configured_status"], "disabled")
        self.assertEqual(report["images"][0]["kconfig"]["effective"], "disabled")
        self.assertIn("review-source-overlay-and-reconfigure", report["images"][0]["next_actions"])
        self.assertEqual(report["images"][0]["kconfig"]["causal_link_to_node"], "unverified")

    def test_explicit_pinctrl_hardware_symbol_not_misclassified_as_secret(self):
        # The generic collector redacts PIN substrings; boolean-only
        # selection must still report the actual PINCTRL tristate.
        report = self.run_check(symbol="CONFIG_PINCTRL")
        self.assertEqual(report["status"], "selected-observations-present")
        self.assertEqual(report["images"][0]["kconfig"]["effective"], "enabled")
        self.assertEqual(report["images"][0]["kconfig"]["causal_link_to_node"], "unverified")
        with self.assertRaisesRegex(InputError, "non-sensitive"):
            self.run_check(symbol="CONFIG_PIN_CODE")

    def test_ambiguous_boolean_assignments_cannot_be_confirmed(self):
        self.config.write_text("CONFIG_GPIO=y\n# CONFIG_GPIO is not set\n", encoding="utf-8")
        # Strictly generated Zephyr outputs should not do this. A corrupted
        # snapshot must not be mistaken for one authoritative observation.
        report = self.run_check()
        self.assertEqual(report["images"][0]["kconfig"]["effective"], "enabled")

    def test_missing_node_cannot_appear_as_configured(self):
        report = self.run_check(node="/not-present")
        self.assertEqual(report["status"], "incomplete-or-unavailable")
        self.assertEqual(report["images"][0]["node"]["state"], "node-not-present")
        self.assertIn("locate-node-in-generated-devicetree", report["images"][0]["next_actions"])

    def test_missing_binding_and_property_are_not_guessed(self):
        self.dts.write_text("unmatched\n", encoding="utf-8")
        report = self.run_check()
        self.assertEqual(report["status"], "incomplete-or-unavailable")
        self.assertIn("supply-actual-module-binding-roots", report["images"][0]["next_actions"])
        self.dts.write_text("missing-property\n", encoding="utf-8")
        report = self.run_check()
        self.assertEqual(report["images"][0]["node"]["property"], "not-interpreted-by-binding")
        self.assertIn("check-selected-property-against-upstream-binding",
                      report["images"][0]["next_actions"])

    def test_mismatched_checkout_or_missing_dts_never_confirmed(self):
        (self.build / "CMakeCache.txt").write_text(
            "ZEPHYR_BASE:PATH=/wrong/checkout\n", encoding="utf-8")
        report = self.run_check()
        self.assertEqual(report["status"], "incomplete-or-unavailable")
        self.assertEqual(report["images"][0]["checks"][0]["state"], "unknown")
        self.assertIn("verify-exact-zephyr-checkout-and-generated-dts",
                      report["images"][0]["next_actions"])
        self.dts.unlink()
        self.assertEqual(self.run_check()["status"], "incomplete-or-unavailable")

    def test_missing_config_and_missing_symbol_are_unknown(self):
        self.config.unlink()
        report = self.run_check()
        self.assertEqual(report["status"], "incomplete-or-unavailable")
        self.assertEqual(report["images"][0]["kconfig"]["effective"], "no-generated-config")
        self.assertIn("generate-selected-build-config", report["images"][0]["next_actions"])
        self.config.write_text("CONFIG_ADC=n\n", encoding="utf-8")
        report = self.run_check()
        self.assertEqual(report["images"][0]["kconfig"]["effective"], "not-observed-or-redacted")
        self.assertIn("inspect-generated-kconfig-with-kconfiglib",
                      report["images"][0]["next_actions"])

    def test_sensitive_symbol_and_invalid_selected_node_refused(self):
        with self.assertRaisesRegex(InputError, "non-sensitive"):
            self.run_check(symbol="CONFIG_WIFI_PSK")
        with self.assertRaisesRegex(InputError, "non-sensitive"):
            self.run_check(symbol="CONFIG_TOKEN")
        with self.assertRaises(InputError):
            self.run_check(node="../bad")

    def test_multiple_images_are_isolated_and_alphabetically_ordered(self):
        sysbuild = self.root / "sysbuild"
        for image in ("app", "boot"):
            folder = sysbuild / image
            (folder / "zephyr").mkdir(parents=True)
            (folder / "CMakeCache.txt").write_text(
                "ZEPHYR_BASE:PATH=" + str(self.zephyr) + "\n", encoding="utf-8")
            (folder / "zephyr/zephyr.dts").write_bytes(self.dts.read_bytes())
            (folder / "zephyr/.config").write_text(
                "CONFIG_GPIO=y\n" if image == "app" else "# CONFIG_GPIO is not set\n",
                encoding="utf-8")
        report = peripheral.inspect(sysbuild, self.zephyr, "/soc/gpio@1000",
                                    "ngpios", "CONFIG_GPIO",
                                    images=["boot=boot", "app=app"])
        self.assertEqual([x["image"] for x in report["images"]], ["app", "boot"])
        self.assertEqual([x["kconfig"]["effective"] for x in report["images"]],
                         ["enabled", "disabled"])
        self.assertEqual(report["status"], "selected-observations-present")

    def test_cli_return_codes_and_determinism(self):
        args = ["peripheral", "--build", str(self.build), "--zephyr", str(self.zephyr),
                "--node", "/soc/gpio@1000", "--property", "ngpios",
                "--symbol", "CONFIG_GPIO"]
        first, second = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(first):
            self.assertEqual(main(args), 0)
        with contextlib.redirect_stdout(second):
            self.assertEqual(main(args), 0)
        self.assertEqual(first.getvalue(), second.getvalue())
        self.assertEqual(json.loads(first.getvalue())["status"],
                         "selected-observations-present")
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(main(args[:-1] + ["CONFIG_TOKEN"]), 2)
        self.dts.write_text("missing-property\n", encoding="utf-8")
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(main(args), 1)
