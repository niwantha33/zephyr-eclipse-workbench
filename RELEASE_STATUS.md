<!-- SPDX-License-Identifier: Apache-2.0 -->
# Evidence Engine release status — 2026-10-30

**Authoritative scope:** [Evidence Engine Program v2](docs/EVIDENCE_ENGINE_PROGRAM_V2.md). Earlier [16-area acceptance](docs/reports/COMMERCIAL_RELEASE_ACCEPTANCE.md) is historical, not the current release plan.
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


## Selected peripheral evidence — headless Phase-0 increment

- Added deterministic `zew peripheral` to correlate the *independent*
  generated selected-image EDT node/binding/property observation and a
  caller-supplied exact generated Kconfig symbol. There is deliberately
  **no** driver/DT causal or physical pin inferred relationship.
- Reuses actual selected Zephyr checkout edtlib and the existing
  read-only generated DTS/Kconfig collectors. The observations are
  independently SHA-256 scoped per selected build/sysbuild image.
  Redacted/missing properties, unavailable binding roots, missing nodes
  and undefined/unobserved Kconfig symbols produce explicit UNKNOWN
  or incomplete outcomes, not fabricated compatible pins.
- Includes fixed next-action codes, CLI 0/1/2 contract, multi-image and
  privacy regression fixtures, portable CLI smoke and real **seven-target**
  Windows managed firmware evidence checks.
- These are *configured-build snapshots*, not live hardware, pinmux/electrical
  validation, linked driver proof, or Kconfig-to-Devicetree causality.
  No new native Studio/editor or automatic overlay change.
- Manual competitor/hardware acceptance and complete licensing audit
  remain outstanding. **G0 is OPEN; no release authorised.**


## Phase-0 native peripheral evidence integration — PR #101

- Existing Configuration Studio Hardware / Insights gained one native
  **Peripheral evidence…** action; it uses an explicitly selected built
  node/property and optional manually entered `CONFIG_` symbol.
- The installed UI packages exact copies of the headless evidence modules
  (`collector.py`, `dt_binding_preview.py`,
  `peripheral_evidence.py` and `__init__.py`), with byte-parity
  regression to prevent separate Eclipse and CLI semantics.
- The native Java bridge validates a strict, bounded encoded protocol,
  hashes selection and generated DTS/Kconfig artifacts, rejects contradictory
  success reports, cancels background work and refuses stale responses.
- Real Windows board matrix checks the packaged native resource against
  the headless `zew peripheral` for each of seven vendor/board scenarios;
  read-only isolated-Python and Java negative tests cover the bridge.
- Evidence is generated-build configuration and independent Kconfig
  observation only, not inferred driver inclusion, pin usage, package
  capability, physical probing or runtime validation.
- Exact PR head CI and manual installed UI acceptance remain prerequisites
  to merge and release. **G0 OPEN; no commercial release approval.**


## Phase-0 binding-grounded guided peripheral source draft (development PR)

- Added headless `zew peripheral-guide` for explicit GPIO, ADC, SPI or I²C
  *review intent* grounded in the exact built generated DTS and selected
  upstream Zephyr edtlib binding. No new SWT pin editor or fake wizard.
- Conservative supported requests are source-only node status, existing
  binding-interpreted 32-bit unsigned integer properties and existing
  binding-interpreted string enum options. Exact type, const and enum checks
  and deprecated-property rejection are applied when known.
- Symbolic `&{/selected/node}` source proposal is **not** written; users
  must opt into `--show-diff` to see private saved overlay context. Default
  machine JSON exposes only fixed classifications and hashes.
- Structural/pinctrl/gpios, compatible, clocks, DMA, interrupts, raw
  phandles, source rewrites and electrical pins are excluded. Missing binding,
  unknown types or unsupported commands yield manual review, not guessed
  outputs. The requested peripheral kind is never classified as verified.
- Unit fixtures cover exact selected checkout and real-style upstream spec,
  negative property/enum/secret cases, source protection, explicit sysbuild
  image and deterministic/privacy behavior. Real seven-target Windows matrix
  exercises the guide's generated DTS provenance and no-guess contract.
- No build, flash, hardware, licence or G0 qualification claim. **G0 OPEN.**


## Native guided peripheral source review — draft PR #105

- Embedded the exact headless `zew peripheral-guide` evaluator into the
  installed SWT plugin and added a **Guided overlay draft…** action to existing
  Hardware, Insights and parameter controls, without creating a second editor.
- Explicit user input distinguishes enable/disable status, existing integer
  property and actual binding-defined string enum. Source preview is opt-in
  because an overlay diff can show user/private source context.
- A typed, bounded, hash-checked Java↔Python wire protocol returns the source
  diff and only the appendable new addition; **Copy proposed change** does
  not copy existing overlay contents. The action never writes a file.
- Added cancellation, stale-selection guards, private temp cleanup, missing
  binding/manual-review states and explicit next steps for separately running
  actual Zephyr configuration *after* saving source deliberately.
- Native bundled Python is byte-identical to the headless core. Unit tests
  verify exact source diff, no writes, wrong-node/enum fail-closed behaviour;
  Windows native matrix tests actual TI GPIO node interpreted by Zephyr
  `edtlib`, comparing guide and native output hashes.
- No claimed controller/pad availability, real firmware compilation/flash,
  electrical correctness or physical qualification. **G0 OPEN**. PR review,
  exact-head CI and manual UI smoke remain required before merge/release.


## Unsaved guided source preflight — real CMake milestone

- Added `zew dt-draft-configure`, a headless-first opt-in check of a
  user-selected, binding-approved **unsaved** status/int/enum source draft.
- The exact guided source addition is recovered from a review diff and
  SHA-256 checked against the headless guide. Original overlay hash,
  selected-build DTS, board/core and checkout identity are verified.
- The existing scratch West/CMake validator now stages additions only
  in the copied app overlay. The original project and generated build are
  never edited. Both prepared scratch source hash and new generated
  DTS SHA-256 are returned.
- Actual selected Zephyr `dtlib` reads the scratch DTS **value**, not
  merely property presence: `status`/string-enum `to_string` and
  int `to_num`. Wrong value rejects success, even if CMake succeeded.
- Negative fixtures cover wrong values, missing binding, stale overlay
  and no-source writes. The native Windows TI MSPM0 matrix additionally
  tests actual GPIO binding selection and actual West/CMake preflight
  without source changes; portable CLI packaging coverage is updated.
- Running CMake executes trusted project hooks with host privileges.
  This is *not* a security sandbox. No compilation, flash, runtime,
  physical-pad or release qualification claim. **G0 remains OPEN**.


## Native unsaved guided CMake preflight — development PR #108

- Added **Test unsaved draft with CMake…** inside existing Configuration
  Studio Changes dock after the binding-guided source review action.
- Native UI retains exact selected project/build/node/property/overlay,
  requested value, original DTS/overlay and source addition SHA-256.
  Any changed selection invalidates the draft and cancels preflight.
- Real trusted West/CMake runs in a cancellable background Eclipse Job,
  with explicit CMake trust/host permissions warning. The source addition
  is staged *only in the disposable application copy*.
- Packaged Python uses byte-identical headless collector, EDT guide and
  real CMake validation modules. A strict 13-field Base64 protocol includes
  only source hashes, generated-value evidence, bounded counts and fixed
  diagnostic reason codes; no raw log or copied source.
- Exact requested generated property value and selected checkout/board/
  overlay provenance are required, not simply CMake exit 0.
- Added Java negative protocol tests, isolated Python byte parity and
  no-source tests, plus real Windows TI MSPM0 headless/native evidence
  parity with actual Zephyr CMake for an unsaved GPIO source draft.
- No source is saved, no firmware is compiled/flashed, and no physical pin
  use/readiness is inferred. G0 remains OPEN; exact PR-head CI and manual
  installed-UI acceptance required before any merge or release.


## Preflight-gated reviewed guided overlay saving — development PR

- Existing native Changes dock now offers **Save verified draft with backup…**
  after the binding-guided draft and real unsaved scratch CMake preflight.
- Reuses existing `DevicetreeOverlayChanges` backed-up atomic writer.
  New `GuidedOverlaySave` requires exact generated DTS, original/proposed
  overlay and proposed-addition hashes, matching selected node/property,
  and an independently parsed successful **exact-value-observed** scratch
  preflight before any write.
- A second explicit modal confirmation shows the exact source addition and
  hashes. A new overlay is created atomically only if absent; an existing
  overlay gets a byte-preserving backup. Stale source/build, symlink/path
  escape, duplicate and wrong generated value cases fail closed.
- Saving a guided source **does not** configure normal build overlays,
  compile, flash or prove physical GPIO availability. The existing native
  **Build & validate** action is deliberately a separate user choice.
- Added real-filesystem Java race and backup tests plus installed Eclipse UI
  smoke verifying an inert, unapproved save button. No auto-merge/release;
  **G0 remains OPEN**.

## Project environment lock — development PR (2026-11-03)

- Added an explicit native-West `manifest --freeze` snapshot plus the Zephyr
  manifest-repository SHA, linked by a SHA-256 integrity check in the application's
  `.zew` metadata. Records exact module SHAs and the selected host tool/SDK
  identities without embedding local absolute setup paths.
- Added native Eclipse menu commands for snapshot, verify and preview of an
  already installed parallel setup. A new application's verified build attempts
  the snapshot automatically; missing/unfrozen modules are **not** represented
  as a reproducible lock.
- A locked project's build refuses changed Zephyr/module/SDK/tool identities,
  but permits normal application source edits. Full verification separately
  reports changes in application files. The original lock is never overwritten.
- Initial offline mocked tests cover integrity, drift, failure-safe behaviour
  and in-place-upgrade rejection. Live 4.4→4.5 integration, archive retention,
  candidate setup installer, GUI smoke and firmware regression acceptance are
  **not yet verified or implemented**. Workbench p2 updates and Zephyr source
  updates remain strictly separate. G0 remains OPEN.
- See [Project Versions](docs/PROJECT_VERSIONS.md).

## Project-version preview user-flow repair (2026-11-03, review PR)

- The candidate setup picker previously defaulted to a context that allowed users
  to select the current 4.4 setup. The backend safely rejected an in-place
  comparison but surfaced error code 2 and two overlapping Eclipse error dialogs.
- The picker now explains that preview requires a distinct, *already installed*
  completed West environment, preflights the selected setup and canonical
  workspace identity before scheduling a Job, and explains missing project locks.
- User Jobs now report one error status instead of also displaying a separate
  error dialog. Plain-text command failures improve Eclipse diagnostics while
  retaining the backend's original JSON CLI contract for automated consumers.
- CI cross-platform and Eclipse smoke verification are required; the automatic
  4.5 installer and upgrade execution remain **not implemented** (issue #128).
  These repairs do not imply G0 qualification or release approval.

## Parallel Zephyr source preparation — Stage 1 (development 2026-11-04)

- Added a separate, opt-in release source setup action for a selected application:
  exact stable tag, destination that does not exist, explicit network confirmation.
- Uses native West init/update and West frozen manifest semantics; records actual
  tag SHA and module commits in source-only evidence without touching the 4.4
  setup or application. Deliberately does **not** produce a .zew-ready setup.
- Implements headless plan/install/compare, with Git source evidence validation;
  no SDK compatibility, firmware-build, flash, debug or offline restoration claim.
- Offline unit fixtures cover path/symlink safety, explicit user permission,
  interrupted installs, digest tampering and comparing commits to project lock.
- Next: version-specific SDK provisioner, independent migration trial, build
  diagnostics, rollback and genuine five-year archive. See
  docs/ZEPHYR_VERSION_MANAGER_ROADMAP.md and issue #128.
- Exact PR-head CI and real Windows/Linux West 4.5 source checkout are still
  needed; **G0 remains open**. Not a production release.

## Build failure investigation — development 2026-11-05

- Existing managed builder now retains configure/compile logs outside pristine
  output folders and surfaces bounded compiler/linker/CMake evidence in failures.
- Read-only core CLI supports text/JSON with exact consumed-log SHA-256. Ninja
  wrappers and the last progress source never establish a root cause.
- Integrated a native Investigate Build Failure view and menu action, automatic
  opening after captured failures, complete-log opening, saved-log import and an
  explicit verbose single-job managed rebuild. Exact-build receipts separate
  profiles and retain failed evidence through later success/pristine rebuilds.
- Java 21 full core checks and Tycho/p2 packaging pass locally. The Python suite
  contains 440 tests (4 skips); new Java investigator/storage suites pass 49
  checks. The standard desktop/debug smoke is blocked by host ptrace restrictions;
  the dedicated installed build-investigation view passes 12 native UI checks
  against package 0.1.21.202611050611. Real Windows MSPM0 regression remains
  unverified. No hardware qualification claim.
- No new release/gate approval; G0 remains open. See
  [Build failure investigator](docs/BUILD_FAILURE_INVESTIGATOR.md).


## Automatic failed-log selection — development 2026-11-05

- Remember the exact failed project/build in workspace preferences and restore
  its saved report when the investigator is reopened without a project selection.
- Add a primary Show latest failed build action; manual Import another log is
  separate. Closed/missing projects never fall back to an arbitrary workspace
  project, and explicitly selected project/profile lookup remains scoped.
- Java 21 full core suite and Tycho/p2 package verification pass locally.
  Installed native UI passes 35 checks, including actual view reopening without
  a picker, closed-project handling and cross-project/profile isolation.
- No automatic build retries, source edits or hardware claims. Exact-head CI
  and real Windows hardware validation remain pending.

## Configuration Studio diagnostics CS-01–CS-03 (2026-11-05, development branches)

**These are reviewed DEVELOPMENT PRs, not an approved installer or completed release train.** The repository G0 gate is still OPEN.

- **CS-01, PR #155:** Build-local configuration evidence receipt after an explicitly successful managed firmware build; matches selected project/board/profile, generated `.config`, `zephyr.dts`, CMake cache, declared config files and recorded DTS dependency inputs by SHA-256. Native three-section view differentiates recorded-current, changed, unverified and no-build. Older builds without receipts remain unverified rather than silently current. The receipt does not establish *all* Kconfig origin inputs, driver inclusion, or runtime hardware state.
- **CS-02, PR #156, stacked on #155:** Source-only/missing/partial generated-build findings, bounded reuse of historical failed-attempt logs and Zephyr's actual undefined-Kconfig diagnostics. Eclipse Overview provides actions to open the existing Build Failure Investigator. Historical errors are not claimed to be current. An isolated configure-only action and deeper dependency reasoning remain unimplemented.
- **CS-03, PR #157, stacked on #156:** Read-only consumer of the selected build's upstream `zephyr/kconfig-trace.md` with exact-symbol matching, assigned/default/selected/implied origins, missing/stale states and safe string handling, surfaced under Build & System advanced tools. Trace generation and a proven pristine build are **not** implemented. It does not claim complete transitive Kconfig source causality.

**Observed validation at authoring:** #155 Linux core (including 22 evidence-receipt assertions), Eclipse plug-in/p2 packaging, and installed SWT smoke passed. Its Windows firmware-matrix attempt failed while fetching an ESP32-S3 Xtensa compiler archive from an external host (HTTP 504); failed jobs were retried. #156 Linux core (including nine source-only/failed-build assertions) and packaging passed, while Windows qualification was still running. #157 exact-head CI was in progress. Do not infer the final status from this snapshot; use the PR's exact-head checks before any merge.

**Not yet implemented:** CS-04 through CS-18; full Kconfig and Devicetree origin graphs, driver compilation causality, pinmux ownership/electrical qualification, complete graphical edit/apply and AI verification require separate accepted milestones. Never promote a missing source or unsigned/partial receipt to confirmed evidence.


### CS-03 transitive expression increment (2026-11-05)

The existing packaged Kconfiglib inspector now offers `--causality-json`:
transitive direct/reverse/weak dependencies, defaults, prompt conditions and
choice dependencies, preserving upstream evaluated expressions. Traversal is
bounded and deterministic, with explicit truncation and inspection-input hashes.
Real Kconfiglib fixtures exercise transitive OR/NOT, select, imply, defaults,
choices and limits. Full source-fragment precedence, trace generation, pristine
build proof and native graph integration remain outstanding. CS-04–CS-18 are
not implemented by this increment; their numbered acceptance criteria are not
present in this checkout. No release or gate qualification is claimed.


## Configuration Studio CS-04–CS-19 continuation (2026-11-05, development)

This supersedes the earlier statement that no later CS increments exist. The
recovered roadmap defines CS-01–CS-18; CS-19 is interpreted as final integration
and qualification for the owner's requested continuation. It is not a newly
claimed production gate. See [milestone acceptance](docs/CONFIGURATION_STUDIO_ROADMAP.md)
for every implemented increment and the precise outstanding acceptance work.

Implemented code now includes upstream Kconfig request diagnostics and native
transitive evaluation, DT source/result locations, separated driver evidence
stages, typed GPIO/pinctrl observations, deterministic finding normalization,
dirty-source save guards, three-section source diagnosis, isolated sysbuild
image discovery, environment checks, image-scoped comparison, hash-checked
Problems markers, grounded explanation/refusal, job cancellation/accessibility
improvements, bounded evidence views and versioned inert rule extensions.

The eleven `studio-*` CLI commands are documented in
[Configuration Studio CLI](docs/CONFIGURATION_STUDIO_CLI.md). The installed
native advanced Kconfig action invokes the same headless evaluator. All 24
required packaged Python modules are byte-identical to their headless source;
CI tests and archive verification reject drift. Reports preserve semantic input
closures through normalization so a grounded answer cannot omit a changed
Kconfig source. No network, flash or automatic source editing is introduced.

Validation on this Linux host: 540 Python tests ran, 536 passed and four skipped;
all Java 21 core suites passed, including guided saving, native source diagnostics
and actual Python subprocess transport. Current upstream dtlib fixtures and a
real host compiler/linker fixture were exercised (not a physical Zephyr board).
Tycho plug-in/feature/p2 packaging passed; archive verification checks all Studio
resources against exact source. Packaging does not establish installed UI behavior.

Full pristine Kconfig source-fragment precedence, authenticated driver-to-final-ELF
causality, vendor/package pin ownership, general AI client integration and full
native visual editing remain incomplete or unknown as detailed in the table.
Native installed desktop acceptance, exact remote-head CI, Windows managed
firmware and physical hardware checks remain unverified. **G0 remains OPEN.**

## EmbeddedHub Board Discovery — Eclipse CDT feasibility increment (draft PR #206, 2026-11-07)

**Scope and gate:** Native Eclipse RCP/CDT, offline-first read-only local
hardware discovery. This is an EmbeddedHub ecosystem proof of concept, not a
new IDE, vendor marketplace, physical-board certification or product release.
Evidence Engine **G0 remains OPEN**; no later release gate is advanced.

**Code:** `EmbeddedHubBoardCatalogue` parses and validates the existing
`tools/hardware_catalog.py` seven-column Zephyr HWMv2 output. Native
`EmbeddedHubBoardCatalogueView` and handler provide exact-target searching,
project-bound selection highlighting and a link to the existing Configuration
Studio. The implementation performs no automatic build, flash, source edits,
network access or telemetry.

**Truth boundary:** A discovered board/SoC/qualifier record means that the
selected checkout supplies metadata. It does **not** establish compile,
runner, debug, physical pin, vendor-certified hardware or electrical support.
Architecture, full product documentation, sample importing and upstream
hardware verification report linking are future, separately reviewed work.

**Tests:** The core's positive/negative metadata fixtures are registered in
`scripts/test_core.py`. Mandatory acceptance is JDK 21 headless tests,
exact-head Tycho and installed UI smoke, Windows firmware matrix, and review
of source preservation. An installed UI interaction with a genuine Zephyr
checkout has not been manually qualified in this PR. Passing synthetic tests
is not evidence of physical hardware operation.

**Documentation:** `docs/EMBEDDEDHUB_BOARD_DISCOVERY.md` and proposed
`docs/adr/0008-embeddedhub-board-discovery.md`.

**Merge status:** Draft, pending exact-head CI and human review. No wider
EmbeddedHub analytics, monetisation or remote-lab functionality is enabled.
