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

## Phase-0 source/module relationship explanation increment

- Added `zew why --source NAME` and `zew why --module NAME` modes. Existing
  `zew why CONFIG_...` behaviour remains intentionally observational.
- Matches an exact `software_File` or `software_Package` element across
  upstream SPDX 3 JSON-LD documents and seeks only direct Build-scoped
  `hasInput` relationships from known Build elements.
- Records the Evidence Graph `artifact:*:spdx_3_build` node and byte hash;
  rechecks the SPDX build document before using its relationships.
- Reports ambiguous identifiers and missing relationships as UNKNOWN.
  Does not infer compiled source, linked ELF presence, device state or CVE impact.
- Positive and negative fixtures cover stale hashes, privacy, malformed SPDX,
  conflicting identities, scope, ambiguities and CLI backwards compatibility.
- **G0 still OPEN**; exact-head CI and real Zephyr v4.5 build-profile tests
  are still prerequisites for progressing into the release trains.


## Phase-0 exact compiler command observation (development PR)

- Added `zew compile` with explicit `--source-root` and optional
  `zew why --source --source-root` enrichment.
- Source identity is an exact local path under the caller-selected source
  root, matched against bounded `compile_commands.json` records.
- All observed command counts are scoped per declared build image and
  anchored to the existing Evidence Graph compiler-database digest.
- No command text, source path, flags, environment or credentials is exported.
  Invalid/foreign-host paths and absence remain UNKNOWN; no execution,
  linker, final ELF or flashed-target assertion is made.
- Tests cover tampering, adversarial JSON, existing/missing database, path
  matching, privacy and multi-image isolation. G0 remains OPEN.


## Phase-0 source/object/GNU linker-map observations (draft PR)

- `zew link --source ... --source-root ... --build ...` reads an explicit
  CMake compiler output field and observes its local object hash.
- Reads a bounded GNU-style link map; distinguishes LOAD from nonzero
  assigned input sections and discards. All compiler/map evidence is
  anchored to the same per-image Evidence Graph artifact SHA-256.
- Optional `zew why --source --link-evidence` enriches upstream SPDX and
  compile-command reports without altering `CONFIG_...` behaviour.
- Does NOT claim link completion, reliable ELF contents, source freshness,
  archive member contribution or actual flashing; foreign/ambiguous
  paths and unsupported formats stay UNKNOWN.
- Negative and cross-image fixtures added. G0 remains OPEN; G1/G2
  release acceptance is not implied by synthetic fixtures.

## Phase-0 GNU archive and ELF range auditor (development PR)

- Read-only `zew link-audit` and opt-in `--range-audit` on existing
  `zew link` and `zew why --source --link-evidence` workflows.
- Independent ELF32/64 little/big-endian PT_LOAD VMA bounds are compared
  with nonzero GNU ld map input-section textual ranges; count file-backed,
  zero-fill, mixed, uncovered and ambiguous intervals separately.
- Exact regular GNU ar member names are inspected and SHA-256 hashed in
  bounded streams without extraction. Thin, duplicate, missing and external
  archive members remain unverified.
- Map/ELF provenance is anchored to existing graph hashes; read-only CLI
  omits raw local paths, section/member names and symbol data.
- Does NOT prove source-to-final ELF inclusion, binary freshness, LMA/physical
  mapping, archive extraction into linker output, target programming or
  production qualification. G0 remains OPEN.

## Phase-0 ELF output-section audit (new development PR)

- `zew section-audit` and opt-in `zew link-audit --section-evidence`
  correlate exact GNU ld output-section header metadata with ELF32/64
  SHF_ALLOC section names, VMAs and byte lengths.
- SHA-256 observations of bounded file-backed ELF section bytes are
  attached to Evidence Graph map/ELF artifact digests, independently per
  declared firmware image.
- NOBITS/zero-fill, duplicate labels, missing/unsupported section headers,
  mismatched metadata, damaged files and stale graph capture are
  differentiated; raw host paths, section labels and compiler commands
  are not exported.
- Metadata agreement or byte hash is NOT source-to-output causality,
  a comparison against object bytes, a live target check, or proof of
  firmware flashed. G0 remains OPEN.

## Phase-0 real artifact acceptance matrix (development PR)

- Existing pinned Windows `west build` matrix now invokes a repeatable,
  read-only Evidence Engine probe after each **real** TI MSPM0, STM32,
  Nordic, Espressif, ADC and MCUboot scenario.
- Records per-target compiler database, GNU linker-map and ELF section
  evidence statuses, reconciles SHA-256 digests with the Evidence Graph,
  checks deterministic results and verifies generated files were unchanged.
- Linux development CI compiles/links actual host GNU ld and LLVM lld
  executables and archives, tests parser/CLI behaviour, and refuses
  silent LLVM test omissions. These are **host** format tests, not Zephyr.
- This matrix does not verify source-to-final-ELF causality, semantic
  correctness of unsupported vendor maps, embedded LLVM Zephyr
  compatibility or any physical hardware. G0 remains OPEN.

## Phase-0 seven-vendor evidence qualification gate (development PR #72)

- PR #71 exact-head CI (Linux and Windows) is **completed SUCCESS**; it
  built seven real Zephyr scenarios. The evidence reports **did not**
  show complete source-to-ELF coverage: each target had some supported
  ELF layout observations and unmatched/non-loadable GNU map rows.
- Provenance-grounded, redacted measurements preserved in
  `docs/evidence/real_firmware_baseline_20261030.json`; this is a
  historical observation baseline, not hardcoded section-count thresholds.
- New `scripts/qualify_firmware_matrix.py` requires exact seven-scenario
  target identity, build verification, valid SHA-256, exact source command,
  real map and ELF section/byte observations, consistent counts and explicit
  unknown coverage. It fails on unsupported/missing observations and
  genuine ELF layout contradictions, **without** assuming that unallocated
  map records are actual firmware faults.
- PR CI now has an aggregate **Evidence qualification gate** job depending
  on both installed Eclipse smoke and all seven Windows firmware builds,
  including the qualification step. The workflow runs on every PR.
- **Repository branch protection remains unverified** (connector returned
  403). A maintainer must require the aggregate status in GitHub rulesets.
  A CI job itself cannot stop merging when repository rules permit bypass.
- No new G0 approval, G1 promotion, production claim, hardware measurement,
  actual flashing or fully proven source-to-binary causality.

## GNU ld map-context evidence (development PR, Phase 0)

- New read-only `zew map-context` uses same-image Evidence Graph map/ELF
  hashes and strict map output/input parent grammar to distinguish
  allocated/nonallocated ELF metadata and unresolved contexts.
- `inspect_sections(..., include_unallocated=True)` is opt-in and does
  **not** hash nonallocated section bytes; existing SHF_ALLOC-only output
  analysis remains unchanged.
- Seven real Windows firmware build reports now capture diagnostic context
  counts without relabelling baseline uncovered sections as valid or
  attributing input objects to final ELF bytes.
- GNU/LLVM host linker smoke now cross-checks ELF .text metadata against
  independent GNU `readelf`. No physical hardware, embedded LLVM, source
  causality or G0/G1 gate claim.


## Phase-0 optional config request-check feasibility increment (review PR)

- Added headless `zew config-check --build ... --snippet ...` against the
  actual collected `.config` per explicitly selected build image.
- Detects exact boolean/tristate request-versus-generated differences,
  conflicting duplicate assignments, absent observations and malformed lines.
  Input line numbers and hashed artifact identity are retained; raw string
  values, local file paths and snippet contents are not emitted.
- Includes bounded UTF-8 input, symlink/size guards, deterministic JSON,
  isolated CLI exit statuses and test cases including the packaged zipapp.
- Only observations are compared: no symbol existence, Kconfig dependency,
  devicetree validation, source freshness, runtime or flashed-device claim.
- **No G0/G1 promotion**. Exact-head Linux/Windows CI and review are needed
  before merge; no release or installation-channel publication is requested.
- See [configuration request checker](docs/CONFIG_REQUEST_CHECK.md).

## Phase-0 ELF nonallocated metadata reconciliation (development PR)

- Completed PR #76 CI (Linux package, installed Eclipse, seven actual
  Windows Zephyr firmware targets and qualification) is **SUCCESS**.
- Independent PR #76 map-context observations showed all 66 historically
  missing-or-unallocated map output labels exactly matched **nonallocated**
  ELF section metadata; evidence saved in
  `docs/evidence/nonallocated_elf_observations_20261030.json`.
- The read-only section auditor now separates exact `SHF_ALLOC` output
  layouts and exact *nonallocated metadata* matches. Existing legacy
  counters remain; new `nonallocated-exact-metadata` and
  `unexplained-map-outputs` counts disaggregate missing/unallocated.
- The seven-vendor qualification report enforces additive accounting,
  keeps original uncovered `PT_LOAD` input rows as unknown, and
  rejects claims beyond supported metadata.
- This is not source-to-binary proof, actual flash evidence, firmware
  fault diagnosis, G0 completion or release-train approval.


## Optional selected-build Kconfiglib semantic check (draft PR)

- A new `zew config-check --semantic` mode evaluates requested symbol
  existence, Kconfig type, current assignability, visibility and dependency
  values against Zephyr's actual Kconfiglib using the last configured Ninja
  environment. The prior snapshot-only CLI remains unchanged.
- Undefined symbols are labelled only when the selected Kconfig tree is
  successfully loaded. Missing/stale build evidence, absent Ninja target
  or missing Kconfiglib yields explicit unknown, not invented causes.
- No project writes, generated guiconfig execution, raw string or path
  exports. Includes mocked unit/hostile-input tests and a **real MSPM0**
  Kconfiglib build-matrix verification, subject to exact-head CI passing.
- No automatic source fix, Devicetree binding evaluation, finished Truth
  Check, physical hardware confirmation or learning-time percentage is claimed.
- This is still Phase-0 feasibility; **G0 remains OPEN**. Do not merge or
  publish until approved and automated Windows + Linux gates pass.


## Native debug metadata preflight repair (review branch)

- Flash metadata validation and native Debug select the runner-declared ELF,
  avoiding unordered selection among kernel/signing ELF byproducts.
- Native Debug waits for the runner's halted target before comparing static
  thread metadata. Halt failure is distinct from an observed byte mismatch.
- A mismatch triggers one diagnostic repeat of the bounded static regions;
  stable versus inconsistent reads are labelled, and neither bypasses the
  launch guard. Initial table evidence is retained for probe investigation.
- Regression includes the reported `0x4ea8`, expected `4`, observed `15` case.
  Physical-board root cause and acceptance remain unverified. No release,
  G0/G1 promotion, automatic flash or decoder metadata patch is included.
- Local validation: `scripts/test_core.py` passed on Linux with OpenJDK 21:
  287 Python tool tests and the full Java core suite, including 29 metadata
  assertions and exact runner ELF selection regressions. Native Eclipse/p2
  packaging, Windows CI and physical-board acceptance have not run for this
  branch. Publication is pending authorization after automatic review blocked
  the remote push check.


## Verified Flash-to-Debug handoff and GPIO visibility repair (review PR)

- Adds explicit **Zephyr > Flash and Debug Application**: one build, existing
  programming confirmation/runner, successful Flash exit, owned-process cleanup
  and lease release, then native Debug with no second build. Cancellation and
  failed programming block the handoff. Separate saved configurations preserve
  plain Flash and plain Debug behaviour.
- Records the exact build/board/profile and ELF, runner and generated-config
  hashes; native Debug rechecks this identity on its worker before starting the
  server and immediately before CDT attaches. A queued or concurrent rebuild
  cannot silently replace the selected verified ELF.
- Repairs GPIO consumers using symbolic generated-DTS references, controller
  labels, conditional direct configure calls and common `_dt` source patterns.
  Saved-source candidates remain explicitly distinct from built DTS consumers,
  compilation inclusion and actual hardware execution.
- Enlarges the logical-index planner and improves selection/empty-state text.
  Adds a copyable **GPIO Example C** panel using exact controller labels with
  readiness and error checks. No physical package pinout is fabricated.
- Adds `examples/gpio_usage_demo`, blinking the board's `led0` alias with an
  optional PA15 output disabled by default. The existing real Windows MSPM0
  firmware matrix now compiles this example instead of an empty Hello World.
- Local OpenJDK21/Linux `scripts/test_core.py` passed, including the Python tool
  suite, full Java suite, 19 Flash/Debug identity checks and 19 new GPIO checks.
  Added installed-Eclipse configuration/lease-handoff smoke checks; CI must
  validate packaging, UI integration and the real example compilation.
- User reported that Flash followed by Debug resolved the observed MSPM0
  four-byte-shift mismatch. Additional combined-flow and GPIO hardware
  acceptance remain outstanding. No G0/G1 promotion or release publication.


## Devicetree Truth Check — read-only binding preview (draft PR)

- Added an opt-in `zew dt-check` CLI that interprets **actual generated DTS**
  through the selected Zephyr checkout's `edtlib` and explicit binding roots,
  with an optional temporary plain-overlay preview.
- Refuses mismatched checkout/build identities and reports unmatched bindings,
  missing nodes/properties and edtlib failures as incomplete; no invented
  pin assignments or readiness claims.
- Uses snapshot hashes, bounded inputs and explicit multi-image selection;
  intentionally does not load Python pickle artifacts or export raw DTS,
  values, paths or arbitrary parser errors.
- Unit fixtures and a native Windows MSPM0 real-generated-DTS smoke are
  included. Exact-head Linux/Windows CI and review remain prerequisites.
- No claim of complete overlay validation, required electrical checks,
  physical evidence, IDE feature release or learning-percentage results.
  **G0 remains OPEN**.


## Real Zephyr Devicetree configure validator (next Phase-0 feasibility job)

- `zew dt-configure` runs the trusted selected Zephyr `west build
  --cmake-only` in a disposable, bounded application snapshot and scratch
  build. Explicit board identity, original CMake cache and selected Zephyr
  checkout must match before a process can start.
- Optional in-project candidate overlay is passed through actual Zephyr
  `EXTRA_DTC_OVERLAY_FILE` with the ordinary overlays preserved. Preprocessor,
  module and binding resolution are exercised by real CMake, not simulated
  DTS text concatenation.
- Output requires the scratch CMake cache and real generated DTS to match the
  selected source identity before reporting `configured-in-scratch`. No
  copied source, selected build or physical target is overwritten, and no
  raw logs or secrets are emitted. CMake custom hooks may run external code;
  never describe this command as a security sandbox.
- Negative unit tests and actual MSPM0 Windows firmware CI probe were added;
  verify the exact PR head and review before merge.
- Original build equivalence, complete compilation, all module/board
  variations, pin/electrical correctness, and physical board validation remain
  **unverified**. No claim of release readiness. **G0 remains OPEN.**
