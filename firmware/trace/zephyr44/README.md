# Experimental Zephyr 4.4.0 CTF → RTT firmware backend

**Status: prototype source, NOT a supported live trace feature.**

This folder contains a portable, byte-exact C implementation of the proposed
`ZEWF` v1 frame encoder, an async Zephyr tracing backend using a **dedicated**
SEGGER RTT up-channel 1, and an explicit checked patcher for **only** Zephyr
commit `684c9e8f32e4373a21098559f748f06915f950c9`.

## Opt-in integration

1. Use a clean, pristine checkout at that exact commit. Do **not** patch a
   managed Workbench checkout, existing dirty workspace or another Zephyr version.
2. Inspect source and call `python firmware/trace/zephyr44/apply_pinned_backend.py
   --zephyr-base <your-Zephyr-4.4.0-path>` for a **read-only check**.
3. Only in an isolated disposable checkout, add `--apply` to modify source.
   The script refuses a changed repo or mismatched commit, updates Kconfig,
   tracing CMake, an atomic source-drop getter and copies three files.
4. Select a board with independently established `HAS_SEGGER_RTT=y` and
   correct debugger RTT support. Add the settings shown in
   `experimental-trace.conf` as **explicit build requests**, not proof that
   Kconfig accepted them. Inspect the generated `build/zephyr/.config` and
   the exact firmware ELF symbols to verify the backend was compiled.
5. **Do not use Start Live Trace**. Independently inspect the selected board,
   probe, debugger owner, RTT channel and emitted wire bytes in a lab setup.
   The existing host collector remains test-only and cannot be connected
   to an arbitrary OpenOCD endpoint.

## Safety contract

- `CONFIG_TRACING_ASYNC=y` is required: ISR/CTF hooks only copy to Zephyr's
  bounded async trace ring, not to OpenOCD, UART or RTT. The existing Zephyr
  tracing thread invokes this backend and performs one all-or-nothing
  `SEGGER_RTT_Write()` per ≤512-byte binary payload plus 116-byte header.
  The dedicated channel is created in `SEGGER_RTT_MODE_NO_BLOCK_SKIP`.
- Channel allocation **must return 1**; the backend refuses fallback to an
  already-owned text console channel or another channel.
- Every accepted frame includes CRC32, contiguous uint64 sequence, core=0,
  stream=0, an in-firmware reported Zephyr tracing packet-drop count and
  two caller-supplied SHA-256 **contracts**. A positive upstream loss count,
  failed/non-atomic RTT write, invalid contract, wrong channel or framing
  error permanently disables the stream. There is no overwrite or retry.
- `CONFIG_ZEW_TRACE_IMAGE_CONTRACT_SHA256` is **not** the digest of the
  finished ELF: embedding the finished image's own digest is circular.
  It identifies prebuilt inputs only. Nor does a chosen metadata digest prove
  that the flashed target contains the selected build. **Separate readback
  verification and an authenticated epoch/transport handshake are missing.**
- `sys_rand_get` supplies an epoch candidate. Its uniqueness/entropy and
  reboot behaviour **must be proven per board**. A claimed epoch in the
  firmware frame is not independent evidence of reset or authenticity.
- The backend currently **never emits END**. Host capture cannot claim a
  complete stream or validated offline session. A coordinated stop, tracing
  disable, final draining and trusted loss checkpoint are pending.
- The UART, RAM snapshot, native Debug, trace console and other vendors'
  existing features are not modified. The normal app remains unchanged.
  No Workbench menu, installer or wizard enables this prototype.

## Limits and required hardware acceptance

Cannot yet claim CPU overhead, cache consistency, SMP support, clock mapping,
repeatability, no probe-induced halts, long-duration zero-loss operation or
closed lifecycle. Integration into a non-SEGGER OpenOCD probe is not proven.
nRF52840DK, STM32 Nucleo, TI LP-MSPM0G3507, ESP32-S3 and RP2040 are all
**PENDING** until matching generated configuration, ELF, flashed readback,
OpenOCD RTT up-channel selection, raw transport logs and reproducible
load/overflow tests are attached.

## Host tests

`tests/tools/test_zephyr_trace_firmware_frame.py` compiles the actual
`zew_trace_frame.c` on Linux CI with a native C compiler and checks every
emitted byte against `ctf_framing.encode_fixture_frame`. It verifies
random TCP segmentation in the host decoder and permanent poison after
simulated no-space write. This is a wire-contract test, **not** a Zephyr
target build or hardware validation. Source patch applicability must be
checked against the exact pinned Zephyr checkout before enabling a firmware
build in CI.
