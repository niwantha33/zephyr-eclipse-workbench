# SPDX-License-Identifier: Apache-2.0
"""No-hardware equivalence and fail-closed incremental Zephyr 4.4 CTF tests."""
from pathlib import Path
import struct
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[2] / (
    'plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis')
sys.path.insert(0, str(TOOLS))
from ctf_incremental import IncrementalPackedCtfDecoder, MAX_CHUNK
from zephyr_ctf_binary import decode_packed
from test_zephyr_ctf_binary import METADATA, raw_event


class CtfIncrementalTests(unittest.TestCase):
    def setUp(self):
        self.data = (
            raw_event(100, 0x11, 0x1234, 'worker')
            + raw_event(200, 0x10, 0x1234, 'worker')
            + raw_event(240, 0xEA, 0x1234, 'worker')
            + raw_event(270, 0xEB, 0x1234, 'worker')
            + raw_event(300, 0x1B))

    def test_every_chunk_boundary_matches_offline_exactly(self):
        with tempfile.TemporaryDirectory() as folder:
            ctf = Path(folder)
            (ctf / 'metadata').write_text(METADATA)
            (ctf / 'channel0_0').write_bytes(self.data)
            expected = list(decode_packed(ctf))
        for stride in (1, 2, 3, 5, 6, 7, 11, 16, 27, 128):
            with self.subTest(stride=stride):
                reader = IncrementalPackedCtfDecoder(METADATA)
                received = []
                for offset in range(0, len(self.data), stride):
                    received.extend(reader.feed(self.data[offset:offset + stride]))
                self.assertEqual(received, expected)
                result = reader.seal()
                self.assertEqual(result['events'], 5)
                self.assertEqual(result['committed_bytes'], len(self.data))
                self.assertEqual(result['pending_bytes'], 0)
                self.assertIsNone(result['source_drops'])
                self.assertFalse(result['live_stream_approved'])

    def test_partial_tail_preserved_as_uncommitted(self):
        reader = IncrementalPackedCtfDecoder(METADATA)
        self.assertEqual(len(reader.feed(self.data[:-2])), 4)
        self.assertEqual(reader.stats()['pending_bytes'], 4)
        with self.assertRaisesRegex(ValueError, 'Truncated CTF tail'):
            reader.seal()
        with self.assertRaisesRegex(ValueError, 'sealed'):
            reader.feed(self.data[-2:])

    def test_corruption_requires_a_new_epoch_not_scanning(self):
        reader = IncrementalPackedCtfDecoder(METADATA)
        bad = raw_event(100, 0x1B) + struct.pack('<IH', 200, 0xFFFF)
        with self.assertRaisesRegex(ValueError, 'no safe resynchronization'):
            reader.feed(bad)
        self.assertEqual(reader.stats()['events'], 1)
        self.assertTrue(reader.stats()['poisoned'])
        with self.assertRaisesRegex(ValueError, 'invalid'):
            reader.feed(raw_event(300, 0x1B))
        with self.assertRaisesRegex(ValueError, 'Poisoned'):
            reader.seal()

    def test_limits_and_no_invented_loss_counters(self):
        with self.assertRaises(ValueError):
            IncrementalPackedCtfDecoder('invalid')
        with self.assertRaises(ValueError):
            IncrementalPackedCtfDecoder(METADATA, max_stream_bytes=0)
        reader = IncrementalPackedCtfDecoder(METADATA)
        with self.assertRaisesRegex(ValueError, 'oversized'):
            reader.feed(b'x' * (MAX_CHUNK + 1))
        self.assertTrue(reader.stats()['poisoned'])
        reader = IncrementalPackedCtfDecoder(METADATA, max_stream_bytes=10)
        with self.assertRaisesRegex(ValueError, 'budget exceeded'):
            reader.feed(self.data)
        self.assertIsNone(reader.stats()['transport_gaps'])

    def test_wrap_and_backwards_clock(self):
        reader = IncrementalPackedCtfDecoder(METADATA)
        actual = reader.feed(raw_event(0xFFFFFFF0, 0x1B))
        actual += reader.feed(raw_event(0x10, 0x1B))
        self.assertEqual([e['timestamp_ns'] for e in actual],
                         [0xFFFFFFF0, (1 << 32) + 0x10])
        reader.seal()
        invalid = IncrementalPackedCtfDecoder(METADATA)
        invalid.feed(raw_event(300, 0x1B))
        with self.assertRaisesRegex(ValueError, 'Nonmonotonic'):
            invalid.feed(raw_event(200, 0x1B))


if __name__ == '__main__':
    unittest.main()
