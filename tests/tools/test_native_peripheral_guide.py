# SPDX-License-Identifier: Apache-2.0
"""Native source-review wire is the same headless guide; no file writes."""
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "plugins/io.github.niwantha33.zew.ui/scripts"


class NativePeripheralGuideTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="zew-guide-native-Ω ")
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.zephyr = self.root / "selected zephyr"
        path = self.zephyr / "scripts/dts/python-devicetree/src/devicetree"
        path.mkdir(parents=True)
        (path / "__init__.py").write_text("", encoding="utf-8")
        (path / "edtlib.py").write_text(
            "from pathlib import Path\n"
            "from types import SimpleNamespace as N\n"
            "class EDT:\n"
            "    def __init__(self, dts, bindings):\n"
            "        self.source=Path(dts).read_text(encoding='utf-8')\n"
            "    def get_node(self, p):\n"
            "        if p!='/soc/spi@1234': raise KeyError(p)\n"
            "        specs={'spi-max-frequency':N(spec=N(type='int',enum=None,const=None,deprecated=False)),\n"
            "               'zephyr,reference':N(spec=N(type='string',enum=['internal','external'],const=None,deprecated=False))}\n"
            "        return N(status='okay',binding_path='/trusted/spi.yaml',\n"
            "                 props=specs,buses=['spi'],on_buses=[])\n",
            encoding="utf-8")
        (self.zephyr / "dts/bindings").mkdir(parents=True)
        self.app = self.root / "my application"
        self.app.mkdir()
        self.overlay = self.app / "app.overlay"
        self.overlay.write_text("/* An earlier source override */\n", encoding="utf-8")
        self.build = self.root / "selected build"
        (self.build / "zephyr").mkdir(parents=True)
        (self.build / "CMakeCache.txt").write_text(
            f"ZEPHYR_BASE:PATH={self.zephyr}\n", encoding="utf-8")
        self.dts = self.build / "zephyr/zephyr.dts"
        self.dts.write_text("/dts-v1/;\n/ { soc { spi@1234 {}; }; };\n",
                            encoding="utf-8")

    def test_bundle_is_exact_headless_source(self):
        for name in ("__init__.py", "collector.py", "dt_binding_preview.py",
                     "peripheral_guide.py"):
            with self.subTest(name=name):
                self.assertEqual((ROOT / "zew_evidence" / name).read_bytes(),
                                 (SCRIPTS / "zew_evidence" / name).read_bytes(),
                                 "Native copy must be byte-identical to headless implementation")

    def run_native(self, *options):
        completed = subprocess.run(
            [sys.executable, "-I", "-B", str(SCRIPTS / "peripheral_guide_inspect.py"),
             "--build", str(self.build), "--zephyr", str(self.zephyr),
             "--node", "/soc/spi@1234", "--application", str(self.app),
             "--overlay", str(self.overlay), *options],
            cwd=self.root, capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=30)
        lines = completed.stdout.strip().splitlines()
        self.assertEqual(len(lines), 2, completed.stderr)
        self.assertEqual(lines[0], "ZEW-GUIDE-1")
        fields = [base64.b64decode(v, validate=True).decode("utf-8")
                  for v in lines[1].split("\t")]
        self.assertEqual(len(fields), 14)
        return completed.returncode, fields, completed.stdout

    def test_real_isolated_python_integer_diff_and_copyable_addition(self):
        before = self.dts.read_bytes(), self.overlay.read_bytes()
        code, fields, wire = self.run_native("--set-int", "spi-max-frequency=1000000")
        self.assertEqual(code, 0)
        self.assertEqual(fields[0:2], ["draft-requires-real-configuration", "int"])
        self.assertEqual(fields[2], hashlib.sha256(b"/soc/spi@1234").hexdigest())
        self.assertEqual(fields[3], hashlib.sha256(b"spi-max-frequency").hexdigest())
        self.assertEqual(fields[4], hashlib.sha256(before[0]).hexdigest())
        self.assertEqual(fields[5], hashlib.sha256(before[1]).hexdigest())
        self.assertEqual(fields[7:11], ["matched", "confirmed", "int", "okay"])
        self.assertEqual(fields[11], "existing-file-proposal")
        self.assertIn("+    spi-max-frequency = <1000000>;", fields[12])
        self.assertIn("&{/soc/spi@1234} {", fields[13])
        self.assertNotIn("An earlier source override", fields[13])
        self.assertEqual((self.dts.read_bytes(), self.overlay.read_bytes()), before)
        self.assertEqual(self.run_native("--set-int", "spi-max-frequency=1000000")[2], wire)
        self.assertNotIn(str(self.root), wire)

    def test_string_enum_is_binding_checked(self):
        code, fields, _ = self.run_native("--set-enum", "zephyr,reference=external")
        self.assertEqual(code, 0)
        self.assertEqual(fields[1], "enum")
        self.assertIn('zephyr,reference = "external";', fields[13])
        code, fields, wire = self.run_native("--set-enum", "zephyr,reference=fabricated")
        self.assertEqual(code, 1)
        self.assertEqual(fields[0], "manual-review-required")
        self.assertEqual(fields[12:14], ["", ""])
        self.assertNotIn("fabricated", wire)

    def test_status_and_unknown_or_wrong_source_never_write(self):
        old = self.overlay.read_bytes()
        code, fields, _ = self.run_native("--status", "disabled")
        self.assertEqual(code, 0)
        self.assertEqual(fields[1], "status")
        self.assertIn('status = "disabled";', fields[13])
        (self.build / "CMakeCache.txt").write_text("ZEPHYR_BASE:PATH=/wrong\n")
        code, fields, _ = self.run_native("--status", "okay")
        self.assertEqual(code, 1)
        self.assertEqual(fields[0], "unavailable")
        self.assertEqual(fields[12], "")
        self.assertEqual(self.overlay.read_bytes(), old)

    def test_new_overlay_proposal_does_not_create_file(self):
        self.overlay.unlink()
        code, fields, _ = self.run_native("--status", "okay")
        self.assertEqual(code, 0)
        self.assertEqual(fields[5], "0" * 64)
        self.assertEqual(fields[11], "new-file-proposal")
        self.assertFalse(self.overlay.exists())


if __name__ == "__main__":
    unittest.main()
