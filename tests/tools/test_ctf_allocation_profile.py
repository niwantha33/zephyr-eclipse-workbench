# SPDX-License-Identifier: Apache-2.0
"""Zephyr CTF memory allocation profiling: evidence-only acceptance tests."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] /
    "plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis"))
from semantic_trace import EventKind as K, SemanticEvent, SemanticTrace, SemanticTraceParser, RAW_EVENT_KINDS
from memory_profiler import MemoryProfiler, NO_EVENTS, INCOMPLETE, BYTES_UNAVAILABLE


def record(kind, t, ptr, size=None, pool="heapA"):
    return SemanticEvent(t, kind, memory_pool=pool, memory_family="heap",
                         memory_phase="allocated" if kind == K.MEM_ALLOC else "freed",
                         allocation_id=ptr, allocation_size_bytes=size)


def balanced():
    a = [record(K.MEM_ALLOC, (i + 1) * 1000, "p" + str(i), 100) for i in range(5)]
    a += [record(K.MEM_FREE, (i + 6) * 1000, "p" + str(i)) for i in range(3)]
    return SemanticTrace(events=a)


class MemoryTraceTests(unittest.TestCase):
    def test_five_allocations_three_frees_peak_fifth_timestamp(self):
        p = MemoryProfiler().calculate(balanced())
        self.assertEqual((p.current_count, p.current_bytes, p.peak_bytes, p.peak_timestamp_ns),
                         (2, 200, 500, 5000))
        self.assertEqual([(s.timestamp_ns, s.outstanding_count, s.outstanding_bytes)
                          for s in p.steps], [(1000, 1, 100), (2000, 2, 200),
                                              (3000, 3, 300), (4000, 4, 400),
                                              (5000, 5, 500), (6000, 4, 400),
                                              (7000, 3, 300), (8000, 2, 200)])
        self.assertEqual((p.pools[0].alloc_count, p.pools[0].free_count,
                          p.pools[0].peak_bytes), (5, 3, 500))
        self.assertEqual(p.imbalance_line,
            "Trace ends with 2 outstanding allocations (200 bytes). This may indicate a leak "
            "or may simply mean the allocations are long-lived. The trace alone cannot distinguish these.")

    def test_empty_stream_exact_message(self):
        p = MemoryProfiler().calculate(SemanticTrace())
        self.assertTrue(p.no_events)
        self.assertEqual(p.steps, [])
        self.assertEqual(p.message,
            "No memory allocation events found. Enable CONFIG_TRACING_HEAP or the equivalent "
            "memory tracing option in the Zephyr build to use this view.")
        self.assertEqual(p.imbalance_line, "")

    def test_truncated_trace_suppresses_imbalance(self):
        trace = balanced()
        trace.incomplete = True
        trace.error = "truncated packet"
        p = MemoryProfiler().calculate(trace)
        self.assertEqual(p.current_bytes, 200)
        self.assertEqual(p.imbalance_line, "")
        self.assertEqual(p.message, INCOMPLETE)

    def test_real_ctf_event_mappings_not_guessed_names(self):
        for name in ("heap_sys_k_malloc_enter", "heap_sys_k_malloc_exit",
                     "mem_slab_alloc_enter", "mem_slab_alloc_exit"):
            self.assertEqual(RAW_EVENT_KINDS[name], K.MEM_ALLOC)
        for name in ("heap_sys_k_free_enter", "heap_sys_k_free_exit",
                     "mem_slab_free_enter", "mem_slab_free_exit"):
            self.assertEqual(RAW_EVENT_KINDS[name], K.MEM_FREE)
        for absent in ("k_malloc", "k_free", "k_mem_slab_alloc",
                       "k_mem_slab_free", "mem_alloc", "mem_free"):
            self.assertNotIn(absent, RAW_EVENT_KINDS)

    def test_native_heap_ptr_without_size_does_not_invent_bytes(self):
        events = [
            {"name": "heap_sys_k_malloc_enter", "timestamp_ns": 1, "id": "0x10"},
            {"name": "heap_sys_k_malloc_exit", "timestamp_ns": 2, "id": "0x10", "ret": "0x100"},
            {"name": "heap_sys_k_malloc_exit", "timestamp_ns": 3, "id": "0x10", "ret": "0x200"},
            {"name": "heap_sys_k_free_enter", "timestamp_ns": 4, "id": "0x10", "heap_ref": "0x100"},
            {"name": "heap_sys_k_free_exit", "timestamp_ns": 5, "id": "0x10", "heap_ref": "0x100"},
        ]
        p = MemoryProfiler().calculate(SemanticTraceParser().parse(events))
        self.assertEqual(p.current_count, 1)
        self.assertIsNone(p.current_bytes)
        self.assertIsNone(p.peak_bytes)
        self.assertEqual(p.imbalance_line, "")
        self.assertEqual(p.message, BYTES_UNAVAILABLE)
        self.assertEqual(len(p.steps), 3)
        self.assertEqual((p.pools[0].alloc_count, p.pools[0].free_count), (2, 1))
        self.assertTrue(all(s.outstanding_bytes is None for s in p.steps))

    def test_slab_outcome_status_controls_counts_and_pool_name(self):
        raw = [{"name": "mem_slab_alloc_enter", "timestamp_ns": 1,
                "id": "0x40", "slab_name": "radio_pool"},
               {"name": "mem_slab_alloc_exit", "timestamp_ns": 2,
                "id": "0x40", "slab_name": "radio_pool", "ret": 0},
               {"name": "mem_slab_alloc_exit", "timestamp_ns": 3,
                "id": "0x40", "slab_name": "radio_pool", "ret": -12},
               {"name": "mem_slab_alloc_exit", "timestamp_ns": 4,
                "id": "0x40", "slab_name": "radio_pool", "ret": 0},
               {"name": "mem_slab_free_enter", "timestamp_ns": 5,
                "id": "0x40", "slab_name": "radio_pool"},
               {"name": "mem_slab_free_exit", "timestamp_ns": 6,
                "id": "0x40", "slab_name": "radio_pool"}]
        p = MemoryProfiler().calculate(SemanticTraceParser().parse(raw))
        self.assertEqual(p.current_count, 1)
        self.assertEqual((p.pools[0].name, p.pools[0].alloc_count,
                          p.pools[0].free_count, p.pools[0].peak_bytes),
                         ("radio_pool", 2, 1, None))
        self.assertEqual(p.imbalance_line, "")

    def test_failed_alloc_and_per_pool_breakdown(self):
        t = SemanticTraceParser().parse([
            {"name": "heap_sys_k_malloc_exit", "timestamp_ns": 1, "ret": 0, "id": 1}
        ])
        self.assertEqual(MemoryProfiler().calculate(t).current_count, 0)
        self.assertEqual(MemoryProfiler().calculate(t).steps, [])
        p = MemoryProfiler().calculate(SemanticTrace(events=[
            record(K.MEM_ALLOC, 1, "p1", 80, "alpha"),
            record(K.MEM_ALLOC, 2, "p2", 20, "beta"),
            record(K.MEM_FREE, 3, "p1", pool="alpha")]))
        self.assertEqual([(x.name, x.alloc_count, x.free_count, x.peak_bytes)
                          for x in p.pools], [("alpha", 1, 1, 80), ("beta", 1, 0, 20)])
        self.assertEqual((p.current_bytes, p.peak_bytes, p.peak_timestamp_ns), (20, 100, 2))

    def test_unmatched_free_or_duplicate_ptr_cannot_prove_balance(self):
        for records in (
            [record(K.MEM_ALLOC, 1, "a", 100), record(K.MEM_FREE, 2, "b")],
            [record(K.MEM_ALLOC, 1, "a", 100), record(K.MEM_ALLOC, 2, "a", 100)],
        ):
            p = MemoryProfiler().calculate(SemanticTrace(events=records))
            self.assertFalse(p.count_complete)
            self.assertIsNone(p.current_count)
            self.assertIsNone(p.peak_bytes)
            self.assertEqual(p.imbalance_line, "")

    def test_discarded_packet_notification_marks_incomplete(self):
        t = SemanticTraceParser().parse([
            {"name": "heap_sys_k_malloc_exit", "timestamp_ns": 1,
             "id": "0x10", "ret": "0x100", "size_bytes": 100},
            {"kind": "discarded_packets", "count": 2},
        ])
        self.assertTrue(t.incomplete)
        p = MemoryProfiler().calculate(t)
        self.assertEqual(p.current_bytes, 100)
        self.assertEqual(p.imbalance_line, "")
        self.assertEqual(p.message, INCOMPLETE)


if __name__ == "__main__":
    unittest.main()
