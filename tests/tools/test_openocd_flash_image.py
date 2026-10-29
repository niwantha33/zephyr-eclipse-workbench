# SPDX-License-Identifier: Apache-2.0
import importlib.util
from pathlib import Path
import tempfile
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('flash_image', ROOT / 'plugins/io.github.niwantha33.zew.ui/tools/openocd_flash_image.py')
flash_image = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(flash_image)


def record(address, kind, payload=b''):
    values = bytes([len(payload), address >> 8, address & 255, kind]) + payload
    return ':' + (values + bytes([-sum(values) & 255])).hex().upper() + '\n'


class FlashImageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='flash image Ω ')
        self.build = Path(self.temp.name)
        self.zephyr = self.build / 'zephyr'
        self.zephyr.mkdir()
        self.elf = self.zephyr / 'zephyr.elf'
        self.elf.write_bytes(b'ELF fixture; extraction is checked by installed CDT tests')
        self.image = self.zephyr / 'zephyr.hex'
        self.data = record(0x5e68, 0, b'\x04') + record(0, 1)
        self.image.write_text(self.data)
        self.document = {'flash-runner': 'openocd', 'config': {'hex_file': 'zephyr.hex', 'elf_file': 'zephyr.elf'},
                         'args': {'openocd': ['--cmd-load', 'flash write_image erase', '--cmd-verify', 'verify_image']}}
        self.write_metadata()

    def tearDown(self):
        self.temp.cleanup()

    def write_metadata(self):
        (self.zephyr / 'runners.yaml').write_text(yaml.safe_dump(self.document), encoding='utf-8')

    def resolve(self):
        return flash_image.resolve(self.build, self.elf, {0x5e68: 4})

    def test_default_image_includes_actual_reported_address(self):
        self.assertEqual(self.resolve(), (self.image.resolve(), 'hex'))

    def test_omitted_and_wrong_metadata_fail_before_flash(self):
        for payload in (record(0, 0, b'\x01') + record(0, 1), record(0x5e68, 0, b'\xff') + record(0, 1)):
            with self.subTest(payload=payload):
                self.image.write_text(payload)
                with self.assertRaisesRegex(ValueError, '0x5e68'):
                    self.resolve()

    def test_checksums_end_marker_and_record_lengths(self):
        for payload in (self.data.replace('04', '05', 1), self.data.splitlines()[0], ':00000000FF\n', self.data + record(0, 0, b'\x04')):
            with self.subTest(payload=payload), self.assertRaises(ValueError):
                flash_image.verify_hex(payload.encode(), {0x5e68: 4})

    def test_extended_linear_and_segment_addresses(self):
        for kind, upper, shift in ((4, 0x1234, 16), (2, 0x1234, 4)):
            payload = record(0, kind, upper.to_bytes(2, 'big')) + record(0x10, 0, b'\x04') + record(0, 1)
            flash_image.verify_hex(payload.encode(), {(upper << shift) + 0x10: 4})

    def test_conflicting_overlapping_records(self):
        payload = record(0x5e68, 0, b'\x04') + record(0x5e68, 0, b'\xff') + record(0, 1)
        with self.assertRaises(ValueError):
            flash_image.verify_hex(payload.encode(), {0x5e68: 4})

    def test_merged_declared_hex_is_preserved(self):
        merged = self.zephyr / 'merged.hex'
        merged.write_text(self.data)
        self.document['config']['hex_file'] = 'merged.hex'
        self.write_metadata()
        self.assertEqual(self.resolve()[0], merged.resolve())

    def test_alternative_flash_backend_is_not_overridden(self):
        self.document['flash-runner'] = 'jlink'
        self.write_metadata()
        with self.assertRaisesRegex(ValueError, 'declared OpenOCD'):
            self.resolve()

    def test_explicit_format_wins_over_legacy_flag(self):
        self.document['args']['openocd'] += ['--file-type', 'hex', '--use-elf']
        self.write_metadata()
        self.assertEqual(self.resolve()[1], 'hex')

    def test_other_elf_and_build_escape_rejected(self):
        for path in ('other.elf', '../outside.hex'):
            self.document['config']['file'] = str((self.zephyr / path).resolve())
            self.document['args']['openocd'] += ['--file-type', 'elf']
            self.write_metadata()
            with self.assertRaises((ValueError, FileNotFoundError)):
                self.resolve()

    def test_bin_requires_actual_declared_load_address(self):
        image = self.zephyr / 'zephyr.bin'
        image.write_bytes(b'\x04')
        self.document['config']['bin_file'] = 'zephyr.bin'
        self.document['args']['openocd'] += ['--use-bin', '--flash-address', '0x5e68']
        self.write_metadata()
        self.assertEqual(self.resolve(), (image.resolve(), 'bin'))
        image.write_bytes(b'\xff')
        with self.assertRaisesRegex(ValueError, '0x5e68'):
            self.resolve()
