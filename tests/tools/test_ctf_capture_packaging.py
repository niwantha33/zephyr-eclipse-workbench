# SPDX-License-Identifier: Apache-2.0
"""Regression: the packaged RAM CTF capture helper must import in isolation.

No target, OpenOCD, GDB, Babeltrace 2 or Zephyr installation is required.
Copy only the exact Python resource names declared by the Java capture handler
into a fresh temp directory, just as the installed OSGi bundle does.
"""
from __future__ import annotations

import ast
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / "plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis"
HANDLER = ROOT / "plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/ZephyrTraceHandler.java"
BUILD = ROOT / "plugins/io.github.niwantha33.zew.ui/build.properties"


def capture_resources():
    source = HANDLER.read_text(encoding="utf-8")
    match = re.search(r"CAPTURE_FILES\s*=\s*List\.of\((.*?)\);", source, re.S)
    if not match:
        raise AssertionError("Capture helper manifest missing from Java launch handler")
    resources = re.findall(r'"([A-Za-z_][A-Za-z0-9_.-]+)"', match.group(1))
    if len(resources) != len(set(resources)):
        raise AssertionError("Duplicate staged trace resource")
    return resources, source


def staged_imports(py_file):
    """All eager top-level imports of tools we ship in the tool directory."""
    root = ast.parse(py_file.read_text(encoding="utf-8"), filename=str(py_file))
    imports = set()
    for node in root.body:
        if isinstance(node, ast.ImportFrom) and node.module:
            imports.add(node.module.split(".", 1)[0])
        elif isinstance(node, ast.Import):
            imports.update(alias.name.split(".", 1)[0] for alias in node.names)
    return {name for name in imports if (TOOLS / (name + ".py")).is_file()}


class CtfCapturePackagingTests(unittest.TestCase):
    def test_manifest_contains_all_eager_local_module_dependencies(self):
        resources, source = capture_resources()
        self.assertIn("capture_snapshot.py", resources)
        self.assertIn("analyze_trace.py", resources)
        self.assertIn("Zephyr_RTOS_Timeline.xml", resources)
        self.assertIn("tools/", BUILD.read_text())
        required = {"capture_snapshot"}
        pending = ["capture_snapshot"]
        inspected = set()
        while pending:
            name = pending.pop()
            if name in inspected:
                continue
            inspected.add(name)
            for dependency in staged_imports(TOOLS / (name + ".py")):
                required.add(dependency)
                pending.append(dependency)
        for name in sorted(required):
            if name not in {path[:-3] for path in resources if path.endswith(".py")}:
                self.fail("Required local Python module omitted from capture package: " + name)
        self.assertIn("stageCaptureTool(", source)
        self.assertLess(source.index("staging = stageCaptureTool("),
                        source.index("server = builder.start();"),
                        "Python staging validation must happen before OpenOCD opens")

    def test_copied_runtime_imports_without_repo_path(self):
        resources, _ = capture_resources()
        with tempfile.TemporaryDirectory(prefix="zew-ctf-packaging-") as temp:
            stage = Path(temp)
            for name in resources:
                (stage / name).write_bytes((TOOLS / name).read_bytes())
            cmd = [sys.executable, "-E", str(stage / "capture_snapshot.py"), "--help"]
            result = subprocess.run(cmd, cwd=stage, text=True, capture_output=True,
                                    timeout=15, check=False)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("--build", result.stdout)

            # Reproduce the user's exact failure; ensure the positive result
            # above is not accidentally importing a repo copy via sys.path.
            (stage / "analyze_trace.py").unlink()
            negative = subprocess.run(cmd, cwd=stage, text=True, capture_output=True,
                                      timeout=15, check=False)
            self.assertNotEqual(negative.returncode, 0)
            self.assertIn("No module named 'analyze_trace'", negative.stderr)


if __name__ == "__main__":
    unittest.main()
