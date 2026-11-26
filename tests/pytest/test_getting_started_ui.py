# SPDX-License-Identifier: Apache-2.0
"""Offline Getting Started UI contract; does not claim native Eclipse qualification."""
from html.parser import HTMLParser
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[2]
PLUGIN = ROOT / "plugins" / "io.github.niwantha33.zew.ui"
HTML = PLUGIN / "getting-started" / "index.html"
JAVA = PLUGIN / "src" / "io" / "github" / "niwantha33" / "zew" / "ui" / "ZephyrBeginnerView.java"


class GuideMarkup(HTMLParser):
    def __init__(self):
        super().__init__()
        self.navigation = []
        self.panels = []
        self.actions = []
        self.external = []

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if "data-step" in values and "step" in values.get("class", "").split():
            self.navigation.append(values["data-step"])
        if "data-panel" in values:
            self.panels.append(values["data-panel"])
        if "data-action" in values:
            self.actions.append(values["data-action"])
        if tag in {"script", "link", "img", "iframe"}:
            for key in ("src", "href"):
                if values.get(key):
                    self.external.append(values[key])


def test_beginner_guide_is_packaged_and_complete():
    html = HTML.read_text(encoding="utf-8")
    markup = GuideMarkup()
    markup.feed(html)
    assert markup.navigation == [str(i) for i in range(7)]
    assert markup.panels == [str(i) for i in range(7)]
    assert len(markup.actions) >= 12
    assert not markup.external
    assert "default-src 'none'" in html
    assert "getting-started/" in (PLUGIN / "build.properties").read_text(encoding="utf-8")
    assert set(re.findall(r"{{([A-Z_]+)}}", html)) == {
        "PROJECT", "BOARD", "PROFILE", "OUTPUT", "OUTPUT_CLASS", "STEP"
    }


def test_every_action_has_a_validated_eclipse_handler():
    html = HTML.read_text(encoding="utf-8")
    java = JAVA.read_text(encoding="utf-8")
    markup = GuideMarkup()
    markup.feed(html)
    actions = set(markup.actions)
    allowed_block = java.split("java.util.Set.of(", 1)[1].split(").contains(action)", 1)[0]
    allowed = set(re.findall(r'"([^"]+)"', allowed_block))
    handled = set(re.findall(r'case "([^"]+)" ->', java))
    assert actions == allowed
    assert actions <= handled
    assert 'action.matches("step:[0-6]")' in java
    assert 'escapeHtml(summary.name())' in java
    assert 'escapeHtml(summary.board())' in java
    assert 'browser.addLocationListener(' in java
    assert 'if (createVisualGuide(parent))' in java  # browser fallback remains opt-in
    assert "installListeners(parent);" in java


def test_ui_does_not_claim_verified_flash_or_live_trace():
    html = HTML.read_text(encoding="utf-8")
    java = JAVA.read_text(encoding="utf-8")
    assert "not target-verified" in java
    assert "Missing or offline captures are not live target data." in html
    assert "Selecting this step does neither." in html
