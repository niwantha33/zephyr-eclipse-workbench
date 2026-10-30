# SPDX-License-Identifier: Apache-2.0
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

MODULE = Path(__file__).resolve().parents[2] / 'plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis/analyze_trace.py'
spec = importlib.util.spec_from_file_location('analyze_trace', MODULE)
subject = importlib.util.module_from_spec(spec)
spec.loader.exec_module(subject)


def event(name, time, thread='1', stream=0):
    return dict(kind='event', name=name, timestamp_ns=time, thread_id=thread,
                thread_name='worker', stream=stream)


class TraceAnalysisTests(unittest.TestCase):
    def test_complete_intervals_only_and_interrupt_time_included(self):
        report = subject.summarize([
            event('thread_switched_out', 1),
            event('thread_switched_in', 10), event('isr_enter', 15, None),
            event('isr_exit', 20, None), event('thread_switched_out', 30),
            event('thread_switched_in', 40)])
        self.assertEqual(report['threads']['1']['scheduled_ns'], 20)
        self.assertEqual(report['threads']['1']['complete_intervals'], 1)
        self.assertEqual(len(report['warnings']), 2)
        self.assertIn('Not CPU', report['timing_scope'])

    def test_loss_or_multiple_streams_withholds_all_timing(self):
        for extra in [dict(kind='discarded_events', count=3),
                      dict(kind='discarded_packets', count=None),
                      event('thread_switched_in', 20, stream=1),
                      event('thread_switched_in', None),
                      event('thread_switched_in', 0)]:
            with self.subTest(extra=extra):
                report = subject.summarize([event('thread_switched_in', 1), event('thread_switched_out', 10), extra])
                self.assertIsNone(report['threads']['1']['scheduled_ns'])
                self.assertIsNone(report['threads']['1']['complete_intervals'])

    def test_incomplete_switch_pairs_are_excluded(self):
        report = subject.summarize([event('thread_switched_in', 1, 'a'),
                                    event('thread_switched_in', 10, 'b'),
                                    event('thread_switched_out', 20, 'b')])
        self.assertEqual(report['threads']['a']['scheduled_ns'], 0)
        self.assertEqual(report['threads']['b']['scheduled_ns'], 10)
        self.assertTrue(report['warnings'])

    def test_empty_and_decode_failure_are_not_validated(self):
        self.assertEqual(subject.summarize([])['ctf_decode'], 'invalid')
        def fail(_):
            yield event('thread_switched_in', 1)
            raise ValueError('truncated CTF event')
        with patch.object(subject, 'decode', fail):
            report = subject.analyze(Path('unused'))
        self.assertEqual(report['ctf_decode'], 'invalid')
        self.assertNotIn('threads', report)

    def test_missing_decoder_is_explicit(self):
        with patch.object(subject, 'decode', side_effect=ImportError('bt2 unavailable')):
            report = subject.analyze(Path('unused'))
        self.assertEqual(report['ctf_decode'], 'not checked')
        self.assertNotIn('threads', report)


if __name__ == '__main__':
    unittest.main()
