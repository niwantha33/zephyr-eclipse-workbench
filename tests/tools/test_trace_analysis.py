# SPDX-License-Identifier: Apache-2.0
import importlib.util
import sys
from pathlib import Path
import unittest
from unittest.mock import patch

MODULE = Path(__file__).resolve().parents[2] / 'plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis/analyze_trace.py'
sys.path.insert(0, str(MODULE.parent))
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

    def test_exclusive_cpu_time_with_nested_interrupts_and_explicit_idle(self):
        records = [
            event('thread_switched_in', 0, 'worker'),
            event('isr_enter', 10, None),
            event('isr_enter', 12, None),
            event('isr_exit', 15, None),
            event('isr_exit', 20, None),
            event('thread_switched_out', 30, 'worker'),
            event('thread_switched_in', 30, 'idle'),
            event('thread_switched_out', 50, 'idle'),
        ]
        cpu = subject.summarize(records, idle_thread_id='idle')['cpu']
        self.assertEqual(cpu['status'], 'measured')
        self.assertEqual(cpu['observed_window_ns'], 50)
        self.assertEqual(cpu['thread_exclusive_ns'], {'idle': 20, 'worker': 20})
        self.assertEqual(cpu['isr_ns'], 10)
        self.assertEqual(cpu['max_isr_nesting'], 2)
        self.assertEqual(cpu['unknown_ns'], 0)
        self.assertEqual(cpu['cpu_busy_percent'], 60.0)
        self.assertEqual(sum(cpu['thread_exclusive_ns'].values()) +
                         cpu['isr_ns'] + cpu['unknown_ns'], 50)

    def test_no_idle_identification_withholds_busy_percent(self):
        cpu = subject.summarize([
            event('thread_switched_in', 0, 'a'),
            event('thread_switched_out', 15, 'a')])['cpu']
        self.assertEqual(cpu['status'], 'partial')
        self.assertIsNone(cpu['cpu_busy_percent'])
        self.assertEqual(cpu['thread_exclusive_total_ns'], 15)

    def test_unobserved_gaps_are_never_classified_as_idle(self):
        cpu = subject.summarize([
            event('thread_switched_in', 0, 'a'),
            event('thread_switched_out', 10, 'a'),
            event('thread_switched_in', 20, 'idle'),
            event('thread_switched_out', 30, 'idle')], idle_thread_id='idle')['cpu']
        self.assertEqual(cpu['unknown_ns'], 10)
        self.assertIsNone(cpu['cpu_busy_percent'])

    def test_reported_loss_clock_wrap_multiple_cores_and_bad_isr_refuse_metrics(self):
        good = [event('thread_switched_in', 1), event('thread_switched_out', 10)]
        invalid_cases = [
            good + [dict(kind='discarded_events', count=1)],
            good + [event('thread_switched_in', 11, stream=1)],
            [event('thread_switched_in', 10), event('thread_switched_out', 5)],
            [event('isr_exit', 1, None), event('thread_switched_in', 2)],
            [event('isr_enter', 1, None), event('thread_switched_in', 2)],
            [event('thread_switched_in', 1), event('thread_switched_in', 2)],
            [event('thread_switched_in', 1), event('thread_switched_out', None)],
        ]
        for data in invalid_cases:
            with self.subTest(data=data):
                cpu = subject.summarize(data)['cpu']
                self.assertEqual(cpu['status'], 'unavailable')
                self.assertIsNone(cpu['cpu_busy_percent'])
                self.assertIsNone(cpu['isr_ns'])

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
