# SPDX-License-Identifier: Apache-2.0
"""Regression: the packaged RAM CTF capture helper must import in isolation.

No target, OpenOCD, GDB, Babeltrace 2 or Zephyr installation is required.
Copy only the exact Python resource names declared by the Java capture handler
into a fresh temp directory, just as the installed OSGi bundle does.
"""
from __future__ import annotations

import ast
import json
import os
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
LAUNCH = ROOT / "plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/TracePythonLaunch.java"


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


def trusted_bootstrap():
    """Extract the actual Java bootstrap, avoiding an independent test-only copy."""
    source = LAUNCH.read_text(encoding="utf-8")
    match = re.search(r"static final String BOOTSTRAP\\s*=\\s*(.*?);", source, re.S)
    if match is None:
        raise AssertionError("Missing trace Python bootstrap")
    pieces = re.findall(r'"([^"\\n]*)"', match.group(1))
    if not pieces:
        raise AssertionError("Cannot extract the actual isolated Python bootstrap")
    self_code = "".join(json.loads('"' + piece + '"') for piece in pieces)
    if "sys.path.insert(0,os.path.dirname(source))" not in self_code:
        raise AssertionError("Trace bootstrap must add only the staged script parent")
    return self_code


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
        self.assertIn("TracePythonLaunch.command(python, stage.resolve", source)
        self.assertIn("var command = TracePythonLaunch.command(", source)
        self.assertEqual(source.count("TracePythonLaunch.command("), 2,
                         "Preflight and actual GDB capture must use identical Python launch mode")
        self.assertLess(source.index("staging = stageCaptureTool("),
                        source.index("server = builder.start();"),
                        "Python staging validation must happen before OpenOCD opens")
        launcher = LAUNCH.read_text(encoding="utf-8")
        for flag in ('command.add("-I")', 'command.add("-c")'):
            self.assertIn(flag, launcher, "Keep Windows embedded Python isolation")

    def test_copied_runtime_imports_without_repo_path(self):
        resources, _ = capture_resources()
        # In the managed Windows embedded python313._pth distribution, script
        # sibling modules are NOT on sys.path. -I reproduces the isolation on
        # ordinary CI CPython, independently of the CI host's platform.
        with tempfile.TemporaryDirectory(prefix="zew-ctf-packaging-Ω ") as temp:
            root = Path(temp)
            stage = root / "trusted trace tool"
            stage.mkdir()
            outside = root / "different cwd"
            outside.mkdir()
            for name in resources:
                (stage / name).write_bytes((TOOLS / name).read_bytes())
            env = dict(os.environ, PYTHONPATH=str(TOOLS), PYTHONSAFEPATH="1",
                       PYTHONDONTWRITEBYTECODE="1")
            script = str(stage / "capture_snapshot.py")
            direct = subprocess.run([sys.executable, "-I", script, "--help"],
                                    cwd=outside, env=env, text=True, capture_output=True,
                                    timeout=15, check=False)
            self.assertNotEqual(direct.returncode, 0,
                                "Regression fixture must reproduce the embedded Python failure")
            self.assertIn("No module named 'analyze_trace'", direct.stderr)

            bootstrap = trusted_bootstrap()
            cmd = [sys.executable, "-I", "-X", "utf8", "-c", bootstrap, script, "--help"]
            result = subprocess.run(cmd, cwd=outside, env=env, text=True,
                                    capture_output=True, timeout=15, check=False)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("--build", result.stdout)

            (stage / "analyze_trace.py").unlink()
            missing = subprocess.run(cmd, cwd=outside, env=env, text=True,
                                     capture_output=True, timeout=15, check=False)
            self.assertNotEqual(missing.returncode, 0)
            self.assertIn("No module named 'analyze_trace'", missing.stderr)

if __name__ == "__main__":
    unittest.main()
