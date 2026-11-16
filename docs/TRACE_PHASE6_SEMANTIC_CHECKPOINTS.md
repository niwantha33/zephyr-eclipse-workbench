<!-- SPDX-License-Identifier: Apache-2.0 -->
# Trace Phase 6 — semantic checkpoints (host-only feasibility)

**Status:** test-only, bounded semantic preview; **NOT live CTF**, not a
hardware-validated trace, not a replacement for offline Trace Compass.
`LIVE_STREAMING_ENABLED=false` and Start Live Trace stay disabled.

## Implementation

- `ctf_incremental.IncrementalPackedCtfDecoder` continues to own complete
  Zephyr 4.4.0 packed event boundaries and 32-bit timestamp rollover.
- `semantic_checkpoint.SemanticEpochCheckpoint` owns one exact nonzero
  16-byte epoch and matching metadata SHA-256, one stream/core and a monotonic
  committed-byte checkpoint. It delegates raw-event normalization to the
  existing `SemanticTraceParser` rather than duplicating CTF name aliases.
- Append is bounded to 16,000 semantic events, 4,096 checkpoints, 4,096
  records per call and 8 MiB committed CTF bytes. Exact checkpoint replays
  are idempotent. Conflicting duplicates, time reversal, wire-offset gaps,
  wrong epochs/metadata and multistream records poison that epoch.
- `live_trace.capture_loopback_test` consumes the same complete records
  for JSONL and semantic preview. The receipt adds `semantic_preview`
  only; it still labels the capture `TEST_ONLY_COMPLETE` or `INCOMPLETE`,
  never `safe_for_offline_analysis` and never live-supported.
- A sealed preview requires an explicit successful synthetic framing END,
  complete record decode, and the test-only zero-loss frame gate. The
  reported `format_complete_test_only` is **not** flashed-image, probe,
  secure boot epoch or hardware loss attestation.
- A failure preserves the bounded observed prefix and reports incomplete.
  There is no implicit repair, reconnection, guessed CPU utilisation, UI
  refresh, socket ownership or RTT target interaction.

## Reproducible synthetic checks

From the repo root:

```powershell
python -m unittest discover -s tests/tools -p test_semantic_checkpoint.py -v
python -m unittest discover -s tests/tools -p test_live_trace_framed.py -v
python scripts/test_core.py
```

The Phase 6 tests compare semantic events and raw records to the *same
saved packed CTF trace* under 1-, 2-, 3-, 5-, 6-, 7-, 11-, 16-, 27-,
128- and 4096-byte split lengths; they also exercise exact retries,
conflicting retries, invalid epoch and metadata, missing END, source loss,
nonmonotonic timestamps, multistream records and bounded memory.

## Hardware/production gates still open

1. Physically validate the LP-MSPM0G3507/XDS110 first, including independent
   flashed-image identity and a complete halted RAM capture. Do not interpret
   a GitHub Actions cross-build as a hardware result.
2. Prove shared transport/debugger ownership and a safe running-target
   RTT or alternative capture on **each** supported probe/board. RAM
   inspection on halt is not continuous streaming.
3. Compare separately attested physical captures to offline saved CTF and
   demonstrate drop/reset handling, counter provenance and host backpressure.
4. Only then connect a qualified per-epoch append session to existing
   trace/RTOS analysis views, without inventing complete CPU or thread
   states. Installed Eclipse UI regression and exact-head CI must pass.

All vendor-specific hardware qualification remains PENDING; the common
Python host adapter is deliberately vendor-neutral. No Nordic board/module
is required for MSPM0 validation. Evidence Engine release gate G0 is OPEN.
