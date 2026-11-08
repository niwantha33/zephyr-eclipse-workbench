# SPDX-License-Identifier: Apache-2.0
import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import urllib.error

spec = importlib.util.spec_from_file_location('firmware_matrix', Path(__file__).resolve().parents[2] / 'scripts/windows_firmware_matrix.py')
matrix = importlib.util.module_from_spec(spec); spec.loader.exec_module(matrix)


class FirmwareMatrixTests(unittest.TestCase):
    def test_bootstrap_transient_504_retries_then_checks_exact_hash(self):
        expected = b'pinned-ci-archive'
        digest = hashlib.sha256(expected).hexdigest()
        unavailable = urllib.error.HTTPError('https://example.invalid/test.zip', 504, 'temporary', {}, None)
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / 'bootstrap.zip'
            with mock.patch.object(matrix.urllib.request, 'urlopen',
                                   side_effect=[unavailable, io.BytesIO(expected)]) as open_url, \\
                 mock.patch.object(matrix.time, 'sleep') as sleep:
                matrix.fetch_pinned_archive('python', 'https://example.invalid/test.zip',
                                            len(expected), digest, output)
            self.assertEqual(output.read_bytes(), expected)
            self.assertEqual(open_url.call_count, 2)
            sleep.assert_called_once_with(1)

    def test_bootstrap_fails_closed_on_permanent_404(self):
        failure = urllib.error.HTTPError('https://example.invalid/test.zip', 404, 'not found', {}, None)
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / 'bootstrap.zip'
            with mock.patch.object(matrix.urllib.request, 'urlopen', side_effect=failure) as open_url, \\
                 mock.patch.object(matrix.time, 'sleep') as sleep:
                with self.assertRaises(urllib.error.HTTPError):
                    matrix.fetch_pinned_archive('python', 'https://example.invalid/test.zip',
                                                3, '0' * 64, output)
            open_url.assert_called_once()
            sleep.assert_not_called()
            self.assertFalse(output.exists())

    def test_bootstrap_fails_closed_on_corrupted_successful_download(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / 'bootstrap.zip'
            with mock.patch.object(matrix.urllib.request, 'urlopen',
                                   return_value=io.BytesIO(b'bad-data')) as open_url, \\
                 mock.patch.object(matrix.time, 'sleep') as sleep:
                with self.assertRaisesRegex(ValueError, 'integrity mismatch'):
                    matrix.fetch_pinned_archive('git', 'https://example.invalid/test.zip',
                                                8, hashlib.sha256(b'expected').hexdigest(), output)
            open_url.assert_called_once()
            sleep.assert_not_called()
            self.assertFalse(output.exists())

    def test_bootstrap_retries_exhaustion_and_discards_partial(self):
        failure = urllib.error.HTTPError('https://example.invalid/test.zip', 503, 'temporary', {}, None)
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / 'bootstrap.zip'
            with mock.patch.object(matrix.urllib.request, 'urlopen', side_effect=failure) as open_url, \\
                 mock.patch.object(matrix.time, 'sleep') as sleep:
                with self.assertRaises(urllib.error.HTTPError):
                    matrix.fetch_pinned_archive('git', 'https://example.invalid/test.zip',
                                                3, '0' * 64, output)
            self.assertEqual(open_url.call_count, 4)
            self.assertEqual([call.args[0] for call in sleep.call_args_list], [1, 2, 4])
            self.assertFalse(output.exists())

    def test_bootstrap_retry_replaces_interrupted_partial_file(self):
        class InterruptedStream(io.BytesIO):
            def read(self, count=-1):
                if self.tell():
                    raise ConnectionResetError('socket disconnected')
                return super().read(3)

        expected = b'recovered-bytes'
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / 'bootstrap.zip'
            with mock.patch.object(matrix.urllib.request, 'urlopen',
                                   side_effect=[InterruptedStream(b'partial'), io.BytesIO(expected)]), \\
                 mock.patch.object(matrix.time, 'sleep'):
                matrix.fetch_pinned_archive('python', 'https://example.invalid/test.zip',
                                            len(expected), hashlib.sha256(expected).hexdigest(), output)
            self.assertEqual(output.read_bytes(), expected)

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
