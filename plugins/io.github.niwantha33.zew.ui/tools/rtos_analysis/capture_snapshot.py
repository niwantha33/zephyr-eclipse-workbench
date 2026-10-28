#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Experimental, offline Zephyr CTF RAM snapshot through an existing OpenOCD server.

This helper does not flash firmware or prove that the target matches the ELF.
It resets and resumes the target, then leaves it halted after capture.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time


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
        binary = stage / 'channel0_0'
        # The script is interpreted by GDB, not by the shell. repr() quotes paths
        # for Python embedded in GDB and prevents paths becoming GDB commands.
        script = stage / 'capture.gdb'
        script.write_text('''set pagination off
set confirm off
set remotetimeout 5
python
import gdb, time
gdb.execute("target extended-remote localhost:%d")
gdb.execute("monitor reset halt")
gdb.execute("monitor resume")
time.sleep(%r)
gdb.execute("monitor halt")
size = int(gdb.parse_and_eval("sizeof(ram_tracing)"))
pos = int(gdb.parse_and_eval("'tracing_backend_ram.c'::pos"))
if not 0 < pos <= size:
    raise gdb.GdbError("Invalid or empty RAM trace: %%s / %%s" %% (pos, size))
address = int(gdb.parse_and_eval("(unsigned long)&ram_tracing[0]"))
data = bytes(gdb.selected_inferior().read_memory(address, pos))
with open(%r, "wb") as output:
    output.write(data)
print("ZEW_TRACE_BYTES=%%d CAPACITY=%%d" %% (pos, size))
end
quit
''' % (port, duration, str(binary)), encoding='utf-8')
        result = subprocess.run([str(gdb), '--nx', '--batch', '-x', str(script), str(elf)],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, errors='replace', timeout=timeout, check=False)
        if result.returncode != 0 or not binary.is_file() or binary.stat().st_size == 0:
            raise RuntimeError('GDB trace capture failed:\n' + result.stdout[-6000:])
        shutil.copyfile(metadata, stage / 'metadata')
        timeline = Path(__file__).with_name('Zephyr_RTOS_Timeline.xml')
        shutil.copyfile(timeline, stage / 'Zephyr_RTOS_Timeline.xml')
        receipt = {'elf_sha256': hashlib.sha256(elf.read_bytes()).hexdigest(),
                   'metadata_sha256': hashlib.sha256(metadata.read_bytes()).hexdigest(),
                   'trace_bytes': binary.stat().st_size,
                   'capture_seconds_requested': duration,
                   'capture_full': 'unknown',
                   'firmware_matches_elf': 'not verified',
                   'gdb_output': result.stdout[-6000:]}
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
