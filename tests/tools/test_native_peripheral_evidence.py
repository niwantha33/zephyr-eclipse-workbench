# SPDX-License-Identifier: Apache-2.0
"""Installed native resource parity, protocol and real Python subprocess tests.

The Workbench MUST NOT ship a forked or stale peripheral evaluator.
"""
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT_ROOT = ROOT / "plugins/io.github.niwantha33.zew.ui/scripts"
SCRIPT = SCRIPT_ROOT / "peripheral_inspect.py"


class NativePeripheralEvidenceTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory(prefix="zew-periph-swt-Ω ")
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.zephyr = self.root / "zephyr"
        root = self.zephyr / "scripts/dts/python-devicetree/src/devicetree"
        root.mkdir(parents=True)
        (root / "__init__.py").write_text("", encoding="utf-8")
        (root / "edtlib.py").write_text(
            "from types import SimpleNamespace as N\n"
            "from pathlib import Path\n"
            "class EDT:\n"
            "    def __init__(self, dts, bindings):\n"
            "        self.text = Path(dts).read_text(encoding='utf-8')\n"
            "    def get_node(self, path):\n"
            "        if path != '/soc/gpio@1234': raise KeyError(path)\n"
            "        if 'no-binding' in self.text:\n"
            "            return N(binding_path=None, status='disabled', props={})\n"
            "        return N(binding_path='/fake/vendor/gpio.yaml', status='okay',\n"
            "                 props={'ngpios':N(type='int',spec=N(deprecated=False))})\n",
            encoding="utf-8")
        (self.zephyr / "dts/bindings").mkdir(parents=True)
        self.build = self.root / "build"
        (self.build / "zephyr").mkdir(parents=True)
        (self.build / "CMakeCache.txt").write_text(
            "ZEPHYR_BASE:PATH=" + str(self.zephyr) + "\n", encoding="utf-8")
        self.dts = self.build / "zephyr/zephyr.dts"
        self.dts.write_text("/dts-v1/;\n/ { soc { gpio@1234 {}; }; };\n",
                            encoding="utf-8")
        self.config = self.build / "zephyr/.config"
        self.config.write_text("CONFIG_GPIO=y\nCONFIG_PINCTRL=y\n",
                               encoding="utf-8")

    def test_packaged_modules_byte_identical_to_headless(self):
        for name in ("__init__.py", "collector.py", "dt_binding_preview.py",
                     "peripheral_evidence.py"):
            with self.subTest(name=name):
                self.assertEqual(
                    (ROOT / "zew_evidence" / name).read_bytes(),
                    (SCRIPT_ROOT / "zew_evidence" / name).read_bytes(),
                    "Installed Eclipse resource drifted from headless contract: " + name)

    def invoke(self, *extra):
        result = subprocess.run(
            [sys.executable, "-I", "-B", str(SCRIPT),
             "--build", str(self.build), "--zephyr", str(self.zephyr),
             "--node", "/soc/gpio@1234", *extra],
            cwd=self.root, text=True, encoding="utf-8", errors="replace",
            capture_output=True, timeout=30)
        lines = result.stdout.strip().splitlines()
        self.assertEqual(len(lines), 2, result.stderr)
        self.assertEqual(lines[0], "ZEW-PERIPHERAL-1")
        fields = [base64.b64decode(x, validate=True).decode("ascii")
                  for x in lines[1].split("\t")]
        self.assertEqual(len(fields), 14)
        return result.returncode, fields, result.stdout

    def test_actual_packaged_upstream_edtlib_and_kconfig(self):
        original = (self.dts.read_bytes(), self.config.read_bytes())
        exit_code, fields, wire = self.invoke("--property", "ngpios",
                                              "--symbol", "CONFIG_GPIO")
        self.assertEqual(exit_code, 0)
        self.assertEqual(fields[0], "selected-observations-present")
        self.assertEqual(fields[1], hashlib.sha256(original[0]).hexdigest())
        self.assertEqual(fields[2], hashlib.sha256(original[1]).hexdigest())
        self.assertEqual(fields[3], hashlib.sha256(b"/soc/gpio@1234").hexdigest())
        self.assertEqual(fields[4], hashlib.sha256(b"CONFIG_GPIO").hexdigest())
        self.assertEqual(fields[5:11], [
            "node-found", "matched", "okay", "interpreted-by-binding", "int", "enabled"])
        self.assertEqual(fields[11:13], ["yes", "yes"])
        self.assertEqual(fields[13], "none")
        self.assertEqual(self.invoke("--property", "ngpios",
                                     "--symbol", "CONFIG_GPIO")[2], wire)
        self.assertEqual((self.dts.read_bytes(), self.config.read_bytes()), original)
        self.assertNotIn(str(self.root), wire)
        self.assertNotIn("/soc/gpio@1234", wire)
        self.assertNotIn("CONFIG_GPIO", wire)

    def test_disabled_and_unknown_without_guessed_causality(self):
        self.dts.write_text("no-binding\n", encoding="utf-8")
        code, fields, _ = self.invoke("--symbol", "CONFIG_PINCTRL")
        self.assertEqual(code, 1)
        self.assertEqual(fields[0], "incomplete-or-unavailable")
        self.assertEqual(fields[6], "unmatched")
        self.assertEqual(fields[7], "disabled")
        self.assertEqual(fields[10], "enabled")
        self.assertIn("supply-actual-module-binding-roots", fields[13])

    def test_invalid_symbol_and_changed_checkout_fail_closed(self):
        code, fields, wire = self.invoke("--symbol", "CONFIG_SECRET_TOKEN")
        self.assertEqual(code, 1)
        self.assertEqual(fields[0], "unavailable")
        self.assertNotIn("SECRET_TOKEN", wire)
        (self.build / "CMakeCache.txt").write_text(
            "ZEPHYR_BASE:PATH=/other\n", encoding="utf-8")
        code, fields, _ = self.invoke()
        self.assertEqual(code, 1)
        self.assertEqual(fields[0], "incomplete-or-unavailable")
        self.assertIn("verify-exact-zephyr-checkout-and-generated-dts", fields[13])


if __name__ == "__main__":
    unittest.main()
