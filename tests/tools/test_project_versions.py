# SPDX-License-Identifier: Apache-2.0
"""Offline, mocked West/Git tests. Does not claim a real 4.5 build."""
import importlib.util
import json
import os
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
        # Windows TEMP may use an 8.3 alias (RUNNER~1). Production probes
        # deliberately canonicalize paths; construct the fixture the same way.
        self.root = Path(self.temp.name).resolve(strict=True)
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

    def command(self, argv, cwd, timeout=120, *, env=None):
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

    def test_managed_probe_environment_uses_only_selected_tools(self):
        (self.root / ".zew-managed").write_text("fixture")
        host = {"Path": "other-setup", "PYTHONPATH": "host-python",
                "Git_Config_Count": "1", "GIT_CONFIG_KEY_0": "core.worktree",
                "WEST_CONFIG_LOCAL": "old-config", "ZEPHYR_BASE": "old-zephyr",
                "CMAKE_PREFIX_PATH": "old-cmake", "TEMP": "host-temp"}
        if os.name == "nt":
            host["SystemRoot"] = os.environ["SystemRoot"]
        env = versions.managed_environment(self.root, self.workspace, self.tools["python"],
                                           self.tools["git"], self.sdk, self.tools["cmake"],
                                           self.tools["ninja"], host)
        self.assertEqual(str(self.tools["git"].parent.resolve()), env["PATH"].split(os.pathsep)[0])
        self.assertNotIn("other-setup", env["PATH"])
        self.assertNotIn("Path", env)
        self.assertNotIn("PYTHONPATH", env)
        self.assertNotIn("Git_Config_Count", env)
        self.assertNotIn("GIT_CONFIG_KEY_0", env)
        self.assertNotIn("CMAKE_PREFIX_PATH", env)
        self.assertEqual(str(self.workspace.resolve() / ".west/config"), env["WEST_CONFIG_LOCAL"])
        self.assertEqual(str(self.sdk.resolve()), env["ZEPHYR_SDK_INSTALL_DIR"])
        self.assertEqual("file", env["GIT_ALLOW_PROTOCOL"])
        self.assertEqual("host-temp", env["TEMP"])
        self.assertEqual("other-setup", host["Path"])

    def test_managed_candidate_refuses_original_tools_before_execution(self):
        candidate = self.root / "candidate-setup"
        candidate.mkdir()
        (candidate / ".zew-managed").write_text("fixture")
        workspace = candidate / "workspace"
        workspace.mkdir()
        sdk = candidate / "sdk"
        sdk.mkdir()
        with self.assertRaisesRegex(versions.VersionError, "outside its setup"):
            versions.system_record(workspace, self.tools["python"], self.tools["git"],
                                   sdk, self.tools["cmake"], self.tools["ninja"],
                                   "qemu_x86/atom", self.application, managed_root=candidate)
        self.assertEqual(0, versions.command.call_count)

    def test_managed_migration_rejects_nested_setup_roots(self):
        candidate = self.root / "candidate-setup"
        candidate.mkdir()
        with self.assertRaisesRegex(versions.VersionError, "non-nested managed setup roots"):
            versions.main(self.args("plan", "--managed-root", str(self.root),
                                    "--candidate-managed-root", str(candidate),
                                    "--candidate-workspace", str(candidate),
                                    "--candidate-sdk", str(self.sdk),
                                    "--candidate-python", str(self.tools["python"]),
                                    "--candidate-git", str(self.tools["git"]),
                                    "--candidate-cmake", str(self.tools["cmake"]),
                                    "--candidate-ninja", str(self.tools["ninja"])))
        self.assertEqual(0, versions.command.call_count)

    def test_two_managed_setups_have_independent_probe_environments(self):
        roots = [self.root / name for name in ("original-setup", "candidate-setup")]
        for root in roots:
            root.mkdir()
            (root / ".zew-managed").write_text("fixture")
            workspace, sdk = root / "workspace", root / "sdk"
            workspace.mkdir()
            sdk.mkdir()
            tools = {}
            for name in ("python", "git", "cmake", "ninja"):
                folder = root / name
                folder.mkdir()
                tools[name] = folder / name
                tools[name].write_bytes(b"fixture")
            env = versions.managed_environment(root, workspace, tools["python"],
                                               tools["git"], sdk, tools["cmake"], tools["ninja"])
            other = roots[1] if root == roots[0] else roots[0]
            self.assertEqual(str(tools["git"].parent.resolve()), env["PATH"].split(os.pathsep)[0])
            self.assertFalse(any(Path(entry).resolve().is_relative_to(other.resolve())
                                 for entry in env["PATH"].split(os.pathsep)))
            self.assertEqual(str(workspace.resolve() / ".west/config"), env["WEST_CONFIG_LOCAL"])
            self.assertEqual(str(sdk.resolve()), env["ZEPHYR_SDK_INSTALL_DIR"])

    def test_managed_record_passes_selected_environment_to_every_probe(self):
        (self.root / ".zew-managed").write_text("fixture")
        versions.system_record(self.workspace, self.tools["python"], self.tools["git"],
                               self.sdk, self.tools["cmake"], self.tools["ninja"],
                               "qemu_x86/atom", self.application, managed_root=self.root)
        calls = versions.command.call_args_list
        self.assertGreater(len(calls), 5)
        for call in calls:
            env = call.kwargs["env"]
            self.assertEqual(str(self.workspace.resolve() / ".west/config"), env["WEST_CONFIG_LOCAL"])
            self.assertEqual(str(self.sdk.resolve()), env["ZEPHYR_SDK_INSTALL_DIR"])
        self.assertEqual(1, len({id(call.kwargs["env"]) for call in calls}))

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

    def migration_args(self, destination, *extra):
        candidate = self.root / "candidate"
        if not candidate.exists():
            import shutil
            shutil.copytree(self.workspace, candidate)
            (candidate / "zephyr" / "VERSION").write_text(
                "VERSION_MAJOR = 4\nVERSION_MINOR = 5\nPATCHLEVEL = 0\n")
        return self.args("migration-copy", "--candidate-workspace", str(candidate),
                         "--candidate-sdk", str(self.sdk),
                         "--candidate-python", str(self.tools["python"]),
                         "--candidate-git", str(self.tools["git"]),
                         "--candidate-cmake", str(self.tools["cmake"]),
                         "--candidate-ninja", str(self.tools["ninja"]),
                         "--destination", str(destination), *extra)

    def test_migration_copy_preserves_original_and_creates_new_lock(self):
        (self.application / "prj.conf").write_text("CONFIG_GPIO=y\n")
        versions.snapshot(self.application, *self.record())
        original_lock = (self.application / versions.LOCK).read_bytes()
        original_manifest = (self.application / versions.FROZEN).read_bytes()
        (self.application / "build").mkdir()
        (self.application / "build" / "old.bin").write_bytes(b"old")
        destination = self.root / "trial"
        result = versions.main(self.migration_args(destination, "--confirm-copy"))
        self.assertEqual("migration-copy-created", result["status"])
        self.assertEqual("not-run", result["trial_build"])
        self.assertEqual("4.5.0", versions.load_lock(destination)["zephyr"]["version"])
        self.assertEqual("CONFIG_GPIO=y\n", (destination / "prj.conf").read_text())
        self.assertFalse((destination / "build").exists())
        self.assertEqual(original_lock, (self.application / versions.LOCK).read_bytes())
        self.assertEqual(original_manifest, (self.application / versions.FROZEN).read_bytes())
        with self.assertRaisesRegex(versions.VersionError, "already exists"):
            versions.main(self.migration_args(destination, "--confirm-copy"))

    def test_migration_copy_rejects_unconfirmed_and_nested_destinations(self):
        versions.snapshot(self.application, *self.record())
        destination = self.root / "trial"
        with self.assertRaisesRegex(versions.VersionError, "confirm-copy"):
            versions.main(self.migration_args(destination))
        self.assertFalse(destination.exists())
        with self.assertRaisesRegex(versions.VersionError, "separate"):
            versions.main(self.migration_args(self.application / "trial", "--confirm-copy"))

    def test_migration_copy_rejects_original_source_drift(self):
        versions.snapshot(self.application, *self.record())
        (self.application / "CMakeLists.txt").write_text("modified")
        destination = self.root / "trial"
        with self.assertRaisesRegex(versions.VersionError, "differs from the saved lock"):
            versions.main(self.migration_args(destination, "--confirm-copy"))
        self.assertFalse(destination.exists())

    def test_module_alias_outside_workspace_is_rejected(self):
        module = self.workspace / "modules" / "test"
        module.rmdir()
        external = self.root / "external"
        external.mkdir()
        try:
            module.symlink_to(external, target_is_directory=True)
        except (NotImplementedError, OSError):
            self.skipTest("This host disallows directory symlinks")
        with self.assertRaisesRegex(versions.VersionError, "escapes the selected workspace"):
            self.record()

    def test_json_lock_never_contains_absolute_setup_path(self):
        frozen, record = self.record()
        versions.snapshot(self.application, frozen, record)
        text = (self.application / versions.LOCK).read_text()
        self.assertNotIn(str(self.root), text)
        self.assertIn('"full_sdk_archive_attested": false', text)


if __name__ == "__main__":
    unittest.main()
