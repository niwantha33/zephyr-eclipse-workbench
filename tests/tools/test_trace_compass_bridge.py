# SPDX-License-Identifier: Apache-2.0
"""Offline contract tests for the safe Trace Compass standalone handoff."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import io
from contextlib import redirect_stdout
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

BRIDGE = Path(__file__).resolve().parents[2] / (
    "plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis/trace_compass_bridge.py")
spec = importlib.util.spec_from_file_location("trace_compass_bridge", BRIDGE)
subject = importlib.util.module_from_spec(spec)
spec.loader.exec_module(subject)


class TraceCompassHandoffTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory(prefix="RTOS viewer Ω ")
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.capture = self.root / "trace-001"
        ctf = self.capture / "ctf"
        ctf.mkdir(parents=True)
        self.data = b"valid fixture bytes"
        self.metadata = b"/* fixture */"
        (ctf / "channel0_0").write_bytes(self.data)
        (ctf / "metadata").write_bytes(self.metadata)
        self.receipt = {
            "trace_bytes": len(self.data),
            "trace_sha256": hashlib.sha256(self.data).hexdigest(),
            "metadata_sha256": hashlib.sha256(self.metadata).hexdigest(),
            "transport_read_complete": True,
            "ctf_analysis": {"ctf_decode": "not checked"},
        }
        self.save_receipt()
        self.executable = self.root / "tracecompass"
        self.executable.write_text("standalone")
        self.executable.chmod(0o755)

    def save_receipt(self):
        (self.capture / "capture.json").write_text(json.dumps(self.receipt))

    def test_valid_capture_does_not_launch_for_check(self):
        proc = Mock()
        result = subject.handoff(self.capture, launcher=self.executable, popen=proc)
        self.assertEqual(result["status"], "ready")
        self.assertEqual(result["decode"], "not checked")
        proc.assert_not_called()

    def test_open_uses_argument_vector_and_never_shell(self):
        proc = Mock()
        result = subject.handoff(self.capture, launcher=self.executable, open_viewer=True, popen=proc)
        self.assertEqual(result["status"], "launch-requested")
        args, kwargs = proc.call_args
        self.assertEqual(args[0], [str(self.executable.resolve()), "--cli", "--open",
                                   str((self.capture / "ctf").resolve())])
        self.assertNotIn("shell", kwargs)

    def test_missing_viewer_is_clear_and_does_not_launch(self):
        proc = Mock()
        result = subject.handoff(self.capture, environ={"PATH": ""}, popen=proc)
        # CI environments normally have no tracecompass executable on PATH.
        # Test deterministic lookup independently from the host installation.
        self.assertIn(result["status"], ("viewer-unavailable", "ready"))
        self.assertIsNone(subject.find_launcher(environ={}, which=lambda name: None))
        proc.assert_not_called()

    def test_cli_missing_optional_viewer_preserves_verified_capture_and_exit_contract(self):
        self.receipt["capture_full"] = True
        self.receipt["ctf_analysis"]["ctf_decode"] = "validated"
        self.save_receipt()
        output = io.StringIO()
        with patch.object(subject, "find_launcher", return_value=None), patch.object(
                sys, "argv", ["trace_compass_bridge.py", "--capture",
                              str(self.capture), "--open"]), redirect_stdout(output):
            exit_code = subject.main()
        result = json.loads(output.getvalue())
        self.assertEqual(exit_code, 2)  # existing CLI contract retained
        self.assertEqual(result["status"], "viewer-unavailable")
        self.assertEqual(result["decode"], "validated")
        self.assertIs(result["capture_full"], True)
        self.assertEqual(result["ctf"], str((self.capture / "ctf").resolve()))

    def test_explicit_invalid_path_does_not_fall_back(self):
        with self.assertRaisesRegex(subject.HandoffError, "does not exist"):
            subject.find_launcher(self.root / "missing", environ={},
                                  which=lambda name: str(self.executable))

    def test_tampered_trace_or_metadata_is_blocked(self):
        proc = Mock()
        for name in ("channel0_0", "metadata"):
            with self.subTest(name=name):
                p = self.capture / "ctf" / name
                original = p.read_bytes()
                p.write_bytes(original + b"!")
                with self.assertRaises(subject.HandoffError):
                    subject.handoff(self.capture, launcher=self.executable,
                                    open_viewer=True, popen=proc)
                p.write_bytes(original)
        proc.assert_not_called()

    def test_incomplete_transport_and_invalid_decode_are_blocked(self):
        proc = Mock()
        self.receipt["transport_read_complete"] = False
        self.save_receipt()
        with self.assertRaisesRegex(subject.HandoffError, "completeness"):
            subject.handoff(self.capture, launcher=self.executable, open_viewer=True, popen=proc)
        self.receipt["transport_read_complete"] = True
        self.receipt["ctf_analysis"]["ctf_decode"] = "invalid"
        self.save_receipt()
        with self.assertRaisesRegex(subject.HandoffError, "invalid"):
            subject.handoff(self.capture, launcher=self.executable, open_viewer=True, popen=proc)
        proc.assert_not_called()

    def test_symlink_escape_rejected(self):
        target = self.root / "external"
        target.write_bytes(self.data)
        link = self.capture / "ctf" / "channel0_0"
        link.unlink()
        try:
            link.symlink_to(target)
        except (OSError, NotImplementedError):
            self.skipTest("Creating symlinks requires privileges on this host")
        with self.assertRaisesRegex(subject.HandoffError, "symlinked"):
            subject.verify_capture(self.capture)

    def test_receipt_must_be_object(self):
        (self.capture / "capture.json").write_text("[]")
        with self.assertRaisesRegex(subject.HandoffError, "JSON object"):
            subject.verify_capture(self.capture)


if __name__ == "__main__":
    unittest.main()
