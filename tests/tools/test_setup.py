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
    def test_source_profile_markers_are_distinct_and_validate_tags(self):
        self.assertEqual(setup.source_profile_marker(None), 'first-build-win-x64-v1\n')
        self.assertEqual(setup.source_profile_marker('v3.7.0'), 'source-only-win-x64-v1:v3.7.0\n')
        for bad in ('v4.4.0', 'main', 'v4.5.0-rc1', 'v4.5.0;echo'):
            with self.assertRaises(ValueError):
                setup.source_profile_marker(bad)

    def source_sdk_fixture(self, root, version="1.0.1"):
        (root / '.zew-managed').write_text(setup.source_profile_marker('v4.5.0'))
        (root / 'cache').mkdir()
        workspace = root / 'workspace'
        (workspace / 'zephyr').mkdir(parents=True)
        (workspace / '.zew').mkdir()
        (workspace / '.zew-source-ready').write_text('SOURCE_VERIFIED_ONLY\n')
        (workspace / 'zephyr/SDK_VERSION').write_text(version)
        record = {'schema': 1, 'kind': 'zephyr-source-only', 'status': 'SOURCE_VERIFIED_ONLY',
                  'release': 'v4.5.0', 'recommended_sdk_version': version,
                  'zephyr_commit': 'a' * 40, 'west_manifest_sha256': 'b' * 64}
        lock = workspace / '.zew/source-lock.json'
        lock.write_text(json.dumps(record))
        return lock, record

    def test_source_sdk_provisions_only_reviewed_recommendation_and_records_identity(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder).resolve()
            self.source_sdk_fixture(root)
            with patch.object(setup, 'install_sdk', return_value=root / 'sdk') as install:
                self.assertEqual(root / 'sdk', setup.prepare_source_sdk(root, 'v4.5.0'))
            self.assertEqual(root, install.call_args.args[0])
            receipt = install.call_args.kwargs['source_receipt']
            self.assertEqual('v4.5.0', receipt['release'])
            self.assertEqual('a' * 40, receipt['zephyr_commit'])
            self.assertEqual(setup.SDK_ASSETS, receipt['artifacts'])
            self.assertFalse(receipt['firmware_built'])
            self.assertFalse((root / '.zew-ready').exists())
            self.assertFalse((root / 'environment.properties').exists())

    def test_source_sdk_refuses_unreviewed_version_before_download(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder).resolve()
            self.source_sdk_fixture(root, '2.0.0')
            with patch.object(setup, 'install_sdk') as install:
                with self.assertRaisesRegex(ValueError, 'No reviewed Windows SDK'):
                    setup.prepare_source_sdk(root, 'v4.5.0')
                install.assert_not_called()
            self.assertTrue((root / 'workspace/.zew-source-ready').exists())

    def test_source_sdk_refuses_tampered_recommendation_and_ready_setup(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder).resolve()
            lock, record = self.source_sdk_fixture(root)
            record['recommended_sdk_version'] = '2.0.0'
            lock.write_text(json.dumps(record))
            with patch.object(setup, 'install_sdk') as install:
                with self.assertRaisesRegex(ValueError, 'differs'):
                    setup.prepare_source_sdk(root, 'v4.5.0')
                (root / '.zew-ready').write_text('existing')
                with self.assertRaisesRegex(ValueError, 'source-only'):
                    setup.prepare_source_sdk(root, 'v4.5.0')
                install.assert_not_called()
            self.assertEqual('existing', (root / '.zew-ready').read_text())

    def test_source_sdk_reuse_requires_matching_receipt(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder).resolve()
            sdk = root / 'sdk'
            compiler = sdk / 'gnu/x86_64-zephyr-elf/bin/x86_64-zephyr-elf-gcc.exe'
            compiler.parent.mkdir(parents=True)
            compiler.write_bytes(b'fixture')
            (sdk / 'sdk_version').write_text('1.0.1')
            (sdk / '.zew-sdk-ready').write_text('SDK 1.0.1 / x86_64-zephyr-elf\n')
            receipt = {'release': 'v4.5.0'}
            with patch.dict('sys.modules', {'py7zr': SimpleNamespace()}), patch.object(setup, 'fetch') as fetch:
                with self.assertRaisesRegex(ValueError, 'matching source provisioning receipt'):
                    setup.install_sdk(root, source_receipt=receipt)
                (sdk / '.zew-source-sdk.json').write_text(json.dumps(receipt))
                self.assertEqual(sdk, setup.install_sdk(root, source_receipt=receipt))
                with self.assertRaisesRegex(ValueError, 'matching source provisioning receipt'):
                    setup.install_sdk(root, source_receipt={'release': 'v4.6.0'})
                fetch.assert_not_called()

    def test_windows_build_arguments_use_cmake_paths(self):
        command = setup.first_build_command(r'C:\Sandbox\Zephyr Setup Ω\python\python.exe',
                r'C:\Sandbox\Zephyr Setup Ω\projects\hello_world',
                r'C:\Sandbox\Zephyr Setup Ω\projects\hello_world\.zew-build\first',
                'qemu_x86/atom', r'C:\Sandbox\Zephyr Setup Ω\python\Scripts\ninja.exe')
        self.assertEqual(command[command.index('--build-dir')+1], 'C:/Sandbox/Zephyr Setup Ω/projects/hello_world/.zew-build/first')
        definitions = command[command.index('--')+1:]
        self.assertIn('-DPython3_EXECUTABLE:FILEPATH=C:/Sandbox/Zephyr Setup Ω/python/python.exe', definitions)
        self.assertIn('-DCMAKE_MAKE_PROGRAM:FILEPATH=C:/Sandbox/Zephyr Setup Ω/python/Scripts/ninja.exe', definitions)
        self.assertTrue(all('\\' not in value for value in definitions))
        self.assertIn('C:/Sandbox/Zephyr Setup Ω/projects/hello_world', command)

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

    def test_offline_cache_preflight_reports_all_missing_and_corrupt_files(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            content = b'valid'
            artifacts = [dict(file=name, size=len(content), sha256=hashlib.sha256(content).hexdigest())
                         for name in ('valid.whl', 'missing.whl', 'partial.whl', 'corrupt.whl', 'short.whl')]
            (cache / 'valid.whl').write_bytes(content)
            (cache / 'partial.whl.partial').write_bytes(b'v')
            (cache / 'corrupt.whl').write_bytes(b'wrong')
            (cache / 'short.whl').write_bytes(b'v')
            with patch.object(setup.urllib.request, 'urlopen', side_effect=AssertionError('network forbidden')):
                result = setup.inspect_cached_artifacts(cache, artifacts)
            self.assertEqual(['verified', 'missing', 'interrupted', 'sha256-mismatch', 'size-mismatch'],
                             [row['status'] for row in result])
            self.assertTrue((cache / 'partial.whl.partial').is_file())

    def test_offline_fetch_has_no_network_fallback(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            content = b'fixture'
            artifact = dict(file='fixture.whl', size=len(content),
                            sha256=hashlib.sha256(content).hexdigest(),
                            url='https://files.pythonhosted.org/fixture.whl')
            with patch.object(setup.urllib.request, 'urlopen', side_effect=AssertionError('network forbidden')):
                with self.assertRaisesRegex(ValueError, 'Offline cache missing or invalid'):
                    setup.fetch(artifact, cache, offline=True)
                target = cache / artifact['file']
                target.write_bytes(content)
                self.assertEqual(target, setup.fetch(artifact, cache, offline=True))
                target.write_bytes(b'bad')
                with self.assertRaisesRegex(ValueError, 'Offline cache missing or invalid'):
                    setup.fetch(artifact, cache, offline=True)

    def test_offline_source_never_clones_missing_checkout(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(setup, 'run', side_effect=AssertionError('clone forbidden')):
                with self.assertRaisesRegex(ValueError, 'requires the original local'):
                    setup.ensure_source(Path(directory), 'git', self.lock(), {}, offline=True)

    def test_offline_environment_does_not_inherit_pip_remotes(self):
        host = {'Path': 'selected', 'pip_find_links': 'https://example.org/wheels',
                'PIP_INDEX_URL': 'https://example.org/simple', 'TEMP': 'system-temp'}
        result = setup.offline_environment(host)
        self.assertNotIn('pip_find_links', result)
        self.assertNotIn('PIP_INDEX_URL', result)
        self.assertEqual('file', result['GIT_ALLOW_PROTOCOL'])
        self.assertEqual('1', result['PIP_NO_INDEX'])
        self.assertEqual('system-temp', result['TEMP'])
        self.assertIn('PIP_INDEX_URL', host)

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
            old = subprocess.run([sys.executable, '-I', '-S', '-X', 'utf8', '-c', 'import runpy;runpy.run_path("setup.py",run_name="__main__")',
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
            self.assertIn(r'C\:\\User Ω\\python.exe', path.read_text(encoding='utf-8'))
            self.assertFalse(path.with_suffix('.partial').exists())

    def test_failed_build_cannot_mark_environment_ready(self):
        self._build_case(False)

    def test_ready_only_after_verified_firmware_and_bindings(self):
        self._build_case(True)

    def test_managed_search_excludes_global_tools_from_installer_and_matrix(self):
        root = Path.cwd() / 'setup'
        windows = root.parent / 'windows'
        foreign = root.parent / 'foreign'
        cmake = setup.installed_executable(root, self.lock(), 'cmake')
        ninja = setup.installed_executable(root, self.lock(), 'ninja')
        git = root / 'git/cmd/git.exe'
        host = {'Path': str(foreign), 'SystemRoot': str(windows),
                'CMAKE_PROGRAM_PATH': str(foreign), 'WEST_CONFIG_LOCAL': str(foreign / 'config')}
        for sdk in (None, root / 'sdk'):
            env = setup.managed_environment(root, cmake, ninja, git, host, sdk)
            paths = env['PATH'].split(setup.os.pathsep)
            self.assertEqual(paths[:3], [str(cmake.parent), str(ninja.parent), str(git.parent)])
            self.assertIn(str(windows / 'System32'), paths)
            self.assertNotIn(str(foreign), paths)
            self.assertNotIn('CMAKE_PROGRAM_PATH', env)
            self.assertNotIn('WEST_CONFIG_LOCAL', env)
            self.assertEqual(str(root / 'sdk/hosttools/bin') in paths, sdk is not None)

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
            sites = subprocess.check_output([str(python), '-I', '-X', 'utf8', '-c',
                    'import site;print(site.getsitepackages()[0])'], text=True).strip()
            Path(sites).mkdir(parents=True, exist_ok=True)
            (Path(sites) / 'zew-test.pth').write_text(str(root / 'python') + '\n', encoding='utf-8')
            def execute(script):
                return subprocess.run([str(python), '-I', '-X', 'utf8', str(script)], cwd=root,
                                      text=True, capture_output=True, timeout=20)
            before = execute(board)
            self.assertNotEqual(before.returncode, 0)
            self.assertIn("No module named 'list_hardware'", before.stderr)
            setup.install_runtime_support(root)
            # The embedded Windows ._pth runtime imports its own sitecustomize.
            # A Linux venv can load /usr/lib/python*/sitecustomize.py first,
            # so explicitly execute the installed hook during .pth startup.
            # Unlike runpy.run_path(), exec preserves sys.argv[0], allowing
            # the real startup hook to inspect the launched Zephyr script.
            hook = root / 'python/sitecustomize.py'
            Path(sites, 'zew-test.pth').write_text(
                str(root / 'python') + '\n' +
                'import pathlib; p=pathlib.Path(' + repr(str(hook)) +
                '); exec(compile(p.read_bytes(), str(p), "exec"), {"__file__":str(p)})\n',
                encoding='utf-8')
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




class DownloadRecoveryTests(unittest.TestCase):
    def test_transient_sdk_gateway_timeout_retries_with_verified_hash(self):
        import urllib.error
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = {'file': 'sdk.7z', 'size': 4,
                        'sha256': hashlib.sha256(b'good').hexdigest(),
                        'url': 'https://github.com/zephyrproject-rtos/sdk-ng/releases/download/v1.0.1/sdk.7z'}
            class Response:
                url = artifact['url']
                def __init__(self): self.sent = False
                def __enter__(self): return self
                def __exit__(self, *unused): pass
                def read(self, size):
                    if self.sent: return b''
                    self.sent = True
                    return b'good'
            failure = urllib.error.HTTPError(artifact['url'], 504, 'Gateway Time-out', {}, None)
            with patch.object(setup.urllib.request, 'urlopen',
                              side_effect=[failure, failure, failure, Response()]) as request, \
                 patch.object(setup.time, 'sleep') as pause:
                result = setup.fetch(artifact, root)
            self.assertEqual(request.call_count, 4)
            self.assertEqual(pause.call_count, 3)
            self.assertEqual(result.read_bytes(), b'good')
            self.assertFalse((root / 'sdk.7z.partial').exists())

    def test_interrupted_download_retries_and_only_activates_verified_content(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = {'file': 'test.whl', 'size': 4, 'sha256': hashlib.sha256(b'good').hexdigest(),
                        'url': 'https://files.pythonhosted.org/test.whl'}
            class Response:
                url = artifact['url']
                def __init__(self, interrupted): self.interrupted, self.reads = interrupted, 0
                def __enter__(self): return self
                def __exit__(self, *unused): pass
                def read(self, size):
                    self.reads += 1
                    if self.interrupted and self.reads == 2: raise OSError('connection lost')
                    return (b'go' if self.interrupted else b'good') if self.reads == 1 else b''
            with patch.object(setup.urllib.request, 'urlopen', side_effect=[Response(True), Response(False)]) as request, patch.object(setup.time, 'sleep'):
                result = setup.fetch(artifact, root)
            self.assertEqual(request.call_count, 2)
            self.assertEqual(result.read_bytes(), b'good')
            self.assertFalse((root / 'test.whl.partial').exists())
