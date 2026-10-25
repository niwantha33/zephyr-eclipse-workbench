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

    def test_secondary_core_is_never_guessed_for_primary_core_scenario(self):
        rows = [('espressif', 'espressif', 'esp32s3', 'esp32s3_devkitc', 'default', 'esp32s3/' + cpu,
                 'esp32s3_devkitc/esp32s3/' + cpu) for cpu in ('appcpu', 'procpu')]
        self.assertEqual(matrix.select_target(rows, 'esp32s3_devkitc', 'esp32s3', 'procpu'),
                         'esp32s3_devkitc/esp32s3/procpu')
        with self.assertRaises(ValueError):
            matrix.select_target(rows, 'esp32s3_devkitc', 'esp32s3')
