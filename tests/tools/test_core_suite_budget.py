# SPDX-License-Identifier: Apache-2.0
"""Keep the merged-commit Windows publisher's whole Python suite bounded.

A 90-second budget timed out while healthy tests were still running on the
Windows release runner (merge 3cb04e7). This guard prevents that regression
without allowing an unbounded wait or skipping the unit-test inventory.
"""
from pathlib import Path
import ast
import unittest


class CoreSuiteBudgetTests(unittest.TestCase):
    def test_python_tool_suite_uses_finite_release_safe_budget(self):
        source = (Path(__file__).resolve().parents[2] / "scripts/test_core.py").read_text()
        tree = ast.parse(source)
        budget = [
            node.value.value
            for node in tree.body
            if isinstance(node, ast.Assign)
            and len(node.targets) == 1
            and isinstance(node.targets[0], ast.Name)
            and node.targets[0].id == "PYTHON_TOOLS_SUITE_TIMEOUT"
            and isinstance(node.value, ast.Constant)
            and type(node.value.value) is int
        ]
        self.assertEqual(len(budget), 1)
        self.assertGreaterEqual(budget[0], 180)
        self.assertLessEqual(budget[0], 360)
        suites = [
            node for node in ast.walk(tree)
            if isinstance(node, ast.Call)
            and isinstance(node.func, ast.Attribute)
            and node.func.attr == "run"
            and any(
                isinstance(kw.value, ast.Name)
                and kw.value.id == "PYTHON_TOOLS_SUITE_TIMEOUT"
                for kw in node.keywords if kw.arg == "timeout"
            )
        ]
        self.assertEqual(len(suites), 1)
        invocation = suites[0]
        self.assertTrue(any(
            kw.arg == "check"
            and isinstance(kw.value, ast.Constant)
            and kw.value.value is True
            for kw in invocation.keywords
        ), "All tests must still fail CI on any test failure")
        self.assertIn("'unittest', 'discover', '-s', 'tests/tools', '-v'", source)
        self.assertIn("timeout=90)", source, "Keep independent Java test timeouts")


if __name__ == "__main__":
    unittest.main()
