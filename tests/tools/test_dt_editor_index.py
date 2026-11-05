#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Protocol safety tests independent of a Zephyr installation."""
import base64
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / (
    "plugins/io.github.niwantha33.zew.ui/scripts/dt_editor_index.py"
)


def field(value):
    return base64.b64decode(value).decode("utf-8")


class DevicetreeEditorIndexTests(unittest.TestCase):
    def test_missing_build_does_not_offer_controller(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = subprocess.check_output([sys.executable, str(SCRIPT), "--zephyr", str(root),
                                              "--application", str(root), "--build", str(root)],
                                             text=True)
            lines = output.strip().splitlines()
            self.assertEqual(lines[0], "ZEW-DTASSIST-1")
            self.assertEqual(lines[1].split("\t")[0], "S")
            self.assertEqual(field(lines[1].split("\t")[1]), "no-build")
            self.assertFalse(any(row.startswith("N\t") for row in lines))

    def test_mismatched_checkout_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "zephyr").mkdir()
            build = root / "build"
            (build / "zephyr").mkdir(parents=True)
            (build / "zephyr/zephyr.dts").write_text('/ { compatible = "fake"; };')
            (build / "CMakeCache.txt").write_text("ZEPHYR_BASE:PATH=/other/location\n")
            output = subprocess.check_output([sys.executable, str(SCRIPT),
                                              "--zephyr", str(root / "zephyr"),
                                              "--application", str(root), "--build", str(build)],
                                             text=True)
            lines = output.strip().splitlines()
            self.assertEqual(lines[0], "ZEW-DTASSIST-1")
            self.assertEqual(field(lines[1].split("\t")[1]), "mismatch")
            self.assertFalse(any(row.startswith("N\t") for row in lines))


if __name__ == "__main__":
    unittest.main()
