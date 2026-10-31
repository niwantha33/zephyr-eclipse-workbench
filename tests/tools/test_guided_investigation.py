# SPDX-License-Identifier: Apache-2.0
"""Offline evidence engine regression tests: capture, CPU, trace and fault guidance."""
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

MODULE = Path(__file__).resolve().parents[2] / "plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis/investigation.py"
spec = importlib.util.spec_from_file_location("rtos_investigation", MODULE)
reviewer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reviewer)


class GuidedInvestigationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="zew-capture-evidence-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.capture = self.root / "trace-001"
        (self.capture / "ctf").mkdir(parents=True)
        self.trace = b"\x01\x02\x03\x04"
        self.metadata = b"CTF metadata"
        (self.capture / "ctf/channel0_0").write_bytes(self.trace)
        (self.capture / "ctf/metadata").write_bytes(self.metadata)
        self.elf = b"ELF test fixture only"
        self.config = b"CONFIG_TRACING=y\n"
        self.build = self.root / "build"
        (self.build / "zephyr").mkdir(parents=True)
        (self.build / "zephyr/zephyr.elf").write_bytes(self.elf)
        (self.build / "zephyr/.config").write_bytes(self.config)
        self.receipt = {
            "trace_sha256": hashlib.sha256(self.trace).hexdigest(),
            "metadata_sha256": hashlib.sha256(self.metadata).hexdigest(),
            "elf_sha256": hashlib.sha256(self.elf).hexdigest(),
            "build_config_sha256": hashlib.sha256(self.config).hexdigest(),
            "trace_bytes": len(self.trace), "buffer_capacity": 4096,
            "capture_full": False, "transport_read_complete": True,
            "firmware_matches_elf": "not verified",
            "ctf_analysis": {
                "ctf_decode": "validated", "discarded_events_reported": 0,
                "discarded_packets_reported": 0,
                "cpu": {"status": "measured", "cpu_busy_percent": 30,
                        "thread_exclusive_ns": {"thread1": 100}, "isr_ns": 20,
                        "unknown_ns": 0}
            }
        }
        (self.capture / "capture.json").write_text(json.dumps(self.receipt))

    def codes(self, output):
        return {f["code"] for f in output["findings"]}

    def test_hash_matched_build_is_not_authentication_of_flashing(self):
        result = reviewer.review(self.capture, build=self.build)
        self.assertEqual(result["status"], "review-with-limits")
        self.assertEqual(result["capture"]["selected_build_elf"], "hash-matched")
        self.assertEqual(result["capture"]["flashed_firmware"], "not independently authenticated")
        self.assertIn("FLASH_IDENTITY_UNKNOWN", self.codes(result))
        self.assertEqual(result["capture"]["cpu_busy_percent"], 30)

    def test_modified_trace_metadata_elf_and_config_fail_closed(self):
        (self.capture / "ctf/channel0_0").write_bytes(b"bad")
        self.assertIn("ARTIFACT_HASH_MISMATCH", self.codes(reviewer.review(self.capture)))
        (self.capture / "ctf/channel0_0").write_bytes(self.trace)
        (self.capture / "ctf/metadata").write_bytes(b"mutated")
        self.assertIn("ARTIFACT_HASH_MISMATCH", self.codes(reviewer.review(self.capture)))
        (self.capture / "ctf/metadata").write_bytes(self.metadata)
        (self.build / "zephyr/zephyr.elf").write_bytes(b"different")
        (self.build / "zephyr/.config").write_bytes(b"CONFIG_TRACING=n\n")
        result = reviewer.review(self.capture, build=self.build)
        self.assertEqual(result["status"], "blocked")
        self.assertIn("SELECTED_ELF_MISMATCH", self.codes(result))
        self.assertIn("CONFIG_SNAPSHOT_MISMATCH", self.codes(result))

    def test_buffer_loss_and_missing_decoder_require_action(self):
        self.receipt["capture_full"] = True
        self.receipt["ctf_analysis"]["ctf_decode"] = "not checked"
        self.receipt["ctf_analysis"]["cpu"] = {"status": "unavailable"}
        self.receipt["ctf_analysis"]["discarded_events_reported"] = 3
        (self.capture / "capture.json").write_text(json.dumps(self.receipt))
        result = reviewer.review(self.capture)
        self.assertEqual(result["status"], "blocked")
        self.assertTrue({"CTF_NOT_VALIDATED", "CTF_REPORTED_LOSS",
                         "TRACE_BUFFER_FULL", "CPU_METRICS_PARTIAL"}.issubset(self.codes(result)))

    def test_fault_file_is_not_falsely_correlated_or_unwound(self):
        log = self.root / "fatal.log"
        log.write_text("ZEPHYR FATAL ERROR\n#CD:BEGIN#\nPC: 0x00001234\n")
        result = reviewer.review(self.capture, fault_log=log)
        self.assertEqual(result["fault"]["fatal_markers"], 1)
        self.assertEqual(result["fault"]["matched_to_trace"], "not verified")
        self.assertIn("FAULT_CORRELATION_UNVERIFIED", self.codes(result))
        self.assertIn("COREDUMP_REQUIRES_DECODER", self.codes(result))
        self.assertNotIn("call_stack", result["fault"])

    def test_missing_and_symlinked_artifacts_are_rejected(self):
        (self.capture / "ctf/channel0_0").unlink()
        with self.assertRaisesRegex(ValueError, "Missing"):
            reviewer.review(self.capture)
        try:
            (self.capture / "ctf/channel0_0").symlink_to(self.build / "zephyr/zephyr.elf")
        except (OSError, NotImplementedError):
            self.skipTest("Windows runner does not permit creation of test symlinks")
        with self.assertRaisesRegex(ValueError, "symlinked"):
            reviewer.review(self.capture)

    def test_multiple_fatal_markers_are_ambiguous(self):
        result = reviewer.review_receipt(self.receipt, trace=self.trace, metadata=self.metadata,
                            fault_log=b"ZEPHYR FATAL ERROR\nZEPHYR FATAL ERROR\n")
        self.assertIn("FAULT_TEXT_AMBIGUOUS", self.codes(result))


if __name__ == "__main__":
    unittest.main()
