<!-- SPDX-License-Identifier: Apache-2.0 -->
# Zephyr Live Trace foundation — Phases 4–5 (MSPM0-first)

**Status: opt-in firmware build and offline RAM evidence; NOT a live feature.**
Use this with your TI **LP-MSPM0G3507**. You do NOT need an nRF board.
The transport implementation is vendor-neutral: the **same** `ZEWF`
frame encoder and finite RAM backend are compiled on MSPM0, STM32 and
ESP32-C3 by the Windows CI matrix. Nordic hardware is NOT a prerequisite.
Hardware targets, RTT probe ownership and real-time capture remain
separately PENDING until real captures are independently verified.

## Architecture

```text
Zephyr 4.4.0 CTF hooks (IRQ/task)
  → bounded Zephyr asynchronous CTF ring (no source IO)
  → Zephyr tracing worker
     ├→ CONFIG_TRACING_BACKEND_ZEW_RAM  (portable; MSPM0 first)
     │     └→ bounded static RAM with CRC+sequence+boot marker+drop count
     └→ CONFIG_TRACING_BACKEND_ZEW_RTT  (only if real SEGGER support verified)
           └→ dedicated no-block RTT up channel 1
  → explicit zew_trace_stop_request() from application thread
  → producer gate closed inside same single-core IRQ lock
  → async worker drains existing bytes → END only if zero loss
  → halted-debug RAM dump → strict offline format inspection
```

**No vendor names or chip peripheral addresses are hard-coded in either
backend.** Selection uses exact effective Kconfig and the board's real
capabilities. RTT is unavailable to a board that does not declare SEGGER
support; the RAM backend never accesses SEGGER/USB/UART or OpenOCD.

## Phase 4 — lifecycle and failures

- `zew_trace_stop_request()` is nonblocking, one-shot, thread-context-only.
  It disables new CTF producers atomically under the single-core IRQ lock.
  Each async producer rechecks the enabled flag **inside** that lock.
- The existing Zephyr tracing worker drains the full ring. The matching
  backend writes exactly one final END frame only when the ring is empty
  and both Zephyr source drops and backend writes are loss-free.
- Missing END, buffer overflow, unmatched metadata, nonzero source loss,
  partial RTT write, duplicate stop, wrong epoch or integrity failure
  invalidate the trace interval. No zero-filled reconstruction or
  automatic reset/restart occurs. Tracing is not silently reenabled.
- The portable RAM backend exports `zew_trace_ram_bytes` and
  `zew_trace_ram_receipt`. The receipt distinguishes capacity/committed
  bytes, END, source drops and error. The binary buffer is **not** a
  polling/running-target transport: inspect it only while halted.
- Native C tests execute the **actual** RAM backend, checking HELLO/DATA/END
  bytes against the existing Python frame decoder, source drops, overrun
  and duplicate stop. Tests use test-only Zephyr shims, no hardware.

## Phase 5 — real firmware build evidence

The Windows CI job builds a real Zephyr application (`examples/trace_framed_probe`)
after explicitly patching a **disposable**, clean Zephyr checkout at
`684c9e8f32e4373a21098559f748f06915f950c9`.
MSPM0G3507 is built **first**, followed by STM32F446, ESP32-C3 (RISC-V) and ESP32-S3 (Xtensa app core).
The same RAM backend is selected on all three boards. The build gate
requires generated `CONFIG_TRACING_CTF=y`, `CONFIG_TRACING_ASYNC=y` and
`CONFIG_TRACING_BACKEND_ZEW_RAM=y`, no SMP or competing RTT backend;
a real `zephyr.elf` with allocated CTF hooks and linked capture/stop
symbols; and a **CURRENT** source/board/build evidence receipt.
The CI report records selected ELF, generated config and exact metadata
hashes, **not** a flashed-firmware or physical hardware qualification.

CI source: `scripts/trace_firmware_build_matrix.py`.
Native check: `tests/core/TraceFirmwareRealBuildProbe.java`.
Existing seven-target Windows CI and Eclipse packaging checks remain.

## How to inspect your MSPM0 board (manual, after CI acceptance)

1. In an **isolated, disposable** pinned Zephyr 4.4 checkout, inspect the
   patcher with `python firmware/trace/zephyr44/apply_pinned_backend.py
   --zephyr-base <your-checkout>`. Only deliberately add `--apply` after
   reviewing the sources. Do not patch a normal Workbench-managed checkout.
2. Use `examples/trace_framed_probe` with
   `firmware/trace/zephyr44/experimental-mspm0-trace.conf`. Configure
   the two exact nonempty 64-hex prebuild image-contract and upstream CTF
   metadata digests. Check the generated `.config`; no defaults are trusted.
3. Build **LP-MSPM0G3507**, inspect the ELF and flash using your existing
   TI XDS110/OpenOCD workflow. No nRF/J-Link connection is necessary.
4. Resume the probe for at least the demonstration's 150 ms and HALT
   manually with the **existing** debugger. In its GDB console, check:

   ```gdb
   p zew_trace_stop_result
   p zew_trace_ram_receipt
   p &zew_trace_ram_bytes[0]
   ```

   Require `zew_trace_stop_result == 0`, `ended == 1`, `error == 0`,
   `source_drops == 0` and `committed` within the configured capacity.
   If any check fails, do not promote the evidence to a complete trace.
   With the target halted, dump exactly the `committed` length:

   ```gdb
   dump binary memory C:/trace/trace.bin &zew_trace_ram_bytes[0] &zew_trace_ram_bytes[zew_trace_ram_receipt.committed]
   ```

5. Use the **offline-only**, checksum/format inspector with your actual
   selected build directory and the pinned Zephyr CTF TSDL metadata:

   ```powershell
   python scripts/inspect_framed_ram.py --wire C:/trace/trace.bin --build C:/trace/my-build --metadata C:/Zephyr/zephyr/subsys/tracing/ctf/tsdl/metadata --output C:/trace/format-report.json
   ```

   The report is deliberately **NOT** a physical hardware or image
   attestation. It checks the packet and decoded-record format and marks
   `flashed_firmware_identity_verified=false` and
   `live_stream_approved=false`. Verify probe identity, actual flash
   readback and any clock/reset evidence separately.

## Cross-vendor support boundary

| Target class | Candidate firmware transport | Build acceptance | Physical acceptance |
| --- | --- | --- | --- |
| TI MSPM0G3507 / XDS110 | Bounded framed RAM | CI real build gate | PENDING |
| STM32F446 / ST-Link | Same RAM, optional dedicated RTT if proven | CI real build gate | PENDING |
| Espressif ESP32-C3 / Espressif OpenOCD | Same RAM; RTT only if proven | CI real build gate | PENDING |
| Espressif ESP32-S3 / Espressif OpenOCD | Same RAM on one selected Xtensa core | CI real build gate | PENDING |
| Nordic nRF52/nRF53 | Same RAM or dedicated RTT if probe supports it | Not included in this new physical-first matrix | PENDING (no board owned) |
| RP2040/RP2350, NXP, Renesas, Microchip, Silicon Labs, other Zephyr SoCs | Same RAM interface subject to memory limits and single-core gate | PENDING | PENDING |

**Future work:** independent flashed ELF/boot attestation, safe running
RTT/UART transport ownership and capture, physical timing and loss tests,
incremental semantic views, Stop/Save workflow and release qualification.
Until then `LIVE_STREAMING_ENABLED=false` and the existing live menu is a stub.
