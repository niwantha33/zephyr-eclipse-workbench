# SPDX-License-Identifier: Apache-2.0
"""No-hardware inspect tests: exact config/metadata, corrupt/loss/end gates."""
from __future__ import annotations

import hashlib
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis"))
from inspect_framed_ram import inspect
from ctf_framing import encode_fixture_frame, HELLO, DATA, END
from test_zephyr_ctf_binary import METADATA, raw_event


class HaltedRamInspectorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="zew-halted-ctf-")
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.build = root / "build"
        (self.build / "zephyr").mkdir(parents=True)
        # This fixture is deliberately NOT a real/attested ELF. The inspector
        # must always deny flashed-image/hardware qualification even on PASS.
        (self.build / "zephyr/zephyr.elf").write_bytes(
            b"\x7fELF" + bytes(128))
        self.meta = root / "metadata"
        self.meta.write_text(METADATA, encoding="utf-8")
        self.contract = "11" * 32
        self.metadata_sha = hashlib.sha256(self.meta.read_bytes()).hexdigest()
        self.conf = self.build / "zephyr/.config"
        self.conf.write_text(
            "CONFIG_TRACING_CTF=y\nCONFIG_TRACING_ASYNC=y\n"
            "CONFIG_TRACING_BACKEND_ZEW_RAM=y\n"
            f'CONFIG_ZEW_TRACE_IMAGE_CONTRACT_SHA256="{self.contract}"\n'
            f'CONFIG_ZEW_TRACE_METADATA_SHA256="{self.metadata_sha}"\n',
            encoding="utf-8")
        self.wire = root / "dump.bin"
        epoch = bytes([27]) * 16
        raw = raw_event(10, 0x1B)
        self.frames = (
            encode_fixture_frame(HELLO, 0, epoch, self.contract, self.metadata_sha)
            + encode_fixture_frame(DATA, 1, epoch, self.contract, self.metadata_sha, raw)
            + encode_fixture_frame(END, 2, epoch, self.contract, self.metadata_sha))
        self.wire.write_bytes(self.frames)

    def test_complete_captured_wire_never_becomes_hardware_attestation(self):
        result = inspect(self.wire, self.build, self.meta)
        self.assertEqual(result["wire_format"], "COMPLETE")
        self.assertEqual(result["observed_event_count"], 1)
        self.assertEqual(result["frame_count"], 3)
        self.assertFalse(result["flashed_firmware_identity_verified"])
        self.assertFalse(result["hardware_capture_qualified"])
        self.assertFalse(result["live_stream_approved"])

    def test_invalid_config_or_metadata_never_accepted(self):
        self.conf.write_text("CONFIG_TRACING_BACKEND_ZEW_RTT=y\n")
        with self.assertRaises(ValueError):
            inspect(self.wire, self.build, self.meta)
        self.conf.write_text(
            "CONFIG_TRACING_CTF=y\nCONFIG_TRACING_ASYNC=y\n"
            "CONFIG_TRACING_BACKEND_ZEW_RAM=y\n"
            f'CONFIG_ZEW_TRACE_IMAGE_CONTRACT_SHA256="{self.contract}"\n'
            f'CONFIG_ZEW_TRACE_METADATA_SHA256="{"00" * 32}"\n')
        with self.assertRaisesRegex(ValueError, "metadata digest"):
            inspect(self.wire, self.build, self.meta)

    def test_missing_end_or_corrupt_crc_fails_closed(self):
        self.wire.write_bytes(self.frames[:-116])
        with self.assertRaisesRegex(ValueError, "Missing END"):
            inspect(self.wire, self.build, self.meta)
        raw = bytearray(self.frames)
        raw[116 + 116 + 1] ^= 1
        self.wire.write_bytes(raw)
        with self.assertRaisesRegex(ValueError, "CRC mismatch"):
            inspect(self.wire, self.build, self.meta)

    def test_incomplete_elf_or_oversized_dump_refused(self):
        (self.build / "zephyr/zephyr.elf").write_bytes(b"fake")
        with self.assertRaisesRegex(ValueError, "not an ELF"):
            inspect(self.wire, self.build, self.meta)
        self.wire.write_bytes(bytes(16385))
        with self.assertRaisesRegex(ValueError, "oversized"):
            inspect(self.wire, self.build, self.meta)


if __name__ == "__main__":
    unittest.main()
