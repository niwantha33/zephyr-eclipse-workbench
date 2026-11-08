# SPDX-License-Identifier: Apache-2.0
"""Fault injection for interrupted setup; no network or host installations."""
import hashlib
import http.client
import io
import json
import os
import importlib.util
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('setup_recovery', ROOT / 'plugins/io.github.niwantha33.zew.ui/tools/setup.py')
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)


class SetupRecoveryTests(unittest.TestCase):
    def test_http_incomplete_read_retries_and_discards_partial(self):
        class Response:
            url = 'https://files.pythonhosted.org/a.whl'
            def __enter__(self): return self
            def __exit__(self, *unused): pass
            def read(self, size): raise http.client.IncompleteRead(b'partial', 100)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / 'a.whl'
            target.write_bytes(b'previous')
            artifact = {'file': target.name, 'size': 4, 'sha256': hashlib.sha256(b'good').hexdigest(), 'url': Response.url}
            with patch.object(setup.urllib.request, 'urlopen', return_value=Response()) as request, patch.object(setup.time, 'sleep'):
                with self.assertRaises(http.client.IncompleteRead): setup.fetch(artifact, root)
            self.assertEqual(request.call_count, 3)
            self.assertEqual(target.read_bytes(), b'previous')
            self.assertFalse((root / 'a.whl.partial').exists())

    def test_ci_large_sdk_read_timeout_uses_verified_github_asset_api(self):
        # A Windows SSL socket can raise OSError rather than TimeoutError.
        # Exercise the actual metadata+binary integrity gate, not a stub.
        artifact = {
            'file': 'toolchain_gnu_windows-x86_64_arm-zephyr-eabi.7z',
            'size': 10,
            'sha256': hashlib.sha256(b'validbytes').hexdigest(),
            'url': ('https://github.com/zephyrproject-rtos/sdk-ng/releases/download/v1.0.1/'
                    'toolchain_gnu_windows-x86_64_arm-zephyr-eabi.7z'),
        }
        metadata = json.dumps({'assets': [
            {'name': artifact['file'], 'id': 123, 'size': artifact['size'],
             'state': 'uploaded', 'digest': 'sha256:' + artifact['sha256']}
        ]}).encode()

        class Response:
            def __init__(self, url, payload, content_type='application/octet-stream'):
                self.url, self.stream = url, io.BytesIO(payload)
                self.headers = {'Content-Type': content_type}
            def __enter__(self):
                return self
            def __exit__(self, *unused):
                self.stream.close()
            def read(self, count=-1):
                return self.stream.read(count)

        api = 'https://api.github.com/repos/zephyrproject-rtos/sdk-ng'
        responses = [
            OSError('The read operation timed out'),
            Response(api + '/releases/tags/v1.0.1', metadata, 'application/json'),
            Response(api + '/releases/assets/123', b'validbytes'),
        ]
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            with patch.dict(os.environ, {'ZEW_CI_SDK_ASSET_API': '1'}), \
                 patch.object(setup.urllib.request, 'urlopen', side_effect=responses) as request, \
                 patch.object(setup.time, 'sleep') as sleep:
                downloaded = setup.fetch(artifact, cache)
            self.assertEqual(downloaded.read_bytes(), b'validbytes')
            self.assertEqual(request.call_count, 3)
            self.assertEqual(request.call_args_list[0].kwargs['timeout'], 120)
            self.assertEqual(request.call_args_list[2].kwargs['timeout'], 120)
            sleep.assert_not_called()
            self.assertFalse((cache / (artifact['file'] + '.partial')).exists())

    def test_ci_sdk_rejects_forged_asset_digest_after_timeout(self):
        artifact = {
            'file': 'toolchain_gnu_windows-x86_64_arm-zephyr-eabi.7z',
            'size': 4,
            'sha256': hashlib.sha256(b'good').hexdigest(),
            'url': ('https://github.com/zephyrproject-rtos/sdk-ng/releases/download/v1.0.1/'
                    'toolchain_gnu_windows-x86_64_arm-zephyr-eabi.7z'),
        }
        metadata = json.dumps({'assets': [
            {'name': artifact['file'], 'id': 123, 'size': 4,
             'state': 'uploaded', 'digest': 'sha256:' + ('0' * 64)}
        ]}).encode()

        class Response:
            url = 'https://api.github.com/repos/zephyrproject-rtos/sdk-ng/releases/tags/v1.0.1'
            def __enter__(self): return self
            def __exit__(self, *unused): pass
            def read(self, count=-1): return metadata

        with tempfile.TemporaryDirectory() as directory:
            with patch.dict(os.environ, {'ZEW_CI_SDK_ASSET_API': '1'}), \
                 patch.object(setup.urllib.request, 'urlopen',
                              side_effect=[OSError('The read operation timed out'), Response()]) as request, \
                 patch.object(setup.time, 'sleep') as sleep:
                with self.assertRaisesRegex(ValueError, 'digest differs'):
                    setup.fetch(artifact, Path(directory))
            self.assertEqual(request.call_count, 2)
            sleep.assert_not_called()
            self.assertFalse((Path(directory) / artifact['file']).exists())

    def test_non_ci_sdk_read_timeout_keeps_standard_timeout_and_no_api_fallback(self):
        artifact = {
            'file': 'toolchain_gnu_windows-x86_64_arm-zephyr-eabi.7z',
            'size': 4,
            'sha256': hashlib.sha256(b'good').hexdigest(),
            'url': ('https://github.com/zephyrproject-rtos/sdk-ng/releases/download/v1.0.1/'
                    'toolchain_gnu_windows-x86_64_arm-zephyr-eabi.7z'),
        }
        with tempfile.TemporaryDirectory() as directory:
            with patch.dict(os.environ, {'ZEW_CI_SDK_ASSET_API': ''}), \
                 patch.object(setup.urllib.request, 'urlopen',
                              side_effect=OSError('The read operation timed out')) as request, \
                 patch.object(setup, 'ci_sdk_asset_api_fetch',
                              side_effect=AssertionError('CI fallback must not be called')), \
                 patch.object(setup.time, 'sleep'):
                with self.assertRaisesRegex(OSError, 'timed out'):
                    setup.fetch(artifact, Path(directory))
            self.assertEqual(request.call_count, 3)
            self.assertTrue(all(c.kwargs['timeout'] == 30 for c in request.call_args_list))
            self.assertFalse((Path(directory) / artifact['file']).exists())

    def test_interrupted_starter_creation_never_activates_partial_application(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = Path.write_text
            def fail(path, data, *args, **kwargs):
                if path.name == 'CMakeLists.txt': raise OSError('disk write interrupted')
                return original(path, data, *args, **kwargs)
            with patch.object(Path, 'write_text', fail):
                with self.assertRaisesRegex(OSError, 'interrupted'): setup.create_application(root)
            self.assertFalse((root / 'projects/hello_world').exists())
            self.assertEqual(list((root / 'projects').iterdir()), [])
            project = setup.create_application(root)
            self.assertTrue((project / 'src/main.c').is_file())
            self.assertTrue((project / 'CMakeLists.txt').is_file())
            self.assertTrue((project / 'prj.conf').is_file())

    def test_legacy_marked_incomplete_starter_is_preserved_and_explained(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            project = setup.create_application(root)
            (project / 'src/main.c').write_text('user changes')
            (project / 'prj.conf').unlink()
            with self.assertRaisesRegex(ValueError, 'incomplete.*prj.conf'):
                setup.create_application(root)
            self.assertEqual((project / 'src/main.c').read_text(), 'user changes')
            self.assertFalse((project / 'prj.conf').exists())

    def test_sdk_marker_alone_cannot_make_incomplete_installation_ready(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sdk = root / 'sdk'; sdk.mkdir()
            (sdk / '.zew-sdk-ready').write_text('ready')
            with patch.dict('sys.modules', {'py7zr': SimpleNamespace()}), patch.object(setup, 'fetch', side_effect=AssertionError('no download')):
                with self.assertRaisesRegex(ValueError, 'preserved'):
                    setup.install_sdk(root)
                (sdk / 'sdk_version').write_text('1.0.1')
                with self.assertRaisesRegex(ValueError, 'preserved'):
                    setup.install_sdk(root)
                compiler = sdk / 'gnu/x86_64-zephyr-elf/bin/x86_64-zephyr-elf-gcc.exe'
                compiler.parent.mkdir(parents=True); compiler.write_bytes(b'fixture')
                self.assertEqual(setup.install_sdk(root), sdk)


if __name__ == '__main__': unittest.main()
