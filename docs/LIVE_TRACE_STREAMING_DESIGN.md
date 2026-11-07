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
