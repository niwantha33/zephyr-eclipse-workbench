<!-- SPDX-License-Identifier: Apache-2.0 -->
# Debug and analysis roadmap — 2026-11-06

G0 remains OPEN per `RELEASE_STATUS.md`. These are development slices, not
release or physical hardware qualifications. Incomplete trace and sampling
are never presented as continuous CPU load or proven target timing.

| Slice | Existing implementation | New work / verification needed |
| --- | --- | --- |
| Automated SVD | Exact installed SVD/DFP discovery, fail-closed parser | Board/SoC catalogue metadata and multi-vendor acceptance |
| Live registers | Manual read-only snapshots, bounded 16-sample CSV history | Opt-in scheduling, chart, stepping safety, physical tests |
| RTT/UART | UART capture and Trace Console build-intent evidence | Safe explicit RTT receiver, port ownership and target tests |
| Thread state | Offline CTF capture and Trace Compass handoff | Timestamped event table, unknown/loss markers and ISR correlation |
| Preflight | Exact ELF metadata verification over owned OpenOCD Tcl | Opt-in byte-accurate diagnostic transcript and contextual 0xFF guidance (focused PR, exact-head CI pending) |

Rules: do not silently flash, reset, write registers, infer chip identity from
an architecture, or claim timing and CPU percentages without adequate evidence.
Each independent slice needs tests, documentation, exact-head CI and physical
validation before a production label is appropriate.
