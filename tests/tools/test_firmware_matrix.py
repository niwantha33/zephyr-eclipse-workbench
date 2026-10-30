# SPDX-License-Identifier: Apache-2.0
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('firmware_matrix', Path(__file__).resolve().parents[2] / 'scripts/windows_firmware_matrix.py')
matrix = importlib.util.module_from_spec(spec); spec.loader.exec_module(matrix)


class FirmwareMatrixTests(unittest.TestCase):
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
