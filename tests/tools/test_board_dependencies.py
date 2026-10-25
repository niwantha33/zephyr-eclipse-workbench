# SPDX-License-Identifier: Apache-2.0
"""Regression tests for board/SoC-driven dependency preparation (no downloads)."""
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import venv
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / 'plugins/io.github.niwantha33.zew.ui/tools'
spec = importlib.util.spec_from_file_location('board_dependencies', TOOLS / 'board_dependencies.py')
board = importlib.util.module_from_spec(spec)
spec.loader.exec_module(board)


class DependencyTests(unittest.TestCase):
    def test_jar_entry_extract_requires_all_sibling_files(self):
        """A single FileLocator extraction must not reach the Windows builder."""
        tool_names = (
            'board_dependencies.py', 'board_tools.py', 'hardware_catalog.py',
            'setup.py', 'managed_sitecustomize.py',
            'sdk-toolchains-windows-1.0.1.json', 'esp32s3-python-lock.json',
            'mcuboot-python-lock.json',
        )
        with tempfile.TemporaryDirectory(prefix='zew-osgi-tools-') as directory:
            staged = Path(directory)
            shutil.copyfile(TOOLS / tool_names[0], staged / tool_names[0])
            missing = subprocess.run(
                [sys.executable, '-I', str(staged / 'board_dependencies.py'), '--help'],
                text=True, capture_output=True, timeout=15)
            self.assertNotEqual(missing.returncode, 0)
            self.assertIn('board_tools.py', missing.stderr)
            for name in tool_names[1:]:
                shutil.copyfile(TOOLS / name, staged / name)
            ready = subprocess.run(
                [sys.executable, '-I', str(staged / 'board_dependencies.py'), '--help'],
                text=True, capture_output=True, timeout=15)
            self.assertEqual(ready.returncode, 0, ready.stderr)
            self.assertIn('--board BOARD', ready.stdout)

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
                    ('cmsis_6', 'cmsis', 'hal_ti', 'hal_stm32', 'hal_nordic', 'hal_espressif')}
        for vendor, module in [('ti', 'hal_ti'), ('st', 'hal_stm32'),
                               ('nordic', 'hal_nordic'), ('espressif', 'hal_espressif')]:
            compiler = 'xtensa-espressif_esp32s3_zephyr-elf' if vendor == 'espressif' else 'arm-zephyr-eabi'
            expected = [module] if vendor == 'espressif' else ['cmsis_6', 'cmsis', module]
            self.assertEqual(board.modules_for(vendor, compiler, manifest), expected)

    def test_cortex_m_requires_modern_cmsis_6_not_legacy_alone(self):
        manifest = {name: ('a' * 40, Path(name)) for name in ('cmsis', 'hal_ti')}
        with self.assertRaisesRegex(ValueError, 'cmsis_6'):
            board.modules_for('ti', 'arm-zephyr-eabi', manifest)

    def test_non_arm_targets_do_not_install_cmsis(self):
        manifest = {name: ('a' * 40, Path(name))
                    for name in ('cmsis_6', 'cmsis', 'hal_espressif', 'hal_ti')}
        for compiler in ('xtensa-espressif_esp32s3_zephyr-elf', 'riscv64-zephyr-elf'):
            self.assertEqual(board.modules_for('espressif', compiler, manifest), ['hal_espressif'])

    def test_required_inactive_modules_are_activated_without_disabling_others(self):
        plan = {'modules': ['cmsis_6', 'cmsis', 'hal_ti']}
        current = type('Result', (), {'returncode': 0,
                    'stdout': '-hal_.*,+hal_ti\n', 'stderr': ''})()
        with patch.object(board, 'active_projects',
                          side_effect=[{'cmsis', 'hal_ti'},
                                       {'cmsis_6', 'cmsis', 'hal_ti'}]) as active:
            with patch('subprocess.run', return_value=current) as run:
                board.activate_modules(plan, Path('.'), 'python')
                self.assertEqual(run.call_count, 2)
                command = run.call_args.args[0]
                self.assertEqual(command[3:6], ['config', '--local', 'manifest.project-filter'])
                self.assertEqual(command[6], '-hal_.*,+hal_ti,+cmsis_6')
                self.assertEqual(active.call_count, 2)

    def test_no_filter_changes_when_all_modules_already_active(self):
        with patch.object(board, 'active_projects',
                          return_value={'cmsis_6', 'cmsis', 'hal_ti'}):
            with patch('subprocess.run') as run:
                board.activate_modules({'modules': ['cmsis_6', 'cmsis', 'hal_ti']},
                                       Path('.'), 'python')
                run.assert_not_called()

    def test_inactive_module_after_activation_fails_before_compilation(self):
        result = type('Result', (), {'returncode': 1, 'stdout': '', 'stderr': 'not set'})()
        with patch.object(board, 'active_projects',
                          side_effect=[set(), set()]):
            with patch('subprocess.run', return_value=result):
                with self.assertRaisesRegex(ValueError, 'inactive in CMake discovery'):
                    board.activate_modules({'modules': ['cmsis_6']}, Path('.'), 'python')

    def test_transitive_build_depends_are_pinned_and_prepared(self):
        with tempfile.TemporaryDirectory() as home:
            workspace = Path(home)
            hal = workspace / 'modules/hal/ti'
            cmsis = workspace / 'modules/hal/cmsis_6'
            for item in (hal, cmsis):
                (item / '.git').mkdir(parents=True)
            (hal / 'zephyr').mkdir()
            (hal / 'zephyr/module.yml').write_text(
                'name: hal_ti\nbuild:\n  depends:\n    - cmsis_6\n')
            plan = {'modules': ['hal_ti'], 'manifest': {
                'hal_ti': ('a' * 40, Path('modules/hal/ti')),
                'cmsis_6': ('b' * 40, Path('modules/hal/cmsis_6'))}}
            with patch.object(board, 'git_revision',
                              side_effect=lambda git, folder: 'a' * 40 if folder == hal else 'b' * 40):
                with patch('subprocess.run') as run:
                    board.prepare_modules(plan, workspace, 'python', 'git')
                    run.assert_not_called()
            self.assertEqual(plan['modules'], ['hal_ti', 'cmsis_6'])

    def test_missing_transitive_project_fails_before_build(self):
        with tempfile.TemporaryDirectory() as home:
            workspace = Path(home)
            hal = workspace / 'modules/hal/ti'
            (hal / '.git').mkdir(parents=True)
            (hal / 'zephyr').mkdir()
            (hal / 'zephyr/module.yml').write_text(
                'build:\n  depends:\n    - missing_dependency\n')
            plan = {'modules': ['hal_ti'],
                    'manifest': {'hal_ti': ('a' * 40, Path('modules/hal/ti'))}}
            with patch.object(board, 'git_revision', return_value='a' * 40):
                with self.assertRaisesRegex(ValueError, 'requires unavailable West project'):
                    board.prepare_modules(plan, workspace, 'python', 'git')

    def test_imgtool_preflight_is_vendor_neutral(self):
        with tempfile.TemporaryDirectory() as home:
            application = Path(home)
            # The SoC vendor does not imply image signing. Implicit board
            # defaults are detected from generated Zephyr build rules.
            self.assertFalse(board.requires_imgtool('espressif', application))
            self.assertFalse(board.requires_imgtool('ti', application))
            (application / 'prj.conf').write_text('CONFIG_BOOTLOADER_MCUBOOT=n\n')
            self.assertFalse(board.requires_imgtool('ti', application))
            (application / 'prj.conf').write_text('CONFIG_BOOTLOADER_MCUBOOT=y\n')
            for vendor in ('ti', 'st', 'nordic', 'nxp', 'espressif', 'silabs'):
                self.assertTrue(board.requires_imgtool(vendor, application))
            (application / 'prj.conf').write_text('# CONFIG_BOOTLOADER_MCUBOOT=y\n')
            (application / 'sysbuild.conf').write_text('SB_CONFIG_BOOTLOADER_MCUBOOT=y\n')
            self.assertTrue(board.requires_imgtool('st', application))

    def test_required_imgtool_flag_is_shared_for_all_boards(self):
        with tempfile.TemporaryDirectory() as home:
            workspace = Path(home)
            # Verify the source contract stays board-independent: the host
            # provider is selected through a missing generated requirement.
            import inspect
            source = inspect.getsource(board.main)
            self.assertIn("args.require_host_tool == 'IMGTOOL'", source)
            self.assertIn("details['modules'].append('mcuboot')", source)
            self.assertIn("details['signing'] = True", source)
            self.assertNotIn("details['vendor'] == 'espressif' and details['signing']", source)

    def test_mcuboot_python_wheels_have_exact_pins(self):
        lock = json.loads((TOOLS / 'mcuboot-python-lock.json').read_text())
        self.assertEqual((lock['schema'], lock['python'], lock['platform']), (1, '3.13', 'win_amd64'))
        self.assertEqual({p['name'].lower() for p in lock['packages']},
                         {'cbor2', 'cffi', 'click', 'cryptography', 'intelhex', 'pycparser', 'pyyaml'})
        for package in lock['packages']:
            self.assertRegex(package['sha256'], r'^[a-f0-9]{64}$')
            self.assertTrue(package['url'].startswith('https://files.pythonhosted.org/'))
            self.assertGreater(package['size'], 1000)

    def test_missing_mcuboot_script_rejected_before_download(self):
        with tempfile.TemporaryDirectory() as home:
            root = Path(home)
            manifest = {'mcuboot': ('a' * 40, Path('bootloader/mcuboot'))}
            with patch.object(board.setup, 'fetch') as fetch:
                with self.assertRaisesRegex(ValueError, 'scripts/imgtool.py'):
                    board.install_signing_tools(root, root, 'python', manifest)
                fetch.assert_not_called()

    def test_signing_install_preserves_previous_esp32_environment(self):
        with tempfile.TemporaryDirectory() as home:
            root = Path(home)
            signing = root / 'bootloader/mcuboot/scripts/imgtool.py'
            signing.parent.mkdir(parents=True)
            signing.write_text('print("imgtool")')
            existing = root / 'board-python/esp32s3/.zew-ready'
            existing.parent.mkdir(parents=True)
            existing.write_text('unchanged')
            site = root / 'python/Lib/site-packages'
            site.mkdir(parents=True)
            manifest = {'mcuboot': ('a' * 40, Path('bootloader/mcuboot'))}

            def process(args, **kwargs):
                if args[1:4] == ['-m', 'pip', 'install']:
                    Path(args[args.index('--target') + 1]).mkdir(parents=True)
                return subprocess.CompletedProcess(args, 0)

            with patch.object(board.sys, 'version_info', (3, 13)):
                with patch.object(board.setup, 'fetch', return_value=root / 'cache' / 'wheel.whl') as fetch:
                    with patch('subprocess.run', side_effect=process) as run:
                        board.install_signing_tools(root, root, 'python', manifest)
                        self.assertEqual(run.call_count, 3)  # install, imports, imgtool --help
                        self.assertEqual(fetch.call_count, 7)
                        board.install_signing_tools(root, root, 'python', manifest)
                        self.assertEqual(run.call_count, 4)  # reused, check imgtool --help
                        self.assertEqual(fetch.call_count, 7)  # no downloads on rebuild
            self.assertEqual(existing.read_text(), 'unchanged')
            self.assertEqual((site / 'zew-mcuboot.pth').read_text(),
                             str(root / 'board-python/mcuboot') + '\n'
                             + str(signing.parent.resolve()) + '\n')

    def test_signing_sibling_imports_with_isolated_python_and_old_mapping(self):
        with tempfile.TemporaryDirectory(prefix='signing runtime Ω ') as home:
            root = Path(home)
            runtime = root / 'python'
            venv.EnvBuilder(with_pip=False).create(runtime)
            python = runtime / ('Scripts/python.exe' if sys.platform == 'win32' else 'bin/python')
            sites = Path(subprocess.check_output(
                [str(python), '-I', '-c', 'import site;print(site.getsitepackages()[0])'],
                text=True).strip())
            managed_site = runtime / 'Lib/site-packages'
            managed_site.mkdir(parents=True, exist_ok=True)
            # Reproduce the embedded runtime's explicit site-packages path.
            (sites / 'managed-test.pth').write_text(str(managed_site) + '\nimport site; site.addsitedir('
                                                   + repr(str(managed_site)) + ')\n')
            scripts = root / 'workspace/bootloader/mcuboot/scripts'
            scripts.mkdir(parents=True)
            package = scripts / 'imgtool'; package.mkdir()
            (package / '__init__.py').write_text('VALUE = "pinned-imgtool-ok"\n')
            entry = scripts / 'imgtool.py'
            entry.write_text('from imgtool import VALUE\nprint(VALUE)\n')
            dependencies = root / 'board-python/mcuboot'
            dependencies.mkdir(parents=True)
            lock = (TOOLS / 'mcuboot-python-lock.json').read_bytes()
            (dependencies / '.zew-ready').write_text(board.hashlib.sha256(lock).hexdigest() + '\n')
            mapping = managed_site / 'zew-mcuboot.pth'
            mapping.write_text(str(dependencies) + '\n')
            before = subprocess.run([str(python), '-I', str(entry)], capture_output=True, text=True)
            self.assertNotEqual(before.returncode, 0)
            manifest = {'mcuboot': ('a' * 40, Path('bootloader/mcuboot'))}
            with patch.object(board.sys, 'version_info', (3, 13)):
                board.install_signing_tools(root, root / 'workspace', python, manifest)
                # Retry reuses dependencies and the upgraded mapping.
                board.install_signing_tools(root, root / 'workspace', python, manifest)
            after = subprocess.run([str(python), '-I', str(entry)], capture_output=True, text=True)
            self.assertEqual(after.returncode, 0, after.stderr)
            self.assertEqual(after.stdout.strip(), 'pinned-imgtool-ok')

    def test_signing_validation_retains_python_traceback(self):
        with tempfile.TemporaryDirectory() as home:
            root = Path(home)
            scripts = root / 'bootloader/mcuboot/scripts'; scripts.mkdir(parents=True)
            (scripts / 'imgtool.py').touch()
            (root / 'python/Lib/site-packages').mkdir(parents=True)
            destination = root / 'board-python/mcuboot'; destination.mkdir(parents=True)
            (destination / '.zew-ready').write_text(board.hashlib.sha256(
                (TOOLS / 'mcuboot-python-lock.json').read_bytes()).hexdigest() + '\n')
            with patch.object(board.sys, 'version_info', (3, 13)), patch('subprocess.run',
                    return_value=subprocess.CompletedProcess([], 1, '', 'ModuleNotFoundError: missing dependency')):
                with self.assertRaisesRegex(ValueError, 'ModuleNotFoundError: missing dependency'):
                    board.install_signing_tools(root, root, 'python',
                                                {'mcuboot': ('a' * 40, Path('bootloader/mcuboot'))})

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
        manifest = {'cmsis_6': ('a' * 40, Path('modules/hal/cmsis_6')),
                    'cmsis': ('a' * 40, Path('modules/hal/cmsis'))}
        with self.assertRaisesRegex(ValueError, 'hal_ti'):
            board.modules_for('ti', 'arm-zephyr-eabi', manifest)

    def test_inactive_west_modules_included_in_manifest_plan(self):
        fake = type('Result', (), {'stdout':
            'cmsis_6\t' + 'c' * 40 + '\tmodules/hal/cmsis_6\n'
            + 'cmsis\t' + 'a' * 40 + '\tmodules/hal/cmsis\n'
            + 'hal_ti\t' + 'b' * 40 + '\tmodules/hal/ti\n'})()
        with patch('subprocess.run', return_value=fake) as run:
            manifest = board.manifest_projects('python', Path('.'))
        self.assertIn('--all', run.call_args.args[0])
        self.assertEqual(board.modules_for('ti', 'arm-zephyr-eabi', manifest), ['cmsis_6', 'cmsis', 'hal_ti'])


if __name__ == '__main__':
    unittest.main()

class StagedModuleTests(unittest.TestCase):
    def test_interrupted_fetch_cannot_activate_partial_module(self):
        with tempfile.TemporaryDirectory() as home:
            workspace = Path(home); destination = workspace / 'modules/hal/test'
            def command(args, **kwargs):
                if 'fetch' in args:
                    raise subprocess.CalledProcessError(1, args)
                return subprocess.CompletedProcess(args, 0)
            with patch('subprocess.check_output', return_value='https://github.com/zephyrproject-rtos/hal_test\n'), patch('subprocess.run', side_effect=command):
                with self.assertRaises(subprocess.CalledProcessError):
                    board.stage_module('hal_test', 'a'*40, destination, workspace, 'python', 'git')
            self.assertFalse(destination.exists())
            self.assertEqual(list(destination.parent.iterdir()), [])

    def test_unapproved_module_origin_rejected_before_git(self):
        with tempfile.TemporaryDirectory() as home:
            workspace = Path(home)
            with patch('subprocess.check_output', return_value='https://unreviewed.example/hal'), patch('subprocess.run') as run:
                with self.assertRaisesRegex(ValueError, 'reviewed GitHub'):
                    board.stage_module('hal', 'a'*40, workspace / 'hal', workspace, 'python', 'git')
                run.assert_not_called()

    def test_staged_module_verified_before_atomic_activation(self):
        with tempfile.TemporaryDirectory() as home:
            workspace = Path(home); destination = workspace / 'modules/hal/test'
            with patch('subprocess.check_output', return_value='https://github.com/zephyrproject-rtos/hal_test'), patch('subprocess.run'), patch.object(board, 'git_revision', return_value='a'*40):
                board.stage_module('hal_test', 'a'*40, destination, workspace, 'python', 'git')
            self.assertTrue(destination.is_dir())
