#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline Hardware Studio asset and optional JS parse guard (no browser/probe)."""
from html.parser import HTMLParser
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / "plugins/io.github.niwantha33.zew.ui/hardware-studio/index.html"


class Inspect(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = set()
        self.scripts = []
        self.in_script = False
        self.inline_script = ""

    def handle_starttag(self, tag, attributes):
        attrs = dict(attributes)
        if "id" in attrs:
            assert attrs["id"] not in self.ids, "Duplicate browser element ID"
            self.ids.add(attrs["id"])
        if tag == "script":
            assert "src" not in attrs, "Scripts must be locally bundled inline"
            self.in_script = True
        if tag == "link":
            assert "href" not in attrs, "No network-linked styles/fonts"

    def handle_endtag(self, tag):
        if tag == "script":
            self.in_script = False

    def handle_data(self, data):
        if self.in_script:
            self.inline_script += data


def main():
    source = ASSET.read_text(encoding="utf-8")
    assert 0 < len(source) < 180_000
    assert source.count("__HARDWARE_STUDIO_DATA__") == 1
    assert "default-src 'none'" in source
    assert "script-src 'unsafe-inline'" in source
    assert "https://cdn" not in source.lower()
    assert "fetch(" not in source and "XMLHttpRequest" not in source
    parser = Inspect()
    parser.feed(source)
    expected = {
        "navigation", "search", "cards", "wires", "viewport", "detail",
        "references", "refresh", "fit", "zoomIn", "zoomOut",
        "theme", "openSource", "openGraph", "help", "helpDialog",
        "closeHelp", "category",
    }
    assert expected <= parser.ids, "Missing interactive Hardware Studio element"
    assert "const DATA = __HARDWARE_STUDIO_DATA__;" in parser.inline_script
    assert "document.createElementNS" in parser.inline_script
    assert "textContent" in parser.inline_script, "DTS fields must be inserted as text"
    assert "CI FIXTURE — NO HARDWARE" in parser.inline_script, "UI fixture screenshots require visible non-hardware warning"
    assert "resetLayout" in parser.ids, "Missing visual layout reset button"
    assert 'id="resetLayout"' in source and "disabled>Reset layout" in source
    assert "function moveCard(" in parser.inline_script
    assert "function redrawWires(" in parser.inline_script
    assert "function resetLayout(" in parser.inline_script
    assert "function nudgeCard(" in parser.inline_script
    assert "setPointerCapture" in parser.inline_script
    assert "releasePointerCapture" in parser.inline_script
    assert "event.pointerId" in parser.inline_script
    assert "const positions = new Map(DATA.cards" in parser.inline_script
    assert "Drag blocks to arrange (view only)" in parser.inline_script
    assert "host('layout'" not in parser.inline_script, "Visual layout cannot mutate project data"
    assert "localStorage" not in parser.inline_script and "sessionStorage" not in parser.inline_script
    node = shutil.which("node")
    if node:
        with tempfile.TemporaryDirectory(prefix="zew-hardware-js-") as folder:
            path = Path(folder) / "hardware-studio.js"
            path.write_text(parser.inline_script, encoding="utf-8")
            # GitHub-hosted Windows runners can take >10s to initialise Node
            # after an extensive Python test suite. Retain the syntax gate,
            # but allow a bounded slow process startup (no silent skip).
            subprocess.run([node, "--check", str(path)], check=True, timeout=45)
    package = ASSET.with_name("package.html").read_text(encoding="utf-8")
    assert package.count("__HARDWARE_STUDIO_DATA__") == 1
    assert "default-src 'none'" in package and "fetch(" not in package
    assert "innerHTML" not in package
    parser = Inspect()
    parser.feed(package)
    assert {"package", "top", "bottom", "left", "right", "nodes", "details"} <= parser.ids
    if node:
        with tempfile.TemporaryDirectory(prefix="zew-package-js-") as folder:
            path = Path(folder) / "package.js"
            path.write_text(parser.inline_script, encoding="utf-8")
            subprocess.run([node, "--check", str(path)], check=True, timeout=45)
            prefix = parser.inline_script.split("// DOM rendering below;")[0].replace(
                "__HARDWARE_STUDIO_DATA__", "{}")
            assertions = r"""
const assert = require("node:assert/strict");
assert.equal(PIN_NAMES.length,64);
assert.equal(new Set(PIN_NAMES).size,64);
assert.equal(PIN_NAMES[17],"PA22");
assert.equal(PIN_NAMES[39],"VDD");
assert.equal(PIN_NAMES[40],"VSS");
assert.equal(PIN_NAMES[63],"PB12");
assert.equal(supportsBoard("lp_mspm0g3507"),true);
assert.equal(supportsBoard("other_mspm0g3507"),false);
const e={kind:"gpio",detail:"[0] pin=22"};
assert.equal(gpioPin(e,{compatible:"ti,mspm0-gpio",labels:"gpioa"}),"PA22");
assert.equal(gpioPin(e,{compatible:"ti,mspm0-gpio",labels:"gpiob"}),"PB22");
assert.equal(gpioPin(e,{compatible:"other,gpio",labels:"gpioa"}),null);
assert.equal(gpioPin({...e,detail:"[0] pin=31"},{compatible:"ti,mspm0-gpio",labels:"gpiob"}),null);
assert.equal(labelPin({kind:"pinctrl"},{labels:"uart0_tx_pa10"}),"PA10");
assert.equal(labelPin({kind:"pinctrl"},{labels:"uart0_tx_pa10,uart0_tx_pa11"}),null);
assert.deepEqual(collect({board:"other",observed:true,state:"built",nodes:[],edges:[]}),[]);
"""
            path.write_text(prefix + assertions, encoding="utf-8")
            subprocess.run([node, str(path)], check=True, timeout=45)
    print("HardwareStudioAssets PASS: offline packaged UI and JS syntax where Node is available")


if __name__ == "__main__":
    main()
