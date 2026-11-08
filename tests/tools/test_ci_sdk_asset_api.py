# SPDX-License-Identifier: Apache-2.0
"""Offline CI-only tests for the official pinned Zephyr SDK release-asset API path."""
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
import urllib.error
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[2] / 'plugins/io.github.niwantha33.zew.ui/tools'
spec = importlib.util.spec_from_file_location('zew_setup_ci_sdk', TOOLS / 'setup.py')
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)

SOURCE = 'https://github.com/zephyrproject-rtos/sdk-ng/releases/download/v1.0.1/test-sdk.7z'
META = 'https://api.github.com/repos/zephyrproject-rtos/sdk-ng/releases/tags/v1.0.1'
ASSET = 'https://api.github.com/repos/zephyrproject-rtos/sdk-ng/releases/assets/1234567'


class Response(io.BytesIO):
    def __init__(self, data, url, content_type='application/octet-stream'):
        super().__init__(data)
        self.url = url
        self.headers = {'Content-Type': content_type}


class CIReleaseApiTests(unittest.TestCase):
    def fixture(self, data=b'good'):
        return {'file': 'test-sdk.7z', 'url': SOURCE, 'size': len(data),
                'sha256': hashlib.sha256(data).hexdigest()}

    def metadata(self, artifact, **changes):
        item = dict(id=1234567, state='uploaded', name=artifact['file'],
                    size=artifact['size'], digest='sha256:' + artifact['sha256'])
        item.update(changes)
        return Response(json.dumps({'assets': [item]}).encode(), META, 'application/json')

    def test_ci_504_uses_api_and_only_commits_hash_verified_archive(self):
        artifact = self.fixture()
        seen = []
        def respond(request, timeout=None):
            url = request.full_url
            seen.append(url)
            if url == SOURCE:
                raise urllib.error.HTTPError(url, 504, 'gateway timeout', {}, None)
            if url == META:
                return self.metadata(artifact)
            if url == ASSET:
                self.assertEqual(request.headers.get('Accept'), 'application/octet-stream')
                return Response(b'good', 'https://release-assets.githubusercontent.com/fixture')
            raise AssertionError('unexpected URL: ' + url)
        with tempfile.TemporaryDirectory() as directory:
            with patch.dict(os.environ, {'ZEW_CI_SDK_ASSET_API': '1'}), \\
                    patch.object(setup.urllib.request, 'urlopen', side_effect=respond), \\
                    patch.object(setup.time, 'sleep') as pause:
                target = setup.fetch(artifact, Path(directory))
            self.assertEqual(target.read_bytes(), b'good')
            self.assertEqual(seen, [SOURCE, META, ASSET])
            self.assertEqual(pause.call_count, 0)
            self.assertFalse((Path(directory) / 'test-sdk.7z.partial').exists())

    def test_ci_release_metadata_disagreement_fails_closed(self):
        artifact = self.fixture()
        def respond(request, timeout=None):
            if request.full_url == SOURCE:
                raise urllib.error.HTTPError(SOURCE, 504, 'gateway timeout', {}, None)
            if request.full_url == META:
                return self.metadata(artifact, size=999)
            raise AssertionError('asset contents must not be fetched')
        with tempfile.TemporaryDirectory() as directory:
            with patch.dict(os.environ, {'ZEW_CI_SDK_ASSET_API': '1'}), \\
                    patch.object(setup.urllib.request, 'urlopen', side_effect=respond):
                with self.assertRaisesRegex(ValueError, 'metadata differs'):
                    setup.fetch(artifact, Path(directory))
            self.assertEqual(list(Path(directory).iterdir()), [])

    def test_ci_alternate_bytes_cannot_bypass_pinned_sha(self):
        artifact = self.fixture()
        def respond(request, timeout=None):
            if request.full_url == SOURCE:
                raise urllib.error.HTTPError(SOURCE, 504, 'gateway timeout', {}, None)
            if request.full_url == META:
                return self.metadata(artifact)
            if request.full_url == ASSET:
                return Response(b'evil', 'https://release-assets.githubusercontent.com/fixture')
            raise AssertionError('unexpected URL')
        with tempfile.TemporaryDirectory() as directory:
            with patch.dict(os.environ, {'ZEW_CI_SDK_ASSET_API': '1'}), \\
                    patch.object(setup.urllib.request, 'urlopen', side_effect=respond):
                with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
                    setup.fetch(artifact, Path(directory))
            self.assertEqual(list(Path(directory).iterdir()), [])

    def test_api_transport_failure_retries_original_pinned_url(self):
        artifact = self.fixture()
        seen = []
        def respond(request, timeout=None):
            url = request.full_url
            seen.append(url)
            if url == META:
                raise urllib.error.URLError('temporary API outage')
            if url == SOURCE and seen.count(SOURCE) == 1:
                raise urllib.error.HTTPError(url, 504, 'gateway timeout', {}, None)
            if url == SOURCE:
                return Response(b'good', SOURCE)
            raise AssertionError('unexpected URL')
        with tempfile.TemporaryDirectory() as directory:
            with patch.dict(os.environ, {'ZEW_CI_SDK_ASSET_API': '1'}), \\
                    patch.object(setup.urllib.request, 'urlopen', side_effect=respond), \\
                    patch.object(setup.time, 'sleep') as pause:
                target = setup.fetch(artifact, Path(directory))
            self.assertEqual(target.read_bytes(), b'good')
            self.assertEqual(seen, [SOURCE, META, SOURCE])
            self.assertEqual(pause.call_count, 1)

    def test_without_explicit_ci_opt_in_no_release_api_is_called(self):
        artifact = self.fixture()
        calls = []
        def respond(request, timeout=None):
            calls.append(request.full_url)
            if len(calls) == 1:
                raise urllib.error.HTTPError(SOURCE, 504, 'gateway timeout', {}, None)
            return Response(b'good', SOURCE)
        with tempfile.TemporaryDirectory() as directory:
            with patch.dict(os.environ, {'ZEW_CI_SDK_ASSET_API': '0'}), \\
                    patch.object(setup.urllib.request, 'urlopen', side_effect=respond), \\
                    patch.object(setup.time, 'sleep'):
                target = setup.fetch(artifact, Path(directory))
            self.assertEqual(target.read_bytes(), b'good')
            self.assertEqual(calls, [SOURCE, SOURCE])


if __name__ == '__main__':
    unittest.main()
