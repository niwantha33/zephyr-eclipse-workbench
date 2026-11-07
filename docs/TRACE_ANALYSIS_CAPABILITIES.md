<!-- SPDX-License-Identifier: Apache-2.0 -->
# Zephyr trace-analysis capabilities

> This table is the single source of truth. Do not describe a feature as supported in any other document unless it appears here with status Implemented.

Scope: offline CTF analysis plus its immediate capture, investigation, console and handoff helpers in `plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis/`. **Implemented** means a functioning code path exists, not that it is hardware-qualified; **Partial** means an evidence-limited code path exists; **Not implemented** means there is no working capability. **No** means this change did not personally test that feature on a physical device. Historical claims, synthetic tests and installed-Eclipse smoke do not turn No into Yes. The memory profiler was merged to main in PR #196; its existence does not imply hardware validation.

| Feature | Status (Implemented / Partial / Not implemented) | Data source | Known limitations | Validated against real hardware (Yes / No) |
|---|---|---|---|---|
| CTF semantic normalization | Partial | Saved CTF via bt2, or decoder-normalized records; `semantic_trace.py` | Explicit version-dependent name mappings only; unknown events retained; partial decoder prefix; no incremental live append | No |
| Offline CTF summary | Partial | `analyze_trace.py` over decoded records | Paired single-stream scheduling intervals only; partial/lossy input withholds aggregate timing; bt2 needed for raw CTF | No |
| Thread timeline | Partial | Scheduler, state and ISR semantic events; `thread_timeline.py` | Only evidence-bounded intervals; unpaired switches remain unknown; no independently verified clock | No |
| CPU load | Partial | Single-stream switches, nested ISR, explicit idle ID; `cpu_analysis.py` | Busy percent withheld for gaps, unknown idle, losses, multi-stream or ambiguous events; no WCET | No |
| Tracepoint plot | Partial | Declared project tracepoints plus CTF `named_event`; `tracepoint_config.py`, `tracepoint_samples.py` | Timestamp/value table with statistics, not a drawn curve; values require declared types; no missing-sample inference | No |
| Contention report | Partial | Mutex/semaphore phases and numeric thread priorities; `contention_detector.py`, `contention_report.py` | Only explicitly observed long waits and inversion patterns; no finding does not imply no contention or missed deadline | No |
| Memory profile | Partial | Heap/slab CTF outcomes; `memory_profiler.py`, `memory_profile_report.py` | Stock CTF lacks sizes; byte totals/peaks often unavailable; imbalance is not a leak; loss prevents a final verdict | No |
| Timing analysis | Partial | Semantic scheduler and ISR transitions; `timing_analysis.py` | Observed maxima and deadline comparisons only; not WCET or calibrated real-world timing | No |
| Thread event log | Partial | Decoder event names and observed payloads; `thread_event_log.py` | Bounded list; unknown states/source locations remain unknown; no missing-event inference | No |
| Offline investigation report | Partial | Saved capture, trace summary and receipts; `investigation.py` | Requires valid saved artifacts; provenance does not prove flashed image or complete capture | No |
| Offline core-dump assessment | Partial | Saved dump and ELF; `coredump_offline.py` | Architecture, ELF identity, available registers and unwinder coverage vary; not live crash capture | No |
| RAM CTF snapshot capture | Partial | RAM via GDB/OpenOCD plus matching CTF metadata; `capture_snapshot.py` | Resets/halts; buffer loss and fullness not generally established; no independent firmware/ELF identity | No |
| Trace Compass handoff | Partial | Verified saved capture and external viewer; `trace_compass_bridge.py`, timeline XML | Launch is not decode proof; XML import manual; no bundled live viewer | No |
| Debug serial console | Partial | Selected serial text stream; `serial_console.py` | No CTF framing, clock synchronization, general RTT/ITM or demonstrated hardware support in this change | No |
| Live CTF streaming | Not implemented | None; potential RTT/UART/network transport | No active incremental reader, decoder, loss policy or live view; design-only work is not support | No |

## Verification boundaries

The [regression fixtures](../tests/fixtures/ctf_regression/README.md) are **synthetic decoder-record replays**, not native raw CTF files, and **not physical captures**. The regression suite validates only Python offline analysis, not Babeltrace import, installed Eclipse presentation, stream transport, device capture or hardware timestamps. A Python module-inventory guard fails when a new helper lacks a table mapping. Document claims elsewhere must be subordinate to this table.

Related implementation guides: [RTOS roadmap](RTOS_ANALYSIS_ROADMAP.md), [contention](CONTENTION_REPORT.md), [memory profile](MEMORY_PROFILE.md), [user tracepoints](USER_TRACEPOINTS.md), [Trace Compass](TRACE_COMPASS_INTEGRATION.md).
