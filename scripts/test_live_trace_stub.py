#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Static regression guard: the live CTF entry point must remain inert."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "plugins/io.github.niwantha33.zew.ui"
JAVA = UI / "src/io/github/niwantha33/zew/ui/LiveTraceStreamingHandler.java"
XML = UI / "plugin.xml"
DOC = ROOT / "docs/LIVE_TRACE_STREAMING_DESIGN.md"
LABEL = "Start Live Trace (not yet available)"
COMMAND = "io.github.niwantha33.zew.liveTraceStreaming"

source = JAVA.read_text(encoding="utf-8")
assert re.search(r"\bLIVE_STREAMING_ENABLED\s*=\s*false\s*;", source)
assert len(re.findall(r"\bLIVE_STREAMING_ENABLED\b", source)) >= 2
assert "MessageDialog.openInformation" in source
assert "docs/LIVE_TRACE_STREAMING_DESIGN.md" in source
assert "not implemented" in source
# This newly introduced handler must never create/drive any transport or worker.
for forbidden in ("new Thread(", "new Job(", "Socket(", "ServerSocket(",
                  "SerialPort(", "ProcessBuilder(", "new Timer(", "connect(",
                  "openPort(", "Runtime.getRuntime("):
    assert forbidden not in source, forbidden

root = ET.parse(XML).getroot()
commands = [n for n in root.iter("command") if n.get("id") == COMMAND]
handlers = [n for n in root.iter("handler") if n.get("commandId") == COMMAND]
menu_entries = [n for n in root.iter("command") if n.get("commandId") == COMMAND]
assert len(commands) == 1
assert len(handlers) == 1
assert handlers[0].get("class") == "io.github.niwantha33.zew.ui.LiveTraceStreamingHandler"
assert len(menu_entries) == 1 and menu_entries[0].get("label") == LABEL
assert DOC.is_file()
design = DOC.read_text(encoding="utf-8")
for term in ("NOT SUPPORTED", "RTT", "UART", "Network", "target RAM",
             "partial", "semantic", "reset", "overflow", "Host falls behind",
             "Even after"):
    assert term.lower() in design.lower(), term
for path in (ROOT / "README.md", ROOT / "docs/DEBUG_TRACE_CONSOLE.md"):
    content = path.read_text(encoding="utf-8")
    assert "## Not supported" in content
    assert "live ctf streaming" in content.lower(), path
print("PASS: disabled live CTF menu stub, design and unsupported documentation")
