# SPDX-License-Identifier: Apache-2.0
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

MODULE = Path(__file__).resolve().parents[2] / 'plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis/capture_snapshot.py'
spec = importlib.util.spec_from_file_location('capture_snapshot', MODULE)
subject = importlib.util.module_from_spec(spec)
spec.loader.exec_module(subject)


class SnapshotTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.build = self.root / 'build'
        self.z = self.root / 'zephyr'
        (self.build / 'zephyr').mkdir(parents=True)
        (self.z / 'subsys/tracing/ctf/tsdl').mkdir(parents=True)
        (self.build / 'zephyr/zephyr.elf').write_bytes(b'elf')
        (self.build / 'zephyr/.config').write_text('CONFIG_TRACING=y\nCONFIG_TRACING_CTF=y\nCONFIG_TRACING_BACKEND_RAM=y\n')
        (self.z / 'subsys/tracing/ctf/tsdl/metadata').write_text('metadata')
        self.gdb = self.root / 'gdb'
        self.gdb.touch()

    def test_build_config_is_required(self):
        (self.build / 'zephyr/.config').write_text('CONFIG_TRACING=y\n')
        with self.assertRaisesRegex(ValueError, 'CONFIG_TRACING_CTF'):
            subject.validate(self.build, self.z, self.gdb)

    def test_capture_creates_unique_trace_and_preserves_existing(self):
        elf, metadata = subject.validate(self.build, self.z, self.gdb)
        def successful_gdb(command, **kwargs):
            script = Path(command[command.index('-x') + 1])
            (script.parent / 'channel0_0').write_bytes(b'new trace')
            return SimpleNamespace(returncode=0, stdout='ZEW_TRACE_BYTES=9 CAPACITY=4096')
        with patch.object(subject.subprocess, 'run', side_effect=successful_gdb):
            first = subject.capture(self.gdb, elf, metadata, self.root / 'captures', 3335, 1)
            second = subject.capture(self.gdb, elf, metadata, self.root / 'captures', 3335, 1)
        self.assertNotEqual(first, second)
        self.assertEqual((first / 'channel0_0').read_bytes(), b'new trace')
        self.assertEqual((first / 'metadata').read_text(), 'metadata')
        receipt = json.loads((second / 'capture.json').read_text())
        self.assertEqual(receipt['capture_full'], 'unknown')
        self.assertEqual(receipt['firmware_matches_elf'], 'not verified')

    def test_gdb_failure_does_not_publish_capture(self):
        elf, metadata = subject.validate(self.build, self.z, self.gdb)
        with patch.object(subject.subprocess, 'run', return_value=SimpleNamespace(returncode=1, stdout='failed')):
            with self.assertRaisesRegex(RuntimeError, 'GDB trace capture failed'):
                subject.capture(self.gdb, elf, metadata, self.root / 'captures', 3335, 1)
        self.assertEqual(list((self.root / 'captures').iterdir()), [])

    def test_rejects_invalid_duration_and_port(self):
        elf, metadata = subject.validate(self.build, self.z, self.gdb)
        with self.assertRaises(ValueError):
            subject.capture(self.gdb, elf, metadata, self.root, 1, 1)
        with self.assertRaises(ValueError):
            subject.capture(self.gdb, elf, metadata, self.root, 3335, 0)


if __name__ == '__main__':
    unittest.main()
