# SPDX-License-Identifier: Apache-2.0
import importlib.util
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('hardware_catalog', ROOT / 'plugins/io.github.niwantha33.zew.ui/tools/hardware_catalog.py')
catalog = importlib.util.module_from_spec(spec); spec.loader.exec_module(catalog)

class HardwareTests(unittest.TestCase):
    def test_resolved_cpu_revision_chip_vendor_and_board_manufacturer(self):
        board = SimpleNamespace(name='third_party_s3', vendor='third_party', revisions=[SimpleNamespace(name='1.0')])
        soc = SimpleNamespace(name='esp32s3', folder=['/zephyr/soc/espressif/esp32s3'])
        boards = SimpleNamespace(find_v2_boards=lambda args: {'board': board}, board_v2_qualifiers=lambda b: ['esp32s3/procpu', 'esp32s3/appcpu'])
        systems = SimpleNamespace(get_socs=lambda: [soc])
        hardware = SimpleNamespace(find_v2_systems=lambda args: systems)
        original = list(sys.path)
        try:
            with patch.dict(sys.modules, list_boards=boards, list_hardware=hardware):
                rows = catalog.catalogue(Path('/zephyr'))
            self.assertEqual(len(rows), 4)
            self.assertTrue(all(r[:3] == ('espressif', 'third_party', 'esp32s3') for r in rows))
            self.assertIn('third_party_s3@1.0/esp32s3/procpu', [r[6] for r in rows])
            self.assertIn('third_party_s3/esp32s3/appcpu', [r[6] for r in rows])
        finally: sys.path[:] = original

    def test_board_variant_without_soc_does_not_abort_catalogue(self):
        board = SimpleNamespace(name='virtual', vendor=None, revisions=[])
        boards = SimpleNamespace(find_v2_boards=lambda args: {'virtual': board}, board_v2_qualifiers=lambda b: ['variant'])
        hardware = SimpleNamespace(find_v2_systems=lambda args: SimpleNamespace(get_socs=lambda: []))
        original = list(sys.path)
        try:
            with patch.dict(sys.modules, list_boards=boards, list_hardware=hardware):
                rows = catalog.catalogue(Path('/zephyr'))
            self.assertEqual(rows[0][6], 'virtual/variant')
        finally: sys.path[:] = original
