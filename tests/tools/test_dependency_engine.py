# SPDX-License-Identifier: Apache-2.0
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[2] / 'plugins/io.github.niwantha33.zew.ui/tools'
spec = importlib.util.spec_from_file_location('engine', TOOLS / 'dependency_engine.py')
engine = importlib.util.module_from_spec(spec); spec.loader.exec_module(engine)


class EngineTests(unittest.TestCase):
    def context(self, root):
        backend = types.SimpleNamespace(setup=types.SimpleNamespace(log=lambda message: None))
        return engine.Context(backend, dict(board='test/soc/cpu', soc='soc', vendor='test', modules=[]),
                              root, root / 'sdk', root / 'workspace', sys.executable, 'git')

    def test_unknown_tool_rejected_before_providers(self):
        with tempfile.TemporaryDirectory() as home:
            called = []
            provider = types.SimpleNamespace(name='compiler', prepare=lambda ctx: called.append(ctx))
            with self.assertRaisesRegex(engine.DependencyError, 'no reviewed|No reviewed'):
                engine.DependencyResolver([provider], {}).prepare(self.context(Path(home)), ['UNKNOWN'])
            self.assertEqual(called, [])
            self.assertFalse((Path(home) / '.zew-dependencies.lock').exists())

    def test_dependency_fingerprint_stable_on_reuse(self):
        with tempfile.TemporaryDirectory(prefix='dependencies Ω ') as home:
            root = Path(home)
            provider = types.SimpleNamespace(name='test', prepare=lambda ctx: ctx.record('compiler', 'test', version='1'))
            resolver = engine.DependencyResolver([provider], {})
            first = resolver.prepare(self.context(root))
            second = resolver.prepare(self.context(root))
            self.assertEqual(first, second)
            self.assertEqual(json.loads(next((root / '.zew-dependency-state').glob('*.json')).read_text()), first)

    def test_failed_provider_cannot_publish_success_and_preserves_old_state(self):
        with tempfile.TemporaryDirectory() as home:
            root = Path(home); ctx = self.context(root)
            resolver = engine.DependencyResolver([], {})
            resolver.prepare(ctx)
            state = next((root / '.zew-dependency-state').glob('*.json')); original = state.read_bytes()
            def fail(ctx):
                raise OSError('interrupted install')
            resolver.bootstrap = [types.SimpleNamespace(name='staging', prepare=fail)]
            with self.assertRaisesRegex(engine.DependencyError, 'staging.*interrupted install'):
                resolver.prepare(self.context(root))
            self.assertEqual(state.read_bytes(), original)
            with engine.InstallationLock(root):
                pass  # failed provider releases the installation lock

    def test_process_lock_contention_and_release(self):
        with tempfile.TemporaryDirectory() as home:
            script = ('import importlib.util,sys; s=importlib.util.spec_from_file_location("engine",sys.argv[1]);'
                      'm=importlib.util.module_from_spec(s);s.loader.exec_module(m);'
                      'm.InstallationLock(sys.argv[2]).__enter__()')
            with engine.InstallationLock(home):
                result = subprocess.run([sys.executable, '-I', '-c', script, str(TOOLS / 'dependency_engine.py'), home],
                                        capture_output=True, text=True, timeout=15)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('Another process', result.stderr)
            result = subprocess.run([sys.executable, '-I', '-c', script, str(TOOLS / 'dependency_engine.py'), home],
                                    capture_output=True, text=True, timeout=15)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_separate_environments_can_prepare_in_parallel(self):
        with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
            with engine.InstallationLock(first), engine.InstallationLock(second):
                pass

    def test_recovery_is_bounded_and_prevalidates_all_tools(self):
        provider = types.SimpleNamespace(name='IMGTOOL')
        recovery = engine.RecoveryManager({'IMGTOOL': provider}, limit=1)
        with self.assertRaisesRegex(engine.DependencyError, 'No reviewed'):
            recovery.resolve(['IMGTOOL', 'UNKNOWN'])
        self.assertEqual(recovery.resolve(['IMGTOOL']), [provider])
        with self.assertRaisesRegex(engine.DependencyError, 'exhausted'):
            recovery.resolve(['IMGTOOL'])

    def test_compiler_wrong_machine_rejected(self):
        with tempfile.TemporaryDirectory() as home:
            ctx = self.context(Path(home)); ctx.details.update(compiler='arm-zephyr-eabi', asset={'sha256':'a'*64})
            ctx.backend.prepare_compiler = lambda *args: None
            ctx.backend.PINNED_SDK = '1.0.1'
            with patch('subprocess.check_output', return_value='riscv64-zephyr-elf\n'):
                with self.assertRaisesRegex(engine.DependencyError, 'architecture mismatch'):
                    engine.CompilerProvider().prepare(ctx)
            self.assertEqual(ctx.evidence, [])

    def test_dirty_module_preserved(self):
        with tempfile.TemporaryDirectory() as home:
            ctx = self.context(Path(home)); ctx.details.update(modules=['hal_test'], manifest={'hal_test':('a'*40, Path('modules/hal/test'))})
            ctx.backend.prepare_modules = lambda *args: None
            ctx.backend.activate_modules = lambda *args: None
            with patch('subprocess.check_output', return_value=' M zephyr/module.yml\n'):
                with self.assertRaisesRegex(engine.DependencyError, 'local changes; preserved'):
                    engine.WestModuleProvider().prepare(ctx)
            self.assertEqual(ctx.evidence, [])

    def test_provider_selection_follows_hal_not_board_vendor(self):
        with tempfile.TemporaryDirectory() as home:
            ctx = self.context(Path(home)); ctx.details['modules'] = ['hal_espressif']
            calls = []; ctx.backend.legacy = types.SimpleNamespace(install_python_tools=lambda root: calls.append(root))
            ctx.backend.HERE = TOOLS
            engine.PythonPackageProvider().prepare(ctx)
            self.assertEqual(calls, [Path(home)])
            ctx.details['modules'] = ['hal_ti']; engine.PythonPackageProvider().prepare(ctx)
            self.assertEqual(len(calls), 1)

    @unittest.skipIf(sys.platform == 'win32', 'symlink creation may require privileges')
    def test_state_symlinks_rejected(self):
        with tempfile.TemporaryDirectory() as home:
            root = Path(home); outside = root / 'outside'; outside.mkdir()
            (root / '.zew-dependency-state').symlink_to(outside, target_is_directory=True)
            with self.assertRaisesRegex(engine.DependencyError, 'symlinked'):
                engine.DependencyResolver([], {}).prepare(self.context(root))
            self.assertEqual(list(outside.iterdir()), [])


if __name__ == '__main__': unittest.main()

class EvidenceTests(unittest.TestCase):
    context = EngineTests.context
    def test_corrupt_evidence_rejected_before_mutation(self):
        with tempfile.TemporaryDirectory() as home:
            root = Path(home)
            engine.DependencyResolver([], {}).prepare(self.context(root))
            state = next((root / '.zew-dependency-state').glob('*.json'))
            evidence = json.loads(state.read_text()); evidence['soc'] = 'tampered'
            state.write_text(json.dumps(evidence))
            with self.assertRaisesRegex(engine.DependencyError, 'corrupt'):
                engine.DependencyResolver([], {}).prepare(self.context(root))

    def test_generated_host_capability_is_remembered_on_repeated_build(self):
        with tempfile.TemporaryDirectory() as home:
            root = Path(home)
            def ctx():
                value = self.context(root)
                value.details['manifest'] = {'mcuboot': ('a'*40, Path('bootloader/mcuboot'))}
                return value
            provider = types.SimpleNamespace(name='IMGTOOL', prepare=lambda value: value.record('host-tool', 'IMGTOOL'))
            resolver = engine.DependencyResolver([], {'IMGTOOL': provider})
            initial = resolver.prepare(ctx(), ['IMGTOOL'])
            self.assertEqual(initial, resolver.prepare(ctx()))
