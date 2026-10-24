# SPDX-License-Identifier: Apache-2.0
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / 'plugins/io.github.niwantha33.zew.ui/tools'
spec = importlib.util.spec_from_file_location('board_tools', TOOLS / 'board_tools.py')
board = importlib.util.module_from_spec(spec); spec.loader.exec_module(board)

class BoardToolsTests(unittest.TestCase):
    def test_only_esp32s3_soc_selects_this_toolchain(self):
        for target in ['esp32s3_devkitc/esp32s3/appcpu', 'esp32s3_devkitc/esp32s3/procpu', 'third_party@1.0/esp32s3/procpu']:
            self.assertEqual(board.toolchain_for(target), board.TOOLCHAIN)
        for target in ['qemu_x86/atom', 'esp32s3_impostor/esp32c3', 'nucleo_f446re/stm32f446xx']:
            self.assertIsNone(board.toolchain_for(target))

    def test_existing_compiler_verified_without_download_or_replacement(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); sdk = root / 'sdk'; sdk.mkdir(); (sdk / 'sdk_version').write_text('1.0.1')
            compiler = sdk / 'gnu' / board.TOOLCHAIN / 'bin' / (board.TOOLCHAIN + '-gcc.exe'); compiler.parent.mkdir(parents=True); compiler.write_bytes(b'keep')
            with patch.object(board, 'verify') as verify, patch.object(board.setup, 'fetch') as fetch:
                self.assertEqual(board.install_compiler(root, sdk), compiler)
                verify.assert_called_once_with(compiler); fetch.assert_not_called()
            self.assertEqual(compiler.read_bytes(), b'keep')

    def test_incomplete_existing_compiler_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); sdk=root/'sdk'; sdk.mkdir(); (sdk/'sdk_version').write_text('1.0.1')
            destination=sdk/'gnu'/board.TOOLCHAIN; destination.mkdir(parents=True); (destination/'keep').write_text('keep')
            with patch.object(board.setup,'fetch') as fetch:
                with self.assertRaises(ValueError):board.install_compiler(root,sdk)
                fetch.assert_not_called()
            self.assertEqual((destination/'keep').read_text(),'keep')

    def test_python_lock_is_pinned_and_platform_specific(self):
        lock=json.loads((TOOLS/'esp32s3-python-lock.json').read_text())
        self.assertEqual(lock['python'],'3.13'); self.assertEqual(lock['platform'],'win_amd64')
        self.assertEqual(len({p['name'].lower() for p in lock['packages']}),len(lock['packages']))
        for package in lock['packages']:
            self.assertRegex(package['sha256'],r'^[0-9a-f]{64}$')
            self.assertTrue(package['url'].startswith('https://files.pythonhosted.org/'))
            self.assertGreater(package['size'],0)
        self.assertEqual(next(p['version'] for p in lock['packages'] if p['name']=='esptool'),'5.2.0')

    def test_unsupported_sdk_not_modified(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'sdk_version').write_text('0.17.4')
            with self.assertRaises(ValueError):board.install_compiler(root,root)
            self.assertFalse((root/'cache').exists())
