# SPDX-License-Identifier: Apache-2.0
"""Safety/plumbing fixtures; actual Zephyr Kconfiglib is exercised in Windows firmware CI."""
import contextlib
import io
import json
from pathlib import Path
from types import SimpleNamespace
from unittest import mock
import tempfile
import unittest

from zew import main
from zew_evidence.collector import InputError, collect
from zew_evidence.config_request_check import check
from zew_evidence import config_semantics as semantic


class FakeSymbol:
    def __init__(self, value="y", assignable=(0, 2), symbol_type=1):
        self.type = symbol_type
        self.str_value = value
        self.assignable = assignable
        self.direct_dep = 2
        self.visibility = 2
        self.nodes = [1]


class FakeLib:
    BOOL = 1
    TYPE_TO_STR = {1: "bool", 2: "string"}
    TRI_TO_STR = {0: "n", 1: "m", 2: "y"}

    @staticmethod
    def expr_value(dependency):
        return dependency

    class Kconfig:
        def __init__(self, source, warn_to_stderr=False):
            self.syms = {
                "GPIO": FakeSymbol(),
                "OFF": FakeSymbol(value="n", assignable=(0,)),
                "SELECTED": FakeSymbol(value="y", assignable=()),
                "TEXT": FakeSymbol(value="private", symbol_type=2),
            }

        def load_config(self, source):
            self.source = source


class KconfigSemanticTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="zew-semantic-Ω ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.build = self.root / "build"
        (self.build / "zephyr").mkdir(parents=True)
        (self.build / "zephyr/.config").write_text(
            "CONFIG_GPIO=y\n# CONFIG_OFF is not set\nCONFIG_SELECTED=y\n"
            'CONFIG_TEXT="private-generated"\n', encoding="utf-8")
        (self.build / "zephyr/zephyr.dts").write_text("/ { };\n", encoding="utf-8")
        (self.build / "build.ninja").write_text("# generated Ninja fixture\n", encoding="utf-8")
        self.source = self.root / "zephyr checkout"
        self.source.mkdir()
        self.kroot = self.source / "Kconfig"
        self.kroot.write_text("mainmenu \"test\"\n", encoding="utf-8")
        self.ninja = self.root / "ninja"
        self.ninja.write_text("fixture\n", encoding="utf-8")
        self.snippet = self.root / "snippet.conf"

    def commands(self):
        return ("cmake -E env srctree=\"" + str(self.source) + "\" "
                "KCONFIG_BINARY_DIR=\"" + str(self.build) + "\" "
                "EDT_PICKLE=generated /unused/guiconfig.py \"" + str(self.kroot) + "\"\n")

    def enriched(self, snippet):
        self.snippet.write_text(snippet, encoding="utf-8")
        base = check(collect(self.build), self.snippet)
        with mock.patch.object(semantic.subprocess, "run",
                               return_value=SimpleNamespace(stdout=self.commands())), \
             mock.patch.object(semantic, "_load_selected_kconfiglib", return_value=FakeLib):
            result = semantic.enrich(base, self.build, [], self.ninja)
        return result

    def test_exact_kconfig_metadata_and_unassignable_requests(self):
        result = self.enriched("CONFIG_GPIO=y\nCONFIG_OFF=y\nCONFIG_SELECTED=y\n"
                               "CONFIG_DOES_NOT_EXIST=y\n")
        by_name = {r["symbol"]: r["kconfiglib"]
                   for r in result["images"][0]["requests"]}
        self.assertEqual(by_name["CONFIG_GPIO"]["request_state"], "assignable-in-current-state")
        self.assertEqual(by_name["CONFIG_OFF"]["request_state"], "request-not-assignable-in-current-state")
        self.assertEqual(by_name["CONFIG_SELECTED"]["request_state"], "effective-but-not-user-assignable")
        self.assertEqual(by_name["CONFIG_DOES_NOT_EXIST"]["state"], "undefined-in-selected-tree")
        self.assertEqual(by_name["CONFIG_GPIO"]["dependency_value"], "y")
        self.assertEqual(result["semantic_validation"]["state"], "incomplete-or-blocked")
        self.assertEqual(result["semantic_validation"]["unresolved"], 2)
        self.assertEqual(result["images"][0]["semantic"]["state"], "evaluated")

    def test_non_boolean_does_not_disclose_values_or_assume_validity(self):
        result = self.enriched('CONFIG_TEXT="private-requested"\nCONFIG_GPIO=y\n')
        value = next(r for r in result["images"][0]["requests"] if r["symbol"] == "CONFIG_TEXT")
        self.assertEqual(value["kconfiglib"]["request_state"], "non-boolean-type")
        raw = json.dumps(result)
        self.assertNotIn("private-requested", raw)
        self.assertNotIn("private-generated", raw)
        self.assertNotIn(str(self.root), raw)

    def test_missing_generated_ninja_reports_unknown_without_running(self):
        self.snippet.write_text("CONFIG_GPIO=y\n", encoding="utf-8")
        (self.build / "build.ninja").unlink()
        with mock.patch.object(semantic.subprocess, "run") as process:
            output = semantic.enrich(check(collect(self.build), self.snippet),
                                     self.build, [], self.ninja)
        process.assert_not_called()
        self.assertEqual(output["images"][0]["semantic"]["state"], "unavailable")
        self.assertEqual(output["images"][0]["requests"][0]["kconfiglib"]["state"], "unknown")
        self.assertEqual(output["semantic_validation"]["state"], "incomplete-or-blocked")

    def test_generated_environment_is_not_executed_or_exported(self):
        values, root = semantic._generated_environment(self.commands())
        self.assertEqual(root, self.kroot)
        self.assertEqual(values["srctree"], str(self.source))
        with self.assertRaises(InputError):
            semantic._generated_environment("cmake -E env srctree=bad /a/guiconfig.py /not/there")

    def test_windows_nested_cmd_wrapper_with_quoted_kconfig_paths(self):
        # Mirror the non-POSIX Ninja quoting already covered by the installed
        # kconfig_inspect.py parser. Do not execute any recovered commands.
        command = (
            r'C:\Windows\system32\cmd.exe /C "cd /D C:\build && '
            r'"C:\Program Files\CMake\bin\cmake.exe" -E env '
            r'"srctree=C:\Zephyr Workspace\zephyr" '
            r'"KCONFIG_BINARY_DIR=C:\Build Folder\zephyr\Kconfig" '
            r'"EDT_PICKLE=C:\Build Folder\zephyr\edt.pickle" '
            r'"C:\Python Tools\python.exe" '
            r'"C:\Zephyr Workspace\zephyr\scripts\kconfig\guiconfig.py" '
            f'"{self.kroot}""')
        values, source = semantic._generated_environment(command)
        self.assertEqual(source, self.kroot)
        self.assertEqual(values["srctree"], r"C:\Zephyr Workspace\zephyr")
        self.assertEqual(values["KCONFIG_BINARY_DIR"], r"C:\Build Folder\zephyr\Kconfig")
        self.assertEqual(values["EDT_PICKLE"], r"C:\Build Folder\zephyr\edt.pickle")

    def test_changed_config_cannot_be_confirmed(self):
        self.snippet.write_text("CONFIG_GPIO=y\n", encoding="utf-8")
        graph = collect(self.build)
        result = check(graph, self.snippet)
        (self.build / "zephyr/.config").write_text("CONFIG_GPIO=n\n", encoding="utf-8")
        with mock.patch.object(semantic.subprocess, "run") as ninja:
            checked = semantic.enrich(result, self.build, [], self.ninja)
        ninja.assert_not_called()
        self.assertEqual(checked["images"][0]["semantic"]["reason"], "generated-config-changed")

    def test_ninja_inspection_failure_has_no_host_leak(self):
        self.snippet.write_text("CONFIG_GPIO=y\n", encoding="utf-8")
        report = check(collect(self.build), self.snippet)
        with mock.patch.object(semantic.subprocess, "run", side_effect=OSError(str(self.root))):
            checked = semantic.enrich(report, self.build, [], self.ninja)
        self.assertNotIn(str(self.root), json.dumps(checked))
        self.assertEqual(checked["images"][0]["semantic"]["reason"], "ninja-command-inspection-failed")

    def test_cli_semantic_success_and_rejection(self):
        self.snippet.write_text("CONFIG_GPIO=y\n", encoding="utf-8")
        with mock.patch.object(semantic.subprocess, "run",
                               return_value=SimpleNamespace(stdout=self.commands())), \
             mock.patch.object(semantic, "_load_selected_kconfiglib", return_value=FakeLib):
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = main(["config-check", "--build", str(self.build),
                             "--snippet", str(self.snippet), "--semantic",
                             "--ninja", str(self.ninja)])
        self.assertEqual(code, 0)
        payload = json.loads(out.getvalue())
        self.assertEqual(payload["semantic_validation"]["state"], "evaluated-without-blockers")
        self.snippet.write_text("CONFIG_UNDEFINED=y\n", encoding="utf-8")
        with mock.patch.object(semantic.subprocess, "run",
                               return_value=SimpleNamespace(stdout=self.commands())), \
             mock.patch.object(semantic, "_load_selected_kconfiglib", return_value=FakeLib):
            with contextlib.redirect_stdout(io.StringIO()):
                code = main(["config-check", "--build", str(self.build),
                             "--snippet", str(self.snippet), "--semantic",
                             "--ninja", str(self.ninja)])
        self.assertEqual(code, 1)

    def test_semantic_flag_not_required_for_existing_snapshot_cli(self):
        self.snippet.write_text("CONFIG_GPIO=y\n", encoding="utf-8")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(main(["config-check", "--build", str(self.build),
                                   "--snippet", str(self.snippet)]), 0)
        self.assertNotIn("semantic_validation", json.loads(out.getvalue()))
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(main(["config-check", "--build", str(self.build),
                                   "--snippet", str(self.snippet),
                                   "--ninja", str(self.ninja)]), 2)


if __name__ == "__main__":
    unittest.main()
