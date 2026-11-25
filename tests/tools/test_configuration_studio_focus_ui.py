# SPDX-License-Identifier: Apache-2.0
"""Source contract for the intentionally progressive native Configuration Studio.

Installed SWT/p2 and real project acceptance remain mandatory. These tests
guard the navigation and disclosure wiring against accidental regression.
"""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
UI = ROOT / "plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui"


class ConfigurationStudioFocusTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.legacy = (UI / "ConfigurationStudioView.java").read_text(encoding="utf-8")
        cls.home = (UI / "ConfigurationHomeView.java").read_text(encoding="utf-8")
        cls.handler = (UI / "ConfigurationStudioHandler.java").read_text(encoding="utf-8")

    def test_primary_entry_remains_visual(self):
        self.assertIn("return ConfigurationHomeView.ID;", self.handler)
        self.assertIn('visualGuideButton = action(primary, "Visual Studio", this::openModernConfiguration)', self.legacy)
        self.assertIn("home.openSection(page);", self.legacy)
        self.assertIn('overview(tab("Overview"))', self.home)

    def test_native_advanced_chrome_is_opt_in(self):
        self.assertIn('advancedChromeButton.setText("Show details");', self.legacy)
        self.assertIn("advancedChromeButton.getSelection()", self.legacy)
        self.assertIn("setExpertVisibility(expertToolbar, advanced);", self.legacy)
        self.assertIn("setExpertVisibility(expertMetrics, advanced);", self.legacy)

    def test_hardware_planner_first_and_evidence_explicit(self):
        self.assertIn("studioVertical.setMaximizedControl(studioColumns);", self.legacy)
        self.assertIn('evidenceToggle = action(focusBar, "Show evidence"', self.legacy)
        self.assertIn("evidenceExpanded = !evidenceExpanded;", self.legacy)
        self.assertIn("evidenceExpanded = true;", self.legacy)
        self.assertIn("evidenceExpanded\n                ? (sourceTab ? hardwareDetails : null) : hardwareCanvas", self.legacy)
        self.assertIn("hardwareDetails.setSelection(3);\n            updateHardwareDetailsLayout();", self.legacy)

    def test_extra_commands_not_lost_and_not_shown_by_default(self):
        self.assertIn('hardwareToolsToggle = action(focusBar, "More tools"', self.legacy)
        self.assertIn("actionsSize.exclude = true;", self.legacy)
        self.assertIn("hardwareActionsScroll = actionsScroll;", self.legacy)
        self.assertIn("setExpertVisibility(hardwareActionsScroll, hardwareToolsExpanded);", self.legacy)
        self.assertIn('action(hardwareActions, "Real configure (scratch)"', self.legacy)
        self.assertIn('action(hardwareActions, "Edit application overlay…"', self.legacy)

    def test_graphical_hardware_handoff_uses_selected_evidence(self):
        self.assertIn('action(focusBar, "Open DTS Topology", this::openModernHardwareStudio)', self.legacy)
        self.assertIn("studio.displayProject(project, inspectionBuild, focus);", self.legacy)
        self.assertIn("focus = node.path();", self.legacy)
        self.assertIn("if (project == null || !project.isAccessible() || inspectionBuild == null)", self.legacy)

    def test_topology_menu_belongs_to_configuration(self):
        plugin = ET.parse(UI.parents[5] / "plugin.xml").getroot()
        menus = {m.attrib.get("id"): m for m in plugin.iter("menu")}
        command = "io.github.niwantha33.zew.hardwareStudio"
        configure = menus["io.github.niwantha33.zew.menu.configure"]
        board = menus["io.github.niwantha33.zew.menu.board"]
        self.assertTrue(any(c.get("commandId") == command and
                            c.get("label") == "DTS Topology..."
                            for c in configure.findall("command")))
        self.assertFalse(any(c.get("commandId") == command
                             for c in board.findall("command")))
        view = next(v for v in plugin.iter("view")
                    if v.get("id") == "io.github.niwantha33.zew.ui.hardwareStudio")
        self.assertEqual(view.get("name"), "Configuration Studio — DTS Topology")
        visual = (UI.parents[5] / "configuration-studio/index.html").read_text(encoding="utf-8")
        self.assertIn("Open DTS Topology", visual)
        self.assertNotIn("Open Hardware Studio", visual)

    def test_visual_and_advanced_tabs_are_distinguishable(self):
        self.assertIn('setPartName("Configuration Studio — " + selected.getName());', self.home)
        self.assertIn('setPartName("Advanced Configuration — " + selected.getName());', self.legacy)


if __name__ == "__main__":
    unittest.main()
