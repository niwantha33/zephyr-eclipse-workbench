# SPDX-License-Identifier: Apache-2.0
import importlib.util
import hashlib
import io
import tempfile
import urllib.error
from unittest import mock
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('firmware_matrix', Path(__file__).resolve().parents[2] / 'scripts/windows_firmware_matrix.py')
matrix = importlib.util.module_from_spec(spec); spec.loader.exec_module(matrix)


class FirmwareMatrixTests(unittest.TestCase):
    def test_ci_bootstrap_retries_gateway_timeout_then_verifies_exact_hash(self):
        payload = b'CI pinned archive bytes'
        digest = hashlib.sha256(payload).hexdigest()
        failures = [urllib.error.HTTPError('https://example.invalid/pin', 504,
                                          'gateway timeout', {}, None),
                    io.BytesIO(payload)]
        sleeps = []
        with tempfile.TemporaryDirectory() as folder:
            archive = Path(folder) / 'pinned.zip'
            with mock.patch.object(matrix.urllib.request, 'urlopen',
                                   side_effect=failures) as download:
                matrix.download_verified_bootstrap(
                    archive, 'test-pinned', 'https://example.invalid/pin',
                    len(payload), digest, pause=sleeps.append)
            self.assertEqual(download.call_count, 2)
            self.assertEqual(sleeps, [3])
            self.assertEqual(archive.read_bytes(), payload)
            self.assertFalse((Path(folder) / 'pinned.zip.part').exists())

    def test_ci_bootstrap_uses_mirror_only_after_transient_primary_exhaustion(self):
        payload = b'exact-release-bytes'
        digest = hashlib.sha256(payload).hexdigest()
        gateway = lambda: urllib.error.HTTPError(
            'https://example.invalid/release', 504, 'gateway timeout', {}, None)
        downloads = [gateway(), gateway(), io.BytesIO(payload)]
        with tempfile.TemporaryDirectory() as folder:
            archive = Path(folder) / 'git.zip'
            with mock.patch.object(matrix.urllib.request, 'urlopen',
                                   side_effect=downloads) as fetch:
                matrix.download_verified_bootstrap(
                    archive, 'git', 'https://example.invalid/release',
                    len(payload), digest, attempts=2, pause=lambda _: None,
                    fallback_url='https://mirror.example.invalid/release')
            self.assertEqual(fetch.call_count, 3)
            self.assertEqual(fetch.call_args_list[0].args[0].full_url,
                             'https://example.invalid/release')
            self.assertEqual(fetch.call_args_list[1].args[0].full_url,
                             'https://example.invalid/release')
            self.assertEqual(fetch.call_args_list[2].args[0].full_url,
                             'https://mirror.example.invalid/release')
            self.assertEqual(archive.read_bytes(), payload)

    def test_ci_bootstrap_mirror_cannot_bypass_sha256(self):
        payload = b'valid-bytes'
        bad = b'wrong-bytes'
        self.assertEqual(len(payload), len(bad))
        with tempfile.TemporaryDirectory() as folder:
            archive = Path(folder) / 'git.zip'
            with mock.patch.object(matrix.urllib.request, 'urlopen',
                                   side_effect=[
                                       urllib.error.HTTPError(
                                           'https://example.invalid/release',
                                           504, 'gateway timeout', {}, None),
                                       io.BytesIO(bad)
                                   ]) as fetch:
                with self.assertRaisesRegex(ValueError, 'integrity mismatch'):
                    matrix.download_verified_bootstrap(
                        archive, 'git', 'https://example.invalid/release',
                        len(payload), hashlib.sha256(payload).hexdigest(),
                        attempts=1, fallback_url='https://mirror.example.invalid/release')
            self.assertEqual(fetch.call_count, 2)
            self.assertFalse(archive.exists())
            self.assertFalse((Path(folder) / 'git.zip.part').exists())

    def test_ci_bootstrap_refuses_nontransient_http_and_cleans_partial(self):
        with tempfile.TemporaryDirectory() as folder:
            archive = Path(folder) / 'pinned.zip'
            with mock.patch.object(matrix.urllib.request, 'urlopen',
                                   side_effect=urllib.error.HTTPError(
                                       'https://example.invalid/pin', 404, 'not found',
                                       {}, None)) as download:
                with self.assertRaises(urllib.error.HTTPError):
                    matrix.download_verified_bootstrap(
                        archive, 'test-pinned', 'https://example.invalid/pin',
                        3, hashlib.sha256(b'abc').hexdigest(), pause=lambda _: None)
            self.assertEqual(download.call_count, 1)
            self.assertFalse(archive.exists())
            self.assertFalse((Path(folder) / 'pinned.zip.part').exists())

    def test_ci_bootstrap_never_accepts_truncated_or_wrong_hash_data(self):
        with tempfile.TemporaryDirectory() as folder:
            archive = Path(folder) / 'pinned.zip'
            for payload, size, digest in [
                (b'ab', 3, hashlib.sha256(b'abc').hexdigest()),
                (b'abc', 3, hashlib.sha256(b'bad').hexdigest()),
            ]:
                with self.subTest(payload=payload):
                    with mock.patch.object(matrix.urllib.request, 'urlopen',
                                           return_value=io.BytesIO(payload)) as download:
                        with self.assertRaisesRegex(ValueError, 'integrity mismatch'):
                            matrix.download_verified_bootstrap(
                                archive, 'test-pinned', 'https://example.invalid/pin',
                                size, digest, pause=lambda _: None)
                    self.assertEqual(download.call_count, 1)
                    self.assertFalse(archive.exists())
                    self.assertFalse((Path(folder) / 'pinned.zip.part').exists())

    def test_requested_soc_disambiguates_multi_soc_board(self):
        rows = [('nordic', 'nordic', soc, 'nrf52840dk', 'default', soc, 'nrf52840dk/' + soc)
                for soc in ('nrf52811', 'nrf52840')]
        self.assertEqual(matrix.select_target(rows, 'nrf52840dk', 'nrf52840'), 'nrf52840dk/nrf52840')
        with self.assertRaisesRegex(ValueError, 'did not uniquely resolve'):
            matrix.select_target(rows, 'nrf52840dk', 'not-a-soc')

    def test_semantic_checker_requires_json_and_keeps_stderr_diagnostic(self):
        from types import SimpleNamespace

        crashed = SimpleNamespace(returncode=1, stdout='', stderr='Traceback: stale data')
        with self.assertRaisesRegex(ValueError, 'stale data'):
            matrix.parse_semantic_check(crashed)

        wrong_exit = SimpleNamespace(returncode=0, stdout='{"images": [{}]}', stderr='')
        with self.assertRaisesRegex(ValueError, 'exit=0'):
            matrix.parse_semantic_check(wrong_exit)

        malformed = SimpleNamespace(returncode=1, stdout='<invalid>', stderr='parse failed')
        with self.assertRaisesRegex(ValueError, 'parse failed'):
            matrix.parse_semantic_check(malformed)

        valid = SimpleNamespace(returncode=1, stdout='{"images": [{"semantic": {"state": "evaluated"}}]}',
                                stderr='')
        self.assertEqual(matrix.parse_semantic_check(valid)['images'][0]['semantic']['state'],
                         'evaluated')

    def test_semantic_checker_rejects_empty_envelope(self):
        from types import SimpleNamespace

        result = SimpleNamespace(returncode=1, stdout='{"images": []}', stderr='')
        with self.assertRaisesRegex(ValueError, 'invalid evidence envelope'):
            matrix.parse_semantic_check(result)

    def test_secondary_core_is_never_guessed_for_primary_core_scenario(self):
        rows = [('espressif', 'espressif', 'esp32s3', 'esp32s3_devkitc', 'default', 'esp32s3/' + cpu,
                 'esp32s3_devkitc/esp32s3/' + cpu) for cpu in ('appcpu', 'procpu')]
        self.assertEqual(matrix.select_target(rows, 'esp32s3_devkitc', 'esp32s3', 'procpu'),
                         'esp32s3_devkitc/esp32s3/procpu')
        with self.assertRaises(ValueError):
            matrix.select_target(rows, 'esp32s3_devkitc', 'esp32s3')
