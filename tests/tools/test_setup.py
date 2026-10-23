# SPDX-License-Identifier: Apache-2.0
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / 'plugins/io.github.niwantha33.zew.ui/tools'
spec = importlib.util.spec_from_file_location('setup_helper', TOOLS / 'setup.py')
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)


class SetupTests(unittest.TestCase):
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
            for name in ('cmake', 'ninja'):
                path = setup.installed_executable(root, self.lock(), name); path.parent.mkdir(parents=True, exist_ok=True); path.touch()
            def run(command, *unused):
                if success:
                    path = Path(command[command.index('--build-dir') + 1]) / 'zephyr/zephyr.elf'
                    path.parent.mkdir(parents=True); path.write_bytes(b'fixture firmware')
            with patch.object(setup.sys, 'platform', 'win32'), patch.object(setup.shutil, 'disk_usage', return_value=SimpleNamespace(free=10 * 1024**3)), \
                 patch.object(setup, 'install_packages'), patch.object(setup, 'install_sdk', return_value=root / 'sdk'), \
                 patch.object(setup, 'ensure_source', return_value=(workspace, workspace / 'zephyr')), patch.object(setup, 'run', side_effect=run):
                if success:
                    setup.setup(root, self.lock()); self.assertTrue((root / '.zew-ready').is_file()); self.assertTrue((root / 'environment.properties').is_file())
                else:
                    self.assertRaises(ValueError, setup.setup, root, self.lock()); self.assertFalse((root / '.zew-ready').exists())


if __name__ == '__main__':
    unittest.main()
