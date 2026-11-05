# SPDX-License-Identifier: Apache-2.0
"""Offline, mocked West/Git tests. Does not claim a real 4.5 build."""
import importlib.util
import json
import subprocess
import sys
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SOURCE = (Path(__file__).resolve().parents[2] / "plugins"
          / "io.github.niwantha33.zew.ui" / "tools" / "project_versions.py")
spec = importlib.util.spec_from_file_location("project_versions", SOURCE)
versions = importlib.util.module_from_spec(spec)
spec.loader.exec_module(versions)

ZEPHYR = "1" * 40
MODULE = "2" * 40
OTHER = "3" * 40


class ProjectVersionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.workspace = self.root / "ws"
        (self.workspace / ".west").mkdir(parents=True)
        (self.workspace / ".west" / "config").write_text("[manifest]\npath = zephyr\n")
        (self.workspace / "zephyr").mkdir()
        (self.workspace / "modules" / "test").mkdir(parents=True)
        (self.workspace / "zephyr" / "VERSION").write_text(
            "VERSION_MAJOR = 4\nVERSION_MINOR = 4\nPATCHLEVEL = 0\n")
        self.application = self.root / "app"
        self.application.mkdir()
        (self.application / "CMakeLists.txt").write_text("cmake_minimum_required(VERSION 3.20)\n")
        self.sdk = self.root / "sdk"
        self.sdk.mkdir()
        (self.sdk / "sdk_version").write_text("1.0.1")
        self.tools = {}
        for name in ("python", "git", "cmake", "ninja"):
            tool = self.root / name
            tool.write_bytes(("fake-" + name).encode())
            self.tools[name] = tool
        self.heads = {"zephyr": ZEPHYR, "test": MODULE}
        self.dirty = False
        self.fail_freeze = False
        self.frozen = ("manifest:\n  projects:\n"
                       "    - name: test\n"
                       "      path: modules/test\n"
                       "      revision: " + MODULE + "\n"
                       "      url: https://example.org/test.git\n"
                       "  self:\n    path: zephyr\n")
        self.mock = patch.object(versions, "command", side_effect=self.command)
        self.mock.start()
        self.addCleanup(self.mock.stop)

    def command(self, argv, cwd, timeout=120):
        parts = [str(x) for x in argv]
        if "manifest" in parts and "--freeze" in parts:
            if self.fail_freeze:
                raise versions.VersionError("Optional checkout missing")
            return self.frozen.rstrip()
        if "manifest" in parts and "--resolve" in parts:
            return self.frozen.rstrip()
        if "rev-parse" in parts:
            return self.heads.get(Path(parts[parts.index("-C") + 1]).name,
                                  self.heads["zephyr"])
        if "status" in parts:
            return " M changed" if self.dirty else ""
        return "Tool version 1.0"

    def record(self, workspace=None, sdk=None):
        return versions.system_record(
            workspace or self.workspace, self.tools["python"], self.tools["git"],
            sdk or self.sdk, self.tools["cmake"], self.tools["ninja"],
            "qemu_x86/atom", self.application)

    def args(self, action, *extra):
        result = [action, "--application", str(self.application),
                  "--workspace", str(self.workspace),
                  "--python", str(self.tools["python"]),
                  "--git", str(self.tools["git"]),
                  "--sdk", str(self.sdk),
                  "--cmake", str(self.tools["cmake"]),
                  "--ninja", str(self.tools["ninja"]),
                  "--board", "qemu_x86/atom"]
        return result + list(extra)

    def test_first_snapshot_creates_real_frozen_manifest_and_lock(self):
        frozen, record = self.record()
        result = versions.snapshot(self.application, frozen, record)
        self.assertEqual("created", result["status"])
        self.assertEqual(MODULE, versions.load_lock(self.application)["modules"][0]["commit"])
        self.assertEqual(versions.sha256(frozen.encode()), versions.file_hash(self.application / versions.FROZEN))
        self.assertEqual("unchanged", versions.snapshot(self.application, frozen, record)["status"])

    def test_new_source_does_not_overwrite_original_snapshot(self):
        frozen, record = self.record()
        versions.snapshot(self.application, frozen, record)
        original = (self.application / versions.LOCK).read_bytes()
        (self.application / "CMakeLists.txt").write_text("changed")
        with self.assertRaisesRegex(versions.VersionError, "Never overwrite"):
            versions.snapshot(self.application, *self.record())
        self.assertEqual(original, (self.application / versions.LOCK).read_bytes())
        self.assertEqual("match", versions.main(self.args("verify-env"))["status"])
        self.assertEqual("drift", versions.main(self.args("verify"))["status"])

    def test_local_build_logs_do_not_change_source_snapshot_or_lock(self):
        (self.application / "prj.conf").write_text("CONFIG_GPIO=y\n")
        metadata = self.application / ".zew"
        metadata.mkdir()
        (metadata / "project.json").write_text('{"board":"qemu_x86/atom"}')
        baseline = versions.project_sources(self.application)
        frozen, record = self.record()
        versions.snapshot(self.application, frozen, record)
        logs = metadata / "local-build-logs" / "debug-attempt"
        logs.mkdir(parents=True)
        log = logs / "build.log"
        log.write_text("compiler error\n")
        receipt = logs / "build.log.failure.txt"
        receipt.write_text("failure investigation\n")
        self.assertEqual(baseline, versions.project_sources(self.application))
        self.assertEqual("match", versions.main(self.args("verify"))["status"])
        log.write_text("updated compiler error\n")
        receipt.write_text("updated investigation\n")
        self.assertEqual(baseline, versions.project_sources(self.application))
        self.assertEqual("match", versions.main(self.args("verify"))["status"])

        oversized = logs / "large.log"
        with oversized.open("wb") as output:
            output.truncate(versions.MAX_FILE_BYTES + 1)
        original_stat = Path.stat
        original_hash = versions.file_hash
        def source_stat(path, *args, **kwargs):
            self.assertFalse(path.is_relative_to(logs), "Diagnostic output must be skipped before stat")
            return original_stat(path, *args, **kwargs)
        def source_hash(path):
            self.assertFalse(path.is_relative_to(logs), "Diagnostic output must be skipped before hashing")
            return original_hash(path)
        with patch.object(Path, "stat", source_stat), patch.object(versions, "file_hash", source_hash):
            self.assertEqual(baseline, versions.project_sources(self.application))

    def test_log_exclusion_keeps_configuration_and_similarly_named_sources(self):
        tracked = ("prj.conf", ".zew/project.json", ".zew/local-build-logs-source/helper.c",
                   "src/.zew/local-build-logs/helper.c", "local-build-logs/helper.c")
        for relative in tracked:
            source = self.application / relative
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text("original source")
        baseline = versions.project_sources(self.application)
        for relative in tracked:
            self.assertIn(relative, baseline)
            source = self.application / relative
            source.write_text("changed source")
            changed = versions.project_sources(self.application)
            self.assertNotEqual(baseline[relative], changed[relative], relative)
            source.write_text("original source")

    def test_module_drift_is_rejected(self):
        self.heads["test"] = OTHER
        with self.assertRaisesRegex(versions.VersionError, "resolved manifest expects"):
            self.record()

    def test_dirty_module_is_rejected(self):
        self.dirty = True
        with self.assertRaisesRegex(versions.VersionError, "uncommitted files"):
            self.record()

    def test_tampered_manifest_is_rejected(self):
        frozen, record = self.record()
        versions.snapshot(self.application, frozen, record)
        (self.application / versions.FROZEN).write_text("tampered")
        with self.assertRaisesRegex(versions.VersionError, "missing or changed"):
            versions.main(self.args("verify"))

    def test_source_drift_does_not_hide_tool_or_sdk_drift(self):
        frozen, record = self.record()
        versions.snapshot(self.application, frozen, record)
        (self.sdk / "sdk_version").write_text("1.0.2")
        report = versions.main(self.args("verify-env"))
        self.assertEqual("drift", report["status"])
        self.assertIn("sdk.version", [diff["item"] for diff in report["differences"]])

    def test_refuses_in_place_upgrade(self):
        frozen, record = self.record()
        versions.snapshot(self.application, frozen, record)
        with self.assertRaisesRegex(versions.VersionError, "in-place update is forbidden"):
            versions.main(self.args("plan", "--candidate-workspace", str(self.workspace),
                                    "--candidate-sdk", str(self.sdk)))

    def test_cli_plain_errors_for_missing_snapshot(self):
        result = subprocess.run([sys.executable, str(SOURCE),
                                 *self.args("verify", "--plain-errors")],
                                capture_output=True, text=True, timeout=20)
        self.assertEqual(2, result.returncode)
        self.assertTrue(result.stdout.startswith("ERROR: No project lock found."), result.stdout)
        self.assertNotIn('"status": "error"', result.stdout)

    def test_cli_json_errors_remain_machine_readable(self):
        result = subprocess.run([sys.executable, str(SOURCE),
                                 *self.args("verify")],
                                capture_output=True, text=True, timeout=20)
        self.assertEqual(2, result.returncode)
        self.assertEqual("error", json.loads(result.stdout)["status"])

    def test_same_workspace_alias_is_rejected(self):
        frozen, record = self.record()
        versions.snapshot(self.application, frozen, record)
        alias = self.root / "ws_alias"
        try:
            alias.symlink_to(self.workspace, target_is_directory=True)
        except (NotImplementedError, OSError):
            self.skipTest("This host disallows creation of directory symlinks")
        with self.assertRaisesRegex(versions.VersionError, "in-place update is forbidden"):
            versions.main(self.args("plan", "--candidate-workspace", str(alias),
                                    "--candidate-sdk", str(self.sdk)))

    def test_rejects_unsafe_west_paths(self):
        self.frozen = self.frozen.replace("modules/test", "../outside")
        with self.assertRaisesRegex(versions.VersionError, "Unsafe West project path"):
            self.record()

    def test_sparse_west_modules_can_still_be_pinned_by_source_sha(self):
        (self.workspace / "modules" / "test").rmdir()
        self.fail_freeze = True
        result = versions.main(self.args("snapshot"))
        self.assertEqual("created", result["status"])
        self.assertEqual(["test"], result["modules_not_installed"])
        self.assertEqual("west-resolve-all-sha",
                         versions.load_lock(self.application)["manifest_origin"])
        self.assertEqual("match", versions.main(self.args("verify-env"))["status"])

    def test_floating_revisions_cannot_be_called_pinned(self):
        self.fail_freeze = True
        self.frozen = self.frozen.replace(MODULE, "main")
        with self.assertRaisesRegex(versions.VersionError, "floating branch"):
            versions.main(self.args("snapshot"))
        self.assertFalse((self.application / versions.LOCK).exists())

    def test_json_lock_never_contains_absolute_setup_path(self):
        frozen, record = self.record()
        versions.snapshot(self.application, frozen, record)
        text = (self.application / versions.LOCK).read_text()
        self.assertNotIn(str(self.root), text)
        self.assertIn('"full_sdk_archive_attested": false', text)


if __name__ == "__main__":
    unittest.main()
