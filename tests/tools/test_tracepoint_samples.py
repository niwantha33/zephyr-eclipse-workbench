# SPDX-License-Identifier: Apache-2.0
"""Contract tests for declared Zephyr CTF named_event custom samples."""
import struct
import sys
import tempfile
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis"))
import tracepoint_config as config
import tracepoint_samples as samples
from semantic_trace import SemanticTraceParser, EventKind


def event(name, ts, arg0):
    return {"kind": "event", "stream": 0, "name": "named_event",
            "named_event_name": name, "timestamp_ns": ts, "arg0": arg0, "arg1": 0}


class TracepointSamplesTests(unittest.TestCase):
    def test_three_integer_samples_are_ordered_value_samples(self):
        definitions = config.parse_text("motor_rpm = Motor RPM | integer")
        source = [event("motor_rpm", 30, 200),
                  event("motor_rpm", 10, 150),
                  event("motor_rpm", 20, 0xFFFFFFFF)]
        trace = SemanticTraceParser().parse(source, tracepoints=definitions)
        self.assertEqual([e.kind for e in trace.events], [EventKind.VALUE_SAMPLE] * 3)
        self.assertEqual([e.timestamp_ns for e in trace.events], [10, 20, 30])
        self.assertEqual([e.value for e in trace.events], [150, -1, 200])
        self.assertEqual([e.tracepoint_name for e in trace.events], ["motor_rpm"] * 3)
        self.assertEqual([e.raw_event_name for e in trace.events], ["named_event"] * 3)
        self.assertEqual(trace.tracepoint_sample_counts, {"motor_rpm": 3})
        self.assertEqual(trace.missing_tracepoints, [])
        self.assertFalse(trace.incomplete)

    def test_declared_but_absent_is_not_error(self):
        definitions = config.parse_text(
            "motor_rpm = Motor RPM | integer\nmotor_temp = Temperature | float\n")
        trace = SemanticTraceParser().parse([event("motor_rpm", 100, 42)], tracepoints=definitions)
        self.assertEqual(trace.missing_tracepoints, ["motor_temp"])
        self.assertEqual(trace.tracepoint_sample_counts["motor_temp"], 0)
        self.assertFalse(trace.incomplete)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / config.FILE_NAME).write_text(
                "motor_rpm = Motor RPM | integer\nmotor_temp = Temperature | float\n")
            wire = samples.extract(root, [event("motor_rpm", 100, 42)])
            rows = wire.splitlines()
            self.assertIn("ZEW_TRACEPOINTS\t1\tactive", rows[0])
            declarations = [r.split("\t") for r in rows if r.startswith("D\t")]
            self.assertEqual([int(r[4]) for r in declarations], [1, 0])
            self.assertEqual(len([r for r in rows if r.startswith("S\t")]), 1)

    def test_float_is_decoded_as_32_bit_ieee_bits(self):
        bits = struct.unpack("<I", struct.pack("<f", 13.25))[0]
        definitions = config.parse_text("motor_temp = Motor temperature | float")
        trace = SemanticTraceParser().parse([event("motor_temp", 3, bits)], tracepoints=definitions)
        self.assertEqual(trace.events[0].kind, EventKind.VALUE_SAMPLE)
        self.assertEqual(trace.events[0].value, 13.25)

    def test_malformed_configuration_gives_line_not_exception(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / config.FILE_NAME).write_text(
                "# comment\nmotor_rpm = Motor RPM | integer\nbad = No type | boolean\n")
            parsed = config.load_project(path)
            self.assertEqual(parsed.status, "invalid")
            self.assertIn("line 3:", parsed.error)
            self.assertEqual(parsed.specs, ())
            wire = samples.extract(path, [event("motor_rpm", 1, 44)])
            self.assertIn("ZEW_TRACEPOINTS\t1\tinvalid", wire)
            self.assertEqual(len([x for x in wire.splitlines() if x.startswith("S\t")]), 0)

    def test_no_config_is_inactive_without_error(self):
        with tempfile.TemporaryDirectory() as directory:
            result = config.load_project(Path(directory))
            self.assertEqual(result.status, "inactive")
            self.assertEqual(result.error, "")
            self.assertIn("ZEW_TRACEPOINTS\t1\tinactive",
                          samples.extract(Path(directory), []))

    def test_reject_truncatable_names_and_duplicate(self):
        self.assertIn("line 1:", config.parse_text(
            "this_name_exceeds_19_chars = Bad | integer").error)
        self.assertIn("line 2:", config.parse_text(
            "a = A | integer\na = B | float").error)

    def test_undeclared_named_event_retained_as_unknown(self):
        definitions = config.parse_text("motor_temp = Motor temperature | float")
        trace = SemanticTraceParser().parse([event("unlisted", 7, 40)], tracepoints=definitions)
        self.assertEqual(len(trace.events), 1)
        self.assertEqual(trace.events[0].kind, EventKind.UNKNOWN)
        self.assertEqual(trace.unknown_count, 1)
        self.assertEqual(trace.missing_tracepoints, ["motor_temp"])

    def test_invalid_nan_sample_is_unknown_not_silent_or_exception(self):
        definitions = config.parse_text("motor_temp = Motor temperature | float")
        trace = SemanticTraceParser().parse([event("motor_temp", 1, 0x7FC00000)], tracepoints=definitions)
        self.assertEqual(trace.unknown_count, 1)
        self.assertEqual(trace.events[0].kind, EventKind.UNKNOWN)
        self.assertIn("not finite", trace.sample_errors[0])
        self.assertEqual(trace.missing_tracepoints, ["motor_temp"])

    def test_direct_ctf_event_name_payload_supported(self):
        definitions = config.parse_text("pressure = Pressure | integer")
        trace = SemanticTraceParser().parse([
            {"name": "pressure", "timestamp_ns": 2, "value": -3},
        ], tracepoints=definitions)
        self.assertEqual(trace.events[0].kind, EventKind.VALUE_SAMPLE)
        self.assertEqual(trace.events[0].value, -3)


if __name__ == "__main__":
    unittest.main()
