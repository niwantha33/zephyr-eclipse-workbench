# SPDX-License-Identifier: Apache-2.0
import base64
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
HAS_PACKAGING = importlib.util.find_spec('packaging') is not None
SCRIPT = ROOT / 'plugins/io.github.niwantha33.zew.ui/tools/inventory.py'
spec = importlib.util.spec_from_file_location('inventory', SCRIPT)
inventory = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inventory)


def records(output):
    return [(parts[0], [base64.b64decode(v).decode('utf-8') for v in parts[1:]])
            for line in output.splitlines() if (parts := line.split('\t'))]


class InventoryTests(unittest.TestCase):
    def capture(self, action):
        stream = io.StringIO()
        with contextlib.redirect_stdout(stream):
            action()
        return records(stream.getvalue())

    def test_protocol_preserves_unicode_spaces_and_newlines(self):
        self.assertEqual(self.capture(lambda: inventory.row('tool', 'Ω\tpath', '', 'Missing', 'a b\nc', ''))[0][1],
                         ['tool', 'Ω\tpath', '', 'Missing', 'a b\nc', ''])

    def test_version_parser_handles_two_components_and_invalid_output(self):
        self.assertEqual(inventory.version_tuple('ninja 1.10'), (1, 10, 0))
        self.assertIsNone(inventory.version_tuple('command not found'))

    def test_explicit_missing_path_cannot_fall_back(self):
        with patch.object(inventory.shutil, 'which', return_value=sys.executable):
            self.assertIsNone(inventory.executable('python', '/missing-explicit-python'))

    def test_failed_tool_cannot_be_ready(self):
        with patch.object(inventory, 'executable', return_value=Path(sys.executable)), patch.object(inventory, 'execute', return_value=(7, 'tool 9.9.9')):
            result = self.capture(lambda: inventory.host_tool('tool', '', (1, 0, 0)))
        self.assertEqual(result[0][1][3], 'Incompatible / failed')
        self.assertFalse(any(kind == 'BIND' for kind, _ in result))

    def test_too_old_tool_is_incompatible(self):
        with patch.object(inventory, 'executable', return_value=Path(sys.executable)), patch.object(inventory, 'execute', return_value=(0, 'cmake 3.1.0')):
            result = self.capture(lambda: inventory.host_tool('cmake', '', (3, 20, 5)))
        self.assertEqual(result[0][1][3], 'Incompatible / failed')

    def test_sdk_recommendation_wins_over_candidate_order(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first, recommended = root / 'a', root / 'b'
            for path, version in ((first, '9.0.0'), (recommended, '1.0.1')):
                path.mkdir(); (path / 'sdk_version').write_text(version)
            with patch.object(inventory, 'sdk_candidates', return_value=[first, recommended]):
                result = self.capture(lambda: inventory.scan_sdk('', '1.0.1'))
            self.assertIn(('BIND', ['sdk', str(recommended)]), result)
            self.assertEqual((first / 'sdk_version').read_text(), '9.0.0')

    def test_sdk_compiler_exit_failure_is_visible(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); (root / 'sdk_version').write_text('1.0.1')
            name = 'xtensa-espressif_esp32s3_zephyr-elf'
            compiler = root / 'gnu' / name / 'bin' / (name + '-gcc' + ('.exe' if os.name == 'nt' else ''))
            compiler.parent.mkdir(parents=True); compiler.touch()
            with patch.object(inventory, 'execute', return_value=(1, 'failed')):
                result = self.capture(lambda: inventory.scan_sdk(str(root), '1.0.1'))
            self.assertTrue(any(values[0] == name and values[3] == 'Failed' for kind, values in result if kind == 'ROW'))

    @unittest.skipUnless(HAS_PACKAGING, 'Optional packaging module not installed in test interpreter')
    def test_requirements_markers_and_missing_packages(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); (root / 'scripts').mkdir()
            (root / 'scripts/requirements-base.txt').write_text('pip>=1\nzew-no-such-package-2244; python_version < "1"\nzew-no-such-package-2255\n')
            result = self.capture(lambda: inventory.check_requirements(root))
            self.assertEqual(result[0][1][3], 'Missing / incompatible')
            self.assertIn('2255', result[0][1][2]); self.assertNotIn('2244', result[0][1][2])

    @unittest.skipUnless(HAS_PACKAGING, 'Optional packaging module not installed in test interpreter')
    def test_requirements_include_cannot_escape_source_tree(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); (root / 'scripts').mkdir()
            (root / 'scripts/requirements-base.txt').write_text('-r ../../untrusted.txt\n')
            result = self.capture(lambda: inventory.check_requirements(root))
            self.assertEqual(result[0][1][3], 'Not verified')

    @unittest.skipUnless(HAS_PACKAGING, 'Optional packaging module not installed in test interpreter')
    def test_requirements_cycles_terminate(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); (root / 'scripts').mkdir()
            (root / 'scripts/requirements-base.txt').write_text('-r requirements-base.txt\npip>=1\n')
            result = self.capture(lambda: inventory.check_requirements(root))
            self.assertEqual(result[0][1][3], 'Ready')

    def test_command_arguments_are_not_shell_evaluated(self):
        code, output = inventory.execute([sys.executable, '-c', 'import sys;print(sys.argv[1])', '$(touch ZEW_NEVER)'])
        self.assertEqual(code, 0); self.assertEqual(output, '$(touch ZEW_NEVER)')

    def test_real_read_only_scan_of_missing_workspace(self):
        with tempfile.TemporaryDirectory() as temporary:
            missing = Path(temporary) / 'not-created'
            p = subprocess.run([sys.executable, SCRIPT, '--workspace', missing, '--sdk', missing,
                                '--git', missing, '--cmake', missing, '--ninja', missing, '--dtc', missing],
                               text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
            self.assertEqual(p.returncode, 0, p.stderr)
            values = records(p.stdout)
            self.assertTrue(any(kind == 'DONE' for kind, _ in values)); self.assertFalse(missing.exists())
            self.assertTrue(any(kind == 'ROW' and v[0] == 'West workspace' and v[3] == 'Missing' for kind, v in values))


if __name__ == '__main__':
    unittest.main()
