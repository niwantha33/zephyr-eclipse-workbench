# SPDX-License-Identifier: Apache-2.0
"""Regression tests for board/SoC-driven dependency preparation (no downloads)."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / 'plugins/io.github.niwantha33.zew.ui/tools'
spec = importlib.util.spec_from_file_location('board_dependencies', TOOLS / 'board_dependencies.py')
board = importlib.util.module_from_spec(spec)
spec.loader.exec_module(board)


class DependencyTests(unittest.TestCase):
    def test_official_compiler_lock_is_complete_and_consistent(self):
        lock = json.loads((TOOLS / 'sdk-toolchains-windows-1.0.1.json').read_text())
        self.assertEqual(lock['schema'], 1)
        self.assertEqual(lock['sdk_version'], '1.0.1')
        self.assertEqual(lock['host'], 'windows-x86_64')
        names = lock['assets']
        for name in ('arm-zephyr-eabi', 'riscv64-zephyr-elf', 'x86_64-zephyr-elf',
                     'xtensa-espressif_esp32s3_zephyr-elf'):
            self.assertIn(name, names)
        self.assertGreaterEqual(len(names), 35)
        for name, entry in names.items():
            self.assertEqual(entry['file'], 'toolchain_gnu_windows-x86_64_' + name + '.7z')
            self.assertRegex(entry['sha256'], '^[a-f0-9]{64}$')
            self.assertGreater(entry['size'], 40_000_000)

    def test_board_target_requires_exact_qualifier_syntax(self):
        for name in ('lp_mspm0g3507/mspm0g3507', 'nucleo_f446re/stm32f446xx',
                     'esp32s3_devkitc/esp32s3/procpu'):
            self.assertEqual(board.checked_board(name), name)
        for name in ('../bad', 'a//b', 'a/b/', 'bad\\escape'):
            with self.assertRaises(ValueError):
                board.checked_board(name)

    def test_ti_series_requires_arm(self):
        with tempfile.TemporaryDirectory() as home:
            directory = Path(home)
            (directory / 'Kconfig').write_text('config SOC_SERIES_MSPM0G\n  select ARM\n  select CPU_CORTEX_M0PLUS\n')
            self.assertEqual(board.architecture('ti', 'mspm0g3507', directory, 'mspm0g3507'),
                             'arm-zephyr-eabi')

    def test_stm32_series_requires_arm(self):
        with tempfile.TemporaryDirectory() as home:
            directory = Path(home)
            (directory / 'Kconfig').write_text('config SOC_STM32F446XX\n  select CPU_CORTEX_M4\n')
            self.assertEqual(board.architecture('st', 'stm32f446xx', directory, 'stm32f446xx'),
                             'arm-zephyr-eabi')

    def test_nordic_mixed_cpu_requires_explicit_cpu_cluster(self):
        with tempfile.TemporaryDirectory() as home:
            directory = Path(home)
            (directory / 'Kconfig').write_text('select ARM\nselect RISCV\n')
            self.assertEqual(board.architecture('nordic', 'nrf54l15', directory, 'nrf54l15/cpuflpr'),
                             'riscv64-zephyr-elf')
            self.assertEqual(board.architecture('nordic', 'nrf54l15', directory, 'nrf54l15/cpuapp'),
                             'arm-zephyr-eabi')
            with self.assertRaises(ValueError):
                board.architecture('nordic', 'nrf54l15', directory, 'nrf54l15')

    def test_espressif_compilers_follow_soc(self):
        with tempfile.TemporaryDirectory() as home:
            directory = Path(home)
            self.assertEqual(board.architecture('espressif', 'esp32s3', directory, 'esp32s3/procpu'),
                             'xtensa-espressif_esp32s3_zephyr-elf')
            self.assertEqual(board.architecture('espressif', 'esp32c3', directory, 'esp32c3'),
                             'riscv64-zephyr-elf')

    def test_vendor_hal_and_shared_cmsis(self):
        manifest = {n: ('a' * 40, Path(n)) for n in
                    ('cmsis', 'hal_ti', 'hal_stm32', 'hal_nordic', 'hal_espressif')}
        for vendor, module in [('ti', 'hal_ti'), ('st', 'hal_stm32'),
                               ('nordic', 'hal_nordic'), ('espressif', 'hal_espressif')]:
            compiler = 'xtensa-espressif_esp32s3_zephyr-elf' if vendor == 'espressif' else 'arm-zephyr-eabi'
            expected = [module] if vendor == 'espressif' else ['cmsis', module]
            self.assertEqual(board.modules_for(vendor, compiler, manifest), expected)

    def test_existing_compiler_is_verified_not_overwritten(self):
        with tempfile.TemporaryDirectory() as home:
            root = Path(home)
            sdk = root / 'sdk'
            sdk.mkdir()
            (sdk / 'sdk_version').write_text('1.0.1')
            name = 'arm-zephyr-eabi'
            executable = sdk / 'gnu' / name / 'bin' / (name + '-gcc.exe')
            executable.parent.mkdir(parents=True)
            executable.write_text('keep')
            with patch.object(board.legacy, 'verify') as verify, patch.object(board.setup, 'fetch') as fetch:
                board.prepare_compiler(root, sdk, name, {'file':'toolchain_gnu_windows-x86_64_' + name + '.7z',
                              'size':79_217_349, 'sha256':'d08df2e83378dc8d28d0c67ab72d021ca633344b7a2bc053ababf9b8e677aa8d'})
                verify.assert_called_once_with(executable)
                fetch.assert_not_called()
            self.assertEqual(executable.read_text(), 'keep')

    def test_wrong_existing_module_revision_is_preserved(self):
        with tempfile.TemporaryDirectory() as home:
            workspace = Path(home)
            folder = workspace / 'modules/hal/ti'
            (folder / '.git').mkdir(parents=True)
            (folder / 'dontdelete.txt').write_text('important')
            plan = {'modules': ['hal_ti'], 'manifest': {'hal_ti': ('a' * 40, Path('modules/hal/ti'))}}
            with patch.object(board, 'git_revision', return_value='b' * 40):
                with patch('subprocess.run') as run:
                    with self.assertRaises(ValueError):
                        board.prepare_modules(plan, workspace, 'python', 'git')
                    run.assert_not_called()
            self.assertEqual((folder / 'dontdelete.txt').read_text(), 'important')

    def test_no_unsafe_module_path_from_manifest(self):
        fake = type('Result', (), {'stdout': 'hal_ti\t' + 'a'*40 + '\t../malicious\n'})()
        with patch('subprocess.run', return_value=fake):
            with self.assertRaises(ValueError):
                board.manifest_projects('python', Path('.'))

    def test_vendor_without_hal_fails_before_build(self):
        manifest = {'cmsis': ('a' * 40, Path('modules/hal/cmsis'))}
        with self.assertRaisesRegex(ValueError, 'hal_ti'):
            board.modules_for('ti', 'arm-zephyr-eabi', manifest)

    def test_inactive_west_modules_included_in_manifest_plan(self):
        fake = type('Result', (), {'stdout':
            'cmsis\t' + 'a' * 40 + '\tmodules/hal/cmsis\n'
            + 'hal_ti\t' + 'b' * 40 + '\tmodules/hal/ti\n'})()
        with patch('subprocess.run', return_value=fake) as run:
            manifest = board.manifest_projects('python', Path('.'))
        self.assertIn('--all', run.call_args.args[0])
        self.assertEqual(board.modules_for('ti', 'arm-zephyr-eabi', manifest), ['cmsis', 'hal_ti'])


if __name__ == '__main__':
    unittest.main()
