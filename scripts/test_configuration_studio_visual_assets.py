#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Safety and packaged-asset contract for the embedded Configuration Studio."""
from html.parser import HTMLParser
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / "plugins/io.github.niwantha33.zew.ui/configuration-studio/index.html"


class Inspector(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = set()
        self.script = ""
        self.in_script = False

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if "id" in attrs:
            assert attrs["id"] not in self.ids, "Duplicate DOM ID"
            self.ids.add(attrs["id"])
        if tag == "script":
            assert "src" not in attrs, "Only packaged inline scripts are permitted"
            self.in_script = True
        if tag == "link":
            assert "href" not in attrs, "No network linked fonts/styles"
        if tag == "iframe":
            raise AssertionError("No iframe navigation in privileged studio")

    def handle_endtag(self, tag):
        if tag == "script":
            self.in_script = False

    def handle_data(self, data):
        if self.in_script:
            self.script += data


def main():
    source = ASSET.read_text(encoding="utf-8")
    assert 0 < len(source) <= 180_000
    assert source.count("__CONFIGURATION_STUDIO_DATA__") == 1
    assert "default-src 'none'" in source
    assert "script-src 'unsafe-inline'" in source
    assert "fetch(" not in source
    assert "XMLHttpRequest" not in source
    assert "localStorage" not in source and "sessionStorage" not in source
    assert "eval(" not in source and "new Function(" not in source
    parser = Inspector()
    parser.feed(source)
    assert {"workspace", "detail", "detailactions", "refresh", "freshness",
            "theme", "classic", "contextTitle", "contextSub",
            "reviewDialog", "commitReview", "cancelReview",
            "reviewDiff", "reviewStatus"} <= parser.ids
    assert "const DATA = __CONFIGURATION_STUDIO_DATA__;" in parser.script
    assert "textContent" in parser.script
    assert "innerHTML" not in parser.script and "document.write" not in parser.script
    assert "zewConfigurationAction" in parser.script
    assert "function requestPreview(" in parser.script
    assert "function confirmReview(" in parser.script
    assert "host('request',payload)" in parser.script
    assert "host('focus-symbol',item.symbol)" in parser.script
    assert 'data-section="overview"' in source
    assert 'data-section="hardware"' in source
    assert 'data-section="software"' in source
    assert 'data-section="system"' in source
    assert "@media(max-width:780px)" in source
    assert "@media(max-width:490px)" in source
    node = shutil.which("node")
    if node:
        with tempfile.TemporaryDirectory(prefix="zew-config-js-") as folder:
            js = Path(folder) / "configuration-studio.js"
            js.write_text(parser.script, encoding="utf-8")
            subprocess.run([node, "--check", str(js)], check=True, timeout=45)
    print("ConfigurationStudioVisualAssets PASS: offline shell, responsive layout and JS syntax")


if __name__ == "__main__":
    main()
