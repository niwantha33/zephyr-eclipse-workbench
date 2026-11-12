# SPDX-License-Identifier: Apache-2.0
"""Cross-component CTF decoder-record replay and trace capability documentation guard.

Runs in scripts/test_core.py via unittest discovery; no bt2 or hardware needed.
Fixtures are synthetic normalized records, not raw CTF files or hardware captures.
"""
from __future__ import annotations

import base64
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / "plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis"
FIXTURES = ROOT / "tests/fixtures/ctf_regression"
CAPABILITIES = ROOT / "docs/TRACE_ANALYSIS_CAPABILITIES.md"
sys.path.insert(0, str(TOOLS))

import analyze_trace
import contention_report
import cpu_analysis
import memory_profile_report
from memory_profiler import MemoryProfiler, NO_EVENTS, INCOMPLETE
from semantic_trace import EventKind, SemanticTraceParser
import thread_timeline
import tracepoint_config
import tracepoint_samples

FILES = (
    "complete_scheduler.jsonl", "truncated.jsonl",
    "no_memory_events.jsonl", "unknown_event_name.jsonl",
)
# Every helper in this directory must have a documented analysis/support row.
# When a helper is added, the guard fails until it is classified honestly.
MODULE_FEATURES = {
    "analyze_trace": "Offline CTF summary",
    "capture_snapshot": "RAM CTF snapshot capture",
    "contention_detector": "Priority inversion detection",
    "contention_report": "Long resource contention detection",
    "coredump_offline": "Offline core-dump assessment",
    "cpu_analysis": "Zephyr CPU Load view",
    "offline_trace_json": "Zephyr Contention Report view",
    "investigation": "Offline investigation report",
    "memory_profile_report": "Memory allocation profiling",
    "memory_profiler": "Memory allocation profiling",
    "semantic_trace": "CTF semantic normalization",
    "synchronization_analysis": "Synchronization object activity (mutex, semaphore, Zephyr message queue)",
    "serial_console": "Debug serial console",
    "thread_event_log": "Thread event log",
    "thread_timeline": "Thread state timeline",
    "timing_analysis": "Timing analysis",
    "trace_compass_bridge": "Trace Compass handoff",
    "tracepoint_config": "Custom tracepoint plotting",
    "tracepoint_samples": "Custom tracepoint plotting",
}


def replay(filename):
    """Create a fresh iterator on every call; simulated decode errors are late."""
    for line in (FIXTURES / filename).read_text(encoding="utf-8").splitlines():
        raw = json.loads(line)
        if "fixture_decoder_error" in raw:
            raise ValueError(raw["fixture_decoder_error"])
        yield raw


class TraceRegressionHarnessTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory(prefix="zew-ctf-regression-")
        self.addCleanup(tmp.cleanup)
        self.project = Path(tmp.name)
        (self.project / tracepoint_config.FILE_NAME).write_text(
            "motor_rpm = Motor RPM | integer\n", encoding="utf-8")

    def test_each_fixture_runs_every_analysis_component_without_exception(self):
        for filename in FILES:
            with self.subTest(fixture=filename):
                timeline = thread_timeline.timeline_from_events(replay(filename))
                cpu = cpu_analysis.account(SemanticTraceParser().parse(replay(filename)),
                                           idle_thread_id="idle")
                plot = tracepoint_samples.extract(self.project, replay(filename))
                contention = contention_report.analyze(replay(filename))
                memory = memory_profile_report.analyze(replay(filename))
                summary = analyze_trace.summarize(replay(filename), idle_thread_id="idle")
                self.assertTrue(thread_timeline.serialize(timeline).startswith("ZEW_THREAD_TIMELINE\t2\t"))
                self.assertIn(cpu["status"], ("unavailable", "partial", "measured"))
                self.assertTrue(plot.startswith("ZEW_TRACEPOINTS\t1\t"))
                self.assertTrue(contention.startswith("ZEW_CONTENTION\t1\t"))
                self.assertTrue(memory.startswith("ZEW_MEMORY\t1\t"))
                self.assertIn(summary["ctf_decode"], ("validated", "invalid", "not checked"))

    def test_complete_scheduler_fixture_has_observed_intervals_and_cpu(self):
        timeline = thread_timeline.timeline_from_events(replay(FILES[0]))
        self.assertEqual([(r.start_ns, r.end_ns, r.state) for r in timeline.intervals],
                         [(100, 500, "Running"), (500, 900, "Running")])
        self.assertFalse(timeline.incomplete)
        cpu = cpu_analysis.account(SemanticTraceParser().parse(replay(FILES[0])),
                                   idle_thread_id="idle")
        self.assertEqual(cpu["status"], "measured")
        self.assertEqual(cpu["cpu_busy_percent"], 50.0)
        trace = SemanticTraceParser().parse(replay(FILES[0]))
        profile = MemoryProfiler().calculate(trace)
        self.assertEqual((profile.memory_events, profile.current_count,
                          profile.current_bytes, profile.peak_bytes), (2, 0, 0, 64))
        report = tracepoint_samples.extract(self.project, replay(FILES[0]))
        self.assertEqual(len([row for row in report.splitlines() if row.startswith("S\t")]), 1)

    def test_truncated_fixture_withholds_unsupported_conclusions(self):
        trace = SemanticTraceParser().parse(replay("truncated.jsonl"))
        self.assertTrue(trace.incomplete)
        self.assertIn("truncated CTF packet", trace.error)
        timeline = thread_timeline.build_intervals(trace)
        self.assertTrue(timeline.incomplete)
        self.assertEqual([(r.start_ns, r.end_ns, r.state) for r in timeline.intervals],
                         [(100, 300, "Running"), (300, 400, "Unknown")])
        cpu = cpu_analysis.account(trace, idle_thread_id="idle")
        self.assertEqual(cpu["status"], "unavailable")
        self.assertIsNone(cpu["cpu_busy_percent"])
        memory = MemoryProfiler().calculate(trace)
        self.assertTrue(memory.incomplete)
        self.assertEqual(memory.current_count, 1)
        self.assertEqual(memory.current_bytes, 32)
        self.assertEqual(memory.message, INCOMPLETE)
        self.assertEqual(memory.imbalance_line, "")
        wire = memory_profile_report.analyze(replay("truncated.jsonl")).splitlines()[0].split("\t")
        self.assertEqual(wire[3], "1")
        self.assertEqual(wire[12], "")
        self.assertEqual(analyze_trace.summarize(replay("truncated.jsonl"))["ctf_decode"], "invalid")
        self.assertEqual(contention_report.analyze(replay("truncated.jsonl")).splitlines()[0].split("\t")[3], "1")

    def test_no_memory_events_exact_message_and_no_profile_steps(self):
        trace = SemanticTraceParser().parse(replay("no_memory_events.jsonl"))
        profile = MemoryProfiler().calculate(trace)
        self.assertTrue(profile.no_events)
        self.assertEqual(profile.memory_events, 0)
        self.assertEqual(profile.steps, [])
        self.assertEqual(profile.pools, [])
        self.assertEqual(profile.message, NO_EVENTS)
        self.assertEqual(profile.imbalance_line, "")
        wire = memory_profile_report.analyze(replay("no_memory_events.jsonl"))
        self.assertEqual(len(wire.splitlines()), 1)
        header = wire.splitlines()[0].split("\t")
        self.assertEqual(header[2], "0")
        self.assertEqual(base64.b64decode(header[11]).decode("utf-8"), NO_EVENTS)
        cpu = cpu_analysis.account(trace, idle_thread_id="idle")
        self.assertIsNone(cpu["cpu_busy_percent"])  # 200-300 ns unobserved gap

    def test_unknown_name_remains_unknown_not_silently_discarded(self):
        trace = SemanticTraceParser().parse(replay("unknown_event_name.jsonl"))
        self.assertEqual(trace.unknown_count, 1)
        self.assertEqual([e.raw_event_name for e in trace.events if e.kind == EventKind.UNKNOWN],
                         ["vendor:unexpected_hook"])
        self.assertEqual(thread_timeline.build_intervals(trace).unknown_events, 1)
        self.assertEqual(analyze_trace.summarize(replay("unknown_event_name.jsonl"))["unknown_events"], 1)

    def test_fixtures_are_small_and_fixed(self):
        self.assertEqual({p.name for p in FIXTURES.glob("*.jsonl")}, set(FILES))
        for filename in FILES:
            with self.subTest(fixture=filename):
                data = (FIXTURES / filename).read_bytes()
                self.assertTrue(0 < len(data) <= 2048)
                self.assertTrue(data.endswith(b"\n"))
                self.assertTrue(list(replay(filename)) if filename != "truncated.jsonl"
                                else (FIXTURES / filename).is_file())

    def test_capabilities_table_covers_every_analysis_module(self):
        actual = {p.stem for p in TOOLS.glob("*.py")}
        self.assertEqual(actual, set(MODULE_FEATURES),
                         "Add every new trace-analysis Python module to MODULE_FEATURES")
        markdown = CAPABILITIES.read_text(encoding="utf-8")
        rows = [line for line in markdown.splitlines()
                if line.startswith("| ") and not line.startswith("|---")
                and not line.startswith("| ---")]
        header = [c.strip() for c in rows[0].strip("|").split("|")]
        self.assertEqual(header, [
            "Feature", "Status (Implemented / Partial / Not implemented)", "Data source",
            "Known limitations", "Validated against real hardware (Yes / No)"])
        entries = [[cell.strip() for cell in row.strip("|").split("|")] for row in rows[1:]]
        self.assertTrue(all(len(row) == 5 for row in entries))
        names = [row[0] for row in entries]
        self.assertEqual(len(names), len(set(names)), "No duplicate feature rows")
        self.assertEqual(set(names), set(MODULE_FEATURES.values()) | {
            "Live streaming", "Custom trace event injection",
            "Hardware validation breadth", "Licensing model", "Runs inside the IDE",
        })
        self.assertTrue(all(row[1] in {"Implemented", "Partial", "Not implemented"}
                            and row[2] and row[3] and row[4] == "No" for row in entries),
                        "No feature may acquire an untested hardware Yes claim")
        self.assertEqual(next(row[1] for row in entries if row[0] == "Live streaming"),
                         "Not implemented")
        memory_status = next(row[1] for row in entries if row[0] == "Memory allocation profiling")
        comparison = (ROOT / "docs/TRACEALYZER_COMPARISON.md").read_text(encoding="utf-8")
        self.assertIn(f"| Memory allocation profiling | {memory_status};", comparison)

    def test_offline_json_adapter_and_cpu_command_are_guarded(self):
        source = CAPABILITIES.read_text(encoding="utf-8")
        cpu_row = next(line for line in source.splitlines()
                       if line.startswith("| Zephyr CPU Load view |"))
        contention_row = next(line for line in source.splitlines()
                              if line.startswith("| Zephyr Contention Report view |"))
        self.assertIn("offline_trace_json.py", cpu_row)
        self.assertIn("OfflineTraceAnalysisService.java", cpu_row)
        self.assertIn("ZephyrCpuLoadView.java", cpu_row)
        self.assertIn("offline_trace_json.py", contention_row)
        self.assertIn("OfflineTraceAnalysisService.java", contention_row)
        self.assertIn("per-ISR identity cannot be determined", cpu_row)
        ui = (ROOT / "plugins/io.github.niwantha33.zew.ui/plugin.xml").read_text(
            encoding="utf-8")
        self.assertIn('id="io.github.niwantha33.zew.ui.cpuLoad"', ui)
        self.assertIn('commandId="io.github.niwantha33.zew.cpuLoad" '
                      'label="Zephyr CPU Load (Saved CTF)..."', ui)
        self.assertIn('commandId="io.github.niwantha33.zew.cpuLoad" '
                      'class="io.github.niwantha33.zew.ui.ZephyrCpuLoadHandler"', ui)

    def test_single_source_of_truth_is_cross_referenced(self):
        doc = CAPABILITIES.read_text(encoding="utf-8")
        self.assertIn("This table is the single source of truth. Do not describe a feature "
                      "as supported in any other document unless it appears here with "
                      "status Implemented.", doc)
        self.assertIn("TRACE_ANALYSIS_CAPABILITIES.md",
                      (ROOT / "docs/RTOS_ANALYSIS_ROADMAP.md").read_text(encoding="utf-8"))
        self.assertIn("TRACE_ANALYSIS_CAPABILITIES.md",
                      (ROOT / "docs/README.md").read_text(encoding="utf-8"))
        for name in (
            "CONTENTION_REPORT.md", "MEMORY_PROFILE.md", "USER_TRACEPOINTS.md",
            "TRACE_COMPASS_INTEGRATION.md", "RTOS_TRACE_PROTOTYPE.md",
            "RTOS_INVESTIGATION_GUIDE.md", "DEBUG_TRACE_CONSOLE.md",
        ):
            with self.subTest(guide=name):
                self.assertIn("TRACE_ANALYSIS_CAPABILITIES.md",
                              (ROOT / "docs" / name).read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
