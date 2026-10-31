<!-- SPDX-License-Identifier: Apache-2.0 -->
# Advanced RTOS Diagnostics — implementation and acceptance

This integrated workbench capability is additive to the existing CTF RAM
snapshot, Trace Compass preview, Zephyr fault-log analysis, SoC SVD register
view and build Evidence Engine. It does not replace Zephyr's native tracing
or core dump format and does not alter any application firmware.

## User workflows

### A. Thread and CPU timing

Use **Zephyr > Capture RTOS Trace (Preview)** on an explicitly supported
OpenOCD-enabled board with built CTF RAM tracing. This capture **resets the
board, runs it, then halts it**: do not invoke on an active production
controller. The generated capture opens its local `report.html` automatically,
with `capture.json`, `diagnostics.json`, CTF bytes, and XML timeline
retained in `.zew-traces/trace-*/`.

A decoder with Babeltrace 2's Python `bt2` module must be available for
the built-in CTF metrics; otherwise analysis says **not checked** and native
Trace Compass can inspect the CTF. The selected Zephyr version's TSDL
metadata is required. Invalid/missing events never become zero CPU load.

`analyze_trace.py` also accepts `--idle-thread-id THREAD` and repeated
`--deadline THREAD_ID:POSITIVE_NS`. Timing observations include matched
scheduling bursts, period range jitter, explicit ready→switch-in latency,
observed priority/state-event counts and observed deadline exceedances.
These are **not worst-case execution time (WCET)**, and they cannot
independently prove ready/blocked states from gaps. CPU/ISR accounting
keeps nested ISR time exclusive; unknown intervals remain unknown.

### B. UART application console

Choose **Zephyr > Capture UART Application Console…**, specify the exact
free COM/tty port, baud rate and bounded duration. The managed Python
interpreter must have **pyserial** installed; if missing, an actionable error
is shown. Do not point this at an OpenOCD/GDB port, RTT socket, or unframed
binary CTF stream. The recorder **does not reset, flash, halt, resume or
read MCU registers**.

Files are saved under `.zew-uart/serial-*/`:

- `raw.bin`: original received serial bytes (with SHA-256 receipt)
- `messages.jsonl`: decoded lines, host reception UTC, truncation and
  replacement-character flags; no invented thread/severity/target time
- `receipt.json`: port, baud, start/end, reason for stop and explicit
  message-index completeness

The recorder bounds raw bytes, line lengths and indexing size. This is a
**host-observed console**, not a synchronized CPU timeline.

### C. SoC registers and comparison

Use **Zephyr > Show Debug Data / SoC Peripherals**, load a trusted
**exact-chip SVD**, select a *suspended* native DSF-GDB frame, watch safe
registers, and use **Refresh selected + watches**. The view does not
perform unrequested reads and honours its SVD read-side-effect guards.

**Pin baseline** stores a selected read-only snapshot. Later manual Refresh
can identify changed register values within the same DSF session and SVD
checksum; changing either resets the bounded history. **Export history CSV…**
writes at most 16 samples, each up to 256 register values, with local
capture time, session ID, SVD SHA-256 and raw values. The reads are
sequential, not an atomic full-chip snapshot. They do **not** establish
an electrical GPIO level or a causal fault without additional evidence.

### D. Cortex-M fatal logs and binary core dumps

**Zephyr > Investigate Fatal Log…** remains the read-only text fault
investigator. It knows architecture limits; e.g. Cortex-M0+ must not be
interpreted as Cortex-M4 CFSR/HFSR. Source lookup uses a user-confirmed
matching ELF, not automatic build guessing.

New **Zephyr > Investigate Saved Binary Core Dump (Offline)…** selects a
raw `ZE` binary dump, trusted target GDB and exact *user-confirmed* build
ELF. It runs the **selected Zephyr checkout's own**
`scripts/coredump/coredump_gdbserver.py --pipe` through local GDB, not a
network server or physical debug probe. It requests core registers and
bounded stack traces. Output is a versioned JSON record containing SHA-256
of the ELF/dump and the unmodified GDB output. A GDB backtrace can be partial,
inconsistent or wrong when memory or ELF identity is missing. This operation
never erases/rewrites crash memory on the device. Rejects absent, oversized
and malformed ELF/dump and requires explicit confirmation.

If only a `#CD:BEGIN#` serial text log is present, first convert using
the **matching** Zephyr checkout's documented
`coredump_serial_log_parser.py`, then inspect the raw binary. A text-only
fatal excerpt must not be mislabelled as a valid binary core dump.

### E. Joined investigation and Evidence Engine

**Zephyr > Review Saved RTOS Capture (Offline)…** optionally attaches a
fatal text log and a saved `.zew-uart/serial-*` session. It checks CTF,
metadata, ELF, `.config` and UART hashes; reports missing timestamps,
backend loss/fullness, trace quality, observed deadline overruns and
unaligned sources. It never infers that the host UART capture, firmware,
fatal event and CTF are the same boot or share a timebase without external
proof. Evidence findings include stable codes and actions. The HTML
report escapes untrusted diagnostic text and requires exclusive time
totals to reconcile before drawing a CPU chart.

## Release qualification

| Area | Automated gate | Hardware gate |
|---|---|---|
| CTF event/timing | Synthetic scheduler/ISR/clock/loss fixtures | Exact Zephyr metadata and MSPM0 capture |
| CPU/accounting | Known exclusive/thread/ISR/unknown durations | Clock calibration, capture overhead, real idle ID |
| UART logs | Fake serial, raw hash, truncation, close and bounded output | Named UART/USB console/baud on each claimed board |
| Register diff | Immutable snapshots, scope invalidation, bounded history | Real halted DSF-GDB + exact SVD; side-effect check |
| Fault/core dump | Input limits, explicit ELF, local GDB pipe contracts | Real fault dump + matching Zephyr target GDB |
| Evidence | Modified artifacts, loss, UART/ELF mismatch | Same boot/session corroboration |
| Eclipse | Native plug-in packaging and smoke tests | Interactive acceptance on actual workbench |

## Still capability-gated — do not claim universal support

Unsupported or unverified by this implementation: live SMP CPU telemetry
across unsynchronized cores, RTT/SWO log transport on every probe,
automatic Trace Compass event synchronization, hardware PMU/cycle
profiling, universal pin voltage/direction reads, kernel-object ownership
and priority inversion without explicit trace evidence, autonomous repair
of crashing firmware, or binary core dump unwind on *every* Zephyr
architecture/version. None may be presented as confirmed until named
target hardware tests substantiate it. Passing a cross-vendor firmware
build alone does not validate runtime instrumentation.
