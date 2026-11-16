<!-- SPDX-License-Identifier: Apache-2.0 -->
# Phase 7 — Real RTT byte-stream preview (NOT a production live-trace release)

**Status:** Experimental host-side ingestion only. The main Workbench **Start
Live Trace** menu remains unavailable. This phase never changes the target,
OpenOCD server, running GDB session, RTT channel allocation or Zephyr checkout.

## What works in this code slice

`rtt_live_preview.py` reads binary ZEWF v1 from a **pre-existing localhost
TCP bridge for the dedicated SEGGER RTT up channel 1**. Unlike the previous
test-only fake-RTT server, it can consume bytes from a real existing server.
It checks the *effective generated* `zephyr/.config` for the Zephyr 4.4
experimental RTT backend, exact TSDL SHA, framing, CRC, sequence, self-reported
epoch and source-loss frames. It incrementally commits complete CTF records and
bounded JSONL events to disk, without halting/resetting/flashing the MCU.

No probe is connected or managed by this script. It does **not** independently
prove that the TCP bridge is really RTT channel 1, that an OpenOCD/GDB session
owns the correct target, that the firmware was flashed, or that a target epoch
is authentic. The backend's ZEWF image field is a **prebuild image-contract
SHA256**, not the final ELF SHA256. Synthetic fixture bytes are not physical
validation. A successful preview remains `FORMAT_COMPLETE_UNVERIFIED`.

## Explicit opt-in command

Only use this after you have **separately configured and verified** a
channel-1 binary RTT TCP bridge with your existing debug tools. Do not pass
the channel-0 RTT text-console port.

```powershell
python scripts/preview_rtt_ctf.py `
  --port 19022 `
  --config C:\Sandbox\my_project\build\zephyr\.config `
  --metadata C:\Sandbox\zephyr\subsys\tracing\ctf\tsdl\metadata `
  --output C:\Sandbox\my_project\.zew-traces `
  --seconds 5 `
  --experimental-unverified
```

The port is an **illustrative placeholder**, not a known configured port;
replace it with the actual dedicated TCP receiver's port. This command never
starts or changes OpenOCD, RTT, or debugging. Preflight refuses RAM-only
Zephyr firmware, absent/mismatched generated CTF settings, unsupported
multi-core, missing/up-channel-0 configuration, invalid hashes and mismatched
metadata before opening a socket.

A new session folder contains `transport.bin`, `committed_ctf.bin`,
`events.jsonl`, and `receipt.json`. There is no `ctf/channel0_0` or
automatic offline qualification. A missing END, source drop, disconnection,
corrupt frame, truncated record, budget overflow or incompatible metadata
preserves the available raw evidence but creates an INCOMPLETE receipt.
The preview is bounded to 30 seconds, 12 MiB raw wire, 8 MiB committed CTF,
8 MiB event JSONL and 16,000 events. This is not yet an hours-long live
capture or timeline UI.

## MSPM0G3507/XDS110 reality

The portable **RAM** backend from Phase 4/5 is supported as experimental
*saved capture*, not live. No dedicated binary RTT channel on the user's TI
OpenOCD/XDS110 has been proven. **Do not use the existing passive RTT text
receiver** for ZEWF, or claim live RTT on MSPM0 without proof. A portable
nonblocking UART/USB binary transport is the practical next candidate for
boards without supported RTT. It needs target-side transport and physical
tests, not a guessed OpenOCD command.

## Production release gates (all currently OPEN)

1. Independently establish target/build identity, boot nonce/epoch and exact
   generated configuration, plus verified image flashed to the MCU.
2. Add a debugger/probe-aware *owned* RTT connection, alongside separate
   vendor-neutral UART/USB transport. Demonstrate no conflict with DSF-GDB,
   no target halt/reset and no console-channel reuse.
3. Add a bounded target drain with explicit counters and overflow invalidation,
   host Save/Stop/Cancel, persistent stream rotation and backpressure policy.
4. Reuse Phase 6 semantics for live timeline, CPU/ISR lanes, mutex, semaphore
   and message queues, with uncertainty/loss overlays. The event view must not
   fabricate CPU load or ownership from incomplete data.
5. Validate installed Eclipse graphical UI, fake endpoint regression, actual
   LP-MSPM0G3507/XDS110 and at least STM32, nRF and ESP32 hardware. Measure
   dropped frames, tracing overhead and timing perturbation with real clocks.
6. Only then gate the user-facing Start Live Trace action on the verified
   transport capability and release checks. Do not change
   `LIVE_STREAMING_ENABLED=false` merely because this preview works.

## Competitive baseline, not a claim of parity

SEGGER SystemView offers continuous RTT recording, multicore and analysis
of tasks, interrupts, timers and user events:
https://www.segger.com/products/development-tools/systemview/

Percepio Tracealyzer provides linked task, CPU, object-interaction, memory
and data views and live visualization:
https://percepio.com/tracealyzer/features-capabilities/

The Workbench objective is an Apache-2.0, all-commercial-use-friendly
native Eclipse trace workbench with vendor-neutral transports and transparent
evidence receipts. The existing partial tools **do not yet match** either
commercial product. Open source is an actual license property, not proof of
runtime completeness.
