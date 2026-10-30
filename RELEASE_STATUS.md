<!-- SPDX-License-Identifier: Apache-2.0 -->
# Evidence Engine release status — 2026-10-30

**Authoritative scope:** [Evidence Engine Program v2](docs/EVIDENCE_ENGINE_PROGRAM_V2.md). Earlier [16-area acceptance](docs/COMMERCIAL_RELEASE_ACCEPTANCE.md) is historical, not the current release plan.
**Gate G0:** OPEN. No evidence-engine installer, release, production or certification claim.

## Baseline
- Phase-0 starting main: e646073bd64da5601eb2258256db2cf7ca7cd265, no open PRs, AGENTS.md absent.
- Development CI [41041729242](https://github.com/niwantha33/zephyr-eclipse-workbench/actions/runs/41041729242) passed Linux p2/UI and Windows firmware-matrix tests.
- Main publisher [41040687808](https://github.com/niwantha33/zephyr-eclipse-workbench/actions/runs/41040687808) failed persisted CDT configuration assertion; newer [41043272886](https://github.com/niwantha33/zephyr-eclipse-workbench/actions/runs/41043272886) passed. An intermittent race is not conclusively closed.
- Public release v0.1.21-preview.1 (2026-10-28) is older than main. Zephyr 4.5 was announced 2026-10-28; managed matrix previously pinned Zephyr 4.4.

## Code and evidence classification
| Area | Source implementation | Verified | Open |
| --- | --- | --- | --- |
| Project, target, build and installers | ZephyrApplicationDiscovery, ProjectProfile, BuildArtifacts, p2 publisher | Linux Eclipse smoke, Windows firmware BUILT | Cross-version sysbuild, clean machine/physical |
| Flash/debug | TargetOperations, ZephyrNativeDebugDelegate, PhysicalOperationLock | Software process tests; historical selective board use | Repeated physical cross-runner coverage |
| Kconfig and Devicetree | KconfigInspector, ConfigurationStudioView, DevicetreeHardware/Parameters, ConfigurationDiagnosis | Fixture tests, installed UI smoke | Full origin/driver/pinctrl semantics |
| SVD and registers | SvdDevice and SocRegistersView | SVD fixtures and UI | Live side-effect/probe validation |
| Trace | ZephyrTraceHandler, capture_snapshot.py, analyze_trace.py | Synthetic fixtures and earlier MSPM0 capture | Complete scheduling clock and event-loss evidence |
| Fault investigation | FaultLogAnalysis, ArmElfSymbols, FaultAnalysisView | Synthetic Cortex-M logs and ELF parsing | Matching binary coredumps and Fault Zoo |
| Offline export | InvestigationBundle, EvidenceSanitizer | ZIP integrity and Java regressions | Full multi-image graph and privacy review |
| Evidence Engine CLI | zew.py, zew_evidence/collector.py (this PR) | Newly added unit tests, exact-head CI pending | Kconfiglib, edtlib, SPDX, why causality, graph v1 |
| VEX, upgrade, agent MCP | No production implementation | None | R2, R3, R4 not started |

## Phase-0 checklist
- [x] Read existing repository guidance, code, GitHub workflows, releases and prior PRs.
- [x] Preserve uploaded program, add authoritative agent instructions.
- [x] Research public competitor features and SPDX/CRA/survey sources.
- [x] Document provisional graph, rules, CLI/adapters and release-train ADRs.
- [x] Implement deterministic, read-only limited collector with unit fixtures.
- [ ] CI green on exact prototype head.
- [ ] Install and exercise all listed competitors on pinned Windows/Linux test hosts.
- [ ] Inventory every redistributed binary/tool/licence/SVD and sign-off.
- [ ] G0 gate assessment: evidence-based gap approval.

G0 OPEN; G1, G2, G3, G4 NOT STARTED. A phase-0 prototype is not G1 delivery.

## Prototype commands
    python zew.py graph --build /path/to/build
    python zew.py diagnose --build /path/to/build
    python zew.py why CONFIG_GPIO --build /path/to/build
    python zew.py graph --build /path/to/sysbuild --image app=app --image mcuboot=mcuboot

Outputs are deterministic JSON. Generated Kconfig assignment observations have no dependency reasoning; generated DTS and ELF are hashed but not semantically parsed. Hardware is never touched.

See [market evidence](docs/MARKET_EVIDENCE.md), [gap audit](docs/COMPETITIVE_GAP.md), [licence audit](docs/LICENSE_AUDIT.md), [CLI reference](docs/CLI_REFERENCE.md) and ADRs.


## Later Phase-0 feasibility increment (separate PR)

- Added deterministic `zew compare` between two build roots, with observed Kconfig
  changes, artifact SHA-256 differences, explicit redacted/unknown states.
- Added preview-first `zew bundle` and bounded `zew inspect` ZIP integrity
  verification without extraction or credential-bearing source files.
- Added regression cases for repeatable archive bytes, malicious entry names,
  duplicate entries, symlink entry modes, compressed entries, tampering, Unicode
  paths, exclusive output creation and privacy.
- All claims are about **offline observed data only**. Build differences never
  establish a cause, and ZIP hashes never establish publisher authenticity.
- **Validation and gate status:** new branch CI pending. G0 remains open;
  Phase-0 prototype work is not a passed G1 milestone.


## Phase-0 provenance verification increment

- Reconciled against main `95ef87e` after the prior collector and offline evidence
  PRs were merged; preserved earlier unfinished studio/peripheral work separately.
- Added `zew verify` for deterministic archived-artifact hash comparison, with
  explicit matching/missing/changed states and 0/1/2 exit codes.
- Hardened offline graph import: exact metadata fields, image path confinement,
  provenance cross-links, graph relationships, numeric-secret rejection and strict
  JSON object parsing. Consistent forged metadata is still unauthenticated.
- Added deterministic CLI packaging and hostile-input/freshness fixtures.
- This increment remains a permitted Phase-0 feasibility test. **G0 remains OPEN**;
  no R1–R4 gate, 30-rule pack, VEX accuracy or migration benchmark is claimed.

Required next evidence remains hands-on competitor scenarios, complete dependency
redistribution review and exact-head cross-host CI. These cannot be replaced by
passing these prototype tests.


## Phase-0 upstream SPDX build evidence adapter (this development PR)

- Introduced read-only `zew sbom` that consumes files already generated by
  `west spdx`; no cloned generator, subprocess, AI explanation or network access.
- Reports `CONFIG_BUILD_OUTPUT_META` prerequisite, per-image SPDX 2 vs 3
  document availability, SHA-256 provenance and SPDX 3 Build relationship
  counts. Unverified source/ELF identity and vulnerability applicability
  remain UNKNOWN.
- Added bounded, strict duplicate-key JSON-LD checks, positive and negative
  fixtures, symlink/oversized/truncated inputs, sysbuild and Unicode paths.
- Follows the upstream [Zephyr SPDX guidance](docs/UPSTREAM_SPDX_EVIDENCE.md)
  and [ADR-0006](docs/adr/0006-upstream-spdx-adapter.md).
- No real current Zephyr 4.5 SPDX build fixture or physical target test is
  claimed by these unit fixtures. G0 remains OPEN; R2 is not started.
