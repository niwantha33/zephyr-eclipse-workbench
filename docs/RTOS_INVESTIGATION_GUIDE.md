<!-- SPDX-License-Identifier: Apache-2.0 -->
# RTOS Investigation — capture → CPU → evidence → fault guidance

This is an offline, read-only **investigation slice**, not a generic MCU live
profiler or a promise of zero tracing overhead. It works with the existing
Zephyr CTF RAM snapshot workflow (OpenOCD and an exact built ELF).

## First use on an already supported Zephyr board

1. Build an application with `CONFIG_TRACING=y`,
   `CONFIG_TRACING_CTF=y`, `CONFIG_TRACING_BACKEND_RAM=y` and a suitable
   `CONFIG_RAM_TRACING_BUFFER_SIZE`. Do not blindly replace existing
   project settings; validate each symbol against your selected Zephyr version.
2. Flash the exact image *separately*. In Workbench choose
   **Zephyr → Capture RTOS Trace (Preview)…**. Confirm that this explicitly
   resets and runs the target and leaves it halted, as the existing capture
   workflow has always done. Do not use capture on a production running target.
3. In the new `.zew-traces/trace-*/` directory, open
   `diagnostics.json` for guided evidence and `capture.json` for raw
   measurements, backend capacity and decoder status.
4. Select the Zephyr project and choose
   **Zephyr → Review Saved RTOS Capture (Offline)…**. Select the *individual*
   `trace-*` directory. Optionally provide one saved fatal text log.
   The analysis runs in an Eclipse Console; it never resets, flashes or
   connects to the target. When a selected build exists its ELF and generated
   `.config` hashes are compared with the saved receipt.
5. For the scheduling timeline, import the CTF directory into Trace Compass
   and import the supplied XML analysis where required. For PC/LR/SP and
   supported Arm CFSR/HFSR/BFAR/MMFAR status, open
   **Zephyr → Investigate Fatal Log…**. This is a separate architecture-aware
   view; it does not infer the fault was produced by this trace.
6. To review saved CTF directly using a Python interpreter with Babeltrace 2
   `bt2`, run `analyze_trace.py path/to/ctf` (optionally
   `--idle-thread-id ID`). On a missing decoder, timing is **not checked**.

## CPU analysis contract

The CTF analyzer produces `cpu` with **thread-exclusive nanoseconds**,
`isr_ns`, nested ISR maximum depth, *explicitly unknown* duration, start/end
timestamps and coverage warnings. Its accounting follows known thread
switch-in/switch-out and nested ISR enter/exit events on one stream. ISR time
is not charged to the interrupted thread.

If an interval has neither a known running thread nor an ISR, it is
**unknown**—not assumed idle. `cpu_busy_percent` remains **null** unless the
caller explicitly provides the **observed idle thread ID** and the selected
clock window is fully covered. Unsupported multiple streams, timestamp
reordering, malformed switch/ISR pairs or decoder-reported loss cause CPU
accounting to be **unavailable**, never fabricated. A partial trace can still
offer clearly labelled observed per-thread totals.

`cpu_busy_percent` is conditional scheduler-derived occupancy, not a power
measurement, instruction-count utilisation, proof of complete capture, or
peripheral/DMA activity. Even a numerically measured result does not prove
an unreported event loss is absent. Trace overhead and clock accuracy require
physical validation. **Max observed duration is not proven WCET**.

## Evidence Engine and guided fault workflow

The saved `diagnostics.json` and offline `investigation.py --capture DIR`
perform bounded checks on CTF stream length and SHA-256, metadata SHA-256,
buffer-full and decoder loss, generated `.config` hash and selected ELF hash
when provided. Use `--build BUILD_DIR` to compare those artifacts, and
`--fault-log TEXT_FILE` to include a separate log checksum, fatal-marker
count and core-dump marker. No memory is read. A matching build ELF hash does
**not** prove that the device was flashed with that ELF.

Each finding has a stable code, severity, provenance and corrective action.
For example:

| Code | Meaning / next step |
|---|---|
| `ARTIFACT_HASH_MISMATCH` | Saved trace/metadata changed; stop and recapture |
| `CTF_NOT_VALIDATED` | No supported CTF decoder or invalid trace; validate using Babeltrace/Trace Compass |
| `CTF_REPORTED_LOSS` | Dropped packets/events; CPU timing withheld |
| `TRACE_BUFFER_FULL` | RAM backend full; increase size or shorten capture |
| `CPU_METRICS_PARTIAL` | Unknown idle/clock/coverage or invalid switches |
| `SELECTED_ELF_MISMATCH` | Selected build differs from capture receipt |
| `FAULT_CORRELATION_UNVERIFIED` | Log and trace lack proven common boot/clock identity |
| `COREDUMP_REQUIRES_DECODER` | Zephyr core-dump payload needs matching upstream parser/GDB |

The review command exits **2** for invalid inputs, **1** for blocking
evidence, and **0** for a review with recorded limits. It never calls GDB,
OpenOCD, `west flash` or writes project source.

## Unfinished cross-vendor capabilities (do not claim supported)

Live CPU charts per core, UART/RTT application console integration,
thread-ready/wait/sleep transitions, priority-inversion/mutex attribution,
automatic source-correlated ISR profiling, verified register histories and
saved binary core-dump unwinding remain dependent on target-specific
instrumentation, synchronized clocks, appropriate vendor/probe adapters and
hardware acceptance. The current pipeline intentionally refuses these
claims when input evidence does not establish them. The existing SoC/SVD and
fault-log tools remain available as separate capabilities.

## Test evidence

Headless fixtures cover nested IRQ accounting, invalid/missing timing,
unidentified idle, loss/multi-stream rejection, tampered CTF, ELF/config
mismatch, absent decoder, fault-marker uncertainty and a denied symlink.
GitHub Actions additionally compiles the native Eclipse plug-in, captures
installed-UI smoke evidence and runs pinned Windows firmware builds.
Passing firmware compilation **does not** prove every target's RTOS trace
transport, hardware exception decoder or CPU profile.
