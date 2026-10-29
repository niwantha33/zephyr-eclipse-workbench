# SPDX-License-Identifier: Apache-2.0
"""Regression tests for read-only Kconfiglib integration (no Zephyr SDK needed)."""
import contextlib
import importlib.util
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import types
import unittest
from unittest import mock

SCRIPT = (Path(__file__).resolve().parents[2] /
          "plugins/io.github.niwantha33.zew.ui/scripts/kconfig_inspect.py")
spec = importlib.util.spec_from_file_location("zew_kconfig_inspect", SCRIPT)
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class KconfigInspectionTests(unittest.TestCase):
    def test_read_generated_ninja_environment(self):
        output = ("cd /workspace/build && /usr/bin/cmake -E env "
                  "srctree=/workspace/zephyr KCONFIG_BINARY_DIR=/workspace/build/zephyr/Kconfig "
                  "EDT_PICKLE=/workspace/build/zephyr/edt.pickle "
                  "/usr/bin/python3 /workspace/zephyr/scripts/kconfig/guiconfig.py "
                  "/workspace/zephyr/Kconfig")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            kroot = root / "Kconfig"
            kroot.write_text("mainmenu \"Test\"\n")
            output = output.replace("/workspace/zephyr/Kconfig", str(kroot))
            values, chosen = probe.extract_command(output)
            self.assertEqual(chosen, kroot)
            self.assertEqual(values["srctree"], "/workspace/zephyr")
            self.assertEqual(values["EDT_PICKLE"], "/workspace/build/zephyr/edt.pickle")

    def test_incomplete_env_never_guesses_board(self):
        with self.assertRaisesRegex(RuntimeError, "No valid"):
            probe.extract_command("cmake -E env BOARD=x python guiconfig.py Kconfig")

    def test_windows_shell_wrapper_and_quoted_paths_preserve_boundaries(self):
        with tempfile.TemporaryDirectory(prefix="Kconfig root ") as directory:
            kroot = Path(directory) / "Kconfig"
            kroot.write_text('mainmenu "Test"\n')
            command = (
                r'C:\Windows\system32\cmd.exe /C "cd /D C:\build && '
                r'"C:\Program Files\CMake\bin\cmake.exe" -E env '
                r'"srctree=C:\Zephyr Workspace\zephyr" '
                r'"KCONFIG_BINARY_DIR=C:\Build Folder\zephyr\Kconfig" '
                r'"EDT_PICKLE=C:\Build Folder\zephyr\edt.pickle" '
                r'"C:\Python Tools\python.exe" '
                r'"C:\Zephyr Workspace\zephyr\scripts\kconfig\guiconfig.py" '
                f'"{kroot}""')
            values, chosen = probe.extract_command(command)
            self.assertEqual(chosen, kroot)
            self.assertEqual(values["srctree"], r"C:\Zephyr Workspace\zephyr")
            self.assertEqual(values["KCONFIG_BINARY_DIR"], r"C:\Build Folder\zephyr\Kconfig")
            self.assertEqual(values["EDT_PICKLE"], r"C:\Build Folder\zephyr\edt.pickle")

    def test_unterminated_root_quote_is_rejected(self):
        command = ('cmake -E env srctree=z KCONFIG_BINARY_DIR=b EDT_PICKLE=e '
                   'python guiconfig.py "unterminated path')
        with self.assertRaisesRegex(RuntimeError, "no Kconfig root argument"):
            probe.extract_command(command)

    def test_kconfiglib_reads_only_existing_build_values(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            zephyr = root / "zephyr"
            kconfig_root = zephyr / "Kconfig"
            library_dir = zephyr / "scripts" / "kconfig"
            library_dir.mkdir(parents=True)
            kconfig_root.write_text('mainmenu "Test"\n')
            (library_dir / "kconfiglib.py").write_text("# fake preinstalled module\n")
            build = root / "build"
            (build / "zephyr").mkdir(parents=True)
            (build / "build.ninja").write_text("rule stub\n")
            dotconfig = build / "zephyr" / ".config"
            dotconfig.write_text("# CONFIG_ADC is not set\n")
            (root / "ninja").write_text("no executable needed: subprocess is mocked\n")
            ninja_command = ("cmake -E env "
                             f"srctree={zephyr} KCONFIG_BINARY_DIR={build / 'zephyr' / 'Kconfig'} "
                             f"EDT_PICKLE={build / 'zephyr' / 'edt.pickle'} "
                             f"/usr/bin/python3 {library_dir / 'guiconfig.py'} {kconfig_root}")

            sym = types.SimpleNamespace(
                nodes=[types.SimpleNamespace(
                    prompt=("Analog input", object()), help="ADC driver requirements.",
                    filename="drivers/adc/Kconfig", linenr=15)],
                direct_dep=object(), type=2, str_value="n", visibility=0,
                assignable=(0,), selects=[], implies=[])
            module = types.SimpleNamespace(
                Kconfig=lambda filename, **kwargs: types.SimpleNamespace(
                    syms={"ADC": sym}, load_config=lambda filename: None),
                expr_str=lambda value: "GPIO && SOC_ADC",
                expr_value=lambda value: 0,
                TRI_TO_STR={0: "n", 1: "m", 2: "y"},
                TYPE_TO_STR={2: "bool"})
            output = io.StringIO()
            with mock.patch.object(subprocess, "run",
                                   return_value=types.SimpleNamespace(stdout=ninja_command)):
                with mock.patch.dict(sys.modules, {"kconfiglib": module}):
                    with contextlib.redirect_stdout(output):
                        probe.inspect(build, root / "ninja", "ADC")
            rows = output.getvalue().splitlines()
            self.assertEqual(rows[0], "ZEW-KCONFIG-1")
            import base64
            values = [base64.b64decode(x).decode() for x in rows[1].split("\t")]
            self.assertEqual(values[0], "ADC")
            self.assertEqual(values[3], "n")
            self.assertEqual(values[4], "n")  # cannot enable
            self.assertEqual(values[5], "GPIO && SOC_ADC")
            self.assertEqual(dotconfig.read_text(), "# CONFIG_ADC is not set\n")

    def test_invalid_symbol_rejected(self):
        with self.assertRaisesRegex(ValueError, "Invalid"):
            probe.inspect(Path("."), Path("ninja"), "../../etc/passwd")


if __name__ == "__main__":
    unittest.main()
