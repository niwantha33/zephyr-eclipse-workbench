# SPDX-License-Identifier: Apache-2.0
"""Experimental real localhost RTT byte ingestion, NOT hardware qualification."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / "plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis"
sys.path.insert(0, str(TOOLS))
sys.path.insert(0, str(ROOT / "scripts"))
from fake_rtt_server import FakeRttServer, fixture_wire
from rtt_live_preview import capture_existing_rtt_preview, inspect_rtt_preflight
from test_zephyr_ctf_binary import METADATA, raw_event


class ExternalRttPreviewTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory(prefix="zew-rtt-preview-test-")
        self.addCleanup(folder.cleanup)
        self.root = Path(folder.name)
        self.meta = self.root / "metadata"
        self.meta.write_text(METADATA, encoding="utf-8")
        self.meta_sha = hashlib.sha256(self.meta.read_bytes()).hexdigest()
        # The actual Zephyr encoder places PREBUILD CONTRACT in this field.
        # It does NOT put the final ELF checksum on the wire.
        self.contract = hashlib.sha256(b"prebuild input, not the ELF").hexdigest()
        self.finished_elf_sha = hashlib.sha256(b"final ELF").hexdigest()
        self.assertNotEqual(self.contract, self.finished_elf_sha)
        self.epoch = bytes([49]) * 16
        self.payload = raw_event(100, 0x1B) + raw_event(200, 0x1B)
        self.config = self.root / ".config"
        self.config.write_text("\n".join((
            "CONFIG_TRACING=y", "CONFIG_TRACING_CTF=y",
            "CONFIG_TRACING_ASYNC=y", "CONFIG_TRACING_BACKEND_ZEW_RTT=y",
            "CONFIG_USE_SEGGER_RTT=y",
            "CONFIG_SEGGER_RTT_MAX_NUM_UP_BUFFERS=2",
            'CONFIG_ZEW_TRACE_IMAGE_CONTRACT_SHA256="' + self.contract + '"',
            'CONFIG_ZEW_TRACE_METADATA_SHA256="' + self.meta_sha + '"',
        )) + "\n", encoding="utf-8")

    def capture(self, wire):
        server = FakeRttServer(wire, chunk_bytes=3).start()
        try:
            folder = capture_existing_rtt_preview(
                server.port, self.config, self.meta, self.root,
                explicit_opt_in=True, seconds=5.0)
        finally:
            server.join()
        return folder, json.loads((folder / "receipt.json").read_text())

    def test_no_socket_without_explicit_consent(self):
        with self.assertRaises(PermissionError):
            capture_existing_rtt_preview(3334, self.config, self.meta, self.root)
        self.assertFalse(any(self.root.glob("zew-rtt-preview-*")))

    def test_real_byte_ingestion_is_not_production_qualification(self):
        wire = fixture_wire(self.payload, self.contract, self.meta_sha,
                            self.epoch, piece_size=7)
        folder, receipt = self.capture(wire)
        self.assertEqual(receipt["status"], "FORMAT_COMPLETE_UNVERIFIED")
        self.assertEqual((folder / "transport.bin").read_bytes(), wire)
        self.assertEqual((folder / "committed_ctf.bin").read_bytes(), self.payload)
        self.assertEqual(receipt["prebuild_image_contract_sha256"], self.contract)
        self.assertEqual(receipt["events_written"], 2)
        self.assertTrue(receipt["protocol"]["framing_complete"])
        for false_key in ("flashed_firmware_identity_verified",
                          "hardware_capture_qualified", "probe_ownership_verified",
                          "safe_for_offline_analysis", "live_stream_approved"):
            self.assertFalse(receipt[false_key], false_key)

    def test_generated_configuration_and_metadata_are_strict(self):
        original = self.config.read_text()
        for replacement in (
            original.replace("CONFIG_TRACING_BACKEND_ZEW_RTT=y", ""),
            original.replace("CONFIG_SEGGER_RTT_MAX_NUM_UP_BUFFERS=2",
                             "CONFIG_SEGGER_RTT_MAX_NUM_UP_BUFFERS=1"),
            original + "CONFIG_SMP=y\n",
            original + "CONFIG_USE_SEGGER_RTT=y\n",
            original.replace(self.contract, self.finished_elf_sha),
        ):
            with self.subTest(replacement=replacement[-80:]):
                self.config.write_text(replacement)
                if self.finished_elf_sha in replacement and replacement.count("CONFIG_") == original.count("CONFIG_"):
                    # A different but well-formed prebuild contract is not
                    # authenticatable from .config alone.
                    self.assertEqual(inspect_rtt_preflight(self.config, self.meta)
                                     ["image_contract_sha256"], self.finished_elf_sha)
                else:
                    with self.assertRaises(ValueError):
                        inspect_rtt_preflight(self.config, self.meta)
        self.config.write_text(original)
        self.meta.write_text(METADATA + "changed", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "metadata digest"):
            inspect_rtt_preflight(self.config, self.meta)

    def test_wrong_image_contract_is_not_accepted_as_elf_digest(self):
        wire = fixture_wire(self.payload, self.finished_elf_sha,
                            self.meta_sha, self.epoch, piece_size=9)
        _, receipt = self.capture(wire)
        self.assertEqual(receipt["status"], "INCOMPLETE")
        self.assertIn("digest mismatch", receipt["reason"])

    def test_loss_bad_crc_and_missing_end_are_incomplete(self):
        valid = fixture_wire(self.payload, self.contract, self.meta_sha, self.epoch)
        bad_crc = bytearray(valid)
        bad_crc[2 * 116 + 1] ^= 0x04
        for bad in (valid[:-116],
                    fixture_wire(self.payload, self.contract, self.meta_sha,
                                 self.epoch, source_losses=1),
                    bytes(bad_crc)):
            with self.subTest(case=len(bad)):
                folder, receipt = self.capture(bad)
                self.assertEqual(receipt["status"], "INCOMPLETE")
                self.assertFalse(receipt["live_stream_approved"])
                self.assertTrue((folder / "transport.bin").is_file())


if __name__ == "__main__":
    unittest.main()
