# SPDX-License-Identifier: Apache-2.0
"""Offline synchronization object regression fixtures; no hardware required."""
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] / (
    "plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis")
sys.path.insert(0, str(ROOT))

from semantic_trace import EventKind, SemanticTraceParser
from synchronization_analysis import summarize
from offline_trace_json import contention_report


def event(name, ns, obj="0x10", ret=None, thread=None, stream=0):
    record = {"kind": "event", "name": name, "timestamp_ns": ns,
              "stream": stream}
    if obj is not None:
        record["object_id"] = obj
    if ret is not None:
        record["ret"] = ret
    if thread is not None:
        record["thread_id"] = thread
    return record


def parse(*records):
    return SemanticTraceParser().parse(records)


class SynchronizationActivityTests(unittest.TestCase):
    def test_queue_native_ctf_names_and_observed_blocked_time(self):
        trace = parse(
            event("thread_switched_in", 0, obj=None, thread="consumer"),
            event("msgq_get_enter", 100),
            event("msgq_get_blocking", 200),
            event("thread_switched_out", 250, obj=None, thread="consumer"),
            event("thread_switched_in", 300, obj=None, thread="producer"),
            event("msgq_put_enter", 400),
            event("msgq_put_exit", 500, ret=0),
            event("thread_switched_out", 600, obj=None, thread="producer"),
            event("thread_switched_in", 1_500_200, obj=None, thread="consumer"),
            event("msgq_get_exit", 1_500_300, ret=0),
        )
        self.assertEqual(trace.events[2].kind, EventKind.MSGQ_GET)
        report = summarize(trace, threshold_us=1000)
        self.assertFalse(report.incomplete)
        self.assertEqual(report.resource_events, 5)
        self.assertEqual(len(report.objects), 1)
        obj = report.objects[0]
        self.assertEqual(obj.kind, "Message queue")
        self.assertEqual(obj.object, "object ID 0x10 (name unavailable)")
        self.assertEqual((obj.take_get, obj.give_put, obj.failures), (1, 1, 0))
        self.assertEqual((obj.blocking_events, obj.paired_blocks, obj.long_blocks),
                         (1, 1, 1))
        self.assertEqual(obj.max_blocked_ns, 1_500_100)
        self.assertEqual(obj.unpaired_blocks, 0)
        payload = contention_report(trace)
        self.assertEqual(payload["objects"][0]["max_blocked_ns"], 1_500_100)
        self.assertEqual(payload["objects"][0]["give_put"], 1)

    def test_failed_queue_operation_is_not_a_success_and_is_timed(self):
        trace = parse(
            event("thread_switched_in", 0, obj=None, thread="producer"),
            event("msgq_put_front_enter", 10),
            event("msgq_put_front_blocking", 20),
            event("msgq_put_front_exit", 2_000_021, ret=-11),
        )
        obj = summarize(trace).objects[0]
        self.assertEqual((obj.take_get, obj.give_put, obj.failures), (0, 0, 1))
        self.assertEqual(obj.paired_blocks, 1)
        self.assertEqual(obj.max_blocked_ns, 2_000_001)

    def test_unknown_return_does_not_invent_failure_or_success(self):
        trace = parse(event("msgq_put_enter", 0),
                      event("msgq_put_exit", 100))
        obj = summarize(trace).objects[0]
        self.assertEqual((obj.give_put, obj.failures), (0, 0))
        self.assertEqual(trace.events[-1].resource_phase, "unknown_result")

    def test_different_thread_cannot_close_another_wait(self):
        trace = parse(
            event("msgq_get_blocking", 100, thread="consumer"),
            event("msgq_get_exit", 2_000_100, thread="other", ret=0),
        )
        obj = summarize(trace).objects[0]
        self.assertEqual((obj.blocking_events, obj.paired_blocks, obj.unpaired_blocks), (1, 0, 1))
        self.assertIsNone(obj.max_blocked_ns)
        self.assertEqual(obj.take_get, 1)

    def test_missing_actor_does_not_produce_duration(self):
        trace = parse(event("msgq_get_blocking", 10),
                      event("msgq_get_exit", 1_000_100, ret=0))
        obj = summarize(trace).objects[0]
        self.assertEqual(obj.unpaired_blocks, 1)
        self.assertIsNone(obj.max_blocked_ns)

    def test_mutex_and_semaphore_are_separate_objects_even_with_same_id(self):
        trace = parse(
            event("mutex_lock_enter", 0),
            event("mutex_lock_exit", 100, ret=0),
            event("mutex_unlock_enter", 200),
            event("mutex_unlock_exit", 300, ret=0),
            event("semaphore_take_enter", 400),
            event("semaphore_take_exit", 500, ret=-11),
            event("semaphore_give_exit", 600),
        )
        result = summarize(trace)
        mutex, semaphore = result.objects
        self.assertEqual(mutex.kind, "Mutex")
        self.assertEqual((mutex.take_get, mutex.give_put, mutex.failures), (1, 1, 0))
        self.assertEqual(semaphore.kind, "Semaphore")
        self.assertEqual((semaphore.take_get, semaphore.give_put, semaphore.failures), (0, 1, 1))

    def test_strict_threshold_and_unpaired_boundaries(self):
        trace = parse(
            event("msgq_get_blocking", 0, thread="A"),
            event("msgq_get_exit", 1_000_000, ret=0, thread="A"),
            event("msgq_get_blocking", 1_500_000, thread="A"),
        )
        obj = summarize(trace, 1000).objects[0]
        self.assertEqual(obj.long_blocks, 0)
        self.assertEqual(obj.max_blocked_ns, 1_000_000)
        self.assertEqual(obj.unpaired_blocks, 1)
        self.assertEqual(summarize(trace, 999).objects[0].long_blocks, 1)

    def test_loss_and_unknown_gate_events_do_not_create_gate_state(self):
        trace = parse(event("gate_hwi_enter", 0),
                      event("msgq_put_exit", 1, ret=0),
                      {"kind": "discarded_events", "count": 2})
        report = summarize(trace)
        self.assertTrue(report.incomplete)
        self.assertEqual(trace.unknown_count, 1)
        self.assertEqual([obj.kind for obj in report.objects], ["Message queue"])

    def test_missing_object_identity_is_unattributed_not_fabricated(self):
        report = summarize(parse(event("msgq_get_exit", 1, obj=None, ret=0)))
        self.assertEqual(report.objects, [])
        self.assertEqual(report.unattributed_events, 1)

    def test_invalid_threshold_fails_closed(self):
        with self.assertRaises(ValueError):
            summarize(parse(), -1)
        with self.assertRaises(ValueError):
            summarize(parse(), 1.1)


if __name__ == "__main__":
    unittest.main()
