# SPDX-License-Identifier: Apache-2.0
"""Offline dashboard asset, action-bridge and packaging regression checks."""
from html.parser import HTMLParser
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
UI = ROOT / "plugins/io.github.niwantha33.zew.ui"
HTML = UI / "developer-dashboard/index.html"
JAVA = UI / "src/io/github/niwantha33/zew/ui/ZephyrDeveloperHomeView.java"
EXPECTED_ACTIONS = {
    "guided", "pins", "build", "workflow", "debug", "source", "settings",
}
EXPECTED_FIELDS = {
    "PROJECT", "BOARD", "STATUS", "STATUS_CLASS", "BUILD_DETAIL",
    "NEXT", "BUILD_HINT", "PROJECT_DISABLED", "BUILD_ACTIVE",
    "FLASH_ACTIVE", "BUILD_BUTTON",
}


class DashboardParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = set()
        self.actions = set()
        self.buttons = 0

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if "id" in attrs:
            assert attrs["id"] not in self.ids, "Duplicate DOM ID"
            self.ids.add(attrs["id"])
        if tag == "button":
            self.buttons += 1
            assert attrs.get("type") == "button", "No implicit form submissions"
            assert "data-action" in attrs, "All buttons must have a real action"
            self.actions.add(attrs["data-action"])
        if tag in {"iframe", "object", "embed", "link"}:
            raise AssertionError("Offline dashboard cannot embed external content")
        if tag == "script":
            assert "src" not in attrs, "No external scripts"


class DevelopmentDashboardTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.html = HTML.read_text(encoding="utf-8")
        cls.java = JAVA.read_text(encoding="utf-8")
        cls.parser = DashboardParser()
        cls.parser.feed(cls.html)

    def test_offline_security_contract(self):
        self.assertLess(len(self.html.encode("utf-8")), 80_001)
        self.assertIn("default-src 'none'", self.html)
        self.assertIn("form-action 'none'", self.html)
        for forbidden in ("fetch(", "XMLHttpRequest", "localStorage",
                          "sessionStorage", "eval(", "new Function(", "http://", "https://"):
            self.assertNotIn(forbidden, self.html)
        self.assertIn("event.target.closest('button[data-action]')", self.html)

    def test_every_button_uses_guarded_existing_action(self):
        self.assertEqual(self.parser.actions, EXPECTED_ACTIONS)
        self.assertEqual(self.parser.buttons, 7)
        for action in EXPECTED_ACTIONS:
            self.assertIn('case "' + action + '" ->', self.java)
        self.assertIn('return "DENIED"', self.java)
        self.assertIn("BrowserFunction(browser, \"zewDashboardAction\")", self.java)
        self.assertIn("event.location", self.java)

    def test_project_evidence_and_html_escaping(self):
        fields = set(re.findall(r"\{\{([A-Z_]+)\}\}", self.html))
        self.assertEqual(fields, EXPECTED_FIELDS)
        for field in fields:
            self.assertIn('.replace("{{' + field + '}}"', self.java)
        self.assertIn("escapeHtml(name)", self.java)
        self.assertIn("escapeHtml(board)", self.java)
        self.assertIn("Files.isRegularFile(elf)", self.java)
        self.assertIn("ELF found · not verified", self.java)
        self.assertIn("An ELF file alone does not prove", self.html)

    def test_accessibility_and_packaging(self):
        self.assertIn("aria-labelledby", self.html)
        self.assertIn(":focus-visible", self.html)
        self.assertIn("prefers-color-scheme:dark", self.html)
        self.assertIn("@media(max-width:700px)", self.html)
        self.assertIn("developer-dashboard/", (UI / "build.properties").read_text(encoding="utf-8"))
        self.assertIn('getEntry("developer-dashboard/index.html")', self.java)
        self.assertIn("SWT fallback selected", self.java)


if __name__ == "__main__":
    unittest.main()
