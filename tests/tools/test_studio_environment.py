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
