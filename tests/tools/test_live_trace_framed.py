# SPDX-License-Identifier: Apache-2.0
"""Hardware-free framed CTF TCP replay: integrity, identity, limits, offline equality."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / "plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis"
sys.path.insert(0, str(TOOLS))
sys.path.insert(0, str(ROOT / "scripts"))

from ctf_framing import (DATA, END, HELLO, HEADER, FramedCtfGate, FrameError,
                         MAX_PAYLOAD, encode_fixture_frame)
from fake_rtt_server import FakeRttServer, fixture_wire
from live_trace import capture_loopback_test
from test_zephyr_ctf_binary import METADATA, raw_event
from zephyr_ctf_binary import decode_packed


class FramedLiveTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="zew-ctf-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.meta = self.root / "metadata"
        self.meta.write_text(METADATA, encoding="utf-8")
        self.metadata_sha = hashlib.sha256(self.meta.read_bytes()).hexdigest()
        self.elf_sha = hashlib.sha256(b"SYNTHETIC ELF ID, NOT A TARGET").hexdigest()
        self.epoch = bytes([27]) * 16
        self.payload = (
            raw_event(100, 0x11, 0x1234, "worker")
            + raw_event(200, 0x10, 0x1234, "worker")
            + raw_event(240, 0xEA, 0x1234, "worker")
            + raw_event(270, 0xEB, 0x1234, "worker")
            + raw_event(300, 0x1B)
        )

    def wire(self, **kwargs):
        return fixture_wire(self.payload, self.elf_sha, self.metadata_sha,
                            self.epoch, **kwargs)

    def capture(self, wire, chunk_bytes=2, *, max_raw_bytes=None):
        server = FakeRttServer(wire, chunk_bytes=chunk_bytes).start()
        try:
            options = {"test_only": True, "seconds": 5}
            if max_raw_bytes is not None:
                options["max_raw_bytes"] = max_raw_bytes
            folder = capture_loopback_test(server.port, self.meta, self.elf_sha,
                                           self.epoch, self.root, **options)
        finally:
            server.join()
        return folder, json.loads((folder / "receipt.json").read_text())

    def test_no_production_or_network_access_without_test_consent(self):
        with self.assertRaises(PermissionError):
            capture_loopback_test(12345, self.meta, self.elf_sha, self.epoch, self.root)
        with self.assertRaises(ValueError):
            FramedCtfGate("invalid", self.metadata_sha, self.epoch)
        with self.assertRaises(ValueError):
            FramedCtfGate(self.elf_sha, self.metadata_sha, bytes(16))

    def test_arbitrary_tcp_splits_produce_byte_exact_offline_records(self):
        offline = self.root / "offline"
        offline.mkdir()
        (offline / "metadata").write_bytes(self.meta.read_bytes())
        (offline / "channel0_0").write_bytes(self.payload)
        expected = list(decode_packed(offline))
        for tcp_stride in (1, 3, 9, 1024):
            with self.subTest(tcp_stride=tcp_stride):
                folder, receipt = self.capture(self.wire(piece_size=7), chunk_bytes=tcp_stride)
                self.assertEqual(receipt["status"], "TEST_ONLY_COMPLETE")
                actual = [json.loads(row) for row in
                          (folder / "events.jsonl").read_text().splitlines()]
                self.assertEqual(actual, expected)
                self.assertEqual((folder / "committed_ctf.bin").read_bytes(), self.payload)
                self.assertEqual(receipt["events_written"], 5)
                self.assertTrue(receipt["protocol"]["framing_complete"])
                self.assertFalse(receipt["safe_for_offline_analysis"])
                self.assertFalse(receipt["live_stream_approved"])
                self.assertEqual(receipt["target_state"], "UNKNOWN")

    def test_valid_frames_split_at_each_possible_header_offset(self):
        wire = self.wire(piece_size=MAX_PAYLOAD)
        for stride in (1, 2, 4, 5, HEADER.size - 1, HEADER.size, HEADER.size + 1):
            with self.subTest(stride=stride):
                gate = FramedCtfGate(self.elf_sha, self.metadata_sha, self.epoch)
                payload = []
                for index in range(0, len(wire), stride):
                    payload.extend(gate.feed(wire[index:index + stride]))
                self.assertEqual(b"".join(payload), self.payload)
                self.assertEqual(gate.finish()["accepted_raw_bytes"], len(self.payload))

    def test_bad_crc_keeps_untrusted_raw_and_incomplete_receipt(self):
        bad = bytearray(self.wire(piece_size=MAX_PAYLOAD))
        # Flip an actual DATA payload byte without correcting its checksum.
        bad[2 * HEADER.size + 5] ^= 0xA5
        folder, receipt = self.capture(bytes(bad), chunk_bytes=37)
        self.assertEqual(receipt["status"], "INCOMPLETE")
        self.assertIn("CRC mismatch", receipt["reason"])
        self.assertFalse(receipt["protocol"]["framing_complete"])
        self.assertFalse(receipt["hardware_capture"])
        self.assertTrue((folder / "transport.bin").read_bytes())

    def test_sequence_gap_or_duplicate_never_silently_recovers(self):
        for bad_sequence in (0, 2):
            with self.subTest(sequence=bad_sequence):
                wire = (
                    encode_fixture_frame(HELLO, 0, self.epoch, self.elf_sha, self.metadata_sha)
                    + encode_fixture_frame(DATA, bad_sequence, self.epoch,
                                           self.elf_sha, self.metadata_sha, self.payload)
                    + encode_fixture_frame(END, 3, self.epoch, self.elf_sha, self.metadata_sha)
                )
                _, receipt = self.capture(wire)
                self.assertEqual(receipt["status"], "INCOMPLETE")
                self.assertIn("sequence gap", receipt["reason"])

    def test_wrong_epoch_metadata_or_elf_fails_before_ctf(self):
        incorrect = [
            ("epoch", bytes([1]) * 16, self.elf_sha, self.metadata_sha),
            ("ELF", self.epoch, hashlib.sha256(b"wrong").hexdigest(), self.metadata_sha),
            ("metadata", self.epoch, self.elf_sha, hashlib.sha256(b"wrong").hexdigest()),
        ]
        for label, epoch, elf, meta in incorrect:
            with self.subTest(label=label):
                wire = encode_fixture_frame(HELLO, 0, epoch, elf, meta)
                _, receipt = self.capture(wire)
                self.assertEqual(receipt["status"], "INCOMPLETE")
                self.assertIn("digest mismatch", receipt["reason"])

    def test_truncated_transport_tail_and_missing_end(self):
        for wire in (self.wire()[:-10], self.wire()[:-HEADER.size]):
            with self.subTest(length=len(wire)):
                _, receipt = self.capture(wire)
                self.assertEqual(receipt["status"], "INCOMPLETE")
                self.assertFalse(receipt["protocol"]["framing_complete"])

    def test_firmware_loss_counter_is_not_silently_discarded(self):
        _, receipt = self.capture(self.wire(source_losses=3))
        self.assertEqual(receipt["status"], "INCOMPLETE")
        self.assertEqual(receipt["protocol"]["source_losses_reported"], 3)
        self.assertIn("source loss", receipt["reason"])

    def test_ctf_decoder_refuses_corrupt_frame_payload(self):
        data = raw_event(100, 0x1B) + struct.pack("<IH", 200, 0xFFFF)
        wire = fixture_wire(data, self.elf_sha, self.metadata_sha, self.epoch,
                            piece_size=MAX_PAYLOAD)
        folder, receipt = self.capture(wire)
        self.assertEqual(receipt["status"], "INCOMPLETE")
        self.assertIn("Unknown Zephyr CTF event ID", receipt["reason"])
        self.assertTrue((folder / "committed_ctf.bin").is_file())
        self.assertFalse(receipt["safe_for_offline_analysis"])

    def test_host_byte_budget_and_protocol_limits(self):
        _, receipt = self.capture(self.wire(piece_size=MAX_PAYLOAD),
                                  max_raw_bytes=len(self.payload) - 1)
        self.assertEqual(receipt["status"], "INCOMPLETE")
        self.assertIn("Raw CTF byte budget exhausted", receipt["reason"])
        huge_header = bytearray(encode_fixture_frame(HELLO, 0, self.epoch,
                                                     self.elf_sha, self.metadata_sha))
        huge_header[8:12] = struct.pack("<I", MAX_PAYLOAD + 1)
        _, receipt = self.capture(bytes(huge_header))
        self.assertEqual(receipt["status"], "INCOMPLETE")
        self.assertIn("length exceeds", receipt["reason"])

    def test_wrong_core_and_post_end_bytes_are_rejected(self):
        wire = encode_fixture_frame(HELLO, 0, self.epoch, self.elf_sha,
                                    self.metadata_sha, core=2)
        _, receipt = self.capture(wire)
        self.assertIn("Multi-core", receipt["reason"])
        _, receipt = self.capture(self.wire() + b"extra")
        self.assertEqual(receipt["status"], "INCOMPLETE")
        self.assertIn("Trailing bytes", receipt["reason"])


if __name__ == "__main__":
    unittest.main()
