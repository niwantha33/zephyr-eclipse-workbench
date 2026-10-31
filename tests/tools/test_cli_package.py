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
    def test_checkout_cli_can_import_sibling_package_in_isolated_python(self):
        # Windows embedded Python's python313._pth behaves like -I: it omits
        # script-directory imports even when the script path is absolute.
        repo = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory(prefix="zew-isolated-cli-") as temporary:
            root = Path(temporary)
            build = root / "build"
            (build / "zephyr").mkdir(parents=True)
            (build / "zephyr/.config").write_text("CONFIG_GPIO=y\n", encoding="utf-8")
            request = root / "requested.conf"
            request.write_text("CONFIG_GPIO=y\nCONFIG_FAKE_UNDEFINED=y\n", encoding="utf-8")
            ninja = root / "ninja.exe"
            ninja.write_text("unused", encoding="utf-8")
            command = [sys.executable, "-I", str(repo / "zew.py"), "config-check",
                       "--build", str(build), "--snippet", str(request),
                       "--semantic", "--ninja", str(ninja)]
            isolated = subprocess.run(command, cwd=root,
                                      env=dict(os.environ, PYTHONPATH="", PYTHONDONTWRITEBYTECODE="1"),
                                      text=True, capture_output=True, timeout=20)
            self.assertEqual(isolated.returncode, 1, isolated.stderr)
            report = json.loads(isolated.stdout)
            self.assertEqual(report["images"][0]["semantic"]["state"], "unavailable")
            self.assertEqual(report["semantic_validation"]["state"], "incomplete-or-blocked")

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
            # The deterministic zipapp must also ship dt-check, even though
            # parsing actual DTS needs the selected checkout's edtlib + PyYAML.
            dt_help = subprocess.run([sys.executable, str(first), "dt-check", "--help"],
                                     cwd=root, env=env, text=True,
                                     capture_output=True, timeout=15, check=True)
            self.assertIn("--zephyr", dt_help.stdout)
            self.assertIn("--overlay", dt_help.stdout)
            workspace = root / "build"
            workspace.mkdir()
            output = subprocess.run([sys.executable, str(first), "graph", "--build", str(workspace)],
                                    cwd=root, env=env, text=True, capture_output=True, timeout=15, check=True)
            self.assertEqual(json.loads(output.stdout)["status"], "observations-only")
            self.assertEqual(list(workspace.iterdir()), [])
            # The packaged CLI must include the new checker and preserve its
            # 0=matching-snapshot, 1=partial/mismatch return-code contract.
            (workspace / "zephyr").mkdir()
            (workspace / "zephyr/.config").write_text("CONFIG_GPIO=y\n", encoding="utf-8")
            request = root / "sample.conf"
            request.write_text("CONFIG_GPIO=y\n", encoding="utf-8")
            command_check = [sys.executable, str(first), "config-check",
                             "--build", str(workspace), "--snippet", str(request)]
            matching = subprocess.run(command_check, cwd=root, env=env, text=True,
                                      capture_output=True, timeout=15)
            self.assertEqual(matching.returncode, 0, matching.stderr)
            self.assertEqual(json.loads(matching.stdout)["status"], "matching-snapshot")
            # The optional packaged semantic engine must never claim success
            # without a generated Ninja/Kconfiglib environment.
            missing_semantics = subprocess.run([*command_check, "--semantic"],
                                              cwd=root, env=env, text=True,
                                              capture_output=True, timeout=15)
            self.assertEqual(missing_semantics.returncode, 1, missing_semantics.stderr)
            self.assertEqual(json.loads(missing_semantics.stdout)["images"][0]["semantic"]["state"],
                             "unavailable")
            request.write_text("CONFIG_GPIO=n\n", encoding="utf-8")
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
