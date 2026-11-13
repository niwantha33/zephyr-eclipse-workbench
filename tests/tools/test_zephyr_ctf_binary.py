# SPDX-License-Identifier: Apache-2.0
"""Synthetic, no-hardware acceptance for Zephyr 4.4 packed CTF RAM records.

Never commit a user's uploaded trace. Validate the native wire schema,
timestamp ns, named scheduler events, full-buffer guard and malformed inputs.
"""
from __future__ import annotations

from pathlib import Path
from unittest.mock import patch
import hashlib
import json
import struct
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[2] / (
    'plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis')
sys.path.insert(0, str(TOOLS))
from zephyr_ctf_binary import decode_packed, supports, buffer_full_evidence
from semantic_trace import EventKind, SemanticTraceParser
from analyze_trace import analyze

METADATA = """/* CTF 1.8 */
struct event_header {
    uint32_t timestamp;
    uint16_t id;
};
trace {
    major = 1;
    minor = 8;
    byte_order = le;
};
stream {
    event.header := struct event_header;
};
event {
    name = thread_switched_in;
    id = 0x11;
    fields := struct {
        uint32_t thread_id;
        ctf_bounded_string_t name[20];
    };
};
event {
    name = thread_switched_out;
    id = 0x10;
    fields := struct {
        uint32_t thread_id;
        ctf_bounded_string_t name[20];
    };
};
event {
    name = thread_sched_ready;
    id = 0xEA;
    fields := struct {
        uint32_t thread_id;
        ctf_bounded_string_t name[20];
    };
};
event {
    name = thread_sched_pend;
    id = 0xEB;
    fields := struct {
        uint32_t thread_id;
        ctf_bounded_string_t name[20];
    };
};
event {
    name = isr_enter;
    id = 0x1B;
};
"""


def raw_event(timestamp, ident, tid=None, name='worker'):
    result = struct.pack('<IH', timestamp, ident)
    if tid is not None:
        return result + struct.pack('<I', tid) + (
            name.encode('ascii').ljust(20, b'\0'))
    return result


class PackedCtfTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='zew-packed-ctf-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.ctf = self.root / 'ctf'
        self.ctf.mkdir()
        (self.ctf / 'metadata').write_text(METADATA, encoding='utf-8')
        self.bytes = (
            raw_event(100, 0x11, 0x1234, 'worker')
            + raw_event(200, 0x10, 0x1234, 'worker')
            + raw_event(240, 0xEA, 0x1234, 'worker')
            + raw_event(270, 0xEB, 0x1234, 'worker')
            + raw_event(300, 0x1B))
        (self.ctf / 'channel0_0').write_bytes(self.bytes)

    def test_structurally_decode_without_bt2(self):
        with patch.dict(sys.modules, {'bt2': None}):
            trace = SemanticTraceParser().parse(self.ctf)
        self.assertTrue(supports(self.ctf))
        self.assertEqual(len(trace.events), 5)
        self.assertFalse(trace.decoder_unavailable)
        self.assertFalse(trace.incomplete)
        self.assertEqual([x.timestamp_ns for x in trace.events],
                         [100, 200, 240, 270, 300])
        self.assertEqual(trace.events[0].thread_name, 'worker')
        self.assertEqual(trace.events[2].kind, EventKind.THREAD_READY)
        self.assertEqual(trace.events[3].kind, EventKind.THREAD_BLOCKED)
        self.assertEqual(trace.events[4].kind, EventKind.ISR_ENTER)
        report = analyze(self.ctf)
        self.assertEqual(report['ctf_decode'], 'validated')
        self.assertEqual(report['events'], 5)
        self.assertEqual(report['semantic_event_count'], 5)

    def test_native_32_bit_nanosecond_timestamp_wrap(self):
        (self.ctf / 'channel0_0').write_bytes(
            raw_event(0xFFFFFFF0, 0x1B) + raw_event(0x10, 0x1B))
        events = list(decode_packed(self.ctf))
        self.assertEqual([e['timestamp_ns'] for e in events],
                         [0xFFFFFFF0, (1 << 32) + 0x10])

    def test_unrecognized_or_truncated_stream_never_guesses_past_error(self):
        for payload, message in (
            (raw_event(100, 0x1B) + raw_event(200, 0xFFFF), 'Unknown Zephyr CTF'),
            (raw_event(100, 0x1B) + raw_event(200, 0x11, 0x1234)[:-4],
             'Truncated thread_switched_in.name')):
            with self.subTest(message=message):
                (self.ctf / 'channel0_0').write_bytes(payload)
                iterator = iter(decode_packed(self.ctf))
                self.assertEqual(next(iterator)['name'], 'isr_enter')
                with self.assertRaisesRegex(ValueError, message):
                    list(iterator)
                semantic = SemanticTraceParser().parse(self.ctf)
                self.assertTrue(semantic.incomplete)
                self.assertEqual(len(semantic.events), 1)

    def test_unsupported_metadata_does_not_claim_native_format(self):
        (self.ctf / 'metadata').write_text(METADATA.replace('byte_order = le;', 'byte_order = be;'))
        self.assertFalse(supports(self.ctf))
        with patch.dict(sys.modules, {'bt2': None}):
            result = SemanticTraceParser().parse(self.ctf)
        self.assertTrue(result.decoder_unavailable)

    def test_matching_buffer_full_receipt_withholds_unqualified_metrics(self):
        capture = {
            'capture_full': True,
            'trace_bytes': len(self.bytes),
            'trace_sha256': hashlib.sha256(self.bytes).hexdigest(),
            'metadata_sha256': hashlib.sha256(METADATA.encode()).hexdigest(),
        }
        (self.root / 'capture.json').write_text(json.dumps(capture))
        self.assertTrue(buffer_full_evidence(self.ctf))
        raw = list(decode_packed(self.ctf))
        self.assertEqual(len(raw), 5)  # full flag does not invent/drop decoded rows
        semantic = SemanticTraceParser().parse(self.ctf)
        self.assertTrue(semantic.loss_reported)
        self.assertTrue(semantic.incomplete)
        self.assertEqual(len(semantic.events), 5)
        report = analyze(self.ctf)
        self.assertEqual(report['ctf_decode'], 'validated')
        self.assertTrue(report['buffer_full_reported'])
        self.assertTrue(report['incomplete'])
        self.assertEqual(report['cpu']['status'], 'unavailable')
        self.assertEqual(report['timing']['status'], 'unavailable')
        self.assertNotIn('discarded_events_reported', {'discarded_events_reported':
                          report['discarded_events_reported']} if
                          report['discarded_events_reported'] else {})

        capture['trace_sha256'] = '0' * 64
        (self.root / 'capture.json').write_text(json.dumps(capture))
        self.assertFalse(buffer_full_evidence(self.ctf))

    def test_staged_python_isolation_closure(self):
        from test_ctf_capture_packaging import capture_resources
        resources, _ = capture_resources()
        self.assertIn('zephyr_ctf_binary.py', resources)
        ui = TOOLS.parent.parent / 'src/io/github/niwantha33/zew/ui'
        for filename in ('ThreadTimelineView.java', 'OfflineTracePythonBridge.java',
                         'TracepointPlotView.java', 'ZephyrMemoryProfileView.java'):
            with self.subTest(view=filename):
                self.assertIn('"zephyr_ctf_binary.py"',
                              (ui / filename).read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
