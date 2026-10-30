# SPDX-License-Identifier: Apache-2.0
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

from scripts.package_cli import build


class CliPackageTests(unittest.TestCase):
    def test_repeatable_standalone_zipapp_and_no_overwrite(self):
        with tempfile.TemporaryDirectory(prefix="zew-cli-Ω ") as temporary:
            root = Path(temporary)
            first, second = root / "first.pyz", root / "second.pyz"
            digest = build(first)
            self.assertEqual(digest, build(second))
            self.assertEqual(digest, hashlib.sha256(first.read_bytes()).hexdigest())
            self.assertEqual(first.read_bytes(), second.read_bytes())
            with self.assertRaises(ValueError):
                build(first)
            with zipfile.ZipFile(first) as archive:
                self.assertIn("LICENSE", archive.namelist())
                self.assertIn("CLI_REFERENCE.md", archive.namelist())
                self.assertFalse(any("__pycache__" in name for name in archive.namelist()))
            env = dict(os.environ, PYTHONPATH="", PYTHONDONTWRITEBYTECODE="1")
            version = subprocess.run([sys.executable, str(first), "--version"], cwd=root,
                                     env=env, text=True, capture_output=True, timeout=15, check=True)
            self.assertIn("0.1.1", version.stdout)
            workspace = root / "build"
            workspace.mkdir()
            output = subprocess.run([sys.executable, str(first), "graph", "--build", str(workspace)],
                                    cwd=root, env=env, text=True, capture_output=True, timeout=15, check=True)
            self.assertEqual(json.loads(output.stdout)["status"], "observations-only")
            self.assertEqual(list(workspace.iterdir()), [])
            # The packaged CLI must include the new checker and preserve its
            # 0=matching-snapshot, 1=partial/mismatch return-code contract.
            (workspace / "zephyr").mkdir()
            (workspace / "zephyr/.config").write_text("CONFIG_GPIO=y\\n", encoding="utf-8")
            request = root / "sample.conf"
            request.write_text("CONFIG_GPIO=y\\n", encoding="utf-8")
            command_check = [sys.executable, str(first), "config-check",
                             "--build", str(workspace), "--snippet", str(request)]
            matching = subprocess.run(command_check, cwd=root, env=env, text=True,
                                      capture_output=True, timeout=15)
            self.assertEqual(matching.returncode, 0, matching.stderr)
            self.assertEqual(json.loads(matching.stdout)["status"], "matching-snapshot")
            request.write_text("CONFIG_GPIO=n\\n", encoding="utf-8")
            mismatch = subprocess.run(command_check, cwd=root, env=env, text=True,
                                      capture_output=True, timeout=15)
            self.assertEqual(mismatch.returncode, 1, mismatch.stderr)
            self.assertEqual(json.loads(mismatch.stdout)["counts"], {"differs-from-generated": 1})
            (workspace / "zephyr/.config").unlink()
            (workspace / "zephyr").rmdir()
            (workspace / "zephyr").mkdir()
            (workspace / "zephyr/.config").write_text("CONFIG_GPIO=y\n", encoding="utf-8")
            (workspace / "zephyr/zephyr.dts").write_text("/ { };\n", encoding="utf-8")
            bundle = root / "packaged-evidence.zip"
            subprocess.run([sys.executable, str(first), "bundle", "--build", str(workspace),
                            "--output", str(bundle), "--write"], cwd=root, env=env,
                           capture_output=True, text=True, timeout=15, check=True)
            command = [sys.executable, str(first), "verify", "--input", str(bundle), "--build", str(workspace)]
            same = subprocess.run(command, cwd=root, env=env, capture_output=True, text=True, timeout=15)
            self.assertEqual(same.returncode, 0, same.stderr)
            self.assertEqual(json.loads(same.stdout)["status"], "recorded-artifacts-match")
            (workspace / "zephyr/.config").write_text("CONFIG_GPIO=n\n", encoding="utf-8")
            changed = subprocess.run(command, cwd=root, env=env, capture_output=True, text=True, timeout=15)
            self.assertEqual(changed.returncode, 1, changed.stderr)
            self.assertEqual(json.loads(changed.stdout)["mismatches"], 1)
