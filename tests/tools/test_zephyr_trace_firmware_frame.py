# SPDX-License-Identifier: Apache-2.0
"""Compile the actual C firmware frame encoder and verify byte-for-byte host v1.

No board or Zephyr SDK required. Linux CI MUST have a C compiler; non-Linux
jobs skip compile-only tests explicitly without claiming hardware support.
"""
from __future__ import annotations

import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / "firmware/trace/zephyr44"
HOST = ROOT / "plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis"
sys.path.insert(0, str(HOST))
from ctf_framing import (FramedCtfGate, HELLO, DATA, END,
                         encode_fixture_frame)


class ZephyrTraceFirmwareFrameContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="zew-c-frame-")
        compiler = shutil.which("cc") or shutil.which("clang") or shutil.which("gcc")
        if not compiler:
            cls.temp.cleanup()
            raise unittest.SkipTest("No system C compiler: native frame contract not tested")
        if sys.platform == "win32":
            cls.temp.cleanup()
            raise unittest.SkipTest("Windows uses separate native firmware matrix; no POSIX C compiler assumption")
        cls.exe = pathlib.Path(cls.temp.name) / "frame-probe"
        command = [
            compiler, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
            "-pedantic", "-I", str(SOURCE),
            str(SOURCE / "zew_trace_frame.c"),
            str(ROOT / "tests/firmware/trace_frame_probe.c"),
            "-o", str(cls.exe),
        ]
        subprocess.run(command, check=True, timeout=25, cwd=ROOT,
                       capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        if hasattr(cls, "temp"):
            cls.temp.cleanup()

    def wire(self):
        epoch = bytes([27]) * 16
        elf_contract = "11" * 32
        metadata = "22" * 32
        payload = bytes([1, 2, 3, 0, 255, 254])
        result = [
            encode_fixture_frame(HELLO, 0, epoch, elf_contract, metadata),
            encode_fixture_frame(DATA, 1, epoch, elf_contract, metadata, payload),
            encode_fixture_frame(END, 2, epoch, elf_contract, metadata),
        ]
        return b"".join(result)

    def test_byte_exact_native_c_matches_python_v1_frame_decoder(self):
        output = subprocess.run([str(self.exe)], check=True,
                                capture_output=True, timeout=5)
        self.assertEqual(output.stdout, self.wire())
        gate = FramedCtfGate("11" * 32, "22" * 32, bytes([27]) * 16)
        pieces = []
        for cursor in range(0, len(output.stdout), 3):
            pieces.extend(gate.feed(output.stdout[cursor:cursor + 3]))
        self.assertEqual(pieces, [bytes([1, 2, 3, 0, 255, 254])])
        self.assertEqual(gate.finish()["frames"], 3)

    def test_firmware_nonblocking_write_failure_poisoned_no_recovery(self):
        output = subprocess.run([str(self.exe), "reject"], check=True,
                                capture_output=True, timeout=5)
        self.assertEqual(
            output.stdout,
            encode_fixture_frame(HELLO, 0, bytes([27]) * 16,
                                 "11" * 32, "22" * 32),
        )

    def test_patch_requires_explicit_user_action(self):
        patcher = SOURCE / "apply_pinned_backend.py"
        text = patcher.read_text(encoding="utf-8")
        self.assertIn("PINNED_ZEPHYR", text)
        self.assertIn("parser.add_argument(\"--apply\"", text)
        self.assertIn('git(root, "status", "--porcelain"', text)
        self.assertIn("source files verified; no writes", text)
        backend = (SOURCE / "tracing_backend_zew_rtt.c").read_text()
        self.assertIn("TRACING_ASYNC", backend)
        self.assertIn("SEGGER_RTT_MODE_NO_BLOCK_SKIP", backend)
        self.assertIn("tracing_packet_drop_count_get()", backend)
        self.assertIn("ZEW_TRACE_RTT_CHANNEL = 1", backend)
        self.assertNotIn("SEGGER_RTT_Init();", backend,
                         "Never reset a pre-existing RTT channel-0 console")
        self.assertIn("void zew_trace_backend_close(void)", backend)
        self.assertIn("ZEW_TRACE_FRAME_END", backend)


if __name__ == "__main__":
    unittest.main()
