# SPDX-License-Identifier: Apache-2.0
"""Wiring checks for PR1 Steps 3–4; installed Eclipse smoke remains separate."""
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
        self.assertIn("new MemoryProfilerCanvas(", view)
        self.assertNotIn("new TableViewer(", view)
        canvas = (UI / "src/io/github/niwantha33/zew/ui/MemoryProfilerCanvas.java").read_text()
        self.assertIn("extends Canvas", canvas)
        self.assertIn("SWT.COLOR_RED", canvas)
        self.assertIn("data.overlaps(region)", canvas)
        self.assertIn("new FileDialog(", view)
        self.assertIn("MemoryProfilerJson.parse(json)", view)
        self.assertIn("MemoryProfilerLayout.fromRows(fields)", view)
        self.assertNotIn("west build", view)
        self.assertNotIn("ProcessBuilder(", view)

    def test_build_integration_is_after_verified_success_not_clean(self):
        ui_source = UI / "src/io/github/niwantha33/zew/ui"
        builder = (ui_source / "ZephyrProjectBuilder.java").read_text()
        hook = "MemoryProfilerBuildIntegration.afterSuccessfulBuild("
        self.assertEqual(builder.count(hook), 1)
        self.assertLess(builder.index("BuildArtifacts.verify(buildDirectory);"), builder.index(hook))
        self.assertLess(builder.index("configuration.verify(buildDirectory);"), builder.index(hook))
        self.assertLess(builder.index("if (!monitor.isCanceled())"), builder.index(hook))
        integration = (ui_source / "MemoryProfilerBuildIntegration.java").read_text()
        self.assertIn('getEntry("scripts/memory_profiler.py")', integration)
        self.assertIn("MemoryProfilerReportGuard.publish(", integration)
        self.assertIn("notifyOpenViews(project, build, output)", integration)
        self.assertIn("page.findView(MemoryProfilerView.ID)", integration)
        self.assertNotIn("page.showView(", integration)
        self.assertNotIn('"west", "build"', integration)

    def test_report_is_packaged_exactly_and_ui_tracks_project_identity(self):
        root = ROOT / "scripts/memory_profiler.py"
        bundled = UI / "scripts/memory_profiler.py"
        self.assertEqual(root.read_bytes(), bundled.read_bytes())
        self.assertIn("scripts/", (UI / "build.properties").read_text())
        view = (UI / "src/io/github/niwantha33/zew/ui/MemoryProfilerView.java").read_text()
        self.assertIn("followedProject != null", view)
        self.assertIn("!followedProject.equals(project)", view)
        self.assertIn("manualImport", view)
        self.assertIn("MemoryProfilerReportGuard.verifyReport(json, mapSha)", view)
        self.assertIn("Follow selected project builds", view)


if __name__ == "__main__":
    unittest.main()
