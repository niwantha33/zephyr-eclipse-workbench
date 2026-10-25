# SPDX-License-Identifier: Apache-2.0
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[2] / 'plugins/io.github.niwantha33.zew.ui/tools'
spec = importlib.util.spec_from_file_location('build_paths', TOOLS / 'build_paths.py')
paths = importlib.util.module_from_spec(spec); spec.loader.exec_module(paths)


class BuildPathTests(unittest.TestCase):
    def test_detects_spaces_unicode_and_cmake_list_separator(self):
        for value in ('C:/my project', 'C:/projectΩ', 'C:/a;b'):
            self.assertTrue(paths.requires_alias(value))
        self.assertFalse(paths.requires_alias('C:/projects/my_project-1.0'))

    def test_output_must_be_confined_to_independent_managed_target(self):
        with tempfile.TemporaryDirectory() as home:
            root = Path(home)
            with self.assertRaisesRegex(ValueError, 'independent directory'):
                paths.resolve(root, root / 'user-output', root, root, windows=False)
            self.assertFalse((root / 'user-output').exists())
            result = paths.resolve(root, root / '.zew-build/target', root, root, windows=False)
            self.assertEqual(result[0], root.resolve().as_posix())
            self.assertEqual(result[-1], 'false')

    def test_unsupported_short_path_preserves_source(self):
        with tempfile.TemporaryDirectory(prefix='source with spaces ') as home:
            root = Path(home); source = root / 'main.c'; source.write_text('USER SOURCE')
            with patch.object(paths, 'short_name', return_value=str(root)):
                with self.assertRaisesRegex(ValueError, 'no verified space-free ASCII alias'):
                    paths.tool_path(root, windows=True)
            self.assertEqual(source.read_text(), 'USER SOURCE')

    @unittest.skipUnless(os.name == 'nt', 'requires native Windows short-name API')
    def test_native_alias_resolves_to_original_directory(self):
        with tempfile.TemporaryDirectory(prefix='build paths Ω ') as home:
            root = Path(home).resolve()
            alias = paths.tool_path(root)
            self.assertFalse(paths.requires_alias(alias))
            self.assertEqual(Path(alias).resolve(), root)

    @unittest.skipIf(os.name == 'nt', 'symlink creation may require Windows privileges')
    def test_symlinked_build_output_cannot_escape_application(self):
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as outside:
            root = Path(home)
            (root / '.zew-build').symlink_to(outside, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, 'independent directory'):
                paths.resolve(root, root / '.zew-build/target', root, root, windows=False)
            self.assertEqual(list(Path(outside).iterdir()), [])
