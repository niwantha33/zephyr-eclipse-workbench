# SPDX-License-Identifier: Apache-2.0
"""Contract tests for the shared, offline Zephyr CTF semantic boundary."""
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parents[2] / "plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis"
sys.path.insert(0, str(HERE))
import semantic_trace as subject


def raw(name, time, **fields):
    return {"kind": "event", "name": name, "timestamp_ns": time,
            "stream": 0, "thread_id": "7", "thread_name": "worker",
            **fields}


class SemanticTraceTests(unittest.TestCase):
    def test_ten_raw_events_produce_ten_semantic_events_in_timestamp_order(self):
        records = [
            raw("zephyr:mutex_lock", 24, mutex_name="guard"),
            raw("thread_ready", 10, priority=4),
            raw("sched_switch", 20, next_tid="9", next_comm="workerB", next_prio=2),
            raw("thread_pend", 21),
            raw("isr_enter_user", 22, thread_id=None, thread_name=None),
            raw("isr_exit_to_scheduler", 23, thread_id=None, thread_name=None),
            raw("mutex_unlock", 25, mutex_name="guard"),
            raw("semaphore_take", 26, semaphore_name="semA"),
            raw("sem_give", 27, semaphore_name="semA"),
            raw("heap_sys_k_malloc_exit", 28, pool_name="heap0", id="heap0", ret=0xA001),
        ]
        result = subject.SemanticTraceParser().parse(records)
        self.assertEqual(len(result.events), 10)
        self.assertEqual([event.timestamp_ns for event in result.events],
                         [10, 20, 21, 22, 23, 24, 25, 26, 27, 28])
        self.assertEqual([event.kind for event in result.events], [
            subject.EventKind.THREAD_READY, subject.EventKind.THREAD_SWITCHED_IN,
            subject.EventKind.THREAD_BLOCKED, subject.EventKind.ISR_ENTER,
            subject.EventKind.ISR_EXIT, subject.EventKind.MUTEX_TAKE,
            subject.EventKind.MUTEX_GIVE, subject.EventKind.SEM_TAKE,
            subject.EventKind.SEM_GIVE, subject.EventKind.MEM_ALLOC])
        self.assertEqual(result.events[1].thread_name, "workerB")
        self.assertEqual(result.events[1].thread_priority, "2")
        self.assertEqual(result.events[1].thread_id, "9")
        self.assertEqual(result.events[3].thread_name, "")
        self.assertEqual(result.events[5].object_name, "guard")
        self.assertEqual(result.events[5].raw_event_name, "zephyr:mutex_lock")
        self.assertEqual(result.events[-1].memory_phase, "allocated")
        self.assertEqual(result.events[-1].allocation_id, str(0xA001))
        self.assertIsNone(result.events[-1].allocation_size_bytes)
        self.assertEqual(result.unknown_count, 0)
        self.assertFalse(result.incomplete)
        self.assertTrue(result.input_out_of_order)

    def test_unknown_is_retained_and_counted_once(self):
        result = subject.SemanticTraceParser().parse([raw("zephyr:new_vendor_event", 17)])
        self.assertEqual(len(result.events), 1)
        self.assertEqual(result.events[0].kind, subject.EventKind.UNKNOWN)
        self.assertEqual(result.events[0].raw_event_name, "zephyr:new_vendor_event")
        self.assertEqual(result.unknown_count, 1)
        self.assertEqual(result.unknown_message, "1 events were not understood")
        self.assertFalse(result.incomplete)

    def test_truncated_file_path_returns_partial_events_with_flag(self):
        def truncated(_path):
            yield raw("thread_ready", 4)
            yield raw("mutex_lock", 7, mutex_name="lock")
            raise RuntimeError("truncated CTF packet")

        with patch.object(subject, "decode_ctf", side_effect=truncated) as decoder:
            result = subject.SemanticTraceParser().parse(Path("saved-trace/ctf"))
        decoder.assert_called_once_with(Path("saved-trace/ctf"))
        self.assertEqual([event.timestamp_ns for event in result.events], [4, 7])
        self.assertEqual(len(result.events), 2)
        self.assertEqual(result.unknown_count, 0)
        self.assertTrue(result.incomplete)
        self.assertIn("truncated CTF packet", result.error)

    def test_missing_timestamp_keeps_unknown_event_without_fabricating_time(self):
        result = subject.SemanticTraceParser().parse([
            raw("thread_ready", 12),
            raw("vendor:event", None),
        ])
        self.assertEqual(len(result.events), 2)
        self.assertIsNone(result.events[1].timestamp_ns)
        self.assertEqual(result.events[1].kind, subject.EventKind.UNKNOWN)
        self.assertEqual(result.unknown_count, 1)
        self.assertTrue(result.incomplete)

    def test_existing_aliases_and_intentionally_unmapped_events(self):
        raw_names = [
            "thread_switched_in", "thread_switched_out", "thread_pending",
            "thread_sleep_enter", "isr_enter", "isr_exit", "mutex_take",
            "mutex_give", "sem_take", "semaphore_give", "mem_free",
            "thread_create", "thread_name_set", "thread_sleep_exit",
            "thread_resume", "thread_priority_set", "thread_sched_prio_set",
        ]
        result = subject.SemanticTraceParser().parse([
            raw(name, index) for index, name in enumerate(raw_names)])
        self.assertEqual(len(result.events), len(raw_names))
        self.assertEqual(result.unknown_count, 7)
        self.assertEqual(
            [event.raw_event_name for event in result.events if event.kind == subject.EventKind.UNKNOWN],
            raw_names[-7:])

    def test_equal_timestamps_preserve_source_order(self):
        result = subject.SemanticTraceParser().parse([
            raw("mutex_lock", 100), raw("thread_ready", 100)])
        self.assertEqual([event.raw_event_name for event in result.events],
                         ["mutex_lock", "thread_ready"])


if __name__ == "__main__":
    unittest.main()
