# SPDX-License-Identifier: Apache-2.0
"""Offline safety tests for optional parallel release source provisioning."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[2] / "plugins" / "io.github.niwantha33.zew.ui" / "tools"
spec = importlib.util.spec_from_file_location("release_sources", TOOLS / "release_sources.py")
sys.path.insert(0, str(TOOLS))
sources = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sources)

ZEPHYR_44 = "a" * 40
ZEPHYR_45 = "b" * 40
MODULE_44 = "c" * 40
MODULE_45 = "d" * 40


class ReleaseSourceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.parent = Path(self.temp.name)
        self.current = self.parent / "installed-4.4" / "workspace"
        self.current.mkdir(parents=True)
        self.target = self.parent / "zephyr-4.5-sources"
        self.python = self.parent / "python.exe"
        self.git = self.parent / "git.exe"
        self.python.write_bytes(b"python")
        self.git.write_bytes(b"git")
        self.manifest = "manifest:\n  projects: []\n"
        self.module = {"name": "cmsis", "path": "modules/hal/cmsis",
                       "commit": MODULE_45, "installed_at_snapshot": True, "url": ""}
        self.record = {
            "release": "v4.5.0",
            "zephyr_commit": ZEPHYR_45,
            "modules": [self.module],
            "module_count": 1,
            "west_manifest_sha256": hashlib.sha256(self.manifest.encode()).hexdigest(),
            "schema": 1,
            "kind": "zephyr-source-only",
            "status": "SOURCE_VERIFIED_ONLY",
            "sdk_installed": False,
            "firmware_built": False
        }

    def test_only_explicit_stable_release_is_accepted(self):
        self.assertEqual("4.5.0", sources.check_release("v4.5.0"))
        for bad in ("main", "4.5.0", "v4.5.0-rc1", "v4.5", "v4.5.0;rm -rf /", "v4.5.0\nx"):
            with self.subTest(value=bad), self.assertRaises(sources.VersionError):
                sources.check_release(bad)

    def test_refuse_old_workspace_and_descendants_and_parents(self):
        for candidate in (self.current, self.current / "candidate", self.current.parent):
            with self.subTest(candidate=candidate), self.assertRaises(sources.VersionError):
                sources.check_destination(candidate, self.current)

    def test_reject_existing_destination_without_mutations(self):
        self.target.mkdir()
        (self.target / "data.txt").write_text("original")
        with self.assertRaisesRegex(sources.VersionError, "already exists"):
            sources.check_destination(self.target, self.current)
        self.assertEqual("original", (self.target / "data.txt").read_text())

    def test_symlink_to_existing_workspace_not_accepted(self):
        link = self.parent / "alias"
        try:
            link.symlink_to(self.current, target_is_directory=True)
        except (OSError, NotImplementedError):
            self.skipTest("Symlinks unavailable on this host")
        with self.assertRaises(sources.VersionError):
            sources.check_destination(link / "new", self.current)

    def test_plan_cli_does_not_write_or_fetch(self):
        command = [sys.executable, str(TOOLS / "release_sources.py"), "plan",
                   "--release", "v4.5.0",
                   "--destination", str(self.target),
                   "--original-workspace", str(self.current)]
        result = subprocess.run(command, capture_output=True, text=True, timeout=20, check=True)
        body = json.loads(result.stdout)
        self.assertEqual("plan-only", body["status"])
        self.assertTrue(body["source_only"])
        self.assertFalse(self.target.exists())

    def test_install_requires_explicit_permission_before_creating_stage(self):
        with self.assertRaisesRegex(sources.VersionError, "allow-network"):
            sources.install("v4.5.0", self.target, self.current, self.python, self.git, False)
        self.assertFalse(self.target.exists())
        self.assertEqual([], list(self.parent.glob(".zew-source-stage-*")))

    def test_installs_sibling_sources_without_claiming_build_ready(self):
        executed = []
        def fake_run(argv, cwd, seconds=1800, capture=False):
            executed.append(list(map(str, argv)))
            return ""
        with patch.object(sources, "run", side_effect=fake_run), patch.object(
                sources, "capture_sources", return_value=(self.manifest, self.record)):
            result = sources.install("v4.5.0", self.target, self.current,
                                     self.python, self.git, True)
        self.assertEqual("source-prepared", result["status"])
        self.assertFalse(result["build_ready"])
        self.assertTrue((self.target / ".zew-source-ready").is_file())
        self.assertFalse((self.target / ".zew-ready").exists())
        self.assertEqual(self.manifest, (self.target / sources.SOURCE_FROZEN).read_text())
        self.assertEqual(ZEPHYR_45, json.loads((self.target / sources.SOURCE_LOCK).read_text())["zephyr_commit"])
        self.assertTrue((self.current / ".").is_dir())
        self.assertIn("init", executed[0])
        self.assertIn("--mr", executed[0])
        self.assertIn("update", executed[1])

    def test_failed_clone_never_moves_staging_to_requested_destination(self):
        with patch.object(sources, "run", side_effect=sources.VersionError("offline")):
            with self.assertRaisesRegex(sources.VersionError, "offline"):
                sources.install("v4.5.0", self.target, self.current,
                                self.python, self.git, True)
        self.assertFalse(self.target.exists())
        self.assertEqual(1, len(list(self.parent.glob(".zew-source-stage-*"))))
        self.assertTrue(self.current.is_dir())

    def test_unchanged_candidate_verifies_without_sdk(self):
        self.target.mkdir()
        (self.target / ".zew").mkdir()
        (self.target / ".zew-source-ready").write_text("SOURCE_VERIFIED_ONLY\n")
        (self.target / sources.SOURCE_FROZEN).write_bytes(self.manifest.encode("utf-8"))
        (self.target / sources.SOURCE_LOCK).write_text(json.dumps(self.record))
        with patch.object(sources, "capture_sources", return_value=(self.manifest, self.record)):
            verified = sources.verify_candidate(self.target, self.python, self.git)
        self.assertEqual(ZEPHYR_45, verified["zephyr_commit"])
        (self.target / sources.SOURCE_FROZEN).write_text("tampered")
        with patch.object(sources, "capture_sources", return_value=(self.manifest, self.record)):
            with self.assertRaisesRegex(sources.VersionError, "differs"):
                sources.verify_candidate(self.target, self.python, self.git)

    def test_compare_does_not_rename_original_source_or_sdk(self):
        app = self.parent / "app"
        (app / ".zew").mkdir(parents=True)
        original = {"schema": 1, "zephyr": {"commit": ZEPHYR_44},
                    "modules": [{"name": "cmsis", "commit": MODULE_44}]}
        lock_path = app / ".zew" / "project-lock.json"
        lock_path.write_text(json.dumps(original))
        before = lock_path.read_bytes()
        with patch.object(sources, "verify_candidate", return_value=self.record):
            answer = sources.compare(app, self.target, self.python, self.git)
        self.assertEqual("source-comparison-only", answer["status"])
        self.assertEqual(["zephyr", "module:cmsis"], [x["item"] for x in answer["differences"]])
        self.assertFalse(answer["build_ready"])
        self.assertEqual(before, lock_path.read_bytes())


if __name__ == "__main__":
    unittest.main()
