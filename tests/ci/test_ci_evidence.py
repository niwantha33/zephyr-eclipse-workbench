# SPDX-License-Identifier: Apache-2.0
import tempfile
from pathlib import Path
import unittest
from scripts.ci_evidence import audit_workflows, passes, quality_metrics


class EvidenceTests(unittest.TestCase):
    def test_threshold(self):
        first = "Percentage of calls with call target"
        second = "Percentage of expressions with known type"
        self.assertTrue(passes(quality_metrics(f"key,value\n{first},85\n{second},99\n")))
        self.assertFalse(passes(quality_metrics(f"{first},84\n{second},99\n")))
        self.assertFalse(passes(quality_metrics(f"{first},99\n{second},84\n")))
        for text in (f"{first},99\n", f"{first},101\n{second},99\n",
                     f"{first},99\n{first},99\n{second},99\n"):
            with self.assertRaises(ValueError):
                quality_metrics(text)

    def test_workflow_pins(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "test.yml"
            path.write_text("  - uses: actions/checkout@" + "a" * 40 +
                            "\n  - uses: ./local\n  image: python@sha256:" +
                            "b" * 64 + "\n")
            self.assertEqual([], audit_workflows(path.parent))
            path.write_text("  - uses: actions/checkout@v7\n  image: python:latest\n")
            self.assertEqual(2, len(audit_workflows(path.parent)))


if __name__ == "__main__":
    unittest.main()
