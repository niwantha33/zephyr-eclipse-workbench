# SPDX-License-Identifier: Apache-2.0
"""Guard against independent CodeQL action upgrades."""
from pathlib import Path
import re
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[2]


def codeql_pins(text):
    return re.findall(r"^\s*(?:-\s*)?uses:\s*github/codeql-action/[\w-]+@([^\s#]+)", text, re.M)


class CodeQLActionTests(unittest.TestCase):
    def test_all_codeql_steps_use_one_immutable_revision_per_workflow(self):
        checked = 0
        for path in (ROOT / ".github/workflows").glob("*.yml"):
            pins = codeql_pins(path.read_text(encoding="utf-8"))
            if not pins:
                continue
            checked += 1
            with self.subTest(workflow=path.name):
                self.assertEqual(1, len(set(pins)))
                self.assertRegex(pins[0], r"^[0-9a-f]{40}$")
        self.assertGreater(checked, 0)

    def test_manual_codeql_workflow_retains_both_steps(self):
        text = (ROOT / ".github/workflows/codeql.yml").read_text(encoding="utf-8")
        self.assertRegex(text, r"uses:\s*github/codeql-action/init@")
        self.assertRegex(text, r"uses:\s*github/codeql-action/analyze@")
        self.assertIn("build-mode: manual", text)

    def test_dependabot_groups_codeql_actions(self):
        config = yaml.safe_load((ROOT / ".github/dependabot.yml").read_text(encoding="utf-8"))
        actions = next(row for row in config["updates"] if row["package-ecosystem"] == "github-actions")
        self.assertIn("github/codeql-action*", actions["groups"]["codeql-actions"]["patterns"])


if __name__ == "__main__":
    unittest.main()
