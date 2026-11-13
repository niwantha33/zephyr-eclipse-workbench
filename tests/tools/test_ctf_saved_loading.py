# SPDX-License-Identifier: Apache-2.0
"""Bug 11: extensionless CTF navigation and isolated Python offline imports.

No target, OpenOCD, Babeltrace native bindings or Eclipse instance is needed.
This does not prove hardware capture validity or actual CTF decoding.
"""
from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from test_ctf_capture_packaging import TOOLS, staged_imports, trusted_bootstrap

ROOT = Path(__file__).resolve().parents[2]
UI = ROOT / "plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui"
VIEWS = {
    "ThreadTimelineView.java": "thread_timeline.py",
    "TracepointPlotView.java": "tracepoint_samples.py",
    "ZephyrMemoryProfileView.java": "memory_profile_report.py",
}
OFFLINE = "offline_trace_json.py"
PICKER_VIEWS = (
    "ThreadTimelineView.java", "ZephyrCpuLoadView.java", "TracepointPlotView.java",
    "ZephyrMemoryProfileView.java", "ZephyrContentionView.java",
)


def closure(entry: str) -> set[str]:
    pending = [entry[:-3]]
    visited = set()
    while pending:
        module = pending.pop()
        if module in visited:
            continue
        visited.add(module)
        pending.extend(staged_imports(TOOLS / (module + ".py")) - visited)
    return {module + ".py" for module in visited}


class SavedCtfLoadingTests(unittest.TestCase):
    def test_native_file_picker_exposes_extensionless_ctf_evidence(self):
        picker = (UI / "SavedCtfFilePicker.java").read_text(encoding="utf-8")
        self.assertIn("new FileDialog(shell, SWT.OPEN)", picker)
        self.assertIn("metadata;channel0_0", picker)
        self.assertIn("Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)", picker)
        self.assertIn("return file.getParent();", picker)
        self.assertIn("Open latest capture", picker)
        for filename in PICKER_VIEWS:
            with self.subTest(view=filename):
                source = (UI / filename).read_text(encoding="utf-8")
                self.assertIn("SavedCtfFilePicker.choose(", source)
                self.assertNotIn("new DirectoryDialog(", source)
        handoff = (UI / "TraceCompassIntegrationView.java").read_text(encoding="utf-8")
        self.assertIn('dialog.setFilterExtensions(new String[] { "capture.json", "*" })', handoff)
        self.assertIn("selectCapture(file.getParent())", handoff)
        self.assertNotIn("new DirectoryDialog(", handoff)

    def test_all_offline_view_launchers_use_safe_ctf_python_bootstrap(self):
        for filename, helper in VIEWS.items():
            with self.subTest(view=filename):
                source = (UI / filename).read_text(encoding="utf-8")
                self.assertIn("TracePythonLaunch.command(", source)
                self.assertIn('temporary.resolve("' + helper + '")', source)
        source = (UI / "OfflineTracePythonBridge.java").read_text(encoding="utf-8")
        self.assertIn("TracePythonLaunch.command(interpreter,", source)
        self.assertIn('temporary.resolve("' + OFFLINE + '")', source)

    def test_offline_helper_closure_imports_under_isolated_python(self):
        with tempfile.TemporaryDirectory(prefix="zew-ctf-offline-Ω ") as temp:
            root = Path(temp)
            outside = root / "not a project"
            outside.mkdir()
            env = dict(os.environ, PYTHONPATH=str(TOOLS),
                       PYTHONSAFEPATH="1", PYTHONDONTWRITEBYTECODE="1")
            for entry in (*VIEWS.values(), OFFLINE):
                with self.subTest(entry=entry):
                    staged = root / entry
                    staged.mkdir()
                    for module in closure(entry):
                        (staged / module).write_bytes((TOOLS / module).read_bytes())
                    script = str(staged / entry)
                    direct = subprocess.run(
                        [sys.executable, "-I", script, "--help"], cwd=outside,
                        env=env, capture_output=True, text=True, timeout=15,
                        check=False)
                    self.assertNotEqual(direct.returncode, 0,
                                        "Fixture must reproduce embedded Python isolated import failure")
                    self.assertIn("No module named", direct.stderr)
                    wrapped = subprocess.run(
                        [sys.executable, "-I", "-X", "utf8", "-c",
                         trusted_bootstrap(), script, "--help"],
                        cwd=outside, env=env, capture_output=True, text=True,
                        timeout=15, check=False)
                    self.assertEqual(wrapped.returncode, 0,
                                     wrapped.stdout + wrapped.stderr)
                    self.assertIn("--help", wrapped.stdout)


if __name__ == "__main__":
    unittest.main()
