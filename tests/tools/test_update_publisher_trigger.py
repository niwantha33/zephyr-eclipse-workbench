# SPDX-License-Identifier: Apache-2.0
"""Regression for a stranded p2 update after a no-diff main merge.

A publisher run built for commit A must refuse publication when the main tip
has moved to B. If the merge to B has no touched code files, an on.push.paths
filter would also suppress a replacement run, leaving the update channel
stranded. The workflow must trigger on every main push, with all existing
exact-commit and physical Windows qualification checks preserved.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/private-update.yml"


class MainPublisherTriggerTests(unittest.TestCase):
    def test_main_push_and_manual_dispatch_are_not_path_filtered(self):
        source = WORKFLOW.read_text(encoding="utf-8")
        header = source.split("\npermissions:", 1)[0]
        self.assertRegex(header, r"(?m)^on:\s*$")
        self.assertRegex(header, r"(?m)^  push:\s*\n    branches: \[main\]\s*$")
        self.assertRegex(header, r"(?m)^  workflow_dispatch:\s*$")
        self.assertNotRegex(header, r"(?m)^\s+paths(?:-ignore)?:",
                            "A no-file-change merge must not strand the update channel")

    def test_publisher_still_rejects_unqualified_or_superseded_builds(self):
        source = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("cancel-in-progress: false", source)
        self.assertIn("needs: [build_and_verify, windows_firmware_qualification]", source)
        self.assertIn("needs.windows_firmware_qualification.result == 'success'", source)
        self.assertIn('git rev-parse refs/remotes/origin/main', source)
        self.assertIn('Main advanced since this build; a newer workflow will publish it.', source)
        self.assertIn('Refusing downgrade or same-version replacement', source)
        self.assertIn('Checksum mismatch:', source)
        self.assertIn('EXPECTED_SITE_SHA256', source)
        self.assertIn('persist-credentials: false', source)


if __name__ == "__main__":
    unittest.main()
