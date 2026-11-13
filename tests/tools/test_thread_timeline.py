# SPDX-License-Identifier: Apache-2.0
"""Evidence-only semantic thread timeline: no GDB and no synthetic runtime states."""
import sys
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / "plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis"
sys.path.insert(0, str(TOOLS))
import thread_timeline as timeline
from semantic_trace import SemanticTrace, SemanticEvent, EventKind


def raw(kind, time, tid="a", name="worker_a", priority="3", stream=0):
    return dict(kind="event", name=kind, timestamp_ns=time, thread_id=tid,
                thread_name=name, priority=priority, stream=stream)


class ThreadTimelineTests(unittest.TestCase):
    def test_three_context_switches_produce_three_exact_running_intervals(self):
        # Three complete scheduling bursts across two distinct threads.
        trace = [
            raw("thread_switched_in", 100, "a", "worker_a", "3"),
            raw("thread_switched_out", 600, "a", "worker_a", "3"),
            raw("thread_switched_in", 600, "b", "worker_b", "1"),
            raw("thread_switched_out", 1800, "b", "worker_b", "1"),
            raw("thread_switched_in", 1800, "a", "worker_a", "3"),
            raw("thread_switched_out", 3000, "a", "worker_a", "3"),
        ]
        actual = timeline.timeline_from_events(trace)
        self.assertEqual(actual.scheduler_events, 6)
        self.assertEqual(len(actual.intervals), 3)
        self.assertEqual([(r.start_ns, r.end_ns, r.state, r.thread_name, r.priority)
                          for r in actual.intervals],
                         [(100, 600, "Running", "worker_a", "3"),
                          (600, 1800, "Running", "worker_b", "1"),
                          (1800, 3000, "Running", "worker_a", "3")])
        self.assertEqual([r.duration_us for r in actual.intervals],
                         ["0.500", "1.200", "1.200"])
        self.assertFalse(actual.incomplete)

    def test_atomic_sched_switch_closes_previous_observed_thread(self):
        # One sched_switch names the incoming thread and proves the outgoing
        # boundary. A different standalone IN still has no such guarantee.
        observed = timeline.timeline_from_events([
            raw("sched_switch", 100, "a", "worker_a"),
            raw("sched_switch", 600, "b", "worker_b"),
            raw("sched_switch", 1800, "a", "worker_a"),
        ])
        self.assertEqual(
            [(r.start_ns, r.end_ns, r.thread_name, r.state)
             for r in observed.intervals],
            [(100, 600, "worker_a", "Running"),
             (600, 1800, "worker_b", "Running")])
        self.assertEqual(observed.scheduler_events, 3)
        self.assertFalse(observed.incomplete)

    def test_unknown_gap_is_not_fabricated_running_interval(self):
        actual = timeline.timeline_from_events([
            raw("thread_switched_in", 0, "a"),
            raw("thread_switched_out", 1000, "a"),
            raw("thread_switched_in", 4000, "b", "worker_b"),
            raw("thread_switched_out", 5000, "b", "worker_b"),
        ])
        self.assertEqual([(r.start_ns, r.end_ns, r.state) for r in actual.intervals],
                         [(0, 1000, "Running"), (1000, 4000, "Unknown"),
                          (4000, 5000, "Running")])
        self.assertEqual(actual.intervals[1].thread_name, "")
        self.assertEqual(actual.intervals[1].priority, "")

    def test_unmatched_switch_cannot_fabricate_running_interval(self):
        actual = timeline.timeline_from_events([
            raw("thread_switched_in", 10, "a"),
            raw("thread_switched_in", 30, "b", "worker_b"),
            raw("thread_switched_out", 40, "b", "worker_b"),
        ])
        self.assertEqual([(r.start_ns, r.end_ns, r.state) for r in actual.intervals],
                         [(10, 30, "Unknown"), (30, 40, "Running")])

    def test_explicit_ready_blocked_sleeping_and_isr_with_proven_boundaries(self):
        actual = timeline.timeline_from_events([
            raw("thread_switched_in", 0, "a"),
            raw("thread_ready", 1, "b", "worker_b"),
            raw("thread_pending", 2, "b", "worker_b"),
            raw("thread_sleep_enter", 3, "b", "worker_b"),
            raw("thread_switched_in", 4, "b", "worker_b"),
            raw("isr_enter", 6, None, ""),
            raw("isr_exit", 9, None, ""),
            raw("thread_switched_out", 12, "b", "worker_b"),
        ])
        self.assertIn((1, 2, "Ready"), [(r.start_ns,r.end_ns,r.state) for r in actual.intervals])
        self.assertIn((2, 3, "Blocked"), [(r.start_ns,r.end_ns,r.state) for r in actual.intervals])
        self.assertIn((3, 4, "Sleeping"), [(r.start_ns,r.end_ns,r.state) for r in actual.intervals])
        self.assertIn((6, 9, "ISR"), [(r.start_ns,r.end_ns,r.state) for r in actual.intervals])
        self.assertNotIn((0, 4, "Running"), [(r.start_ns,r.end_ns,r.state) for r in actual.intervals])

    def test_unmapped_sleep_exit_breaks_sleeping_without_guessing(self):
        actual = timeline.timeline_from_events([
            raw("thread_switched_in", 0, "a"),
            raw("thread_sleep_enter", 10, "b", "worker_b"),
            raw("thread_sleep_exit", 20, "b", "worker_b"),
            raw("thread_ready", 30, "b", "worker_b"),
            raw("thread_switched_out", 40, "a"),
        ])
        self.assertIn((10, 20, "Sleeping"),
                      [(r.start_ns, r.end_ns, r.state) for r in actual.intervals])
        self.assertIn((20, 30, "Unknown"),
                      [(r.start_ns, r.end_ns, r.state) for r in actual.intervals])
        self.assertEqual(actual.unknown_events, 1)

    def test_zero_scheduler_events_reports_empty_state(self):
        actual = timeline.timeline_from_events([raw("isr_enter", 5, None, ""),
                                                 raw("isr_exit", 8, None, "")])
        self.assertEqual(actual.intervals, [])
        self.assertEqual(actual.scheduler_events, 0)
        self.assertIn("likely not enabled", actual.detail)
        self.assertEqual(actual.total_events, 2)
        self.assertEqual([e.event_name for e in actual.observed_events], ["isr_enter", "isr_exit"])

    def test_incomplete_semantic_input_and_unknown_counter_propagate(self):
        events = [SemanticEvent(10, EventKind.THREAD_SWITCHED_IN, "worker", "2",
                                thread_id="a", stream=0),
                  SemanticEvent(20, EventKind.THREAD_SWITCHED_OUT, "worker", "2",
                                thread_id="a", stream=0)]
        actual = timeline.build_intervals(SemanticTrace(events, unknown_count=4,
                                                          incomplete=True, error="truncated"))
        self.assertTrue(actual.incomplete)
        self.assertEqual(actual.unknown_events, 4)
        self.assertEqual(len(actual.intervals), 1)
        self.assertIn("\t1\t4\t2\t", timeline.serialize(actual))
        self.assertNotIn("\t0\t4\t2\t", timeline.serialize(actual))

    def test_nested_isr_produces_outermost_interval_only(self):
        trace = [
            raw("thread_switched_in", 0),
            raw("isr_enter", 2, None, ""),
            raw("isr_enter", 3, None, ""),
            raw("isr_exit", 4, None, ""),
            raw("isr_exit", 5, None, ""),
            raw("thread_switched_out", 8),
        ]
        actual = timeline.timeline_from_events(trace)
        self.assertEqual([r for r in actual.intervals if r.state == "ISR"],
                         [timeline.Interval(2, 5, "", "", "ISR")])

    def test_v3_header_keeps_raw_trace_range_and_observed_events(self):
        result = timeline.timeline_from_events([
            raw("thread_name_set", 5, "a"),
            raw("thread_switched_in", 20, "a"),
            raw("thread_switched_out", 30, "a"),
            raw("thread_name_set", 50, "b"),
        ])
        self.assertEqual((result.trace_start_ns, result.trace_end_ns), (5, 50))
        self.assertEqual([(x.start_ns, x.end_ns) for x in result.intervals],
                         [(20, 30)])
        header = timeline.serialize(result).splitlines()[0].split("\t")
        self.assertEqual(header[1], "3")
        self.assertEqual(header[7:9], ["5", "50"])
        self.assertEqual(header[9:], ["4", "4"])
        self.assertEqual(len(header), 11)
        self.assertEqual(len(result.observed_events), 4)

    def test_cross_stream_switches_never_pair(self):
        actual = timeline.timeline_from_events([
            raw("thread_switched_in", 10, stream=0),
            raw("thread_switched_out", 20, stream=1),
        ])
        self.assertEqual([r for r in actual.intervals if r.state == "Running"], [])


if __name__ == "__main__":
    unittest.main()
