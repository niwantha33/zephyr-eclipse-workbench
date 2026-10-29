#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Test-only GDB relay: model a target that supplies thread lists but no MI creation events."""
import os
import subprocess
import sys
import threading

if '--version' in sys.argv[1:]:
    os.execv(os.environ['ZEW_REAL_GDB'], [os.environ['ZEW_REAL_GDB'], *sys.argv[1:]])

child = subprocess.Popen([os.environ['ZEW_REAL_GDB'], *sys.argv[1:]], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
trace = os.environ.get('ZEW_MI_TRACE_LOG')

def trace_mi(direction, line):
    # Bounded diagnostic trace, only relevant GDB MI commands/results.
    # Saved only inside the disposable test directory, never in the installed IDE.
    if not trace or len(line) > 4096:
        return
    if b'stack-' in line or b'^error' in line or b'=thread-' in line or b'*stopped' in line:
        with open(trace, 'ab') as output:
            if output.tell() < 1_000_000:
                output.write(direction + line)

def forward_input():
    try:
        for line in sys.stdin.buffer:
            trace_mi(b'COMMAND ', line)
            child.stdin.write(line)
            child.stdin.flush()
    except (BrokenPipeError, OSError):
        pass
    finally:
        try:
            child.stdin.close()
        except (BrokenPipeError, OSError):
            pass

threading.Thread(target=forward_input, daemon=True).start()
try:
    for line in child.stdout:
        trace_mi(b'OUTPUT  ', line)
        if line.startswith((b'=thread-created,', b'=thread-exited,')):
            with open(os.environ['ZEW_THREAD_EVENT_LOG'], 'ab') as log:
                log.write(line)
        else:
            sys.stdout.buffer.write(line)
            sys.stdout.buffer.flush()
    sys.exit(child.wait())
finally:
    if child.poll() is None:
        child.kill()
        child.wait()
