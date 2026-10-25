# SPDX-License-Identifier: Apache-2.0
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('editor_settings', ROOT / 'plugins/io.github.niwantha33.zew.ui/tools/editor_settings.py')
editor = importlib.util.module_from_spec(spec); spec.loader.exec_module(editor)


class EditorTests(unittest.TestCase):
    def test_windows_quoting_round_trip(self):
        for args in ([r'C:\Program Files\SDK\gcc.exe', '-I' + r'C:\path with spaces\include', '-DAPP_NAME="Hello machan"', r'C:\src\main.c'],
                     ['gcc.exe', '', 'trailing path \\', r'-DVALUE=a\b', 'quote"inside']):
            self.assertEqual(editor.windows_split(subprocess.list2cmdline(args)), args)
        self.assertRaises(ValueError, editor.windows_split, '"unclosed')

    def test_argument_arrays_preserve_literals(self):
        args = ['gcc', '-DNAME=$(touch NEVER)', '-DSTR=hello world']
        self.assertEqual(editor.arguments({'arguments': args}), args)

    def test_preprocessor_flags_exclude_build_and_plugin_execution(self):
        flags, includes, forced, macros = editor.preprocess_flags(['-I', 'inc', '-DVALUE=3', '-UOLD',
                '-imacros', 'config.h', '-include', 'forced.h', '-m32', '-std=c11', '-o', 'out.o', '-c', 'main.c',
                '-fplugin=NEVER', '-specs=NEVER'], Path('/work'))
        self.assertIn(str(Path('/work/inc').resolve()), includes); self.assertEqual(macros, [str(Path('/work/config.h').resolve())])
        self.assertEqual(forced, [str(Path('/work/forced.h').resolve())]); self.assertIn('-m32', flags)
        self.assertFalse(any('NEVER' in a or a in ('-o', '-c', 'main.c') for a in flags))

    def test_compiler_outside_selected_sdk_rejected(self):
        self.assertRaises(ValueError, editor.probe, '/usr/bin/gcc', [], 'c', Path('/'), Path('/other-sdk'))

    def test_application_filter_duplicate_and_probe_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve(); build = root / '.zew-build/first'; build.mkdir(parents=True)
            entries = [{'directory': str(root), 'file': str(root / file), 'arguments': ['gcc', '-Iinclude', '-c', file]}
                       for file in ['src/a.c', 'src/b.c', '.zew-build/first/generated.c']]
            database = build / 'compile_commands.json'; database.write_text(json.dumps(entries))
            with patch.object(editor, 'probe', return_value=(['/sdk/include'], [('CONFIG_TEST', '7')])) as probe:
                rows = editor.collect(root, build, root / 'sdk'); self.assertEqual(probe.call_count, 1)
                self.assertEqual([r[1] for r in rows if r[0] == 'F'], ['src/a.c', 'src/b.c'])
                entries.append(entries[0]); database.write_text(json.dumps(entries))
                self.assertRaises(ValueError, editor.collect, root, build, root / 'sdk')

    def test_response_file_depth_and_expansion(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve(); (root / 'flags.rsp').write_text('-I "include path" -DVALUE=9')
            self.assertEqual(editor.expand_response(['@flags.rsp'], root), ['-I', 'include path', '-DVALUE=9'])
            (root / 'cycle.rsp').write_text('@cycle.rsp')
            self.assertRaises(ValueError, editor.expand_response, ['@cycle.rsp'], root)

    def test_no_application_commands_cannot_write_success(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve(); (root / 'compile_commands.json').write_text('[]')
            self.assertRaises(ValueError, editor.collect, root, root, root / 'sdk')

    @unittest.skipUnless(shutil.which('gcc'), 'Requires an installed GCC for the real probe')
    def test_real_compiler_generated_config_builtins_and_headers(self):
        with tempfile.TemporaryDirectory(prefix='editor Ω ') as directory:
            root = Path(directory).resolve(); source = root / 'src/main.c'; source.parent.mkdir(); source.write_text('int main(void){return 0;}')
            build = root / '.zew-build/first'; build.mkdir(parents=True)
            (root / 'include').mkdir(); (build / 'autoconf.h').write_text('#define CONFIG_ZEW_TEST 73\n')
            compiler = Path(shutil.which('gcc')).resolve(); sdk = compiler.parent.parent
            entry = {'directory': str(root), 'file': str(source), 'arguments': [str(compiler), '-Os', '-ffreestanding', '-I', str(root / 'include'),
                     '-imacros', str(build / 'autoconf.h'), '-DAPP_NAME="Hello machan"', '-c', str(source)]}
            (build / 'compile_commands.json').write_text(json.dumps([entry]))
            rows = editor.collect(root, build, sdk)
            self.assertIn(('D', 'src/main.c', 'CONFIG_ZEW_TEST', '73'), rows)
            self.assertTrue(any(r[0] == 'D' and r[2] == '__GNUC__' for r in rows))
            self.assertIn(('D', 'src/main.c', '__STDC_HOSTED__', '0'), rows)
            self.assertIn(('D', 'src/main.c', '__OPTIMIZE__', '1'), rows)
            self.assertIn(('I', 'src/main.c', str(root / 'include'), ''), rows)
            output = build / 'editor.tsv'; editor.write(rows, output)
            self.assertTrue(output.read_text().startswith('ZEW_EDITOR_1\n'))
            self.assertFalse(output.with_suffix('.partial').exists())
