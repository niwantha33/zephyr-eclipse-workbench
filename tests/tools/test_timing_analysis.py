# SPDX-License-Identifier: Apache-2.0
"""Evidence-bounded thread execution/latency tests."""
import importlib.util
from pathlib import Path
import unittest

HERE = Path(__file__).resolve().parents[2] / "plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis"
spec = importlib.util.spec_from_file_location("timing_analysis", HERE / "timing_analysis.py")
subject = importlib.util.module_from_spec(spec)
spec.loader.exec_module(subject)


def ev(name, ts, tid="a", stream=0):
    return {"kind": "event", "name": name, "timestamp_ns": ts,
            "thread_id": tid, "thread_name": tid, "stream": stream}


class TimingTests(unittest.TestCase):
    def test_paired_burst_observed_deadline_period_and_ready_latency(self):
        data = [
            ev("thread_ready", 0),
            ev("thread_switched_in", 5),
            ev("isr_enter", 10, None), ev("isr_exit_to_scheduler", 15, None),
            ev("thread_switched_out", 25),
            ev("thread_ready", 30),
            ev("thread_switched_in", 35),
            ev("thread_switched_out", 43),
            ev("thread_switched_in", 60),
            ev("thread_switched_out", 64),
        ]
        report = subject.analyze_timing(data, deadlines_ns={"a": 10})
        self.assertEqual(report["status"], "observed")
        item = report["threads"]["a"]
        self.assertEqual(item["complete_bursts"], 3)
        self.assertEqual(item["total_scheduled_ns"], 32)
        self.assertEqual(item["max_observed_burst_ns"], 20)
        self.assertEqual(item["observed_bursts_exceeding_deadline"], 1)
        self.assertEqual(item["ready_latency_samples"], 2)
        self.assertEqual(item["max_observed_ready_latency_ns"], 5)
        self.assertEqual(item["period_min_ns"], 25)
        self.assertEqual(item["period_max_ns"], 30)
        self.assertEqual(item["period_range_jitter_ns"], 5)
        self.assertIn("never proven WCET", report["maxima"])

    def test_explicit_state_counts_without_inventing_blocked_intervals(self):
        report = subject.analyze_timing([
            ev("thread_pending", 1), ev("thread_sleep_enter", 2),
            ev("thread_sleep_exit", 4), ev("thread_resume", 5),
            ev("thread_priority_set", 6), ev("thread_switched_in", 7),
            ev("thread_switched_out", 9)])
        item = report["threads"]["a"]
        self.assertEqual(item["observed_pending_events"], 1)
        self.assertEqual(item["observed_sleep_enters"], 1)
        self.assertEqual(item["observed_sleep_exits"], 1)
        self.assertEqual(item["observed_resume_events"], 1)
        self.assertEqual(item["observed_priority_changes"], 1)
        self.assertEqual(item["ready_latency_samples"], 0)

    def test_incomplete_or_unpaired_burst_never_invents_duration(self):
        report = subject.analyze_timing([
            ev("thread_switched_out", 0), ev("thread_switched_in", 3),
            ev("thread_switched_out", 7), ev("thread_switched_in", 8)])
        self.assertEqual(report["threads"]["a"]["complete_bursts"], 1)
        self.assertEqual(report["threads"]["a"]["total_scheduled_ns"], 4)
        self.assertEqual(len(report["warnings"]), 2)

    def test_loss_multistream_and_clock_invalid_withhold_all_metrics(self):
        base = [ev("thread_switched_in", 2), ev("thread_switched_out", 5)]
        for data in (
            base + [{"kind": "discarded_events", "count": 1}],
            base + [ev("thread_switched_in", 7, stream=1)],
            [ev("thread_switched_in", 5), ev("thread_switched_out", 2)],
            [ev("thread_switched_in", None), ev("thread_switched_out", 6)],
        ):
            with self.subTest(data=data):
                report = subject.analyze_timing(data)
                self.assertEqual(report["status"], "unavailable")
                self.assertFalse(report["threads"])

    def test_bad_deadline_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "positive"):
            subject.analyze_timing([ev("thread_switched_in", 0), ev("thread_switched_out", 10)],
                                   deadlines_ns={"a": 0})


if __name__ == "__main__":
    unittest.main()
