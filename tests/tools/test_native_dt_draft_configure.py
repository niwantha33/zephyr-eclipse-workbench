# SPDX-License-Identifier: Apache-2.0
"""Installed native unsaved CMake adapter source parity and fail-closed protocol."""
import base64
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "plugins/io.github.niwantha33.zew.ui/scripts"
SPEC = importlib.util.spec_from_file_location(
    "native_unsaved_cmake_adapter", SCRIPTS / "dt_draft_configure_inspect.py")
adapter = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(adapter)


class InstalledDraftCmakeTests(unittest.TestCase):
    def test_installed_logic_identical_to_headless_contract(self):
        for name in ("__init__.py", "collector.py", "dt_binding_preview.py",
                     "peripheral_guide.py", "dt_build_validation.py",
                     "dt_draft_validation.py"):
            with self.subTest(name=name):
                self.assertEqual((ROOT / "zew_evidence" / name).read_bytes(),
                    (SCRIPTS / "zew_evidence" / name).read_bytes(),
                    "Installed UI module drifted from authoritative headless core: " + name)

    def test_success_requires_selected_exact_value_and_staged_proposal(self):
        node, prop = "/soc/gpio@1234", "status"
        nodehash = hashlib.sha256(node.encode()).hexdigest()
        prophash = hashlib.sha256(prop.encode()).hexdigest()
        draft = "a" * 64
        report = {"status": "configured-in-scratch",
                  "guide": {"generated_dts_sha256": "b"*64,
                            "original_overlay_sha256": None,
                            "proposed_overlay_sha256": draft,
                            "source_addition_sha256": "d"*64,
                            "node_sha256": nodehash,
                            "property_sha256": prophash},
                  "real_configure": {
                      "scratch_generated_dts_sha256": "c"*64,
                      "diagnostic": {"code": "real-zephyr-cmake-accepted"},
                      "overlay": {"draft_staged_in_scratch_only": True,
                                  "prepared_sha256": draft},
                      "application_snapshot_files": 4},
                  "post_generated_value": {"state": "exact-value-observed"}}
        fields = adapter.evidence(report, node, prop)
        self.assertEqual(fields[0:2], ["configured", "real-zephyr-cmake-accepted"])
        self.assertEqual(fields[9:12],
                         ["exact-value-observed", "staged", "identity-verified"])
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            adapter.wire(fields)
        self.assertEqual(output.getvalue().splitlines()[0], "ZEW-DRAFT-CMAKE-1")
        self.assertEqual(len(output.getvalue().splitlines()[1].split("\t")), 13)
        self.assertNotIn(node, output.getvalue())
        self.assertNotIn(prop, output.getvalue())
        not_generated = dict(report)
        not_generated["post_generated_value"] = {"state": "unavailable-or-mismatched"}
        with self.assertRaisesRegex(Exception, "Unproven"):
            adapter.evidence(not_generated, node, prop)
        not_staged = dict(report)
        not_staged["real_configure"] = {
            **report["real_configure"],
            "overlay": {"draft_staged_in_scratch_only": False,
                        "prepared_sha256": draft}}
        with self.assertRaisesRegex(Exception, "Unproven"):
            adapter.evidence(not_staged, node, prop)

    def test_mismatched_review_cannot_launch_cmake(self):
        with tempfile.TemporaryDirectory(prefix="zew-Ω-native-") as root:
            root = Path(root)
            args = ["--build", str(root), "--application", str(root),
                    "--zephyr", str(root), "--node", "/soc/gpio@1234",
                    "--overlay", str(root / "app.overlay"),
                    "--board", "lp_mspm0g3507/mspm0g3507",
                    "--expected-dts-sha256", "b"*64,
                    "--expected-overlay-sha256", "0"*64,
                    "--expected-addition-sha256", "d"*64,
                    "--status", "disabled"]
            output = io.StringIO()
            with mock.patch.object(adapter, "preflight",
                                   side_effect=adapter.InputError(
                                       "Reviewed guided draft changed; request a fresh source preview")) as proc:
                with contextlib.redirect_stdout(output):
                    self.assertEqual(adapter.main(args), 1)
                proc.assert_called_once()
            lines = output.getvalue().splitlines()
            self.assertEqual(lines[0], "ZEW-DRAFT-CMAKE-1")
            values = [base64.b64decode(item).decode("ascii")
                      for item in lines[1].split("\t")]
            self.assertEqual(len(values), 13)
            self.assertEqual(values[0:2], ["unavailable", "reviewed-draft-changed"])
            self.assertEqual(values[9], "not-evaluated")


if __name__ == "__main__":
    unittest.main()
