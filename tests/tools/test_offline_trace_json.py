# SPDX-License-Identifier: Apache-2.0
"""No-hardware tests for the on-demand JSON adapter over existing semantic functions."""
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] / (
    "plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis")
sys.path.insert(0, str(ROOT))
from offline_trace_json import cpu_report, contention_report
from semantic_trace import SemanticTraceParser


def raw(name, timestamp, tid="worker", priority=None, obj=None):
    result = {"kind": "event", "stream": 0, "name": name,
              "timestamp_ns": timestamp, "thread_id": tid,
              "thread_name": tid}
    if priority is not None:
        result["priority"] = priority
    if obj is not None:
        result["object_id"] = obj
    return result


class OfflineTraceJsonTests(unittest.TestCase):
    def trace(self, *events):
        return SemanticTraceParser().parse(events)

    def test_cpu_window_uses_observed_scheduler_intervals(self):
        trace = self.trace(raw("thread_switched_in", 0), raw("thread_switched_out", 4000),
                           raw("thread_switched_in", 4000, "idle"),
                           raw("thread_switched_out", 10000, "idle"))
        report = cpu_report(trace, 1000, 9000)
        self.assertEqual(report["status"], "ok")
        self.assertEqual(report["scheduler_events"], 4)
        self.assertEqual([row["name"] for row in report["cpu"]["rows"]], ["idle", "worker"])
        self.assertEqual([row["running_ns"] for row in report["cpu"]["rows"]], [5000, 3000])
        self.assertIsNone(report["cpu"]["cpu_busy_percent"])

    def test_atomic_sched_switch_exclusive_cpu_is_measurable(self):
        # A complete single-stream switch event is an observed boundary,
        # not an ambiguous second switched_in. No idle identification is made.
        trace = self.trace(raw("sched_switch", 0, "worker"),
                           raw("sched_switch", 4000, "idle"),
                           raw("isr_enter", 6000, None),
                           raw("isr_exit", 7000, None),
                           raw("sched_switch", 10000, "worker"),
                           raw("thread_switched_out", 12000, "worker"))
        report = cpu_report(trace)
        self.assertEqual(report["status"], "ok", report["reason"])
        self.assertEqual(report["cpu"]["unknown_ns"], 0)
        self.assertEqual(report["cpu"]["isr_ns"], 1000)
        self.assertEqual({r["name"]: r["running_ns"]
                          for r in report["cpu"]["rows"]},
                         {"worker": 6000, "idle": 5000})
        self.assertEqual(sum(r["running_ns"] for r in report["cpu"]["rows"])
                         + report["cpu"]["isr_ns"], 12000)
        self.assertIsNone(report["cpu"]["cpu_busy_percent"])

    def test_nested_isr_is_exclusive_across_window_boundaries(self):
        # 0..10us worker, with two nested ISRs occupying 2..5us.
        trace = self.trace(raw("thread_switched_in", 0),
                           raw("isr_enter", 2000),
                           raw("isr_enter", 3000),
                           raw("isr_exit", 4000),
                           raw("isr_exit", 5000),
                           raw("thread_switched_out", 10000))
        for start, end, thread_ns, irq_ns in (
                (0, 10000, 7000, 3000),
                (1000, 9000, 5000, 3000),
                (2500, 4500, 0, 2000),
                (4000, 7000, 2000, 1000)):
            with self.subTest(window=(start, end)):
                report = cpu_report(trace, start, end)
                self.assertEqual(report["status"], "ok")
                rows = report["cpu"]["rows"]
                self.assertEqual(sum(r["running_ns"] for r in rows), thread_ns)
                self.assertEqual(report["cpu"]["isr_ns"], irq_ns)
                self.assertEqual(sum(r["running_ns"] for r in rows)
                                 + irq_ns + report["cpu"]["unknown_ns"], end - start)
                self.assertLessEqual(sum(r["percentage"] for r in rows)
                                     + report["cpu"]["isr_percentage"], 100.0001)

    def test_state_intervals_keep_scheduled_time_distinct_from_cpu_time(self):
        trace = self.trace(raw("thread_switched_in", 0),
                           raw("thread_ready", 1000, "other"),
                           raw("thread_pending", 2000, "other"),
                           raw("thread_sleep_enter", 3000, "other"),
                           raw("isr_enter", 4000),
                           raw("isr_exit", 5000),
                           raw("thread_ready", 8000, "other"),
                           raw("thread_switched_out", 10000))
        result = cpu_report(trace, 1500, 9000)
        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["cpu"]["isr_ns"], 1000)
        self.assertEqual(result["cpu"]["rows"][0]["running_ns"], 6500)
        self.assertEqual([(s["state"], s["start_ns"], s["end_ns"]) for s in
                          result["cpu"]["thread_states"] if s["name"] == "other"],
                         [("Ready", 1500, 2000), ("Blocked", 2000, 3000),
                          ("Sleeping", 3000, 8000)])
        scheduled = [s for s in result["cpu"]["thread_states"]
                     if s["state"] == "Scheduled"]
        self.assertEqual(len(scheduled), 1)
        self.assertTrue(scheduled[0]["scheduled_includes_isr"])
        self.assertEqual((scheduled[0]["start_ns"], scheduled[0]["end_ns"]), (1500, 9000))
        self.assertEqual(result["cpu"]["per_isr_status"], "identity_unavailable")
        self.assertEqual(result["cpu"]["per_isr_rows"], [])

    def test_unpaired_isr_transition_never_emits_cpu_percentages(self):
        trace = self.trace(raw("thread_switched_in", 0),
                           raw("isr_exit", 2000),
                           raw("thread_switched_out", 10000))
        report = cpu_report(trace)
        self.assertEqual(report["status"], "incomplete")
        self.assertTrue(report["incomplete"])
        self.assertEqual(report["cpu"]["rows"], [])
        self.assertIsNone(report["cpu"]["isr_percentage"])

    def test_no_scheduler_is_not_empty_success_table(self):
        trace = self.trace(raw("thread_ready", 100))
        report = cpu_report(trace)
        self.assertEqual(report["status"], "no_scheduler")
        self.assertEqual(report["cpu"]["rows"], [])

    def test_incomplete_trace_is_explicit(self):
        trace = self.trace(raw("thread_switched_in", 0),
                           raw("thread_switched_out", None))
        report = cpu_report(trace)
        self.assertEqual(report["status"], "incomplete")
        self.assertTrue(report["incomplete"])

    def test_bad_window_is_rejected(self):
        trace = self.trace(raw("thread_switched_in", 0), raw("thread_switched_out", 100))
        with self.assertRaises(ValueError):
            cpu_report(trace, 200, 300)

    def test_semaphore_and_queue_activity_requires_observed_results(self):
        events = [
            raw("sem_take", 0, "worker", obj="semA"),  # attempt, not success
            raw("semaphore_take_enter", 1, "worker", obj="semA"),
            raw("semaphore_take_blocking", 2, "worker", obj="semA"),
            {**raw("semaphore_take_exit", 100_002, "worker", obj="semA"),
             "ret": 0},
            raw("semaphore_give_exit", 100_003, "worker", obj="semA"),
            raw("msgq_put_enter", 100_010, "worker", obj="queueA"),
            {**raw("msgq_put_exit", 100_011, "worker", obj="queueA"),
             "ret": -11},  # proven failure
            raw("msgq_get_enter", 100_012, "worker", obj="queueA"),
            {**raw("msgq_get_exit", 100_013, "worker", obj="queueA"),
             "ret": 0},
        ]
        report = contention_report(self.trace(*events), threshold_us=50)
        objects = {(o["kind"], o["object"]): o for o in report["objects"]}
        sem = objects[("Semaphore", "object ID semA (name unavailable)")]
        self.assertEqual((sem["take_get"], sem["give_put"], sem["failures"]),
                         (1, 1, 0))
        self.assertEqual((sem["blocking_events"], sem["paired_blocks"],
                          sem["long_blocks"], sem["max_blocked_ns"]),
                         (1, 1, 1, 100_000))
        queue = objects[("Message queue", "object ID queueA (name unavailable)")]
        self.assertEqual((queue["take_get"], queue["give_put"], queue["failures"]),
                         (1, 0, 1))

    def test_long_wait_survives_without_priorities_or_holder(self):
        trace = self.trace(raw("mutex_lock_enter", 0, "high", obj="lock"),
                           raw("mutex_lock_blocking", 1, "high", obj="lock"),
                           raw("thread_ready", 2_000_000, "high"))
        report = contention_report(trace)
        self.assertFalse(report["inversion_enabled"])
        self.assertEqual(len(report["findings"]), 1)
        self.assertEqual(report["findings"][0]["kind"], "Long Wait")
        self.assertIsNone(report["findings"][0]["holder"])
        self.assertTrue(report["findings"][0]["holder_unknown"])


if __name__ == "__main__":
    unittest.main()
