<!-- SPDX-License-Identifier: Apache-2.0 -->
# Agent instructions — Zephyr Eclipse Workbench

The authoritative development scope is [Evidence Engine Program v2](docs/EVIDENCE_ENGINE_PROGRAM_V2.md), uploaded by the product owner on 2026-10-30. **It supersedes** the previous sixteen-area commercial release prompt; preserve existing working Eclipse behaviour but do not expand redundant IDE studios in this program.

Read that specification completely, then read [RELEASE_STATUS.md](RELEASE_STATUS.md), [Phase 0 competitive gap](docs/COMPETITIVE_GAP.md), [market sources](docs/MARKET_EVIDENCE.md), [licence audit](docs/LICENSE_AUDIT.md) and architecture ADRs. Do not infer a completed gate from documentation or passing compilation.

**Baseline:** Eclipse/p2 and old workspace remain; new zew.py and zew_evidence/ are a deliberately limited Phase-0, read-only implementation. No Kconfig dependency or driver inclusion causality is claimed. Provenance hashes identify artifact bytes, not a flashed device.

**Method:** isolate each milestone in a reviewed PR; verify CI at its exact head; use original upstream semantics (west, kconfiglib, edtlib, SPDX) instead of imitating them; label discovered/configured/built/flashed/debugged/analysed separately. Do not publish, merge unapproved changes, rewrite users' sources, infer hardware measurements or fetch network resources without explicit user action.

**G0:** Not yet passed; competitor hands-on exercises, complete licence audit and all required ADRs are outstanding. A Phase-0 collector prototype is permitted as a feasibility test, but do not claim release train 1 started or approved until the gate is assessed.
