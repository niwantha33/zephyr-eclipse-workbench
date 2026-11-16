# SPDX-License-Identifier: Apache-2.0
"""Phase 6 semantic checkpoint tests: no boards, no debug probes, no live UI."""
from __future__ import annotations

import hashlib
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / "plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis"
sys.path.insert(0, str(TOOLS))

from ctf_incremental import IncrementalPackedCtfDecoder
from semantic_checkpoint import SemanticEpochCheckpoint
from semantic_trace import SemanticTraceParser
from test_zephyr_ctf_binary import METADATA, raw_event
from zephyr_ctf_binary import decode_packed


class SemanticCheckpointTests(unittest.TestCase):
    def setUp(self):
        self.epoch = bytes([37]) * 16
        self.metadata_sha = hashlib.sha256(METADATA.encode("utf-8")).hexdigest()
        self.raw = (raw_event(100, 0x11, 0x1234, "worker")
                    + raw_event(200, 0x10, 0x1234, "worker")
                    + raw_event(240, 0xEA, 0x1234, "worker")
                    + raw_event(270, 0xEB, 0x1234, "worker")
                    + raw_event(300, 0x1B))

    def new_session(self, **options):
        return SemanticEpochCheckpoint(METADATA, self.metadata_sha,
                                       self.epoch, **options)

    def append(self, session, records, checkpoint, **kwargs):
        return session.append(records, checkpoint=checkpoint,
                              metadata_sha256=kwargs.get("metadata_sha256", self.metadata_sha),
                              epoch=kwargs.get("epoch", self.epoch))

    def test_every_record_split_matches_offline_semantics_without_global_sort(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "metadata").write_text(METADATA, encoding="utf-8")
            (root / "channel0_0").write_bytes(self.raw)
            expected = SemanticTraceParser().parse(list(decode_packed(root)))
        self.assertFalse(expected.incomplete)
        for stride in (1, 2, 3, 5, 6, 7, 11, 16, 27, 128, 4096):
            with self.subTest(stride=stride):
                decoder = IncrementalPackedCtfDecoder(METADATA)
                session = self.new_session()
                added = []
                for pos in range(0, len(self.raw), stride):
                    rows = decoder.feed(self.raw[pos:pos + stride])
                    committed = decoder.stats()["committed_bytes"]
                    added.extend(self.append(session, rows, committed))
                decoder.seal()
                report = session.finish(format_sealed=True, source_losses=0)
                self.assertEqual(added, expected.events)
                self.assertEqual(session.snapshot().events, expected.events)
                self.assertEqual(session.snapshot().raw_records, expected.raw_records)
                self.assertEqual(report["semantic_events"], 5)
                self.assertTrue(report["format_complete_test_only"])
                self.assertFalse(report["hardware_capture_verified"])
                self.assertFalse(report["metrics_qualified"])
                self.assertFalse(report["live_stream_approved"])

    def test_exact_retry_is_idempotent_and_conflicting_retry_poisoned(self):
        decoder = IncrementalPackedCtfDecoder(METADATA)
        session = self.new_session()
        first = decoder.feed(self.raw[:40])
        checkpoint = decoder.stats()["committed_bytes"]
        self.assertTrue(first)
        self.assertEqual(len(self.append(session, first, checkpoint)), len(first))
        self.assertEqual(self.append(session, first, checkpoint), [])
        self.assertEqual(session.stats()["semantic_events"], len(first))
        changed = [dict(row) for row in first]
        changed[0]["thread_name"] = "tampered"
        with self.assertRaisesRegex(ValueError, "Conflicting duplicate"):
            self.append(session, changed, checkpoint)
        self.assertTrue(session.stats()["poisoned"])
        with self.assertRaisesRegex(ValueError, "invalid"):
            self.append(session, first, checkpoint)

    def test_wrong_metadata_epoch_and_nonzero_source_loss_fail_closed(self):
        for options in ({"metadata_sha256": "0" * 64},
                        {"epoch": bytes([2]) * 16}):
            with self.subTest(options=options):
                decoder = IncrementalPackedCtfDecoder(METADATA)
                session = self.new_session()
                rows = decoder.feed(self.raw)
                with self.assertRaisesRegex(ValueError, "identity drift"):
                    self.append(session, rows, decoder.stats()["committed_bytes"], **options)
                self.assertTrue(session.stats()["poisoned"])
        decoder = IncrementalPackedCtfDecoder(METADATA)
        session = self.new_session()
        rows = decoder.feed(self.raw)
        self.append(session, rows, decoder.stats()["committed_bytes"])
        report = session.finish(format_sealed=True, source_losses=2)
        self.assertFalse(report["format_complete_test_only"])
        self.assertTrue(report["poisoned"])
        self.assertTrue(session.snapshot().incomplete)

    def test_rejects_gaps_reordered_timestamps_and_multistream(self):
        decoder = IncrementalPackedCtfDecoder(METADATA)
        records = decoder.feed(self.raw)
        checkpoint = decoder.stats()["committed_bytes"]
        scenarios = [
            ("wire-offset gap", lambda rows: rows[0].update(wire_offset=1)),
            ("Out-of-order semantic", lambda rows: rows[1].update(timestamp_ns=1)),
            ("Unsupported", lambda rows: rows[1].update(stream=1)),
            ("Unsupported", lambda rows: rows[1].update(kind="discarded_events")),
        ]
        for fragment, mutate in scenarios:
            with self.subTest(fragment=fragment):
                session = self.new_session()
                raw = [dict(row) for row in records]
                mutate(raw)
                with self.assertRaisesRegex(ValueError, fragment):
                    self.append(session, raw, checkpoint)
                self.assertTrue(session.stats()["poisoned"])
                self.assertFalse(session.stats()["format_complete_test_only"])

    def test_invalid_budget_and_identity_inputs_rejected(self):
        for kwargs in ({"max_events": 0}, {"max_events": 16001}):
            with self.assertRaises(ValueError):
                self.new_session(**kwargs)
        with self.assertRaisesRegex(ValueError, "metadata digest mismatch"):
            SemanticEpochCheckpoint(METADATA, "0" * 64, self.epoch)
        with self.assertRaisesRegex(ValueError, "epoch"):
            SemanticEpochCheckpoint(METADATA, self.metadata_sha, bytes(16))
        decoder = IncrementalPackedCtfDecoder(METADATA)
        rows = decoder.feed(self.raw)
        session = self.new_session(max_events=2)
        with self.assertRaisesRegex(ValueError, "event budget"):
            self.append(session, rows, decoder.stats()["committed_bytes"])

    def test_missing_end_and_abort_retain_prefix_but_never_approve(self):
        decoder = IncrementalPackedCtfDecoder(METADATA)
        session = self.new_session()
        rows = decoder.feed(self.raw)
        self.append(session, rows, decoder.stats()["committed_bytes"])
        report = session.finish(format_sealed=False, source_losses=None)
        self.assertTrue(report["poisoned"])
        self.assertFalse(report["format_complete_test_only"])
        self.assertEqual(len(session.snapshot().events), 5)
        with self.assertRaisesRegex(ValueError, "closed"):
            self.append(session, rows, decoder.stats()["committed_bytes"])
        aborted = self.new_session().abort("transport disconnected")
        self.assertIn("disconnected", aborted["reason"])
        self.assertFalse(aborted["live_stream_approved"])

    def test_equal_timestamps_preserve_decoder_source_order(self):
        raw = raw_event(100, 0x1B) + raw_event(100, 0x1B)
        decoder = IncrementalPackedCtfDecoder(METADATA)
        records = decoder.feed(raw)
        session = self.new_session()
        self.append(session, records, decoder.stats()["committed_bytes"])
        self.assertEqual([e.timestamp_ns for e in session.snapshot().events], [100, 100])
        self.assertEqual([r["wire_offset"] for r in session.snapshot().raw_records],
                         [0, len(raw_event(100, 0x1B))])


if __name__ == "__main__":
    unittest.main()
