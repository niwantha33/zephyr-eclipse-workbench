<!-- SPDX-License-Identifier: Apache-2.0 -->
# Live CTF streaming into the IDE — design only

**Status: NOT SUPPORTED.** This document is a proposed architecture, not an
implementation or user guide for an available feature. As of this change,
`LIVE_STREAMING_ENABLED = false`. The **Start Live Trace (not yet available)**
menu item only displays an unavailable-feature message. No live CTF sender,
receiver, decoder, scheduler, connection, or incremental UI update exists.

Existing **saved RAM CTF snapshot analysis** and **separate UART/RTT text
console capture** continue to operate independently. Neither is a live CTF
stream, and the text receiver must never be treated as one.

## 1. Contract and boundaries

The existing offline entry points are
`plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis/analyze_trace.py`
and `semantic_trace.py`. The current `decode_ctf(path)` uses a Babeltrace 2
trace-directory iterator; `SemanticTraceParser.parse(source)` consumes the
entire iterator, holds `raw_records` and `events`, and sorts them at the end.
This is **not** an incremental append API. Do not call it on a growing CTF file
and assume incomplete final records, reopened iterators or repeated events
will behave correctly.

Future implementation requires a distinct, opt-in CTF binary pipeline:

```text
Zephyr CTF hooks
  -> bounded target-RAM trace buffer (producer; loss accounted)
  -> firmware-owned drain mechanism (outside latency-critical hooks)
  -> framed, integrity-checked transport (RTT | UART/USB | network)
  -> host ingestion + bounded append-only staging for ONE session/epoch
  -> metadata-verified, complete-record/packet decoder checkpoint
  -> stateful semantic append -> evidence-qualified views
```

The workbench must **not** co-opt its current RTT *text* socket, UART
*text* parser, OpenOCD/GDB session, or the existing reset-and-halt RAM
snapshot code. A future change must prove the build identity, metadata
identity, transport capabilities and ownership before enabling capture.

## 2. Transport choices for binary CTF

| Option | Potential benefit | CTF-specific limitation and safety requirement |
| --- | --- | --- |
| **SEGGER RTT** | Debug-probe path avoids extra board pins and can be low overhead if hardware/probe support it. | Requires a correctly configured RTT control block, a **dedicated binary channel**, cooperating debug server, bounded receiver rate, and detection of RTT ring overflow. The current passive localhost **text** receiver is not a binary CTF protocol. No universal probe support. |
| **UART / USB CDC** | Broad availability and testable raw-byte transfer without debugger ownership. | Throughput/baud and interrupts may perturb the schedule; framing, binary-transparent handling, port ownership, reset/re-enumeration and flow control are mandatory. Never multiplex unframed printk text with CTF. USB CDC is not a guarantee against dropped data. |
| **Network (TCP or UDP)** | Higher potential throughput and physically remote targets. | Requires target networking, memory and CPU budget, network-session identity and security. TCP backpressure can stall a drain task; UDP needs explicit ordering, loss and deduplication. Neither transport implies target-clock synchronisation or integrity of source metadata. |

**No transport has been selected or implemented.** The future protocol must
negotiate protocol version, run/session/boot epoch, target/build identity,
metadata digest, stream/core ID, monotonically increasing sequence number,
payload length/checksum and explicit loss counters. A reset or reconnect
must not silently splice separate epochs together. Limits and transport
security must be reviewed before enabling any network listener.

## 3. Target buffer placement, ownership and draining

- The producer's primary buffer must be allocated in **target RAM** with
  known capacity and lifetime; it cannot live on a transient task stack,
  unbounded heap, or host-only `ctf/` directory. Use a dedicated static RAM
  ring or a Zephyr-supported tracing backend after verifying the exact Zephyr
  version and SoC. The current `CONFIG_TRACING_BACKEND_RAM` is a **snapshot**
  source, not proof that firmware can drain it concurrently.
- CTF hook/ISR producers must be bounded and nonblocking. Define per-core
  ownership where applicable, concurrency/atomic-index rules, memory barriers,
  wrap-around semantics and cache/DMA coherence. Never perform blocking
  socket writes, UART writes or RTT connection management in the trace hook.
- A **future firmware-side** low-priority draining mechanism copies only
  committed bytes/packets out of the ring. It needs explicit chunk/packet
  boundaries, a monotonic sequence/offset, sufficient staging memory and
  observable head/tail high-water and lost-byte/event counters. Firmware must
  not read partially written producer data.
- Overflow policies must be explicit (drop newest or overwrite oldest), with
  loss counts and gap markers. Do not infer "no loss" from the absence of
  counters. A host pause or target halt may change tracing behaviour; never
  silently halt/resume the target to drain the buffer.
- The **host** keeps its own bounded raw staging files; these do not replace
  the target buffer. Firmware version-specific Kconfig options, buffer
  alignment, memory region and transport driver dependencies need hardware
  validation before they can be documented as working.

## 4. Incremental CTF decode and saved evidence

Proposed host state machine (not implemented):
`DISCONNECTED -> HANDSHAKE -> CAPTURING -> FINALISING -> SEALED`;
`RESET`, `LOSS`, `MALFORMED` or `HOST_OVERFLOW` terminates the
current trustworthy interval. Reconnection starts a **new epoch**.

1. Verify metadata against the **matching firmware/Zephyr checkout**, save
   immutable `metadata`, and refuse unknown protocol or wrong build identity.
   Keep raw frames, transport receipts and per-epoch provenance with the CTF
   material. Do not fabricate missing metadata.
2. Append raw data to a temporary session-specific file. Transport **frame**
   boundaries are not automatically CTF **record/packet** boundaries. Commit
   only decoder-safe, complete CTF units after validating the actual wire
   format. Hold trailing partial units in a **bounded** pending buffer without
   padding them or guessing record length.
3. The present `bt2.TraceCollectionMessageIterator` reads a saved directory;
   it does not expose a supported feed-more-bytes method. A future decoder
   adapter must either (a) use a verified streaming-capable Babeltrace/CTF
   source, or (b) re-open **only sealed, monotonically committed** units with
   explicit offset/packet checkpoints and deduplication. Re-opening a growing
   `channel0_0` with no checkpoint is **not** acceptable.
4. Propagate decoder-reported discarded events/packets and transport gaps as
   separate evidence records. For an incomplete final packet, show "pending
   partial data" while connected; on shutdown/timeout, mark the session
   incomplete and preserve the bytes. Never mark a truncated capture valid.
5. An explicit **Save/Close** action would seal a normal offline-compatible
   `ctf/metadata` and `ctf/channel0_0` folder (only when valid), plus a
   provenance/loss receipt. Existing `analyze_trace.py` remains the
   independently reproducible final analysis. A live preview is not a
   substitute for validated offline analysis.

## 5. Semantic normalization changes needed before live append

`SemanticTraceParser.parse()` currently allocates a fresh `SemanticTrace`,
tracks a local previous timestamp, appends all raw records and performs a
global sort. `analyze_trace.summarize()` and other consumers expect
whole-trace inputs. Any future append interface must be **separate** from
this existing API until fully regression-tested:

- Introduce a session-scoped normalizer with e.g. `begin_epoch(metadata_id,
  stream_id, clock_id)`, `append_committed(records, checkpoint)`, and
  `end_epoch(reason)`. These are **design examples, not callable methods**.
- Carry across calls the last timestamp **per stream/clock**, tracepoint
  specifications/sample counts, unknown and decoder-error counts, loss status,
  thread scheduling/ISR/resource state and transport checkpoint. Reject
  out-of-order source order, conflicting metadata or invalid timestamps; do
  not globally sort away contradictions or invent boundaries.
- Each append must be exactly once using an epoch + stream + packet/record
  offset identity. A retried chunk must be detected as a duplicate, not
  counted twice. Keep unmatched boundary events open/UNKNOWN until real
  evidence arrives; reset invalidates previous ownership and timing.
- Bound raw and semantic memory usage, use append-only snapshots for the
  read-only UI, and rate-limit UI redraws independently of receive rate.
  Consumers needing complete intervals must show partial/unknown status,
  not a fabricated CPU percentage, WCET, memory peak or priority inversion.
- Test the same saved trace parsed once versus committed in irregular
  chunks, including record splits, loss, duplicates, stream boundaries,
  counter wrap, reset, corrupted metadata and out-of-order timestamps.
  No live update can be claimed before equivalence and real-board tests.

## 6. Failure handling (fail closed)

| Failure | Required future behaviour |
| --- | --- |
| **Target resets mid-stream** | Detect new boot/epoch or link loss; close prior epoch incomplete, discard in-flight decoder state, keep its raw evidence, revalidate firmware/metadata. No stitched cross-reset timing. |
| **Target RAM buffer overflows** | Surface sequence gaps/loss counter and affected time range as **unknown**; never reconstruct vanished events, claim lossless capture or issue complete-timeline metrics. |
| **Host falls behind** | Bound receive queue, pending packet, disk usage and UI redraw. If backpressure cannot preserve data, emit a visible host-loss condition and stop/mark incomplete. No hidden unbounded queue. |
| **Transport disconnect/reordering** | Preserve sequence receipts, de-duplicate retries, mark missing spans, require explicit reconnect/new epoch. UDP reordering must be bounded and authenticated if used. |
| **Partial/corrupt CTF or metadata mismatch** | Quarantine uncommitted suffix, preserve raw bytes, reject semantic updates until complete and validated; withhold dependent metrics. |
| **Debugger/port ownership conflict** | Refuse to attach and explain conflict. Do not reset, flash, halt or steal a serial/RTT session. |

## 7. Explicitly NOT supported

**Now:** Live CTF streaming by RTT, UART, USB or network; any automatic
binary CTF receiver, incrementally growing CTF parser, live semantic view,
background capture worker or dynamic feature switch. The menu entry is
**message-box only**. There is **no fake or simulated live trace source**.

**Even after a future implementation:** universal board/probe/transport
compatibility; guaranteed lossless or zero-overhead recording; ITM/SWO on
Cortex-M0/M0+; automatic conversion of arbitrary `printf` text into CTF;
accurate wall-clock correlation without validated synchronisation;
multi-core causal ordering without clock mapping; complete CPU/load or
resource/memory accounting when source events, clocks or loss evidence are
missing; remote exposure without separate authentication/security review; or
proof of flashed firmware identity from a CTF file alone.

## 8. Preconditions for a separate future implementation PR

Demonstrate a **real target** and an independently decoded, saved byte-exact
capture; confirm Zephyr/backend version-specific CTF framing; create
transport-specific firmware/host tests; verify bounded target/host loss
accounting and reset epochs; prove existing GDB/RTT/serial ownership remains
safe; compare chunked vs offline semantic outputs; test installed Eclipse UI,
package CI and documented unsupported combinations. Until then,
`LIVE_STREAMING_ENABLED` stays `false` and the stub must not be advertised
as an available capability.


## 9. Pinned Zephyr 4.4.0 implementation audit (2026-11-15)

**Live CTF remains NOT SUPPORTED.** The first core-only code slice adds
`TraceKconfigProfile` (configuration request templates) and
`KconfigTraceEvidence.inspectGenerated` (bounded check of the effective
`zephyr/.config`). These APIs neither apply an overlay nor enable a menu or
create a socket. The existing `ConfigurationEvidenceReceipt` must also be
CURRENT for build-input freshness; a matching requested symbol does *not*
prove a matching flashed image or safe trace transport.

**Source of truth:** `tools/setup-lock.json` in the UI bundle pins Zephyr
4.4.0 at `684c9e8f32e4373a21098559f748f06915f950c9` (not a floating tag).
The following were checked against that exact revision:

| Upstream pinned file | Verified Kconfig/behaviour |
| --- | --- |
| `subsys/tracing/Kconfig` | `TRACING`, `TRACING_CTF`, `TRACING_SYNC`, `TRACING_ASYNC`, `TRACING_BACKEND_RAM`, `TRACING_BACKEND_UART`, `RAM_TRACING_BUFFER_SIZE`, `TRACING_BUFFER_SIZE`, `TRACING_OBJECT_TRACKING`. **No `TRACING_BACKEND_RTT`** |
| `kernel/Kconfig` | `THREAD_NAME`, `THREAD_MONITOR`, `THREAD_STACK_INFO`, `INIT_STACKS`, `THREAD_RUNTIME_STATS`, `SCHED_THREAD_USAGE` |
| `subsys/debug/Kconfig` | `DEBUG_THREAD_INFO` depends on **!SMP**; never force from vendor name alone |
| `modules/segger/Kconfig` | `USE_SEGGER_RTT`, `SEGGER_RTT_MAX_NUM_UP_BUFFERS`, `SEGGER_RTT_BUFFER_SIZE_UP`, `SEGGER_RTT_MODE_NO_BLOCK_SKIP`; default console RTT channel 0 is **not** a dedicated CTF channel |
| `subsys/tracing/CMakeLists.txt` | no CTF RTT backend is compiled; `tracing_backend_rtt.c` **does not exist** |
| `subsys/tracing/tracing_backend_uart.c` | output iterates `uart_poll_out` for every byte; cannot assert nonblocking |
| `subsys/tracing/tracing_backend_ram.c` | `ram_tracing[]`, `pos`, `buffer_full`; stops on capacity, does not wrap |
| `subsys/tracing/ctf/ctf_top.h` and bundled `zephyr_ctf_binary.py` | packed timestamp/ID records; **no CTF packet framing**. A corrupt/unframed stream cannot be safely resynchronised by guessing a plausible event ID |

Relevant upstream source:
[tracing Kconfig](https://github.com/zephyrproject-rtos/zephyr/blob/684c9e8f32e4373a21098559f748f06915f950c9/subsys/tracing/Kconfig),
[SEGGER Kconfig](https://github.com/zephyrproject-rtos/zephyr/blob/684c9e8f32e4373a21098559f748f06915f950c9/modules/segger/Kconfig),
[UART backend](https://github.com/zephyrproject-rtos/zephyr/blob/684c9e8f32e4373a21098559f748f06915f950c9/subsys/tracing/tracing_backend_uart.c),
[RAM backend](https://github.com/zephyrproject-rtos/zephyr/blob/684c9e8f32e4373a21098559f748f06915f950c9/subsys/tracing/tracing_backend_ram.c).

**Plan deviation:** Do not manufacture `CONFIG_TRACING_BACKEND_RTT=y`,
assume RTT channel index 1, treat `SEGGER_SYSTEMVIEW` as CTF, or enable
the menu based on `CONFIG_TRACING_CTF=y` alone. An upstream-compatible,
bounded, nonblocking, *firmware-side* binary transport with an independently
verified channel, framing, epoch/sequence/checksum, loss receipts and target
build identity must be designed and tested before the host live pipeline.

### Transport × vendor readiness (design only)

| SoC families | Candidate route, not a support claim | Evidence status |
| --- | --- | --- |
| nRF52 / nRF91 | Probe-specific RTT subject to CTF firmware adapter; UART only after bounded drain | PENDING |
| nRF53 application core | Single-core application tracing only; no claimed network-core cross-clock stream | PENDING |
| STM32 | Probe-specific RTT candidate; UART/RAM as explicit alternatives | PENDING |
| TI MSPM0 | TI OpenOCD/XDS110 ownership and running-memory reads unverified; saved RAM remains separate | PENDING |
| ESP32 / S2 / S3 / C2 / C3 / C6 / H2 / P4 | Espressif OpenOCD fork required by registry; USB-Serial-JTAG availability must be proven per board | PENDING |
| RP2040 / RP2350 | Probe and core ownership need verification; no assumed RTT control-block RAM range | PENDING |
| NXP LPC / i.MX RT | Probe/SoC memory-map and channel evidence required | PENDING |
| Microchip SAMD / SAME | Probe/SoC memory-map and channel evidence required | PENDING |
| Renesas RA6 | Probe/SoC memory-map and channel evidence required | PENDING |
| Silicon Labs EFR32 | Probe/SoC memory-map and channel evidence required | PENDING |
| WCH and niche RISC-V | Tier-3 study only; UART/RAM must first satisfy data integrity and CPU-safety rules | PENDING |

No `rttRamBase`, `rttRamSize`, OpenOCD target cfg or `tracingChannel`
can be seeded as a real value without generated Devicetree, selected runner
and real firmware evidence. Do not silently use a default or autodiscovery
guess. Hardware acceptance: **nrf52840dk — PENDING; STM32 Nucleo — PENDING;
LP-MSPM0G3507 — PENDING; ESP32-S3 — PENDING; RP2040 — PENDING.**

### Remaining exit gates, in order

1. Complete **ELF symbol/CTF metadata/build receipt identity** for the exact
   selected build and prove the running target image independently. Verify
   `_SEGGER_RTT` only for a genuinely compiled RTT backend; names alone are
   not runtime evidence.
2. Implement a versioned, nonblocking target-side transport with dedicated
   binary channel, bounded drain, integrity framing, sequence/epoch and
   explicit drop counters. UART text and CTF are separate endpoints.
3. Validate the host ingestion/streaming semantic append *against the same
   saved trace*, arbitrary boundaries, corruption and lost frames. Preserve
   the raw bytes and checkpoint; never pretend resynchronisation without a
   verified frame boundary.
4. Attach via shared probe/port/physical-operation ownership; no extra GDB
   client, reset, automatic halt or running-target RAM scan. Rate-limit and
   bound all host consumption; seal only independently valid offline CTF.
5. Gate the actual UI on all receipts, exercise fake transport in CI and
   qualify each hardware row with attached reproducible captures before
   removing the existing disabled menu label.

## 10. Hardware-free framed transport regression harness

**Status: host-only test infrastructure. Live CTF remains NOT SUPPORTED.**
The new ctf_framing.py, live_trace.py, scripts/fake_rtt_server.py and
tests/tools/test_live_trace_framed.py exercise a candidate wire contract
through 127.0.0.1 only. The test collector requires explicit test_only=True;
it never opens OpenOCD, GDB, a physical probe or a production socket.
Artifacts are transport.bin, committed_ctf.bin, events.jsonl and receipt.json.
These are deliberately not validated offline ctf/channel0_0 sessions.

This is a **candidate test protocol**, not a claim that pinned Zephyr 4.4.0
produces it. Each frame has a fixed 116-byte little-endian header:

| Field | Width | Requirement |
| --- | --- | --- |
| Magic/version/type/reserved | 4+1+1+2 bytes | ZEWF, v1, HELLO=1 / DATA=2 / END=3, reserved zero |
| Payload length | uint32 | 0..4096 bytes; only DATA has nonempty payload |
| Sequence | uint64 | HELLO=0; all frames strictly contiguous |
| Expected boot/session epoch | 16 bytes | Nonzero exact match; not firmware attestation |
| ELF SHA-256 / metadata SHA-256 | 32+32 bytes | Exact host-expected hashes, not proof of flashed image |
| Core/stream | uint16 + uint16 | Zero only; multi-core mapping not supported |
| Cumulative firmware loss count | uint64 | Any reported positive count invalidates interval |
| CRC32 | uint32 | Checksum over header without CRC plus payload; not authentication |

Arbitrary TCP chunk splits are accepted; only verified complete DATA enters
the existing shared packed-CTF decoder. Unknown event IDs, CRC corruption,
metadata drift, sequence gaps/duplicates, loss, wrong core/stream, truncated
tails, oversized files and missing END invalidate the epoch without unsafe
resynchronisation. Received raw bytes are preserved. Even a successful
TEST_ONLY_COMPLETE receipt explicitly sets hardware_capture=false,
flashed_firmware_identity_verified=false, safe_for_offline_analysis=false
and live_stream_approved=false. The live UI stub is unchanged.

**Production gates still missing:** firmware-side nonblocking CTF RTT
backend/drain, exact channel and RAM evidence, physical image identity,
shared probe ownership, trusted source-loss counters, halted/running state,
live semantic/UI append, validated Save/Trace Compass and physical captures.
All vendor hardware acceptance entries remain PENDING.

## 11. Pinned Zephyr 4.4 opt-in firmware prototype (source only)

**Not production support or a hardware qualification.** The isolated source
in [`firmware/trace/zephyr44/`](../firmware/trace/zephyr44/README.md)
adds a portable ZEWF v1 frame encoder and proposed Zephyr tracing backend.
The explicit checked installer patches only pristine Zephyr commit
`684c9e8f32e4373a21098559f748f06915f950c9`; default mode only
checks exact source anchors, and `--apply` must be requested deliberately.
It augments the backend Kconfig choice, CMake source list, and the existing
Zephyr core atomic tracing drop counter getter. It changes no existing
Workbench startup or application and does not patch managed workspaces.

The proposed runtime relies on `CONFIG_TRACING_ASYNC=y` so source hooks
use the bounded async ring instead of doing RTT writes. The tracing worker
allocates one named binary RTT up buffer using NO_BLOCK_SKIP, refuses
anything other than channel index 1, sends HELLO plus bounded DATA frames,
checks Zephyr ring source drops and permanently latches nonblocking RTT
write failure. No sleeping or retrying takes place in ISR callbacks.

**Important integrity limits:** the firmware emits a supplied prebuild
image contract digest, not the SHA256 of the finished ELF; building the
complete ELF digest into the same ELF is self-referential. Host-side
independent memory/flash readback is still mandatory. The boot epoch
candidate is generated using sys_rand_get, not yet independently qualified
for uniqueness/entropy on any target. At the time of the original Phase 3 prototype, the backend did not emit
END. **Phase 4 supersedes this limitation** with an opt-in stop barrier,
worker drain and conditional END. This still does not prove real hardware. RTT channel/probe support, low
overhead, resets, source loss and all hardware rows remain PENDING.

Native CI must validate portable C frame bytes against the Python verifier.
An actual pinned Zephyr build, real RTT capture and coordinated end/drain
test are separate gates. Do not enable Start Live Trace from this source.

## 12. Phase 4/5: MSPM0-first lifecycle and real build qualification

See [MSPM0 and cross-vendor detailed design](TRACE_PHASE4_5_MSPM0.md).
RTT is NOT mandatory: `CONFIG_TRACING_BACKEND_ZEW_RAM` uses the same
portable framing code on any supported single-core Zephyr target with
enough RAM, and is first built for the user's TI MSPM0G3507. The
`CONFIG_TRACING_BACKEND_ZEW_RTT` backend remains separately opt-in only
where a dedicated SEGGER RTT channel and probe actually exist.

Phase 4 adds a Zephyr async producer stop recheck **inside** its IRQ lock;
`zew_trace_stop_request()` disables tracing and wakes the existing
tracing worker, which drains all buffered CTF and attempts one final END.
A partial/nonblocking write, nonzero source loss or RAM capacity overflow
still fails closed. A clean END is framing integrity, **not** independent
target attestation. The RAM capture is read only with a halted target. The demo/CI opts in to
Zephyr's TEST_RANDOM_GENERATOR + TIMER_RANDOM_GENERATOR for unsupported
entropy devices such as MSPM0; these epochs are explicitly untrusted
and cannot certify physical capture identity.

Phase 5 adds a disposable, exact-version pinned Zephyr source patch and
real Windows CI builds for MSPM0G3507, STM32F446, ESP32-C3 and
ESP32-S3, each
checking generated .config, ELF/map CTF and backend symbols, metadata
hashes and a CURRENT source/config receipt. Hardware tests are not
implied by a compiler PASS. The existing OpenOCD runtime, live menu,
running-target memory reads and vendor physical qualification are
unchanged and all remain PENDING.
