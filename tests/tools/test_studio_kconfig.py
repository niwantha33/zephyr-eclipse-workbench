# SPDX-License-Identifier: Apache-2.0
import contextlib
import hashlib
import importlib.util
import inspect
import json
from pathlib import Path
import shutil
import tempfile
import types
import unittest
from unittest import mock

import kconfiglib

from zew_evidence import studio_kconfig as studio
from zew_evidence.kconfig_causality import causality

ROOT = Path(__file__).resolve().parents[2]


class StudioKconfigTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="cs-kconfig-Ω ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.zephyr = self.root / "zephyr"
        lib = self.zephyr / "scripts/kconfig"
        lib.mkdir(parents=True)
        shutil.copyfile(kconfiglib.__file__, lib / "kconfiglib.py")
        self.kroot = self.zephyr / "Kconfig"
        self.kroot.write_text('''mainmenu "Fixture"
config BASE
    bool "Base"
config CHILD
    bool "Child"
    depends on BASE
config VISIBLE
    bool "Visible"
config HIDDEN
    bool
config SECRET
    string "Private"
    default "NEVER_EXPORT_THIS"
config LEAK
    bool "Leak check"
    depends on SECRET = "NEVER_EXPORT_THIS"
''')
        self.tree = kconfiglib.Kconfig(str(self.kroot), warn_to_stderr=False)
        self.build = self.root / "build"
        (self.build / "zephyr").mkdir(parents=True)
        self.config = self.build / "zephyr/.config"
        self.tree.write_config(str(self.config))
        (self.build / "build.ninja").write_text("# fixture\n")
        self.snippet = self.root / "prj.conf"
        self.ninja = self.root / "ninja"
        self.ninja.write_text("# mocked tool\n")

    def evaluate(self, content, run=None):
        self.snippet.write_text(content)
        command = (f'cmake -E env "srctree={self.zephyr}" "KCONFIG_BINARY_DIR={self.build}" '
                   f'"EDT_PICKLE={self.build}/zephyr/edt.pickle" /unused/guiconfig.py "{self.kroot}"')
        with mock.patch.object(studio.subprocess, "run", side_effect=run,
                               return_value=types.SimpleNamespace(stdout=command)):
            return studio.inspect(self.build, self.snippet, ninja=self.ninja)

    def test_all_core_diagnostic_classes_use_actual_upstream(self):
        result = self.evaluate("CONFIG_UNKNOWN=y\nCONFIG_CHILD=y\nCONFIG_HIDDEN=y\n"
                               "CONFIG_VISIBLE=n\nCONFIG_BASE=y\nCONFIG_BASE=n\n")
        self.assertEqual(result["status"], "evaluated")
        findings = {r["symbol"]: r for r in result["findings"]}
        self.assertEqual(findings["CONFIG_UNKNOWN"]["rule_id"], "CS-KCONFIG-UNDEFINED")
        self.assertEqual(findings["CONFIG_CHILD"]["rule_id"], "CS-KCONFIG-UNMET-DEPENDENCY")
        self.assertEqual(findings["CONFIG_HIDDEN"]["rule_id"], "CS-KCONFIG-INVISIBLE")
        self.assertEqual(findings["CONFIG_VISIBLE"]["rule_id"], "CS-KCONFIG-VALID")
        self.assertEqual(findings["CONFIG_BASE"]["rule_id"], "CS-KCONFIG-CONFLICTING-REQUESTS")
        self.assertEqual(findings["CONFIG_CHILD"]["evidence"][0]["line"], 2)
        self.assertTrue(all(r["evidence"][0]["sha256"] for r in result["findings"]))
        graph = next(g for g in result["images"][0]["graphs"] if g["root"] == "CHILD")
        self.assertIn("BASE", {n["symbol"] for n in graph["nodes"]})

    def test_no_strings_leak_and_reports_are_stable(self):
        a = self.evaluate('CONFIG_LEAK=y\nCONFIG_SECRET="NEVER_EXPORT_THIS"\n')
        b = self.evaluate('CONFIG_LEAK=y\nCONFIG_SECRET="NEVER_EXPORT_THIS"\n')
        self.assertEqual(a, b)
        self.assertNotIn("NEVER_EXPORT_THIS", json.dumps(a))
        self.assertEqual(a["images"][0]["freshness"], "unverified")

    def test_native_and_headless_causality_algorithms_are_identical(self):
        spec = importlib.util.spec_from_file_location("cs_probe", ROOT / "plugins/io.github.niwantha33.zew.ui/scripts/kconfig_inspect.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.assertEqual(inspect.getsource(module.causality).strip(), inspect.getsource(causality).strip())

    def test_failure_no_generated_input_and_no_write(self):
        self.config.unlink()
        original = self.kroot.read_bytes()
        report = self.evaluate("CONFIG_CHILD=y\n")
        self.assertEqual(report["status"], "partial")
        self.assertEqual(report["findings"][0]["confidence"], "unknown")
        self.assertEqual(self.kroot.read_bytes(), original)

    def test_changed_source_discards_evaluation(self):
        original = studio._inputs
        calls = []
        def changing(*args):
            result = original(*args)
            calls.append(1)
            if len(calls) == 1:
                self.kroot.write_text(self.kroot.read_text() + "\n# changed\n")
            return result
        with mock.patch.object(studio, "_inputs", side_effect=changing):
            result = self.evaluate("CONFIG_VISIBLE=y\n")
        self.assertEqual(result["images"][0]["state"], "unavailable")
        self.assertEqual(result["findings"][0]["rule_id"], "CS-KCONFIG-UNKNOWN")

    def test_unavailable_tool_does_not_fabricate_semantics(self):
        with mock.patch.object(studio.subprocess, "run", side_effect=OSError("private path")):
            result = studio.inspect(self.build, symbol="CHILD", ninja=self.ninja)
        self.assertEqual(result["status"], "partial")
        self.assertNotIn("private path", json.dumps(result))


if __name__ == "__main__":
    unittest.main()
