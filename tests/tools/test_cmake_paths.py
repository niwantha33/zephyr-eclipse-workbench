# SPDX-License-Identifier: Apache-2.0
"""Exercise the real CMake macro parser with Windows paths on any host."""
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('cmake_setup_helper', ROOT / 'plugins/io.github.niwantha33.zew.ui/tools/setup.py')
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)
CMAKE = os.environ.get('ZEW_TEST_CMAKE') or shutil.which('cmake')
NINJA = shutil.which('ninja')


@unittest.skipUnless(CMAKE and NINJA, 'Install CMake and Ninja to exercise the CMake parser')
class CMakePathTests(unittest.TestCase):
    def test_windows_macro_escape_and_same_cache_recovery(self):
        with tempfile.TemporaryDirectory(prefix='cmake Windows Ω ') as directory:
            root = Path(directory); source = root / 'source'; source.mkdir()
            (source / 'CMakeLists.txt').write_text('''cmake_minimum_required(VERSION 3.20)
project(path_escape NONE)
macro(zephyr_custom_target_shared)
  add_custom_target(${ARGN})
endmacro()
zephyr_custom_target_shared(menuconfig "${Python3_EXECUTABLE}" "${TEST_NINJA}")
''', encoding='utf-8')
            python = r'C:\Sandbox\Zephyr Setup Ω\python\python.exe'
            ninja = r'C:\Sandbox\Zephyr Setup Ω\python\Scripts\ninja.exe'
            def configure(build, definitions):
                # This parser fixture has no compiled languages. Avoid the host
                # default Visual Studio generator and its installation discovery.
                return subprocess.run([CMAKE, '-G', 'Ninja',
                                       '-DCMAKE_MAKE_PROGRAM:FILEPATH=' + setup.cmake_path(NINJA),
                                       '-S', str(source), '-B', str(build), *definitions],
                                      text=True, capture_output=True, timeout=30)
            failed = configure(root / 'retry', ['-DPython3_EXECUTABLE=' + python, '-DTEST_NINJA=' + ninja])
            self.assertNotEqual(failed.returncode, 0)
            self.assertIn('Invalid character escape', failed.stderr)
            fixed = ['-DPython3_EXECUTABLE:FILEPATH=' + setup.cmake_path(python),
                     '-DTEST_NINJA:FILEPATH=' + setup.cmake_path(ninja)]
            for build in (root / 'retry', root / 'fresh'):
                result = configure(build, fixed)
                self.assertEqual(result.returncode, 0, result.stderr)
