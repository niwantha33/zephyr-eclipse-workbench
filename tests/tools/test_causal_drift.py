# SPDX-License-Identifier: Apache-2.0
"""Regression tests for explicit activation/drift versus scheduler switch-ins."""
import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[2] / "plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis"
sys.path.insert(0, str(TOOLS))
from semantic_trace import SemanticTraceParser
from causal_drift import analyze_drift


def marker(phase, ts, seq, tag=7, stream=0):
    return {"kind": "event", "name": "named_event", "named_event_name": phase,
            "timestamp_ns": ts, "arg0": tag, "arg1": seq, "stream": stream}


def ev(name, ts, tid="motor", stream=0, obj=None, ret=None):
    raw = {"kind": "event", "name": name, "timestamp_ns": ts, "stream": stream,
           "thread_id": tid, "thread_name": tid}
    if obj is not None:
        raw["object_id"] = obj
    if ret is not None:
        raw["ret"] = ret
    return raw


def analyse(*events, **args):
    return analyze_drift(SemanticTraceParser().parse(events), tag=7,
                         period_ns=1000, **args)


class DriftTests(unittest.TestCase):
    def test_switch_ins_never_become_extra_task_cycles(self):
        result = analyse(
            marker("zew_cycle_start", 100, 0),
            ev("thread_switched_in", 100),
            ev("thread_switched_out", 110),
            ev("thread_switched_in", 120, "logger"),
            ev("thread_switched_out", 180, "logger"),
            ev("thread_switched_in", 200),
            marker("zew_cycle_finish", 300, 0),
            marker("zew_cycle_start", 1200, 1),
            marker("zew_cycle_finish", 1300, 1),
            thread_id="motor")
        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["measured_cycles"], 2)
        self.assertEqual(result["phase_trend_ns"], 100)
        self.assertEqual(result["cycles"][0]["scheduler_interruptions_observed"], 1)
        self.assertEqual(result["cycles"][0]["intervening_threads"], ["logger"])
        self.assertIsNone(result["cycles"][0]["start_lateness_ns"])
        self.assertEqual(result["cycles"][0]["relative_phase_ns"], 0)
        self.assertEqual(result["cycles"][1]["relative_phase_ns"], 100)
        self.assertIsNone(result["cycles"][0]["deadline_missed"])

    def test_anchor_release_deadline_and_paired_mutex_block(self):
        result = analyse(
            marker("zew_cycle_release", 50, 0),
            marker("zew_cycle_start", 100, 0),
            ev("thread_switched_in", 100),
            ev("mutex_lock_enter", 110, obj="0x8"),
            ev("mutex_lock_blocking", 130, obj="0x8"),
            ev("thread_pending", 140),
            ev("thread_ready", 220),
            ev("mutex_lock_exit", 250, obj="0x8", ret=0),
            marker("zew_cycle_finish", 600, 0),
            marker("zew_cycle_release", 1000, 1),
            marker("zew_cycle_start", 1300, 1),
            marker("zew_cycle_finish", 1700, 1),
            anchor_ns=0, thread_id="motor", deadline_ns=500)
        self.assertEqual(result["cycles"][0]["start_lateness_ns"], 100)
        self.assertEqual(result["cycles"][1]["start_lateness_ns"], 300)
        self.assertEqual(result["cycles"][0]["release_to_start_ns"], 50)
        self.assertEqual(result["cycles"][0]["paired_mutex_blocks"],
                         [{"object": "object ID 0x8", "blocked_ns": 120}])
        self.assertTrue(result["cycles"][0]["deadline_missed"])
        self.assertTrue(result["cycles"][1]["deadline_missed"])
        self.assertEqual(result["phase_trend_ns"], 200)

    def test_attempt_without_block_and_failed_lock_not_assigned(self):
        result = analyse(
            marker("zew_cycle_start", 100, 0),
            ev("thread_switched_in", 100),
            ev("mutex_lock_enter", 110, obj="m"),
            ev("mutex_lock_exit", 130, obj="m", ret=-11),
            marker("zew_cycle_finish", 200, 0), thread_id="motor")
        self.assertEqual(result["cycles"][0]["paired_mutex_blocks"], [])

    def test_duplicate_marker_rejected_instead_of_overwritten(self):
        r = analyse(marker("zew_cycle_start", 100, 0),
                    marker("zew_cycle_start", 101, 0))
        self.assertEqual(r["status"], "incomplete")
        self.assertEqual(r["cycles"], [])

    def test_missing_start_refuses_timing(self):
        r = analyse(marker("zew_cycle_release", 10, 0),
                    marker("zew_cycle_finish", 15, 0))
        self.assertEqual(r["status"], "incomplete")

    def test_other_tag_ignored_and_no_markers_explained(self):
        r = analyse(marker("zew_cycle_start", 10, 0, tag=99))
        self.assertEqual(r["status"], "no_markers")

    def test_loss_clock_and_cross_stream_invalidate_all_metrics(self):
        for records in (
            [marker("zew_cycle_start", 10, 0),
             {"kind": "discarded_events", "count": 1}],
            [marker("zew_cycle_start", 20, 0), marker("zew_cycle_start", 10, 1)],
            [marker("zew_cycle_start", 10, 0, stream=0),
             marker("zew_cycle_start", 100, 1, stream=1)],
        ):
            with self.subTest(records=records):
                r = analyse(*records)
                self.assertEqual(r["status"], "incomplete")
                self.assertEqual(r["cycles"], [])

    def test_no_preemption_claim_without_paired_scheduler_evidence(self):
        r = analyse(marker("zew_cycle_start", 10, 0),
                    ev("thread_switched_out", 12),
                    marker("zew_cycle_finish", 20, 0), thread_id="motor")
        self.assertEqual(r["cycles"][0]["scheduler_interruptions_observed"], 0)
        self.assertEqual(r["cycles"][0]["intervening_threads"], [])

    def test_invalid_parameters(self):
        trace = SemanticTraceParser().parse([marker("zew_cycle_start", 100, 0)])
        for kwargs in ({"tag": -1, "period_ns": 1},
                       {"tag": 7, "period_ns": 0},
                       {"tag": 7, "period_ns": 1, "anchor_ns": -1},
                       {"tag": 7, "period_ns": 1, "deadline_ns": 0}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                analyze_drift(trace, **kwargs)


if __name__ == "__main__":
    unittest.main()
