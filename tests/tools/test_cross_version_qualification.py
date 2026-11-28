# SPDX-License-Identifier: Apache-2.0
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("cross_version_qualification", ROOT / "scripts/qualify_cross_version.py")
qualification = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qualification)


class CrossVersionQualificationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.old = self.setup("old", "4.4.0")
        self.new = self.setup("new", "4.5.0")
        self.app = self.root / "application"
        self.app.mkdir()
        (self.app / "CMakeLists.txt").write_text("fixture")
        self.output = self.root / "evidence"

    def setup(self, name, version):
        root = self.root / name
        root.mkdir()
        for marker in (".zew-managed", ".zew-ready"):
            (root / marker).write_text("fixture")
        values = {"workspace": root / "workspace", "tools.sdk": root / "sdk",
                  "python": root / "python.exe", "tools.git": root / "git.exe",
                  "tools.cmake": root / "cmake.exe", "tools.ninja": root / "ninja.exe"}
        for key, path in values.items():
            if key in ("workspace", "tools.sdk"):
                path.mkdir()
            else:
                path.write_bytes(b"fixture")
        workspace = values["workspace"]
        (workspace / ".west").mkdir()
        (workspace / ".west/config").write_text("fixture")
        (workspace / "zephyr").mkdir()
        major, minor, patchlevel = version.split(".")
        (workspace / "zephyr/VERSION").write_text(
            f"VERSION_MAJOR = {major}\nVERSION_MINOR = {minor}\nPATCHLEVEL = {patchlevel}\n")
        (values["tools.sdk"] / "sdk_version").write_text("1.0.1")
        def escape(value):
            return str(value).replace("\\", "\\\\").replace(":", "\\:").replace("=", "\\=")
        (root / "environment.properties").write_text("\n".join(key + "=" + escape(value)
                                                                for key, value in values.items()))
        return root

    def test_plan_is_read_only_and_rejects_unsafe_destinations(self):
        with patch.object(qualification, "build_one") as build:
            result = qualification.qualify(self.old, self.new, self.app, self.output, ["qemu_x86/atom"])
            self.assertEqual("plan-only", result["status"])
            self.assertFalse(self.output.exists())
            for destination in (self.old / "trial", self.new / "trial", self.app / "trial"):
                with self.assertRaises(ValueError):
                    qualification.qualify(self.old, self.new, self.app, destination, ["qemu_x86/atom"])
            with self.assertRaises(ValueError):
                qualification.qualify(self.old, self.new, self.app, self.output, ["--erase"])
            build.assert_not_called()

    def test_two_compile_results_do_not_claim_hardware_or_byte_equivalence(self):
        first = {"artifacts": {"zephyr.elf": "a", ".config": "b", "zephyr.dts": "c"}}
        second = {"artifacts": {"zephyr.elf": "d", ".config": "b", "zephyr.dts": "c"}}
        with patch.object(qualification, "build_one", side_effect=[first, second]) as build:
            result = qualification.qualify(self.old, self.new, self.app, self.output, ["qemu_x86/atom"], True)
        self.assertEqual(2, build.call_count)
        self.assertEqual("compile-matrix-passed", result["status"])
        self.assertEqual("not-tested", result["physical_hardware"])
        self.assertFalse(result["boards"][0]["elf_bytes_match"])
        self.assertTrue(result["boards"][0]["config_bytes_match"])
        self.assertTrue((self.output / "qualification.json").is_file())
        self.assertEqual("fixture", (self.app / "CMakeLists.txt").read_text())

    def test_failed_build_keeps_incomplete_evidence(self):
        with patch.object(qualification, "build_one", side_effect=ValueError("failed")):
            result = qualification.qualify(self.old, self.new, self.app, self.output, ["qemu_x86/atom"], True)
        self.assertEqual("incomplete", result["status"])
        self.assertEqual("not-tested", result["physical_hardware"])
        self.assertTrue((self.output / "qualification.json").is_file())
