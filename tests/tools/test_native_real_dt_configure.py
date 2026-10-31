# SPDX-License-Identifier: Apache-2.0
"""Installed Eclipse resource: true configure, exact build evidence, no source writes."""
import contextlib
import importlib.util
import io
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
from unittest import TestCase, mock

RESOURCE = (Path(__file__).resolve().parents[2] /
            "plugins/io.github.niwantha33.zew.ui/scripts/dt_real_configure_inspect.py")
spec = importlib.util.spec_from_file_location("zew_native_real_dt", RESOURCE)
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)


class NativeRealConfigureTests(TestCase):
    def setUp(self):
        context = tempfile.TemporaryDirectory(prefix="zew-native-real-Ω ")
        self.addCleanup(context.cleanup)
        self.root = Path(context.name)
        self.app = self.root / "application"
        self.app.mkdir()
        (self.app / "src").mkdir()
        (self.app / "src/main.c").write_text("int main(void) { return 0; }\n", encoding="utf-8")
        (self.app / "CMakeLists.txt").write_text("project(sample)\n", encoding="utf-8")
        (self.app / ".zew-build/ci/zephyr").mkdir(parents=True)
        self.build = self.app / ".zew-build/ci"
        self.zephyr = self.root / "zephyr"
        self.zephyr.mkdir()
        (self.zephyr / "CMakeLists.txt").write_text("project(zephyr)\n", encoding="utf-8")
        self.board = "lp_mspm0g3507/mspm0g3507"
        self.dts = self.build / "zephyr/zephyr.dts"
        self.dts.write_text("/dts-v1/;\n/ { status = \"okay\"; };\n", encoding="utf-8")
        (self.build / "CMakeCache.txt").write_text(
            f"ZEPHYR_BASE:PATH={self.zephyr}\n"
            f"CACHED_BOARD:STRING={self.board}\n"
            "BOARD:STRING=lp_mspm0g3507\n", encoding="utf-8")
        self.overlay = self.app / "extra.overlay"
        self.overlay.write_text('&{/} { test-native-marker = "verified"; };\n',
                                encoding="utf-8")
        self.ninja = self.root / "ninja.exe"
        self.ninja.write_text("ninja", encoding="utf-8")
        self.args = SimpleNamespace(application=str(self.app), build=str(self.build),
                                    zephyr=str(self.zephyr), board=self.board,
                                    node="/", property="test-native-marker",
                                    overlay=str(self.overlay), ninja=str(self.ninja))

    def mock_cmake(self, success=True, match=True, marker=True, output="configured"):
        def emulate(command, **kwargs):
            self.assertEqual(command[1:4], ["-m", "west", "build"])
            self.assertIn("--cmake-only", command)
            self.assertIn("--pristine=always", command)
            self.assertNotIn("flash", command)
            self.assertNotIn("debug", command)
            self.assertIn("-DEXTRA_DTC_OVERLAY_FILE:STRING=", " ".join(command))
            scratch_build = Path(command[command.index("-d") + 1])
            self.assertNotEqual(scratch_build, self.build)
            self.assertTrue((scratch_build.parent / "app/src/main.c").is_file())
            self.assertFalse((scratch_build.parent / "app/.zew-build").exists())
            kwargs["stdout"].write(output.encode("utf-8"))
            if success:
                (scratch_build / "zephyr").mkdir(parents=True)
                (scratch_build / "zephyr/zephyr.dts").write_text(
                    '/dts-v1/;\n/ { test-native-marker = "verified"; };\n',
                    encoding="utf-8")
                (scratch_build / "CMakeCache.txt").write_text(
                    f"ZEPHYR_BASE:PATH={self.zephyr if match else self.root / 'other'}\n"
                    f"CACHED_BOARD:STRING={self.board}\n"
                    "BOARD:STRING=lp_mspm0g3507\n"
                    "EXTRA_DTC_OVERLAY_FILE:STRING=" + command[-1].split("=", 1)[1] + "\n",
                    encoding="utf-8")
            return SimpleNamespace(returncode=0 if success else 1)
        return emulate

    def test_true_configuration_preserves_source_and_dts(self):
        before = self.dts.read_bytes(), self.overlay.read_bytes()
        with mock.patch.object(native.subprocess, "run", side_effect=self.mock_cmake()), \
             mock.patch.object(native, "generated_node",
                               return_value=("observed", "observed")):
            result = native.assess(self.args)
        self.assertEqual(result[0], "configured")
        self.assertEqual(result[4:6], ("observed", "observed"))
        self.assertEqual(result[6], "additional")
        self.assertEqual((self.dts.read_bytes(), self.overlay.read_bytes()), before)
        self.assertFalse(any(p.name.startswith("zew-studio-real-dt-") for p in self.root.iterdir()))
        self.assertNotIn(str(self.root), str(result))

    def test_configure_without_generated_property_is_not_accepted(self):
        with mock.patch.object(native.subprocess, "run", side_effect=self.mock_cmake()), \
             mock.patch.object(native, "generated_node",
                               return_value=("observed", "not-observed")):
            result = native.assess(self.args)
        self.assertEqual(result[0], "incomplete")
        self.assertEqual(result[1], "node-or-property-unverified")

    def test_identity_mismatch_is_not_configured(self):
        with mock.patch.object(native.subprocess, "run",
                               side_effect=self.mock_cmake(match=False)):
            result = native.assess(self.args)
        self.assertEqual(result[0], "unavailable")
        self.assertEqual(result[1], "scratch-identity-unverified")

    def test_bad_original_board_prevents_any_cmake(self):
        (self.build / "CMakeCache.txt").write_text(
            f"ZEPHYR_BASE:PATH={self.zephyr}\nCACHED_BOARD:STRING=different\n",
            encoding="utf-8")
        with mock.patch.object(native.subprocess, "run") as process:
            with self.assertRaisesRegex(native.Rejected, "selected-build-identity-mismatch"):
                native.assess(self.args)
            process.assert_not_called()

    def test_failed_cmake_sanitises_error_text(self):
        with mock.patch.object(native.subprocess, "run",
                               side_effect=self.mock_cmake(success=False,
                                    output="extra.overlay:12:3: undefined node label TOP_SECRET")):
            result = native.assess(self.args)
        self.assertEqual(result[0], "failed")
        self.assertEqual(result[1], "unknown-node-or-label")
        self.assertEqual(result[9], 12)
        self.assertNotIn("TOP_SECRET", str(result))

    def test_outside_overlay_and_symlink_refused(self):
        outside = self.root / "outside.overlay"
        outside.write_text("private\n", encoding="utf-8")
        self.args.overlay = str(outside)
        with self.assertRaisesRegex(native.Rejected, "overlay-outside"):
            native.assess(self.args)
        self.args.overlay = str(self.overlay)
        link = self.app / "link.txt"
        try:
            link.symlink_to(self.root / "private.txt")
        except (OSError, NotImplementedError):
            self.skipTest("Symlink unavailable")
        with self.assertRaisesRegex(native.Rejected, "unsafe-input"):
            native.assess(self.args)

    def test_no_false_success_on_timeout(self):
        with mock.patch.object(native.subprocess, "run",
                               side_effect=native.subprocess.TimeoutExpired(["private"], 1)):
            result = native.assess(self.args)
        self.assertEqual(result[0], "unavailable")
        self.assertEqual(result[1], "configure-timeout-or-launch-failed")

    def test_protocol_is_ascii_and_no_user_controlled_source(self):
        example = ("failed", "unknown-node-or-label", "a" * 64, "0" * 64,
                   "unknown", "unknown", "additional", "b" * 64, 3, 12, "heuristic")
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            native.wire(example)
        self.assertTrue(output.getvalue().startswith("ZEW-REAL-DT-1\n"))
        self.assertEqual(len(output.getvalue().splitlines()[1].split("\t")), 11)
        self.assertNotIn(str(self.root), output.getvalue())
