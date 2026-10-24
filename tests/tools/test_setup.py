# SPDX-License-Identifier: Apache-2.0
import copy
import importlib
import importlib.util
import hashlib
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import subprocess
import sys
import tarfile
import io
import unittest
import venv
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / 'plugins/io.github.niwantha33.zew.ui/tools'
spec = importlib.util.spec_from_file_location('setup_helper', TOOLS / 'setup.py')
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)


class SetupTests(unittest.TestCase):
    def test_child_install_into_initially_missing_package_path(self):
        # Reproduce a first-ever embedded-runtime install: the path was absent
        # when the parent searched it, then pip's child creates the directory.
        module = 'zew_new_package_fixture'
        with tempfile.TemporaryDirectory(prefix='fresh packages Ω ') as directory:
            packages = Path(directory) / 'site-packages'
            previous = sys.path.copy()
            sys.path.insert(0, str(packages))
            try:
                self.assertIsNone(importlib.util.find_spec(module))
                self.assertIn(str(packages), sys.path_importer_cache)
                self.assertIsNone(sys.path_importer_cache[str(packages)])
                subprocess.run([sys.executable, '-I', '-c',
                    'import pathlib,sys;p=pathlib.Path(sys.argv[1]);p.mkdir();'
                    '(p/"zew_new_package_fixture.py").write_text("VALUE=42")', str(packages)], check=True, timeout=20)
                with self.assertRaises(ModuleNotFoundError):
                    importlib.import_module(module)
                original = importlib.import_module
                def import_extractor(name):
                    self.assertEqual(name, 'py7zr')
                    return original(module)
                with patch.object(setup.importlib, 'import_module', side_effect=import_extractor):
                    setup.verify_installed_runtime()
                self.assertEqual(sys.modules[module].VALUE, 42)
            finally:
                sys.path[:] = previous
                sys.modules.pop(module, None)
                sys.path_importer_cache.pop(str(packages), None)

    def test_broken_extractor_import_stops_package_stage(self):
        with patch.object(setup.importlib, 'import_module', side_effect=ModuleNotFoundError('broken dependency')):
            self.assertRaisesRegex(ModuleNotFoundError, 'broken dependency', setup.verify_installed_runtime)

    def lock(self):
        return json.loads((TOOLS / 'setup-lock.json').read_text())

    def test_pinned_profile_and_all_licence_records(self):
        lock = self.lock(); setup.validate_lock(lock)
        self.assertEqual(len(lock['packages']), 32)
        self.assertTrue(all(not p['mandatory_fees'] for p in lock['packages']))
        self.assertEqual({p['name'] for p in lock['packages']} & {'pylink-square'}, set())

    def test_pending_licence_blocks_installation(self):
        lock = self.lock(); lock['packages'][0]['review'] = 'pending'
        self.assertRaises(ValueError, setup.validate_lock, lock)

    def test_changed_source_profile_rejected(self):
        lock = self.lock(); lock['zephyr_commit'] = '0' * 40
        self.assertRaises(ValueError, setup.validate_lock, lock)

    def test_duplicate_package_rejected(self):
        lock = self.lock(); lock['packages'].append(copy.deepcopy(lock['packages'][0]))
        self.assertRaises(ValueError, setup.validate_lock, lock)

    def test_cache_requires_size_and_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'artifact'; path.write_bytes(b'good')
            item = {'size': 4, 'sha256': hashlib.sha256(b'good').hexdigest()}
            self.assertTrue(setup.valid_cache(path, item))
            path.write_bytes(b'evil'); self.assertFalse(setup.valid_cache(path, item))
            path.write_bytes(b'go'); self.assertFalse(setup.valid_cache(path, item))

    def test_cached_download_is_reused_without_network(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'a.whl'; path.write_bytes(b'good')
            item = {'file': path.name, 'size': 4, 'sha256': hashlib.sha256(b'good').hexdigest(), 'url': 'https://files.pythonhosted.org/a.whl'}
            with patch.object(setup.urllib.request, 'urlopen', side_effect=AssertionError('unexpected network')):
                self.assertEqual(setup.fetch(item, Path(directory)), path)

    def test_foreign_download_origin_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            item = {'file': 'a', 'size': 1, 'sha256': 'a' * 64, 'url': 'https://unapproved.invalid/a'}
            self.assertRaises(ValueError, setup.fetch, item, Path(directory))

    def test_requirements_handle_spaces_and_unicode(self):
        with tempfile.TemporaryDirectory(prefix='setup Ω ') as directory:
            root = Path(directory); target = root / 'requirements.txt'
            setup.requirements_file([{'file': 'a.whl', 'sha256': 'a' * 64}], root, target)
            self.assertIn('%20', target.read_text()); self.assertIn('--hash=sha256:', target.read_text())

    def test_reviewed_wheel_executable_layouts(self):
        root = Path('/managed')
        self.assertEqual(setup.installed_executable(root, self.lock(), 'ninja'), root / 'python/Scripts/ninja.exe')
        self.assertEqual(setup.installed_executable(root, self.lock(), 'cmake'), root / 'python/Lib/site-packages/cmake/data/bin/cmake.exe')
        lock = self.lock(); next(p for p in lock['packages'] if p['name'] == 'ninja')['installed_executable'] = '../../outside'
        self.assertRaises(ValueError, setup.installed_executable, root, lock, 'ninja')

    def source_fixture(self, root):
        source = root / 'fixture/docopt-0.6.2'; source.mkdir(parents=True)
        (source / 'docopt.py').write_text('__version__ = "0.6.2"\n')
        (source / 'setup.py').write_text('''from docopt import __version__
import pathlib,sys,zipfile
output=pathlib.Path(sys.argv[sys.argv.index('--dist-dir')+1])
with zipfile.ZipFile(output/'docopt-0.6.2-py3-none-any.whl','w') as wheel:
    wheel.writestr('docopt.py','__version__="0.6.2"')
    wheel.writestr('docopt-0.6.2.dist-info/METADATA','Metadata-Version: 2.1\\nName: docopt\\nVersion: '+__version__+'\\n')
    wheel.writestr('docopt-0.6.2.dist-info/LICENSE-MIT','MIT fixture licence')
''')
        archive = root / 'docopt-0.6.2.tar.gz'
        with tarfile.open(archive, 'w:gz') as output: output.add(source, arcname='docopt-0.6.2')
        return source, {'name': 'docopt', 'version': '0.6.2', 'file': archive.name,
                        'size': archive.stat().st_size, 'sha256': setup.digest(archive), 'review': 'approved-build-use'}

    def test_isolated_source_failure_reproduced_and_fixed(self):
        with tempfile.TemporaryDirectory(prefix='isolated source Ω ') as directory:
            root = Path(directory); source, package = self.source_fixture(root)
            output = root / 'old-output'; output.mkdir()
            old = subprocess.run([sys.executable, '-I', '-c', 'import runpy;runpy.run_path("setup.py",run_name="__main__")',
                                  'bdist_wheel', '--dist-dir', output], cwd=source, text=True,
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=20)
            self.assertNotEqual(old.returncode, 0); self.assertIn("No module named 'docopt'", old.stdout)
            previous_path = sys.path.copy()
            result = setup.build_source_wheel(package, root, dict(setup.os.environ))
            self.assertEqual(sys.path, previous_path)
            self.assertTrue(result['file'].endswith('.whl'))
            self.assertTrue(setup.valid_cache(root / result['file'], result))
            proof = json.loads((root / 'docopt-local-build.json').read_text())
            self.assertEqual(proof['source_sha256'], package['sha256'])
            self.assertEqual(proof['wheel_sha256'], result['sha256'])
            self.assertFalse(any(root.glob('*._pth')))

    def test_changed_source_archive_blocks_build_execution(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); _, package = self.source_fixture(root)
            (root / package['file']).write_bytes(b'tampered')
            with patch.object(setup, 'run', side_effect=AssertionError('should not execute')):
                self.assertRaises(ValueError, setup.build_source_wheel, package, root, {})

    def test_source_archive_traversal_and_links_rejected(self):
        for kind in ('traversal', 'link'):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory); archive = root / 'docopt-0.6.2.tar.gz'
                with tarfile.open(archive, 'w:gz') as output:
                    member = tarfile.TarInfo('../outside' if kind == 'traversal' else 'docopt-0.6.2/link')
                    if kind == 'link': member.type = tarfile.SYMTYPE; member.linkname = '../../outside'
                    output.addfile(member, io.BytesIO())
                package = {'name': 'docopt', 'version': '0.6.2', 'file': archive.name, 'size': archive.stat().st_size,
                           'sha256': setup.digest(archive), 'review': 'approved-build-use'}
                with patch.object(setup, 'run', side_effect=AssertionError('should not execute')):
                    self.assertRaises(ValueError, setup.build_source_wheel, package, root, {})

    def test_unreviewed_source_build_rejected(self):
        self.assertRaises(ValueError, setup.build_source_wheel,
                          {'name': 'other', 'version': '0.6.2', 'review': 'approved-build-use'}, Path('.'), {})

    def test_sdk_archive_traversal_rejected(self):
        for name in ('../outside', '/outside', 'C:/outside', 'folder\\outside'):
            self.assertRaises(ValueError, setup.safe_archive_names, [SimpleNamespace(filename=name, uncompressed=1)])

    def test_sdk_archive_link_and_expansion_rejected(self):
        self.assertRaises(ValueError, setup.safe_archive_names, [SimpleNamespace(filename='link', uncompressed=1, is_symlink=True)])
        self.assertRaises(ValueError, setup.safe_archive_names, [SimpleNamespace(filename='large', uncompressed=9 * 1024**3)])

    def test_starter_creation_and_retry_preserve_edits(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); app = setup.create_application(root)
            (app / 'src/main.c').write_text('USER EDIT')
            self.assertEqual(setup.create_application(root), app)
            self.assertEqual((app / 'src/main.c').read_text(), 'USER EDIT')
            self.assertTrue((app / 'CMakeLists.txt').is_file()); self.assertTrue((app / 'prj.conf').is_file())

    def test_existing_nonowned_project_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); project = root / 'projects/hello_world'; project.mkdir(parents=True)
            (project / 'user.txt').write_text('keep')
            self.assertRaises(ValueError, setup.create_application, root)
            self.assertEqual((project / 'user.txt').read_text(), 'keep')

    def test_properties_escape_windows_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'environment.properties'
            setup.write_properties({'python': r'C:\User Ω\python.exe'}, path)
            self.assertIn(r'C\:\\User Ω\\python.exe', path.read_text())
            self.assertFalse(path.with_suffix('.partial').exists())

    def test_failed_build_cannot_mark_environment_ready(self):
        self._build_case(False)

    def test_ready_only_after_verified_firmware_and_bindings(self):
        self._build_case(True)

    def _build_case(self, success):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); (root / '.zew-managed').write_text('first-build-win-x64-v1\n'); workspace = root / 'workspace'; workspace.mkdir()
            (root / 'python').mkdir()
            for name in ('cmake', 'ninja'):
                path = setup.installed_executable(root, self.lock(), name); path.parent.mkdir(parents=True, exist_ok=True); path.touch()
            def run(command, *unused):
                if success:
                    path = Path(command[command.index('--build-dir') + 1]) / 'zephyr/zephyr.elf'
                    path.parent.mkdir(parents=True); path.write_bytes(b'fixture firmware')
            with patch.object(setup.sys, 'platform', 'win32'), patch.object(setup.shutil, 'disk_usage', return_value=SimpleNamespace(free=10 * 1024**3)), \
                 patch.object(setup, 'install_packages'), patch.object(setup, 'install_sdk', return_value=root / 'sdk'), \
                 patch.object(setup, 'check_zephyr_scripts'), \
                 patch.object(setup, 'ensure_source', return_value=(workspace, workspace / 'zephyr')), patch.object(setup, 'run', side_effect=run):
                if success:
                    setup.setup(root, self.lock()); self.assertTrue((root / '.zew-ready').is_file()); self.assertTrue((root / 'environment.properties').is_file())
                else:
                    self.assertRaises(ValueError, setup.setup, root, self.lock()); self.assertFalse((root / '.zew-ready').exists())

    def test_managed_script_sibling_imports_under_real_isolated_startup(self):
        with tempfile.TemporaryDirectory(prefix='managed runtime Ω ') as directory:
            root = Path(directory); (root / 'python').mkdir()
            scripts = root / 'workspace/zephyr/scripts'; scripts.mkdir(parents=True)
            (scripts / 'list_hardware.py').write_text('VALUE = "sibling-import-ok"\n')
            board = scripts / 'list_boards.py'
            board.write_text('from list_hardware import VALUE\nprint(VALUE)\n')
            runtime = root / 'test-venv'; venv.EnvBuilder(with_pip=False).create(runtime)
            python = runtime / ('Scripts/python.exe' if sys.platform == 'win32' else 'bin/python')
            # Add just the embedded runtime directory, reproducing ._pth's
            # explicit search path; -I excludes the script directory and cwd.
            sites = subprocess.check_output([str(python), '-I', '-c',
                    'import site;print(site.getsitepackages()[0])'], text=True).strip()
            Path(sites).mkdir(parents=True, exist_ok=True)
            (Path(sites) / 'zew-test.pth').write_text(str(root / 'python') + '\n', encoding='utf-8')
            def execute(script):
                return subprocess.run([str(python), '-I', str(script)], cwd=root,
                                      text=True, capture_output=True, timeout=20)
            before = execute(board)
            self.assertNotEqual(before.returncode, 0)
            self.assertIn("No module named 'list_hardware'", before.stderr)
            setup.install_runtime_support(root)
            after = execute(board)
            self.assertEqual(after.returncode, 0, after.stderr)
            self.assertEqual(after.stdout.strip(), 'sibling-import-ok')
            nested = scripts / 'build'; nested.mkdir()
            (nested / 'helper.py').write_text('VALUE = "nested-import-ok"\n')
            (nested / 'generate.py').write_text('from helper import VALUE\nprint(VALUE)\n')
            self.assertEqual(execute(nested / 'generate.py').stdout.strip(), 'nested-import-ok')
            outside = root / 'outside'; outside.mkdir()
            (outside / 'list_boards.py').write_text(board.read_text())
            (outside / 'list_hardware.py').write_text('VALUE = "must-not-import"\n')
            self.assertNotEqual(execute(outside / 'list_boards.py').returncode, 0)
            # A corrupted hook is repaired on retry, without touching source.
            (root / 'python/sitecustomize.py').write_text('raise RuntimeError("old")')
            setup.install_runtime_support(root)
            self.assertEqual(execute(board).returncode, 0)
            self.assertEqual(board.read_text(), 'from list_hardware import VALUE\nprint(VALUE)\n')

    def test_script_preflight_failure_cannot_mark_ready(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); (root / 'python').mkdir()
            (root / '.zew-managed').write_text('first-build-win-x64-v1\n')
            for name in ('cmake', 'ninja'):
                path = setup.installed_executable(root, self.lock(), name)
                path.parent.mkdir(parents=True, exist_ok=True); path.touch()
            with patch.object(setup.sys, 'platform', 'win32'), \
                 patch.object(setup.shutil, 'disk_usage', return_value=SimpleNamespace(free=10 * 1024**3)), \
                 patch.object(setup, 'install_packages'), patch.object(setup, 'install_sdk', return_value=root / 'sdk'), \
                 patch.object(setup, 'ensure_source', return_value=(root / 'workspace', root / 'workspace/zephyr')), \
                 patch.object(setup, 'check_zephyr_scripts', side_effect=RuntimeError('broken imports')), \
                 patch.object(setup, 'create_application', side_effect=AssertionError('must stop before project/build')):
                self.assertRaisesRegex(RuntimeError, 'broken imports', setup.setup, root, self.lock())
            self.assertFalse((root / '.zew-ready').exists())


if __name__ == '__main__':
    unittest.main()


