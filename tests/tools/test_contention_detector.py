# SPDX-License-Identifier: Apache-2.0
"""Evidence-qualified contention detection regression tests."""
import sys
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis"))
from semantic_trace import SemanticEvent, SemanticTrace, SemanticTraceParser, EventKind as K
from contention_detector import ContentionDetector, detect_events, DEFAULT_THRESHOLD_US


def ev(kind, ts, who="", priority=None, object_name="", phase="", stream=0):
    return SemanticEvent(timestamp_ns=ts, kind=kind, thread_name=who,
                         thread_priority=None if priority is None else str(priority),
                         object_name=object_name, resource_phase=phase, stream=stream)


def inversion_trace(priorities=True, medium=True):
    p = (lambda n: n) if priorities else (lambda _: None)
    records = [
        ev(K.MUTEX_TAKE, 0, "low", p(5), "lock_A", "acquired"),
        ev(K.MUTEX_TAKE, 100, "high", p(1), "lock_A", "attempt"),
        ev(K.THREAD_BLOCKED, 200, "high", p(1)),
    ]
    if medium:
        records.append(ev(K.THREAD_SWITCHED_IN, 300, "medium", p(3)))
        records.append(ev(K.THREAD_SWITCHED_OUT, 400, "medium", p(3)))
    records.extend([
        ev(K.MUTEX_GIVE, 500, "low", p(5), "lock_A", "released"),
        ev(K.THREAD_READY, 700, "high", p(1)),
    ])
    return SemanticTrace(records)


class ContentionTests(unittest.TestCase):
    def test_pattern_a_three_threads_detected_exactly_once(self):
        report = ContentionDetector().detect(inversion_trace())
        self.assertTrue(report.inversion_enabled)
        self.assertEqual(len(report.findings), 1)
        finding = report.findings[0]
        self.assertEqual(finding.kind, "Inversion")
        self.assertEqual((finding.waiter, finding.holder, finding.medium_thread),
                         ("high", "low", "medium"))
        self.assertEqual((finding.waiter_priority, finding.holder_priority,
                          finding.medium_priority), (1, 5, 3))
        self.assertEqual(finding.object_name, "lock_A")
        self.assertEqual(finding.duration_ns, 500)
        self.assertFalse(finding.holder_unknown)

    def test_no_medium_schedule_means_no_inversion(self):
        report = ContentionDetector().detect(inversion_trace(medium=False))
        self.assertTrue(report.inversion_enabled)
        self.assertEqual([f for f in report.findings if f.kind == "Inversion"], [])

    def test_1500us_wait_above_1000_threshold_and_500us_not(self):
        self.assertEqual(DEFAULT_THRESHOLD_US, 1000)
        records = [ev(K.MUTEX_TAKE, 0, "waiter", 2, "m", "attempt"),
                   ev(K.MUTEX_TAKE, 1_500_000, "waiter", 2, "m", "acquired")]
        result = ContentionDetector().detect(SemanticTrace(records), threshold_us=1000)
        self.assertEqual(len(result.findings), 1)
        finding = result.findings[0]
        self.assertEqual(finding.kind, "Long Wait")
        self.assertEqual(finding.duration_us, "1500.000")
        self.assertEqual(finding.holder, "")
        self.assertTrue(finding.holder_unknown)
        self.assertIn("holder unknown", finding.explanation)

        short = ContentionDetector().detect(SemanticTrace([
            ev(K.MUTEX_TAKE, 0, "waiter", 2, "m", "attempt"),
            ev(K.MUTEX_TAKE, 500_000, "waiter", 2, "m", "acquired")]))
        self.assertEqual(short.findings, [])

    def test_missing_all_priorities_disables_a_without_false_positives(self):
        result = ContentionDetector().detect(inversion_trace(priorities=False))
        self.assertFalse(result.inversion_enabled)
        self.assertEqual([f for f in result.findings if f.kind == "Inversion"], [])

    def test_owner_unknown_no_inversion_even_if_medium_scheduled(self):
        report = ContentionDetector().detect(SemanticTrace([
            ev(K.MUTEX_TAKE, 10, "high", 1, "m", "attempt"),
            ev(K.THREAD_BLOCKED, 20, "high", 1),
            ev(K.THREAD_SWITCHED_IN, 30, "medium", 3),
            ev(K.THREAD_READY, 2_000_020, "high", 1),
        ]))
        self.assertEqual([f.kind for f in report.findings], ["Long Wait"])
        self.assertTrue(report.findings[0].holder_unknown)
        self.assertEqual(report.findings[0].holder, "")

    def test_release_before_medium_runs_does_not_make_inversion(self):
        r = ContentionDetector().detect(SemanticTrace([
            ev(K.MUTEX_TAKE, 0, "low", 5, "m", "acquired"),
            ev(K.MUTEX_TAKE, 10, "high", 1, "m", "attempt"),
            ev(K.THREAD_BLOCKED, 20, "high", 1),
            ev(K.MUTEX_GIVE, 25, "low", 5, "m", "released"),
            ev(K.THREAD_SWITCHED_IN, 30, "medium", 3),
            ev(K.THREAD_READY, 40, "high", 1),
        ]))
        self.assertFalse(any(f.kind == "Inversion" for f in r.findings))

    def test_unclosed_block_does_not_get_guessed_duration(self):
        r = ContentionDetector().detect(SemanticTrace([
            ev(K.MUTEX_TAKE, 0, "low", 5, "m", "acquired"),
            ev(K.MUTEX_TAKE, 10, "high", 1, "m", "attempt"),
            ev(K.THREAD_BLOCKED, 20, "high", 1),
            ev(K.THREAD_SWITCHED_IN, 30, "medium", 3),
        ], incomplete=True))
        self.assertEqual(r.findings, [])
        self.assertTrue(r.incomplete)

    def test_semaphore_take_wait_unknown_holder(self):
        r = ContentionDetector().detect(SemanticTrace([
            ev(K.SEM_TAKE, 0, "reader", 4, "sem_x", "attempt"),
            ev(K.SEM_TAKE, 1_500_000, "reader", 4, "sem_x", "acquired"),
        ]))
        self.assertEqual(len(r.findings), 1)
        self.assertEqual(r.findings[0].kind, "Long Wait")
        self.assertTrue(r.findings[0].holder_unknown)
        self.assertEqual(r.findings[0].object_name, "sem_x")

    def test_boundary_is_strict_and_threshold_reconfigures(self):
        trace = SemanticTrace([
            ev(K.MUTEX_TAKE, 0, "thread", 2, "m", "attempt"),
            ev(K.MUTEX_TAKE, 1_500_000, "thread", 2, "m", "acquired"),
        ])
        self.assertEqual(len(ContentionDetector().detect(trace, 1400).findings), 1)
        self.assertEqual(ContentionDetector().detect(trace, 1500).findings, [])
        with self.assertRaisesRegex(ValueError, "nonnegative integer"):
            ContentionDetector().detect(trace, -1)

    def test_ctf_lock_enter_exit_phases_and_ret_validation(self):
        def raw(name, ts, who, ret=None):
            record = {"kind": "event", "name": name, "timestamp_ns": ts,
                      "thread_name": who, "priority": 4,
                      "object_id": "0x10", "stream": 0}
            if ret is not None:
                record["ret"] = ret
            return record
        trace = SemanticTraceParser().parse([
            raw("mutex_lock_enter", 1, "low"),
            raw("mutex_lock_blocking", 2, "low"),
            raw("mutex_lock_exit", 3, "low", -11),
            raw("mutex_lock_enter", 4, "low"),
            raw("mutex_lock_exit", 5, "low", 0),
            raw("mutex_unlock_enter", 6, "low"),
            raw("mutex_unlock_exit", 7, "low", 0),
        ])
        self.assertEqual([event.kind for event in trace.events], [
            K.MUTEX_TAKE, K.MUTEX_TAKE, K.MUTEX_TAKE, K.MUTEX_TAKE,
            K.MUTEX_TAKE, K.MUTEX_GIVE, K.MUTEX_GIVE,
        ])
        self.assertEqual([event.resource_phase for event in trace.events],
                         ["attempt", "blocking", "failed", "attempt", "acquired",
                          "release_attempt", "released"])
        self.assertTrue(all(e.object_name == "" and e.object_id == "0x10"
                            for e in trace.events))
        self.assertFalse(trace.incomplete)

    def test_generic_lock_alias_does_not_prove_successful_ownership(self):
        trace = SemanticTraceParser().parse([
            {"kind": "event", "name": "mutex_lock", "timestamp_ns": 0,
             "thread_name": "low", "priority": 5, "object_name": "m"},
            {"kind": "event", "name": "mutex_lock_enter", "timestamp_ns": 10,
             "thread_name": "high", "priority": 1, "object_name": "m"},
            {"kind": "event", "name": "thread_pending", "timestamp_ns": 20,
             "thread_name": "high", "priority": 1},
            {"kind": "event", "name": "thread_switched_in", "timestamp_ns": 30,
             "thread_name": "medium", "priority": 3, "stream": 0},
            {"kind": "event", "name": "thread_ready", "timestamp_ns": 40,
             "thread_name": "high", "priority": 1},
        ])
        self.assertEqual(trace.events[0].resource_phase, "attempt")
        self.assertEqual([f for f in ContentionDetector().detect(trace).findings
                          if f.kind == "Inversion"], [])

    def test_anonymous_resource_never_produces_a_finding(self):
        trace = SemanticTrace([
            ev(K.MUTEX_TAKE, 0, "a", 4, "", "attempt"),
            ev(K.MUTEX_TAKE, 2_000_000, "a", 4, "", "acquired"),
        ])
        self.assertEqual(ContentionDetector().detect(trace).findings, [])

    def test_no_priority_but_long_wait_still_available(self):
        r = ContentionDetector().detect(SemanticTrace([
            ev(K.MUTEX_TAKE, 0, "waiter", None, "m", "attempt"),
            ev(K.MUTEX_TAKE, 2_000_000, "waiter", None, "m", "acquired"),
        ]))
        self.assertFalse(r.inversion_enabled)
        self.assertEqual([f.kind for f in r.findings], ["Long Wait"])


if __name__ == "__main__":
    unittest.main()
