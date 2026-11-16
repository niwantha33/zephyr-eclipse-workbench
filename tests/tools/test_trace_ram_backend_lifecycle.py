# SPDX-License-Identifier: Apache-2.0
"""Run actual C memory transport, verify cross-language END/loss/overflow wire."""
from __future__ import annotations

from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
FIRMWARE = ROOT / "firmware/trace/zephyr44"
sys.path.insert(0, str(ROOT / "plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis"))

from ctf_framing import FramedCtfGate, FrameError, HELLO, DATA, END, encode_fixture_frame


class TraceRamBackendLifecycleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if sys.platform == "win32":
            raise unittest.SkipTest("Native C compiler wiring is exercised in Linux CI")
        cc = shutil.which("cc") or shutil.which("clang") or shutil.which("gcc")
        if not cc:
            raise unittest.SkipTest("No native C compiler")
        cls.tmp = tempfile.TemporaryDirectory(prefix="zew-ram-fw-")
        cls.exe = str(Path(cls.tmp.name) / "trace-ram-probe")
        subprocess.run([
            cc, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-pedantic",
            "-I", str(ROOT / "tests/firmware/stubs"),
            "-I", str(FIRMWARE),
            str(ROOT / "tests/firmware/trace_ram_backend_probe.c"),
            str(FIRMWARE / "zew_trace_frame.c"),
            "-o", cls.exe,
        ], check=True, capture_output=True, text=True, timeout=30, cwd=ROOT)

    @classmethod
    def tearDownClass(cls):
        if hasattr(cls, "tmp"):
            cls.tmp.cleanup()

    def run_firmware(self, kind=None):
        command = [self.exe] + ([kind] if kind else [])
        return subprocess.run(command, capture_output=True, check=True,
                              timeout=5).stdout

    def test_normal_close_emits_exact_one_end_frame(self):
        actual = self.run_firmware()
        epoch = bytes([27]) * 16
        sha1 = "11" * 32
        sha2 = "22" * 32
        ctf = bytes([10, 0, 0, 0, 0x1B, 0])
        expected = (
            encode_fixture_frame(HELLO, 0, epoch, sha1, sha2)
            + encode_fixture_frame(DATA, 1, epoch, sha1, sha2, ctf)
            + encode_fixture_frame(END, 2, epoch, sha1, sha2)
        )
        self.assertEqual(actual, expected)
        gate = FramedCtfGate(sha1, sha2, epoch)
        outputs = []
        for p in range(0, len(actual), 5):
            outputs.extend(gate.feed(actual[p:p + 5]))
        self.assertEqual(outputs, [ctf])
        self.assertTrue(gate.finish()["framing_complete"])
        self.assertFalse(gate.stats()["live_stream_approved"])

    def test_duplicate_stop_does_not_append_duplicate_end(self):
        self.assertEqual(self.run_firmware("duplicate"), self.run_firmware())

    def test_source_packet_loss_poisoned_and_not_sealed(self):
        raw = self.run_firmware("loss")
        gate = FramedCtfGate("11" * 32, "22" * 32, bytes([27]) * 16)
        with self.assertRaisesRegex(FrameError, "source loss"):
            for pos in range(0, len(raw), 7):
                gate.feed(raw[pos:pos + 7])
        self.assertEqual(gate.stats()["source_losses_reported"], 1)
        self.assertFalse(gate.stats()["framing_complete"])

    def test_ram_capacity_overflow_never_claims_end(self):
        raw = self.run_firmware("overflow")
        self.assertLessEqual(len(raw), 4096)
        gate = FramedCtfGate("11" * 32, "22" * 32, bytes([27]) * 16)
        for pos in range(0, len(raw), 333):
            gate.feed(raw[pos:pos + 333])
        with self.assertRaisesRegex(FrameError, "Missing END"):
            gate.finish()
        self.assertFalse(gate.stats()["framing_complete"])

    def test_vendor_free_backend_and_pinned_patcher_has_stop_barrier(self):
        ram = (FIRMWARE / "tracing_backend_zew_ram.c").read_text()
        patch = (FIRMWARE / "apply_pinned_backend.py").read_text()
        for symbol in ("zew_trace_ram_bytes", "zew_trace_ram_receipt",
                       "zew_trace_backend_close", "tracing_packet_drop_count_get"):
            self.assertIn(symbol, ram)
        self.assertNotIn("SEGGER_RTT", ram)
        self.assertNotIn("uart_poll_out", ram)
        self.assertIn("is_tracing_enabled()", patch)
        self.assertIn("atomic_cas(&zew_trace_stop_requested, 1, 2)", patch)
        self.assertIn("CONFIG_TRACING_BACKEND_ZEW_RAM", patch)
        self.assertIn("tracing_format_async.c", patch)


if __name__ == "__main__":
    unittest.main()
