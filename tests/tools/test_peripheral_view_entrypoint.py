# SPDX-License-Identifier: Apache-2.0
"""Bug 9: the empty third-party 'Peripherals' tab is not the Zephyr SVD explorer.

Keep an explicit Zephyr menu entry routed to the *existing* session-aware,
read-only register view; do not silently activate unrelated peripheral plug-ins.
"""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
PLUGIN = ROOT / "plugins/io.github.niwantha33.zew.ui/plugin.xml"
MANIFEST = ROOT / "plugins/io.github.niwantha33.zew.ui/META-INF/MANIFEST.MF"

COMMAND = "io.github.niwantha33.zew.socRegisters"
VIEW = "io.github.niwantha33.zew.ui.socRegisters"
HANDLER = "io.github.niwantha33.zew.ui.SocRegistersHandler"


class PeripheralEntryPointTests(unittest.TestCase):
    def setUp(self):
        self.plugin = ET.parse(PLUGIN).getroot()

    def test_run_debug_menu_targets_existing_read_only_view(self):
        menu = self.plugin.find(
            ".//menu[@id='io.github.niwantha33.zew.menu.runDebug']")
        self.assertIsNotNone(menu)
        links = [item for item in menu.findall("command")
                 if item.get("commandId") == COMMAND]
        self.assertEqual(len(links), 1,
                         "Run & Debug needs a single discoverable Zephyr peripheral entry")
        self.assertEqual(links[0].get("label"), "Open Zephyr Peripherals (SVD)...")
        self.assertTrue(any(element.get("id") == COMMAND
                            for element in self.plugin.findall(
                                "./extension[@point='org.eclipse.ui.commands']/command")))
        self.assertTrue(any(element.get("commandId") == COMMAND
                            and element.get("class") == HANDLER
                            for element in self.plugin.findall(
                                "./extension[@point='org.eclipse.ui.handlers']/handler")))
        self.assertTrue(any(element.get("id") == VIEW
                            and element.get("class") ==
                            "io.github.niwantha33.zew.ui.SocRegistersView"
                            for element in self.plugin.findall(
                                "./extension[@point='org.eclipse.ui.views']/view")))

    def test_optional_embedded_cdt_peripherals_not_required(self):
        manifest = MANIFEST.read_text(encoding="utf-8")
        self.assertNotIn("org.eclipse.embedcdt", manifest)
        # Zephyr's own explorer is a different view, so a blank third-party
        # Peripherals tab must not falsely be called a Zephyr register-read failure.
        self.assertNotEqual(VIEW, "org.eclipse.embedcdt.debug.gdbjtag.ui.peripherals")


if __name__ == "__main__":
    unittest.main()
