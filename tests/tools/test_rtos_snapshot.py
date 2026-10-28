# SPDX-License-Identifier: Apache-2.0
import importlib.util
import json
import subprocess
import sys
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

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
        self.children = []

    def test_build_config_is_required(self):
        (self.build / 'zephyr/.config').write_text('CONFIG_TRACING=y\n')
        with self.assertRaisesRegex(ValueError, 'CONFIG_TRACING_CTF'):
            subject.validate(self.build, self.z, self.gdb)

    def fake_gdb(self, behavior='ok', pos=9, capacity=4096):
        # A real child process speaks MI only. It rejects every unknown command,
        # including Python scripting, and emits asynchronous noise before replies.
        fake = self.root / 'fake gdb.py'
        fake.write_text(FAKE_MI)
        log = self.root / 'commands.jsonl'
        real_popen = subprocess.Popen

        def start(command, **kwargs):
            self.assertIn('--interpreter=mi2', command)
            self.assertIn('set auto-load off', command)
            self.assertNotIn('-x', command)
            self.assertEqual(command[-1], str(self.build / 'zephyr/zephyr.elf'))
            child = real_popen([sys.executable, str(fake), behavior, str(pos),
                                str(capacity), str(log)], **kwargs)
            self.children.append(child)
            return child
        return patch.object(subject.subprocess, 'Popen', side_effect=start), log

    def test_capture_creates_unique_trace_and_preserves_existing(self):
        elf, metadata = subject.validate(self.build, self.z, self.gdb)
        fake, log = self.fake_gdb()
        with fake:
            first = subject.capture(self.gdb, elf, metadata, self.root / 'captures', 3335, 0.05)
            second = subject.capture(self.gdb, elf, metadata, self.root / 'captures', 3335, 0.05)
        self.assertNotEqual(first, second)
        self.assertEqual((first / 'ctf' / 'channel0_0').read_bytes(), bytes(range(9)))
        self.assertEqual((first / 'ctf' / 'metadata').read_text(), 'metadata')
        self.assertEqual({p.name for p in (first / 'ctf').iterdir()}, {'channel0_0', 'metadata'})
        self.assertFalse((first / 'capture.gdb').exists())
        receipt = json.loads((second / 'capture.json').read_text())
        self.assertEqual(receipt['capture_full'], 'unknown')
        self.assertEqual(receipt['firmware_matches_elf'], 'not verified')
        self.assertEqual(receipt['buffer_capacity'], 4096)
        commands = [json.loads(line) for line in log.read_text().splitlines()]
        self.assertEqual(commands.count('-target-select extended-remote localhost:3335'), 2)
        self.assertEqual(commands.count('-interpreter-exec console "monitor halt"'), 2)
        self.assertFalse(any('python' in c.lower() for c in commands))

    def test_reads_multiple_chunks_without_truncation(self):
        fake, _ = self.fake_gdb(pos=2556)
        with fake:
            data, capacity, _ = subject.read_snapshot(self.gdb, self.build / 'zephyr/zephyr.elf', 3335, 0.05, 3)
        self.assertEqual(data, bytes(i % 256 for i in range(2556)))
        self.assertEqual(capacity, 4096)

    def test_failures_do_not_publish_partial_trace(self):
        elf, metadata = subject.validate(self.build, self.z, self.gdb)
        for behavior, pos, message in [('ok', 0, 'Invalid or empty'),
                                        ('ok', 4097, 'Invalid or empty'),
                                        ('error', 9, 'Connection refused'),
                                        ('eof', 9, 'GDB exited'),
                                        ('fragment', 9, 'incomplete RAM'),
                                        ('missing', 9, 'No symbol')]:
            with self.subTest(behavior=behavior, pos=pos):
                fake, _ = self.fake_gdb(behavior, pos)
                with fake, self.assertRaisesRegex(RuntimeError, message):
                    subject.capture(self.gdb, elf, metadata, self.root / 'captures', 3335, 0.05)
                self.assertEqual(list((self.root / 'captures').iterdir()), [])

    def test_timeout_closes_child_process(self):
        for behavior in ('timeout', 'stuck'):
            with self.subTest(behavior=behavior):
                fake, log = self.fake_gdb(behavior)
                with fake, self.assertRaisesRegex(RuntimeError, 'Timed out'):
                    subject.read_snapshot(self.gdb, self.build / 'zephyr/zephyr.elf', 3335, 0.05, 0.2)
                self.assertIn('-gdb-exit', log.read_text())
                self.assertIsNotNone(self.children[-1].poll())

    def test_rejects_invalid_duration_and_port(self):
        elf, metadata = subject.validate(self.build, self.z, self.gdb)
        with self.assertRaises(ValueError):
            subject.capture(self.gdb, elf, metadata, self.root, 1, 1)
        with self.assertRaises(ValueError):
            subject.capture(self.gdb, elf, metadata, self.root, 3335, 0)


FAKE_MI = r'''import json, re, sys, time
behavior, pos, capacity, log = sys.argv[1:]
position = int(pos)
resumed = False
for line in sys.stdin:
    token, command = re.match(r"(\d+)(.*)", line.strip()).groups()
    with open(log, 'a') as out:
        out.write(json.dumps(command) + '\n')
    if behavior == 'stuck':
        continue
    if command == '-gdb-exit':
        print(token + '^exit', flush=True)
        break
    if behavior == 'timeout':
        continue
    if behavior == 'eof':
        break
    result = '^done'
    if command.startswith('-target-select'):
        if behavior == 'error':
            result = '^error,msg="Connection refused"'
        else:
            result = '^connected'
    elif command == '-gdb-set remotetimeout 5':
        pass
    elif command.startswith('-interpreter-exec console '):
        monitor = json.loads(command[len('-interpreter-exec console '):])
        assert monitor in ('monitor reset halt', 'monitor resume', 'monitor halt')
        if monitor == 'monitor resume':
            resumed = True
        if monitor == 'monitor halt':
            assert resumed
    elif command.startswith('-data-evaluate-expression '):
        expression = json.loads(command[len('-data-evaluate-expression '):])
        values = {'sizeof(ram_tracing)': capacity,
                  '(unsigned long)&ram_tracing[0]': '536870912',
                  "'tracing_backend_ram.c'::pos": pos if resumed else '0'}
        result = '^done,value=' + json.dumps(values[expression])
        if behavior == 'missing':
            result = '^error,msg="No symbol ram_tracing in current context"'
    elif command.startswith('-data-read-memory-bytes '):
        _, address, count = command.split()
        begin, size = int(address, 16), int(count)
        if behavior == 'fragment':
            begin += 1
            size -= 1
        data = bytes((begin - 536870912 + i) % 256 for i in range(size))
        result = '^done,memory=[{begin="%#x",offset="0x0",end="%#x",contents="%s"}]' % (begin, begin + size, data.hex())
    else:
        result = '^error,msg="Unsupported command"'
    print('~"console noise\\n"')
    print('*stopped,reason="signal-received"')
    print('9999^done,value="wrong token"')
    print(token + result, flush=True)
'''


if __name__ == '__main__':
    unittest.main()
