#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Experimental, offline Zephyr CTF RAM snapshot through an existing OpenOCD server.

This helper does not flash firmware or prove that the target matches the ELF.
It resets and resumes the target, then leaves it halted after capture.
"""
from __future__ import annotations

import argparse
from collections import deque
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import shutil
import subprocess
import tempfile
import threading
import time


class GdbMI:
    """Small synchronous MI client; Python runs here, never inside GDB."""

    def __init__(self, gdb: Path, elf: Path, timeout: float):
        self.deadline = time.monotonic() + timeout
        self.token = 0
        self.records = queue.Queue()
        self.log = deque(maxlen=40)
        self.process = subprocess.Popen(
            [str(gdb), '--nx', '--quiet', '--interpreter=mi2',
             '-iex', 'set auto-load off', str(elf)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding='utf-8', errors='replace', bufsize=1)
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self):
        try:
            for line in self.process.stdout:
                self.records.put(line.rstrip('\r\n'))
        finally:
            self.records.put(None)

    def command(self, command: str) -> str:
        self.token += 1
        prefix = f'{self.token}^'
        try:
            self.process.stdin.write(f'{self.token}{command}\n')
            self.process.stdin.flush()
        except (OSError, ValueError) as exc:
            raise RuntimeError('GDB closed its command connection') from exc
        while True:
            remaining = self.deadline - time.monotonic()
            if remaining <= 0:
                raise RuntimeError('Timed out waiting for GDB: ' + command)
            try:
                line = self.records.get(timeout=remaining)
            except queue.Empty as exc:
                raise RuntimeError('Timed out waiting for GDB: ' + command) from exc
            if line is None:
                raise RuntimeError('GDB exited before completing ' + command + '\n' + '\n'.join(self.log))
            self.log.append(line[:512])
            if not line.startswith(prefix):
                continue  # Stream output and asynchronous notifications are not replies.
            result = line[len(prefix):]
            kind = result.split(',', 1)[0]
            if kind not in ('done', 'connected', 'exit'):
                raise RuntimeError('GDB command failed: ' + command + '\n' + result)
            return result

    def integer(self, expression: str) -> int:
        result = self.command('-data-evaluate-expression ' + json.dumps(expression))
        match = re.search(r'(?:^|,)value="([^"\\]*)"', result)
        if not match:
            raise RuntimeError('GDB did not return a numeric value for ' + expression)
        try:
            return int(match.group(1), 0)
        except ValueError as exc:
            raise RuntimeError('Invalid GDB numeric value: ' + match.group(1)) from exc

    def close(self):
        # Do not wait for a debugger reply during failure cleanup.
        try:
            if self.process.poll() is None:
                self.process.stdin.write(f'{self.token + 1}-gdb-exit\n')
                self.process.stdin.flush()
                self.process.wait(timeout=2)
        except (OSError, ValueError, subprocess.TimeoutExpired):
            pass
        finally:
            if self.process.poll() is None:
                self.process.kill()
                self.process.wait(timeout=2)
            self.reader.join(timeout=2)
            self.process.stdin.close()
            self.process.stdout.close()


def read_snapshot(gdb: Path, elf: Path, port: int, duration: float,
                  timeout: float) -> tuple[bytes, int, str]:
    session = GdbMI(gdb, elf, timeout + duration)
    try:
        session.command('-gdb-set remotetimeout 5')
        session.command(f'-target-select extended-remote localhost:{port}')
        session.command('-interpreter-exec console "monitor reset halt"')
        # Check symbols before running the target, so missing configuration has
        # an actionable error rather than an apparently successful empty capture.
        size = session.integer('sizeof(ram_tracing)')
        address = session.integer('(unsigned long)&ram_tracing[0]')
        session.integer("'tracing_backend_ram.c'::pos")
        session.command('-interpreter-exec console "monitor resume"')
        time.sleep(duration)
        session.command('-interpreter-exec console "monitor halt"')
        pos = session.integer("'tracing_backend_ram.c'::pos")
        if not 0 < pos <= size:
            raise RuntimeError(f'Invalid or empty RAM trace: {pos} / {size}')
        # Read bounded chunks. Require each whole requested range, since GDB MI
        # may otherwise return only readable fragments of an address range.
        data = bytearray()
        for offset in range(0, pos, 1024):
            count = min(1024, pos - offset)
            result = session.command(f'-data-read-memory-bytes {address + offset:#x} {count}')
            blocks = re.findall(r'\{begin="(0x[0-9a-fA-F]+)",offset="[^"\\]*",end="(0x[0-9a-fA-F]+)",contents="([0-9a-fA-F]*)"\}', result)
            if len(blocks) != 1:
                raise RuntimeError('GDB returned incomplete RAM trace memory')
            begin, end, contents = blocks[0]
            block = bytes.fromhex(contents)
            if int(begin, 16) != address + offset or int(end, 16) != address + offset + count or len(block) != count:
                raise RuntimeError('GDB returned incomplete RAM trace memory')
            data.extend(block)
        return bytes(data), size, '\n'.join(session.log)[-6000:]
    finally:
        session.close()


def validate(build: Path, zephyr: Path, gdb: Path) -> tuple[Path, Path]:
    elf = build / 'zephyr' / 'zephyr.elf'
    config = build / 'zephyr' / '.config'
    metadata = zephyr / 'subsys' / 'tracing' / 'ctf' / 'tsdl' / 'metadata'
    for path in (elf, config, metadata, gdb):
        if not path.is_file():
            raise ValueError(f'Missing required file: {path}')
    settings = set(config.read_text(encoding='utf-8').splitlines())
    for option in ('CONFIG_TRACING=y', 'CONFIG_TRACING_CTF=y', 'CONFIG_TRACING_BACKEND_RAM=y'):
        if option not in settings:
            raise ValueError(f'The selected build does not enable {option}')
    return elf.resolve(), metadata.resolve()


def capture(gdb: Path, elf: Path, metadata: Path, destination: Path, port: int,
            duration: float, timeout: float = 30) -> Path:
    if not 1024 <= port <= 65535 or not 0.05 <= duration <= 30:
        raise ValueError('Port must be 1024..65535 and duration 0.05..30 seconds')
    destination.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.zew-capture-', dir=destination) as temporary:
        stage = Path(temporary)
        ctf = stage / 'ctf'
        ctf.mkdir()
        binary = ctf / 'channel0_0'
        try:
            data, capacity, gdb_output = read_snapshot(gdb, elf, port, duration, timeout)
        except RuntimeError as exc:
            raise RuntimeError('GDB trace capture failed:\n' + str(exc)) from exc
        binary.write_bytes(data)
        shutil.copyfile(metadata, ctf / 'metadata')
        timeline = Path(__file__).with_name('Zephyr_RTOS_Timeline.xml')
        shutil.copyfile(timeline, stage / 'Zephyr_RTOS_Timeline.xml')
        receipt = {'elf_sha256': hashlib.sha256(elf.read_bytes()).hexdigest(),
                   'metadata_sha256': hashlib.sha256(metadata.read_bytes()).hexdigest(),
                   'trace_bytes': binary.stat().st_size,
                   'buffer_capacity': capacity,
                   'capture_seconds_requested': duration,
                   'capture_full': 'unknown',
                   'firmware_matches_elf': 'not verified',
                   'gdb_output': gdb_output}
        (stage / 'capture.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
        # A fresh directory for each snapshot prevents silently reusing an old trace.
        stamp = time.strftime('%Y%m%d-%H%M%S')
        for suffix in range(1000):
            target = destination / f'trace-{stamp}-{suffix:03d}'
            if not target.exists():
                os.rename(stage, target)
                return target
        raise RuntimeError('Could not allocate a unique capture directory')


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gdb', type=Path, required=True)
    parser.add_argument('--build', type=Path, required=True)
    parser.add_argument('--zephyr', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--port', type=int, default=3335)
    parser.add_argument('--seconds', type=float, default=1.0)
    args = parser.parse_args()
    try:
        elf, metadata = validate(args.build, args.zephyr, args.gdb)
        path = capture(args.gdb.resolve(), elf, metadata, args.output, args.port, args.seconds)
        print(f'Trace saved to {path}; target halted. Firmware/ELF match and buffer fullness are not verified.')
        return 0
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as exc:
        print(f'Capture failed: {exc}', file=__import__('sys').stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
