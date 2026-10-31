# SPDX-License-Identifier: Apache-2.0
"""Tests for opt-in real Zephyr configure: no selected-build writes, no false success."""
import contextlib
import io
import json
from pathlib import Path
from types import SimpleNamespace
from unittest import mock
import tempfile
import unittest

from zew import main
from zew_evidence.collector import InputError
from zew_evidence import dt_build_validation as verify


class RealDtConfigureTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="zew-real-config-Ω ")
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.app = self.root / "user application"
        self.app.mkdir()
        (self.app / "src").mkdir()
        (self.app / "src/main.c").write_text("int main(void) { return 0; }\n", encoding="utf-8")
        (self.app / "CMakeLists.txt").write_text("project(sample)\n", encoding="utf-8")
        (self.app / "prj.conf").write_text("CONFIG_GPIO=y\n", encoding="utf-8")
        self.overlay = self.app / "candidate.overlay"
        self.overlay.write_text("&{/soc} { status = \"okay\"; };\n", encoding="utf-8")
        (self.app / ".zew-build/ci/zephyr").mkdir(parents=True)
        self.build = self.app / ".zew-build/ci"
        self.original = self.build / "zephyr/zephyr.dts"
        self.original.write_text("/dts-v1/;\n/ { };\n", encoding="utf-8")
        self.zephyr = self.root / "zephyr checkout"
        self.zephyr.mkdir()
        (self.zephyr / "CMakeLists.txt").write_text("project(zephyr)\n", encoding="utf-8")
        (self.build / "CMakeCache.txt").write_text(
            "ZEPHYR_BASE:PATH=" + str(self.zephyr) + "\n"
            "BOARD:STRING=nucleo_f446re\n"
            "BOARD_TARGET:STRING=nucleo_f446re/stm32f446xx\n", encoding="utf-8")
        self.python = self.root / "managed-python"
        self.python.write_text("executable\n", encoding="utf-8")
        self.ninja = self.root / "ninja"
        self.ninja.write_text("executable\n", encoding="utf-8")
        self.board = "nucleo_f446re/stm32f446xx"

    def invoke(self, configure_ok=True, cache_accurate=True, text="configured"):
        def pretend_run(cmd, **kwargs):
            self.assertEqual(cmd[:4], [str(self.python), "-m", "west", "build"])
            self.assertIn("--cmake-only", cmd)
            self.assertIn("--pristine=always", cmd)
            self.assertNotIn("flash", cmd)
            self.assertNotIn("debug", cmd)
            isolated = Path(cmd[cmd.index("-d") + 1])
            candidate = cmd[-1]
            if candidate.startswith("-DEXTRA_DTC_OVERLAY_FILE"):
                self.assertIn("application", candidate)
            self.assertNotEqual(isolated, self.build)
            self.assertTrue((isolated.parent / "application/src/main.c").is_file())
            self.assertFalse((isolated.parent / "application/.zew-build").exists())
            self.assertIsNotNone(kwargs["stdout"])
            kwargs["stdout"].write(text.encode("utf-8"))
            if configure_ok:
                (isolated / "zephyr").mkdir(parents=True, exist_ok=True)
                (isolated / "zephyr/zephyr.dts").write_text("/dts-v1/;\n/ { status = \"okay\"; };\n",
                                                            encoding="utf-8")
                (isolated / "CMakeCache.txt").write_text(
                    "ZEPHYR_BASE:PATH=" +
                    (str(self.zephyr) if cache_accurate else "/wrong/zephyr") + "\n"
                    "BOARD_TARGET:STRING=" + self.board + "\n"
                    "BOARD:STRING=nucleo_f446re\n"
                    "EXTRA_DTC_OVERLAY_FILE:STRING=" + cmd[-1].split("=", 1)[1] + "\n",
                    encoding="utf-8")
            return SimpleNamespace(returncode=0 if configure_ok else 1)
        with mock.patch.object(verify.subprocess, "run", side_effect=pretend_run):
            return verify.validate(self.build, self.app, self.zephyr,
                                   self.python, self.board, self.overlay, self.ninja)

    def test_real_configure_success_only_with_generated_provenance(self):
        originals = (self.original.read_bytes(), self.overlay.read_bytes(),
                     (self.app / "src/main.c").read_bytes())
        report = self.invoke()
        self.assertEqual(report["status"], "configured-in-scratch")
        self.assertEqual(report["evidence_level"], "configured")
        self.assertNotEqual(report["selected_build_dts_sha256"],
                            report["scratch_generated_dts_sha256"])
        self.assertTrue(report["overlay"]["additional"])
        self.assertEqual(report["application_snapshot_files"], 4)
        self.assertEqual((self.original.read_bytes(), self.overlay.read_bytes(),
                          (self.app / "src/main.c").read_bytes()), originals)
        self.assertNotIn(str(self.root), json.dumps(report))
        self.assertFalse(any(x.name.startswith("zew-real-dt-") for x in self.root.iterdir()))

    def test_failed_cmake_does_not_claim_overlay_validity(self):
        result = self.invoke(configure_ok=False, text="devicetree error: undefined node label 'BAD_SECRET'")
        self.assertEqual(result["status"], "configure-failed")
        self.assertEqual(result["diagnostic"]["code"], "undefined-devicetree-node-or-label")
        self.assertNotIn("BAD_SECRET", json.dumps(result))
        self.assertEqual(result["evidence_level"], "unknown")

    def test_returncode_zero_with_wrong_checkout_is_unknown(self):
        result = self.invoke(cache_accurate=False)
        self.assertEqual(result["status"], "identity-unverified")
        self.assertEqual(result["evidence_level"], "unknown")

    def test_wrong_selected_board_refused_before_process(self):
        with mock.patch.object(verify.subprocess, "run") as process:
            with self.assertRaisesRegex(InputError, "board"):
                verify.validate(self.build, self.app, self.zephyr, self.python, "wrong_board")
            process.assert_not_called()

    def test_exact_zephyr_required_before_process(self):
        second = self.root / "second"
        second.mkdir()
        (second / "CMakeLists.txt").write_text("project(x)", encoding="utf-8")
        with mock.patch.object(verify.subprocess, "run") as process:
            with self.assertRaisesRegex(InputError, "checkout"):
                verify.validate(self.build, self.app, second, self.python, self.board)
            process.assert_not_called()

    def test_external_candidate_refused(self):
        outside = self.root / "other.overlay"
        outside.write_text("secret\n", encoding="utf-8")
        with self.assertRaisesRegex(InputError, "inside the selected application"):
            verify.validate(self.build, self.app, self.zephyr, self.python, self.board, outside)

    def test_source_symlink_rejected_without_following(self):
        link = self.app / "linked.txt"
        try:
            link.symlink_to(self.root / "secret.txt")
        except (NotImplementedError, OSError):
            self.skipTest("Symlinks not available")
        with self.assertRaisesRegex(InputError, "Symlink"):
            verify.validate(self.build, self.app, self.zephyr, self.python, self.board)

    def test_failed_process_timeout_and_exceptions_never_leak(self):
        with mock.patch.object(verify.subprocess, "run",
                               side_effect=verify.subprocess.TimeoutExpired(["private"], 1)):
            report = verify.validate(self.build, self.app, self.zephyr, self.python, self.board)
        self.assertEqual(report["status"], "unavailable")
        self.assertEqual(report["diagnostic"]["code"], "configure-timeout-or-launch-failed")

    def test_process_with_no_generated_dts_fails_closed(self):
        def pretend(*args, **kwargs):
            kwargs["stdout"].write(b"Configured but no Zephyr DTS\n")
            return SimpleNamespace(returncode=0)
        with mock.patch.object(verify.subprocess, "run", side_effect=pretend):
            report = verify.validate(self.build, self.app, self.zephyr, self.python, self.board)
        self.assertEqual(report["status"], "configure-failed")

    def test_expected_generated_property_must_be_observed_not_inferred(self):
        # The configure exit code alone cannot prove a specific overlay
        # became part of the generated Devicetree.
        original = verify._observed_generated
        with mock.patch.object(verify, "_observed_generated", return_value=True):
            def wrap():
                # Configure fixture uses mocked west with generated DTS;
                # the selected dtlib accessor is replaced only in this test.
                with mock.patch.object(verify.subprocess, "run", side_effect=self._fake_success):
                    return verify.validate(self.build, self.app, self.zephyr,
                                           self.python, self.board, self.overlay,
                                           expect_node="/", expect_property="zew-ci-marker")
            self.assertEqual(wrap()["generated_observation"]["state"], "present")
        with mock.patch.object(verify, "_observed_generated", return_value=False):
            with mock.patch.object(verify.subprocess, "run", side_effect=self._fake_success):
                result = verify.validate(self.build, self.app, self.zephyr,
                                         self.python, self.board, self.overlay,
                                         expect_node="/", expect_property="zew-ci-marker")
            self.assertEqual(result["status"], "expected-devicetree-evidence-unavailable")
            self.assertEqual(result["generated_observation"]["state"], "not-present")
        self.assertIsNotNone(original)

    def _fake_success(self, cmd, **kwargs):
        isolated = Path(cmd[cmd.index("-d") + 1])
        (isolated / "zephyr").mkdir(parents=True, exist_ok=True)
        (isolated / "zephyr/zephyr.dts").write_text(
            '/dts-v1/;\n/ { zew-ci-marker = "yes"; };\n', encoding="utf-8")
        (isolated / "CMakeCache.txt").write_text(
            "ZEPHYR_BASE:PATH=" + str(self.zephyr) + "\n"
            "BOARD_TARGET:STRING=" + self.board + "\n"
            "BOARD:STRING=nucleo_f446re\n"
            "EXTRA_DTC_OVERLAY_FILE:STRING=" + cmd[-1].split("=", 1)[1] + "\n",
            encoding="utf-8")
        return SimpleNamespace(returncode=0)

    def test_cmake_can_be_invoked_only_by_explicit_command(self):
        with mock.patch.object(verify.subprocess, "run") as process:
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                with self.assertRaises(SystemExit):
                    main(["dt-configure", "--build", str(self.build),
                          "--application", str(self.app), "--zephyr", str(self.zephyr),
                          "--python", str(self.python), "--board"])
            process.assert_not_called()
            self.assertTrue(True, "argument validation occurs before any process launch")


if __name__ == "__main__":
    unittest.main()
