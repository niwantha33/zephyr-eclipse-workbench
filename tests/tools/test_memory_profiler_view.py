# SPDX-License-Identifier: Apache-2.0
"""Wiring checks for PR1 Step 2; native SWT still requires installed Eclipse smoke."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
UI = ROOT / "plugins/io.github.niwantha33.zew.ui"


class MemoryProfilerViewWiringTests(unittest.TestCase):
    def test_view_registration_matches_implementation(self):
        doc = ET.parse(UI / "plugin.xml")
        views = [node for node in doc.findall("./extension[@point='org.eclipse.ui.views']/view")
                 if node.get("id") == "io.github.niwantha33.zew.ui.memoryProfiler"]
        self.assertEqual(len(views), 1)
        self.assertEqual(views[0].get("class"),
                         "io.github.niwantha33.zew.ui.MemoryProfilerView")
        self.assertTrue((UI / "src/io/github/niwantha33/zew/ui/MemoryProfilerView.java").is_file())

    def test_report_is_explicit_import_not_a_build_trigger(self):
        view = (UI / "src/io/github/niwantha33/zew/ui/MemoryProfilerView.java").read_text()
        self.assertIn("new TableViewer(", view)
        self.assertIn("new FileDialog(", view)
        self.assertIn("MemoryProfilerJson.parse(json)", view)
        self.assertNotIn("west build", view)
        self.assertNotIn("ProcessBuilder(", view)


if __name__ == "__main__":
    unittest.main()
