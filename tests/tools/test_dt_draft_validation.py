# SPDX-License-Identifier: Apache-2.0
"""Opt-in unsaved source proposal goes only to disposable real-configure input."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
from types import SimpleNamespace
from unittest import mock
import tempfile
import unittest

from zew import main
from zew_evidence.collector import InputError
from zew_evidence import dt_build_validation as validator
from zew_evidence import dt_draft_validation as draft


class DraftCmakePreflightTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="zew-unsaved-draft-Ω ")
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.app = self.root / "user app"
        (self.app / "src").mkdir(parents=True)
        (self.app / "CMakeLists.txt").write_text("project(my_app)\n")
        (self.app / "src/main.c").write_text("int main() { return 0; }\n")
        self.overlay = self.app / "app.overlay"
        self.overlay.write_text("/* existing selected source */\n")
        self.build = self.app / ".zew-build/ci"
        (self.build / "zephyr").mkdir(parents=True)
        self.original_dts = self.build / "zephyr/zephyr.dts"
        self.original_dts.write_text('/dts-v1/;\n/ { soc { gpio@1234 { status = "okay"; }; }; };\n')
        self.zephyr = self.root / "zephyr"
        root = self.zephyr / "scripts/dts/python-devicetree/src/devicetree"
        root.mkdir(parents=True)
        (root / "__init__.py").write_text("")
        (root / "edtlib.py").write_text(
            "from types import SimpleNamespace as NS\n"
            "class EDT:\n"
            "    def __init__(self,dts,roots): pass\n"
            "    def get_node(self,path):\n"
            "        if path != '/soc/gpio@1234': raise KeyError(path)\n"
            "        p=lambda typ,enum=None:NS(spec=NS(type=typ,enum=enum,const=None,deprecated=False))\n"
            "        return NS(binding_path='/trusted/gpio.yaml',status='okay',buses=[],on_buses=[],\n"
            "                  props={'clock-frequency':p('int'),'mode':p('string',['low','high'])})\n")
        (root / "dtlib.py").write_text(
            "from pathlib import Path\n"
            "import re\n"
            "class V:\n"
            "    def __init__(self, source, name):\n"
            "        self.value=re.search(r'\\b'+re.escape(name)+r'\\s*=\\s*(?:\"([^\"]+)\"|<(\\d+)>)\\s*;',source)\n"
            "    def to_string(self):\n"
            "        if not self.value or self.value.group(1) is None: raise ValueError('not string')\n"
            "        return self.value.group(1)\n"
            "    def to_num(self):\n"
            "        if not self.value or self.value.group(2) is None: raise ValueError('not int')\n"
            "        return int(self.value.group(2))\n"
            "class DT:\n"
            "    def __init__(self, dts): self.text=Path(dts).read_text()\n"
            "    def get_node(self,p):\n"
            "        if p!='/soc/gpio@1234': raise ValueError('node missing')\n"
            "        return type('N',(),{'props':{key:V(self.text,key) for key in\n"
            "            ('status','clock-frequency','mode')}})()\n")
        (self.zephyr / "CMakeLists.txt").write_text("project(zephyr)\n")
        (self.zephyr / "dts/bindings").mkdir(parents=True)
        self.board = "lp_mspm0g3507/mspm0g3507"
        (self.build / "CMakeCache.txt").write_text(
            f"ZEPHYR_BASE:PATH={self.zephyr}\n"
            f"CACHED_BOARD:STRING={self.board}\n"
            f"BOARD_TARGET:STRING={self.board}\n"
            "BOARD:STRING=lp_mspm0g3507\n")
        self.python = self.root / "managed-python"
        self.python.write_bytes(b"python")
        self.scratch_parent = self.root / "scratch-parent"
        self.scratch_parent.mkdir()
        self.configured = "disabled"
        self.calls = 0

    def fake_west(self, cmd, **kwargs):
        self.calls += 1
        build = Path(cmd[cmd.index("-d") + 1])
        copied = build.parent / "application"
        original_source = (copied / "app.overlay").read_text()
        self.assertIn('status = "disabled"', original_source)
        if self.overlay.exists():
            self.assertNotIn('status = "disabled"', self.overlay.read_text())
        self.assertNotEqual(build, self.build)
        self.assertFalse((copied / ".zew-build").exists())
        self.assertEqual(kwargs["stdin"], validator.subprocess.DEVNULL)
        (build / "zephyr").mkdir(parents=True)
        # Actual real CMake is exercised separately by Windows CI; this fake
        # checks that the selected dtlib compares the *generated* value.
        (build / "zephyr/zephyr.dts").write_text(
            f'/dts-v1/;\n/ {{ soc {{ gpio@1234 {{ status = "{self.configured}"; }}; }}; }};\n')
        (build / "CMakeCache.txt").write_text(
            f"ZEPHYR_BASE:PATH={self.zephyr}\n"
            f"CACHED_BOARD:STRING={self.board}\n"
            "BOARD:STRING=lp_mspm0g3507\n"
            f'EXTRA_DTC_OVERLAY_FILE:STRING={copied / "app.overlay"}\n')
        kwargs["stdout"].write(b"selected Zephyr CMake configure invoked\n")
        return SimpleNamespace(returncode=0)

    def check(self, **request):
        with mock.patch.object(validator.subprocess, "run", side_effect=self.fake_west):
            return draft.preflight(
                self.build, self.app, self.zephyr, self.python,
                self.board, "/soc/gpio@1234", self.overlay,
                scratch_parent=self.scratch_parent, **request)

    def test_unsaved_status_requires_real_generated_value_and_no_mutations(self):
        original = (self.original_dts.read_bytes(), self.overlay.read_bytes(),
                    (self.app / "src/main.c").read_bytes())
        result = self.check(status="disabled", kind="gpio")
        self.assertEqual(result["status"], "configured-in-scratch")
        self.assertEqual(result["post_generated_value"]["state"], "exact-value-observed")
        self.assertEqual(result["guide"]["binding"], "matched")
        self.assertTrue(result["real_configure"]["overlay"]["draft_staged_in_scratch_only"])
        self.assertEqual(result["real_configure"]["overlay"]["prepared_sha256"],
                         result["guide"]["proposed_overlay_sha256"])
        self.assertEqual(result["real_configure"]["generated_observation"]["state"], "present")
        self.assertEqual((self.original_dts.read_bytes(), self.overlay.read_bytes(),
                          (self.app / "src/main.c").read_bytes()), original)
        self.assertEqual(list(self.scratch_parent.iterdir()), [])
        output = json.dumps(result)
        for private in ("existing selected source", "/soc/gpio@1234", str(self.root)):
            self.assertNotIn(private, output)
        self.assertEqual(self.calls, 1)

    def test_west_success_wrong_value_rejected(self):
        self.configured = "okay"
        result = self.check(status="disabled")
        self.assertEqual(result["status"], "expected-devicetree-evidence-unavailable")
        self.assertEqual(result["post_generated_value"]["state"], "unavailable-or-mismatched")
        self.assertEqual(result["real_configure"]["generated_observation"]["state"], "not-present")

    def test_unmatched_binding_or_wrong_property_never_launches_west(self):
        bad = self.check(integer="not-a-property=42")
        self.assertEqual(bad["status"], "manual-review-required")
        self.assertIsNone(bad["real_configure"])
        self.assertEqual(self.calls, 0)

    def test_changed_overlay_hash_rejected_before_west(self):
        before = self.overlay.read_bytes()
        with mock.patch.object(draft, "guide", wraps=draft.guide) as original_guide:
            def race(*args, **kwargs):
                result = original_guide(*args, **kwargs)
                self.overlay.write_text("/* changed while drafting */\n")
                return result
            with mock.patch.object(draft, "guide", side_effect=race):
                with self.assertRaisesRegex(InputError, "overlay"):
                    self.check(status="disabled")
        self.assertEqual(self.calls, 0)
        self.assertNotEqual(self.overlay.read_bytes(), before)

    def test_new_app_overlay_exists_only_inside_temp_copy(self):
        self.overlay.unlink()
        result = self.check(status="disabled")
        self.assertEqual(result["status"], "configured-in-scratch")
        self.assertFalse(self.overlay.exists())
        self.assertEqual(result["guide"]["original_overlay_sha256"], None)

    def test_numeric_enum_and_cli_help_boundary(self):
        # The exact generator is tested under the real CMake witness on MSPM0;
        # here assert that a mismatched proposal cannot produce acceptance.
        failed = self.check(enum="mode=invalid")
        self.assertEqual(failed["status"], "manual-review-required")
        args = ["dt-draft-configure", "--build", str(self.build),
                "--application", str(self.app), "--zephyr", str(self.zephyr),
                "--python", str(self.python), "--board", self.board,
                "--node", "/soc/gpio@1234", "--overlay", str(self.overlay),
                "--status", "disabled", "--scratch-parent", str(self.scratch_parent)]
        with mock.patch.object(validator.subprocess, "run", side_effect=self.fake_west):
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(main(args), 0)
        self.assertEqual(json.loads(output.getvalue())["status"], "configured-in-scratch")

    def test_actual_dts_reader_checks_type_and_value(self):
        scratch = self.root / "generated.dts"
        scratch.write_text('/dts-v1/; / { soc { gpio@1234 { status = "disabled"; '
                           'clock-frequency = <400000>; mode = "high"; }; }; };\n')
        self.assertTrue(validator._observed_generated(
            self.zephyr, scratch, "/soc/gpio@1234", "status", ("status", "disabled")))
        self.assertTrue(validator._observed_generated(
            self.zephyr, scratch, "/soc/gpio@1234", "clock-frequency", ("int", 400000)))
        self.assertFalse(validator._observed_generated(
            self.zephyr, scratch, "/soc/gpio@1234", "clock-frequency", ("int", 100000)))
        self.assertTrue(validator._observed_generated(
            self.zephyr, scratch, "/soc/gpio@1234", "mode", ("enum", "high")))
        self.assertFalse(validator._observed_generated(
            self.zephyr, scratch, "/soc/gpio@1234", "mode", ("enum", "low")))


if __name__ == "__main__":
    unittest.main()
