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

## Focused implementation PRs and acceptance status

These are independent development slices. **PR #186 was merged on 2026-11-06**
after its checks passed. All other PRs below remain drafts as of this update;
passing software CI does not establish a physical target feature.

| Task | PR | Implementation scope | Still unverified |
| --- | --- | --- | --- |
| Automated SVD | [#187](https://github.com/niwantha33/zephyr-eclipse-workbench/pull/187) | Generated board/SoC cross-check and exact installed SVD/PDSC verifier | Vendor packs and installed native Eclipse UI on hardware |
| Live Register Watch | [#188](https://github.com/niwantha33/zephyr-eclipse-workbench/pull/188) | Explicit 1–8 watch opt-in, halted-only DSF refresh, 16-snapshot chart | Hardware probe load, stepping stress, installed UI |
| RTT and UART | [#189](https://github.com/niwantha33/zephyr-eclipse-workbench/pull/189) | Existing UART with project defaults; passive localhost RTT TCP receiver | RTT server setup/control block, probe ownership, target logging |
| Thread state events | [#190](https://github.com/niwantha33/zephyr-eclipse-workbench/pull/190) | Bounded offline decoded CTF table of direct thread/ISR observations | Real-version CTF hook mapping, loss/clock integrity, Gantt |
| OpenOCD diagnostics | [#186](https://github.com/niwantha33/zephyr-eclipse-workbench/pull/186) | Opt-in Tcl transcript with exact ELF symbol/address and mismatch context | Probe/flash-readback classification on named hardware |

**Qualification rule:** CI must pass at the PR's latest head; real hardware and
installed Eclipse UI are separate gates. Never describe an SVD match as verified
physical silicon, sampled register history as hardware trace, a TCP RTT
connection as probe verification, or decoded event names as complete
ready/wait/sleep timelines. This roadmap does not close Evidence Engine G0.
