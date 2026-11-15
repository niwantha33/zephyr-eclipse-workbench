# Experimental pinned Zephyr 4.4.0 CTF transport (MSPM0-first)

**NOT a supported Start Live Trace feature.**

The transport implementation is **vendor-neutral**, not an nRF-specific
implementation. The same portable CRC-framed ZEWF v1 C encoder serves:

- `CONFIG_TRACING_BACKEND_ZEW_RAM`: bounded, non-wrapping static RAM capture;
  requires only Zephyr CTF, asynchronous tracing and one selected core.
  Suitable for TI MSPM0G3507 and other supported vendors with enough SRAM.
- `CONFIG_TRACING_BACKEND_ZEW_RTT`: dedicated SEGGER RTT channel 1, only
  for boards whose generated Kconfig/probe **prove** RTT capability.
  No automatic fallback to channel 0 or RTT text output.

See [complete Phase 4/5 MSPM0 instructions](../../../docs/TRACE_PHASE4_5_MSPM0.md).

## Setup (explicit, never managed automatically)

1. Use a **disposable clean Zephyr checkout** pinned to
   `684c9e8f32e4373a21098559f748f06915f950c9`. The Workbench/installer
   never patches its managed Zephyr checkout.
2. `python firmware/trace/zephyr44/apply_pinned_backend.py
   --zephyr-base <disposable-zephyr>` checks source anchors without writes.
   After review, add `--apply` deliberately.
3. For an MSPM0 use `experimental-mspm0-trace.conf`, not the RTT config.
   The two 64-hex contracts are empty by default and **must be provided
   from verified prebuild inputs**. The backend refuses to initialize
   if they are empty or malformed. Generated `.config` and ELF must be
   inspected; requested flags alone are not sufficient.
4. Build the portable `examples/trace_framed_probe` and flash only with
   your existing selected hardware/probe workflow. Run, manually halt,
   check `zew_trace_ram_receipt` and dump `zew_trace_ram_bytes` up to
   `committed`. `scripts/inspect_framed_ram.py` checks offline frame and
   CTF-record integrity but does **not** qualify a physical board.

## Stop/drain contract

Zephyr's trace hooks enqueue events into its existing bounded async ring.
Nothing performs RTT, UART or debug I/O in a producer/ISR callback.
The example app calls `zew_trace_stop_request()` once. Under the same
single-core IRQ lock as the async producers, Zephyr disables new tracing.
Each producer repeats the enable check *inside* the lock, so records
cannot be appended after the stop barrier. The dedicated Zephyr worker
drains the ring and invokes `zew_trace_backend_close()` to emit exactly
one END frame **only if the source and sink did not report loss**.

An invalid contract, source drop, RAM capacity overflow, partial/nonblocking
RTT failure, malformed data, repeated stop, or missing final frame fails
closed. No automatic restart or unsafe resynchronization. The RAM buffer
must be read while **halted**; running-target reads are unproven.

## Build and test evidence

The Windows CI runs real pinned Zephyr builds of the same RAM backend for
**TI MSPM0G3507 first**, then STM32F446, ESP32-C3 (RISC-V) and ESP32-S3 (Xtensa). It verifies generated
config, CTF/stop/RAM symbols in the linked ELF/map, and a CURRENT
configuration provenance receipt. It writes `trace-firmware-matrix.json`.
Linux host tests compile the **actual backend C source** using test-only
Zephyr shims and check exact HELLO/DATA/END bytes, duplicate stop, source
loss and overflow against the existing Python verifier.

**CI builds are not physical tests.** We do not have a connected user board.
No production OpenOCD RTT attach, firmware flash readback, reset/epoch
attestation, loss-free performance, live semantic UI or 10 Hz workbench
stream is proven. Start Live Trace remains disabled. Every vendor physical
qualification row remains PENDING. A prebuild image contract is not the
SHA256 of the finished ELF or proof of what is flashed.
