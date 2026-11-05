# SPDX-License-Identifier: Apache-2.0
from pathlib import Path
import tempfile
import unittest
from unittest import mock
from zew_evidence.collector import InputError
from zew_evidence.studio_environment import inspect


class EnvironmentPreflightTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="preflight Ω ")
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.zephyr = self.root / "zephyr-checkout"
        self.zephyr.mkdir()
        self.tool = self.root / "ninja"
        self.tool.write_text("not an executable; must never run")
        self.cache = self.root / "CMakeCache.txt"
        self.cache.write_text("BOARD:STRING=board/core\nZEPHYR_BASE:PATH=" + str(self.zephyr) +
                              "\nCMAKE_MAKE_PROGRAM:FILEPATH=" + str(self.tool) +
                              "\nCMAKE_GENERATOR:INTERNAL=Ninja\n")

    def test_metadata_does_not_execute_and_distinguishes_unknown_compatibility(self):
        with mock.patch("subprocess.run", side_effect=AssertionError("must not run")):
            report = inspect(self.root, expected_board="board/core", expected_zephyr=self.zephyr)
        image = report["images"][0]
        self.assertEqual(image["board"], "matching")
        self.assertEqual(image["checkout"], "matching")
        self.assertEqual(image["tools"]["ninja"]["state"], "available")
        self.assertEqual(image["tools"]["ninja"]["version"], "unknown")
        self.assertEqual(image["tools"]["c_compiler"]["state"], "unknown")
        self.assertEqual(image["compatibility"], "unknown-no-tool-execution-or-sdk-qualification")
        self.assertEqual(report, inspect(self.root, expected_board="board/core", expected_zephyr=self.zephyr))

    def test_missing_tool_and_different_board_are_actionable_errors(self):
        self.tool.unlink()
        report = inspect(self.root, expected_board="other")
        image = report["images"][0]
        self.assertEqual(image["state"], "blockers-observed")
        self.assertEqual({f["rule_id"] for f in image["findings"]}, {"CS12-MISSING-NINJA", "CS12-BOARD-MISMATCH"})
        self.assertTrue(all(f["next_action"] for f in image["findings"]))

    def test_source_only_and_checkout_mismatch_not_silently_good(self):
        other = self.root / "other"
        other.mkdir()
        report = inspect(self.root, expected_zephyr=other)
        self.assertEqual(report["images"][0]["checkout"], "mismatch")
        self.cache.unlink()
        self.assertEqual(inspect(self.root)["images"][0]["reason"], "missing-cmake-cache")

    def test_duplicate_cache_identities_and_symlink_cache_rejected(self):
        self.cache.write_text("BOARD:STRING=a\nBOARD:STRING=b\n")
        with self.assertRaises(InputError):
            inspect(self.root)
        self.cache.unlink()
        try:
            self.cache.symlink_to(self.tool)
        except (OSError, NotImplementedError):
            return
        with self.assertRaises(InputError):
            inspect(self.root)

    def test_image_selection_stays_scoped(self):
        child = self.root / "boot"
        child.mkdir()
        (child / "CMakeCache.txt").write_text("BOARD:STRING=boot-target\n")
        report = inspect(self.root, images=["boot=boot"], expected_board="boot-target")
        self.assertEqual([i["image"] for i in report["images"]], ["boot"])
        self.assertEqual(report["images"][0]["board"], "matching")

    def test_recorded_cache_version_mismatch_does_not_invent_installed_version(self):
        with self.cache.open("a") as stream:
            stream.write("CMAKE_COMMAND:FILEPATH=" + str(self.tool) + "\n"
                         "CMAKE_CACHE_MAJOR_VERSION:INTERNAL=3\n"
                         "CMAKE_CACHE_MINOR_VERSION:INTERNAL=31\n"
                         "CMAKE_CACHE_PATCH_VERSION:INTERNAL=10\n")
        report = inspect(self.root, expected_versions={"cmake": "3.30.0", "python": "3.13.7"})
        image = report["images"][0]
        self.assertEqual(image["tools"]["cmake"]["version"], "3.31.10")
        self.assertEqual(image["tools"]["cmake"]["version_match"], "mismatch")
        self.assertEqual(image["tools"]["cmake"]["version_scope"], "recorded-cache-metadata-not-current-executable")
        self.assertEqual(image["tools"]["python"]["version_match"], "unknown")
        self.assertIn("CS12-RECORDED-VERSION-MISMATCH-CMAKE", {f["rule_id"] for f in image["findings"]})

    def test_relative_configured_paths_are_unknown_not_confirmed_missing(self):
        for value in ("ninja", "relative/tools/ninja"):
            self.cache.write_text("CMAKE_MAKE_PROGRAM:FILEPATH=" + value + "\n")
            image = inspect(self.root)["images"][0]
            self.assertEqual(image["tools"]["ninja"]["state"], "unknown")
            self.assertEqual(image["findings"], [])

    def test_oversized_expected_version_is_rejected(self):
        with self.assertRaises(InputError):
            inspect(self.root, expected_versions={"ninja": "1." + "0" * 100000})
